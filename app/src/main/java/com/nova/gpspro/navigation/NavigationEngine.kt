package com.nova.gpspro.navigation

import android.content.Context
import android.os.SystemClock
import com.nova.gpspro.data.Destination
import com.nova.gpspro.data.DestinationRepository
import com.nova.gpspro.location.GpsState
import com.nova.gpspro.location.LocationEngine
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.max
import kotlin.math.min

data class NavState(
    val destination: Destination? = null,
    val gps: GpsState? = null,
    val hasFix: Boolean = false,
    val distanceRemainingM: Double = -1.0,
    val destinationBearing: Float = 0f,
    val arrowAngle: Float = 0f,
    val arrowMode: ArrowEngine.Mode = ArrowEngine.Mode.NO_BEARING,
    val distanceCoveredM: Double = 0.0,
    val maxSpeedMps: Double = 0.0,
    val speedMps: Double = 0.0,
    val arrived: Boolean = false,
    val etaEpochMs: Long = -1L,
    val arrivalEventId: Int = 0,
    val accuracyM: Float = 0f
)

/**
 * Navigation session: destination distance/bearing (live), arrow, distance covered,
 * max speed, arrival with hysteresis and ETA. Session stats are persisted.
 */
class NavigationEngine(
    context: Context,
    private val gpsEngine: LocationEngine,
    private val repo: DestinationRepository
) {
    fun interface Listener { fun onNav(state: NavState) }

    private val prefs = context.getSharedPreferences("nova_nav_state", Context.MODE_PRIVATE)
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val arrow = ArrowEngine()
    private val arrival = ArrivalEngine()

    var state = NavState(); private set

    private var destination: Destination? = null
    private var covered = 0.0
    private var maxSpeed = 0.0
    private var arrived = false
    private var anchorLat = Double.NaN
    private var anchorLon = Double.NaN
    private var anchorNanos = 0L
    private var etaSpeed = 0.0
    private var lastPersist = 0L

    init {
        val id = prefs.getString(KEY_DEST, null)
        destination = id?.let { repo.get(it) }
        covered = prefs.getFloat(KEY_COVERED, 0f).toDouble()
        maxSpeed = prefs.getFloat(KEY_MAX, 0f).toDouble()
        gpsEngine.addListener { onGps(it) }
        repo.addListener { syncDestination() }
    }

    fun addListener(l: Listener) { listeners.add(l); l.onNav(state) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    fun setDestination(d: Destination?) {
        val changed = d?.id != destination?.id
        destination = d
        if (changed) resetSessionInternal()
        persist(force = true)
        onGps(gpsEngine.state)
    }

    fun resetTrip() {
        resetSessionInternal()
        persist(force = true)
        onGps(gpsEngine.state)
    }

    /** Clear-all-data hook */
    fun clearAll() {
        destination = null
        resetSessionInternal()
        prefs.edit().clear().apply()
        onGps(gpsEngine.state)
    }

    private fun resetSessionInternal() {
        covered = 0.0; maxSpeed = 0.0; arrived = false; etaSpeed = 0.0
        arrival.reset()
        anchorLat = Double.NaN; anchorLon = Double.NaN
        arrow.reset()
    }

    private fun syncDestination() {
        val cur = destination ?: return
        val fresh = repo.get(cur.id)
        if (fresh == null) setDestination(null)
        else if (fresh != cur) { destination = fresh; arrived = false; arrival.reset(); onGps(gpsEngine.state) }
    }

    private fun onGps(g: GpsState) {
        val d = destination
        val fix = g.isUsable && g.location != null
        if (!fix) {
            publish(NavState(destination = d, gps = g, hasFix = false,
                distanceCoveredM = covered, maxSpeedMps = maxSpeed, arrived = arrived, arrivalEventId = arrival.eventId,
                arrowAngle = arrow.angle, arrowMode = arrow.mode))
            return
        }

        // --- distance covered (anchor based → GPS noise does not accumulate)
        if (d != null) {
            if (anchorLat.isNaN() || g.reanchored) {
                anchorLat = g.latitude; anchorLon = g.longitude
                anchorNanos = g.elapsedRealtimeNanos
            } else {
                val step = GeoMath.between(anchorLat, anchorLon, g.latitude, g.longitude).distanceM
                val minStep = max(3.0, min(g.accuracy.toDouble() * 0.6, 15.0))
                if (step >= minStep && (g.speed >= 0.5 || step > g.accuracy * 2)) {
                    covered += step
                    anchorLat = g.latitude; anchorLon = g.longitude; anchorNanos = g.elapsedRealtimeNanos
                }
            }
            if (g.accuracy <= 50f && g.speed > maxSpeed) maxSpeed = g.speed
        }

        if (d == null) {
            publish(NavState(gps = g, hasFix = true, speedMps = g.speed))
            return
        }

        val geo = GeoMath.between(g.latitude, g.longitude, d.latitude, d.longitude)
        val angle = arrow.update(geo.initialBearing, g)

        // --- arrival with hysteresis (threshold adapts to accuracy, never too small)
        arrived = arrival.update(geo.distanceM, g.accuracy)

        // --- ETA from a calm speed average
        etaSpeed = if (etaSpeed <= 0) g.speed else etaSpeed + 0.15 * (g.speed - etaSpeed)
        val eta = if (!arrived && etaSpeed >= 1.0) System.currentTimeMillis() + (geo.distanceM / etaSpeed * 1000).toLong() else -1L

        persist(force = false)
        publish(NavState(d, g, true, geo.distanceM, geo.initialBearing, angle, arrow.mode,
            covered, maxSpeed, g.speed, arrived, eta, arrival.eventId, g.accuracy))
    }

    private fun persist(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPersist < 5000) return
        lastPersist = now
        prefs.edit()
            .putString(KEY_DEST, destination?.id)
            .putFloat(KEY_COVERED, covered.toFloat())
            .putFloat(KEY_MAX, maxSpeed.toFloat())
            .apply()
    }

    fun flush() = persist(force = true)

    private fun publish(s: NavState) {
        state = s
        for (l in listeners) l.onNav(s)
    }

    companion object {
        private const val KEY_DEST = "dest_id"
        private const val KEY_COVERED = "covered"
        private const val KEY_MAX = "max_speed"
    }
}
