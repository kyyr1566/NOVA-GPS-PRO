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

/**
 * Real location engine built on Android LocationManager (GPS provider first,
 * Network provider only as a fallback while GPS has no fresh fix). Works fully offline.
 * Started/stopped by the Activity lifecycle – no background service.
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
        satUsed = 0; satVisible = 0
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
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            satUsed = used; satVisible = status.satelliteCount
        }
        override fun onStopped() { satUsed = 0 }
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
            val s = evaluate(null)
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
            // Network is only a fallback while GPS has no fresh fix.
            if (hadGpsFix && now - lastGpsFixNanos < NET_BLOCK_NS) return
            if (!isGpsEnabled()) return
        } else if (!isGpsEnabled()) return

        when (filter.evaluate(loc, now)) {
            GpsQualityFilter.Verdict.ACCEPT -> accept(loc, isGps, false)
            GpsQualityFilter.Verdict.ACCEPT_REANCHOR -> accept(loc, isGps, true)
            GpsQualityFilter.Verdict.SUSPECT, GpsQualityFilter.Verdict.REJECT -> { /* keep last good values */ }
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
    private fun evaluate(fresh: Location?): GpsState {
        val precise = hasFine()
        val base = GpsState(GpsStatus.SEARCHING, preciseGranted = precise,
            satellitesUsed = satUsed, satellitesVisible = satVisible)
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
        // Hysteresis so status does not flicker around the threshold.
        weakLatched = if (weakLatched) loc.accuracy > WEAK_EXIT_M else loc.accuracy > WEAK_ENTER_M
        val status = if (!isGps || weakLatched) GpsStatus.WEAK_ACCURACY else GpsStatus.GPS_CONNECTED
        return base.copy(
            gpsStatus = status,
            latitude = loc.latitude,
            longitude = loc.longitude,
            accuracy = loc.accuracy,
            speed = speedEngine.speedMps,
            bearing = bearingEngine.bearing,
            hasBearing = bearingEngine.isUsable(nowNs),
            bearingLive = bearingEngine.isUsable(nowNs),  // fresh fixes → no HOLD during movement
            bearingQuality = bearingEngine.quality,
            provider = loc.provider,
            timestamp = loc.time,
            elapsedRealtimeNanos = loc.elapsedRealtimeNanos,
            isUsable = loc.accuracy <= USABLE_ACCURACY_M,
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
        const val LOST_TIMEOUT_NS = 8_000_000_000L   // not too short → no Connected/Lost flicker
        const val NET_BLOCK_NS = 6_000_000_000L
        const val WEAK_ENTER_M = 25f
        const val WEAK_EXIT_M = 18f
        const val USABLE_ACCURACY_M = 100f
    }
}
