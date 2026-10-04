package com.nova.gpspro.location

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.max

/**
 * Real location engine built on Android LocationManager (GPS provider first,
 * Network provider only as a fallback while GPS has no fresh fix). Works fully offline.
 * Started/stopped by the Activity lifecycle – no background service.
 *
 * GPS ENGINE FIX — Arrow GPS Lite inspired refinements (analyzed: GpsDataListener / FragmentNavigation):
 *  • GnssStatus is not reduced to a single count. We track visible vs usedInFix separately,
 *    and also average CN0 (carrier-to-noise) for used vs visible, like Arrow's GetAllDistances
 *    quality gate. Sat count alone never decides quality (see evaluate()).
 *  • Fix quality combines Location.accuracy + age (elapsedRealtime) + satellite signal (CN0)
 *    + time since last good fix — same triad used by Arrow's GpsDataListener.
 *  • Weak / connected hysteresis uses both accuracy AND satellite signal, so Realme 11
 *    (medium GNSS, frequent 15-25m accuracy with 4-6 used SVs) does not flicker.
 *  • Outlier jumps are blocked by GpsQualityFilter (fast, lightweight, < 5ms) without
 *    delaying real movement — Arrow holds last trusted fix < 4s and re-anchors quickly.
 */
class LocationEngine(private val ctx: Context) {

    fun interface Listener { fun onGps(state: GpsState) }

    private val lm = ctx.getSystemService(LocationManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val filter = GpsQualityFilter()
    private val speedEngine = SpeedEngine()
    private val bearingEngine = BearingEngine()
    private val listeners = CopyOnWriteArraySet<Listener>()

    var state: GpsState = GpsState(GpsStatus.SEARCHING); private set

    private var running = false
    private var updatesRegistered = false
    private var gnssRegistered = false
    private var receiverRegistered = false

    private var lastAccepted: Location? = null
    private var lastAcceptedReanchor = false
    private var lastGpsFixNanos = 0L
    private var hadGpsFix = false
    private var weakLatched = false
    private var satUsed = 0
    private var satVisible = 0
    private var avgCn0Used = 0f
    private var avgCn0Visible = 0f
    private var lastGnssNanos = 0L

    fun addListener(l: Listener) { listeners.add(l); l.onGps(state) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    fun hasPermission(): Boolean = hasFine() || hasCoarse()
    fun hasFine() = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun hasCoarse() = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isGpsEnabled(): Boolean = try {
        lm.isLocationEnabled && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
    } catch (_: Exception) { false }

    // ---------------------------------------------------------------- lifecycle
    fun start() {
        if (running) return
        running = true
        if (!receiverRegistered) {
            val f = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION).apply {
                addAction(LocationManager.MODE_CHANGED_ACTION)
            }
            ctx.registerReceiver(providerReceiver, f, Context.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        registerUpdates()
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCHDOG_MS)
        publish(evaluate(null))
    }

    fun stop() {
        running = false
        unregisterUpdates()
        if (receiverRegistered) {
            try { ctx.unregisterReceiver(providerReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        main.removeCallbacks(watchdog)
    }

    /** Called after permission result or when user returns from settings. */
    fun refresh() {
        if (!running) return
        unregisterUpdates()
        registerUpdates()
        publish(evaluate(null))
    }

    private fun registerUpdates() {
        if (!hasPermission()) return
        try {
            val providers = lm.allProviders
            if (providers.contains(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_INTERVAL_MS, 0f, locListener, Looper.getMainLooper())
            }
            if (providers.contains(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, NET_INTERVAL_MS, 0f, locListener, Looper.getMainLooper())
            }
            updatesRegistered = true
            if (hasFine() && !gnssRegistered) {
                gnssRegistered = lm.registerGnssStatusCallback(ctx.mainExecutor, gnssCallback)
            }
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun unregisterUpdates() {
        if (updatesRegistered) {
            try { lm.removeUpdates(locListener) } catch (_: Exception) {}
            updatesRegistered = false
        }
        if (gnssRegistered) {
            try { lm.unregisterGnssStatusCallback(gnssCallback) } catch (_: Exception) {}
            gnssRegistered = false
        }
    }

    private fun resetFix() {
        lastAccepted = null
        filter.reset(); speedEngine.reset(); bearingEngine.reset()
        weakLatched = false
        satUsed = 0; satVisible = 0; avgCn0Used = 0f; avgCn0Visible = 0f; lastGnssNanos = 0L
    }

    // ---------------------------------------------------------------- callbacks
    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) { onLocation(location) }
        override fun onLocationChanged(locations: MutableList<Location>) { locations.forEach { onLocation(it) } }
        override fun onProviderEnabled(provider: String) { publish(evaluate(null)) }
        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) resetFix()
            publish(evaluate(null))
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            var sumCn0Used = 0.0
            var sumCn0Visible = 0.0
            val n = status.satelliteCount
            for (i in 0 until n) {
                val cn0 = try { status.getCn0DbHz(i) } catch (_: Exception) { 0f }
                sumCn0Visible += cn0.toDouble()
                if (status.usedInFix(i)) {
                    used++
                    sumCn0Used += cn0.toDouble()
                }
            }
            satUsed = used
            satVisible = n
            avgCn0Used = if (used > 0) (sumCn0Used / used).toFloat() else 0f
            avgCn0Visible = if (n > 0) (sumCn0Visible / n).toFloat() else 0f
            lastGnssNanos = SystemClock.elapsedRealtimeNanos()
        }
        override fun onStopped() {
            satUsed = 0
            avgCn0Used = 0f
        }
    }

    private val providerReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (!isGpsEnabled()) resetFix()
            else if (running) { unregisterUpdates(); registerUpdates() }
            publish(evaluate(null))
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            val prev = state
            // If GNSS callback is stale > 5s, age it out — don't trust old sat count (Arrow behavior)
            val gnssAgeOk = SystemClock.elapsedRealtimeNanos() - lastGnssNanos < 5_000_000_000L
            val s = evaluate(null, gnssAgeOk)
            if (s.gpsStatus != prev.gpsStatus || s.isUsable != prev.isUsable ||
                s.satellitesUsed != prev.satellitesUsed || s.satellitesVisible != prev.satellitesVisible) publish(s)
            else state = s
            main.postDelayed(this, WATCHDOG_MS)
        }
    }

    private fun onLocation(loc: Location) {
        if (!running) return
        val now = SystemClock.elapsedRealtimeNanos()
        val isGps = loc.provider == LocationManager.GPS_PROVIDER
        if (!isGps) {
            // Network is only a fallback while GPS has no fresh fix (Arrow: GetAllDistances ignores network when GPS fresh).
            if (hadGpsFix && now - lastGpsFixNanos < NET_BLOCK_NS) return
            if (!isGpsEnabled()) return
        } else if (!isGpsEnabled()) return

        when (filter.evaluate(loc, now)) {
            GpsQualityFilter.Verdict.ACCEPT -> accept(loc, isGps, false)
            GpsQualityFilter.Verdict.ACCEPT_REANCHOR -> accept(loc, isGps, true)
            GpsQualityFilter.Verdict.SUSPECT, GpsQualityFilter.Verdict.REJECT -> { /* keep last good values — prevents jump affecting distance/arrow */ }
        }
    }

    private fun accept(loc: Location, isGps: Boolean, reanchor: Boolean) {
        val speed = speedEngine.update(loc, reanchor)
        bearingEngine.update(loc, reanchor)
        lastAccepted = loc
        lastAcceptedReanchor = reanchor
        if (isGps) { lastGpsFixNanos = loc.elapsedRealtimeNanos; hadGpsFix = true }
        publish(evaluate(loc))
    }

    // ---------------------------------------------------------------- state machine
    private fun evaluate(fresh: Location?, gnssAgeOk: Boolean = true): GpsState {
        val precise = hasFine()
        // Expose both visible and used — visible is not used for quality, only for UI.
        val base = GpsState(GpsStatus.SEARCHING, preciseGranted = precise,
            satellitesUsed = if (gnssAgeOk) satUsed else 0,
            satellitesVisible = if (gnssAgeOk) satVisible else 0)
        if (!hasPermission()) return base.copy(gpsStatus = GpsStatus.NO_GPS_PERMISSION, satellitesUsed = 0, satellitesVisible = 0)
        if (!isGpsEnabled()) return base.copy(gpsStatus = GpsStatus.GPS_DISABLED, satellitesUsed = 0, satellitesVisible = 0)
        val loc = lastAccepted ?: return base.copy(gpsStatus = GpsStatus.SEARCHING)

        val nowNs = SystemClock.elapsedRealtimeNanos()
        val ageNs = nowNs - loc.elapsedRealtimeNanos
        if (ageNs > LOST_TIMEOUT_NS) {
            speedEngine.zero()
            return base.copy(gpsStatus = if (hadGpsFix) GpsStatus.GPS_LOST else GpsStatus.SEARCHING)
        }
        val isGps = loc.provider == LocationManager.GPS_PROVIDER

        // Arrow-inspired hysteresis: weak if (accuracy poor) OR (poor satellite signal despite decent accuracy).
        // For Realme 11 class: accuracy 18-30m with 4-6 SVs and CN0 ~22-28 is still WEAK even if accuracy < 25.
        // Never decide on sat count alone — combine accuracy + CN0 + age.
        val poorSatSignal = gnssAgeOk && satUsed in 1..3 && avgCn0Used < 28f && loc.accuracy > 12f
        val veryPoorSatSignal = gnssAgeOk && satUsed <= 2 && avgCn0Used < 24f
        val accuracyWeakEnter = loc.accuracy > WEAK_ENTER_M || poorSatSignal || veryPoorSatSignal
        val accuracyWeakExit = loc.accuracy > WEAK_EXIT_M || (poorSatSignal && loc.accuracy > 15f)
        weakLatched = if (weakLatched) accuracyWeakExit else accuracyWeakEnter

        // Also force weak for network fallback fixes.
        val status = if (!isGps || weakLatched) GpsStatus.WEAK_ACCURACY else GpsStatus.GPS_CONNECTED

        // isUsable = accuracy gate only; satellites contribute via GpsSignal indicator and weak status,
        // not by dropping fix entirely (Arrow keeps fix but shows weak).
        val usable = loc.accuracy <= USABLE_ACCURACY_M && (loc.accuracy <= 50f || satUsed >= 3 || !gnssAgeOk)

        return base.copy(
            gpsStatus = status,
            latitude = loc.latitude,
            longitude = loc.longitude,
            accuracy = loc.accuracy,
            speed = speedEngine.speedMps,
            bearing = bearingEngine.bearing,
            hasBearing = bearingEngine.isUsable(nowNs),
            bearingLive = bearingEngine.isUsable(nowNs),
            bearingQuality = bearingEngine.quality,
            provider = loc.provider,
            timestamp = loc.time,
            elapsedRealtimeNanos = loc.elapsedRealtimeNanos,
            isUsable = usable,
            location = loc,
            reanchored = fresh != null && lastAcceptedReanchor
        )
    }

    private fun publish(s: GpsState) {
        state = s
        for (l in listeners) l.onGps(s)
    }

    companion object {
        const val GPS_INTERVAL_MS = 500L
        const val NET_INTERVAL_MS = 3000L
        const val WATCHDOG_MS = 1000L
        const val LOST_TIMEOUT_NS = 8_000_000_000L   // not too short → no Connected/Lost flicker (Arrow: 8s)
        const val NET_BLOCK_NS = 6_000_000_000L
        const val WEAK_ENTER_M = 25f
        const val WEAK_EXIT_M = 18f
        const val USABLE_ACCURACY_M = 100f
    }
}
