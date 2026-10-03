package com.nova.gpspro.navigation

import android.content.Context
import android.location.LocationManager
import android.os.SystemClock
import com.nova.gpspro.R
import com.nova.gpspro.data.Destination
import com.nova.gpspro.data.DestinationRepository
import com.nova.gpspro.location.GpsState
import com.nova.gpspro.location.LocationEngine
import java.util.concurrent.CopyOnWriteArraySet

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
    private val context: Context,
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
    private var anchorMotionSegment = 0L
    private var etaSpeed = 0.0
    private var lastPersist = 0L

    init {
        val id = prefs.getString(KEY_DEST, null)
        destination = restoreDestination(id)
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
        anchorLat = Double.NaN; anchorLon = Double.NaN; anchorMotionSegment = 0L
        arrow.reset()
    }

    private fun syncDestination() {
        val cur = destination ?: return
        if (isTripEndpoint(cur.id)) return   // a recorded GPS endpoint is not a saved Destinations entry
        val fresh = repo.get(cur.id)
        if (fresh == null) setDestination(null)
        else if (fresh != cur) { destination = fresh; arrived = false; arrival.reset(); onGps(gpsEngine.state) }
    }

    /** Restores a real trip endpoint snapshot when the trip was not linked to a saved place. */
    private fun restoreDestination(id: String?): Destination? {
        if (id == null) return null
        repo.get(id)?.let { return it }
        if (!isTripEndpoint(id) || !prefs.getBoolean(KEY_TEMP, false)) return null
        val lat = prefs.getString(KEY_TEMP_LAT, null)?.toDoubleOrNull()?.takeIf { it.isFinite() && it in -90.0..90.0 } ?: return null
        val lon = prefs.getString(KEY_TEMP_LON, null)?.toDoubleOrNull()?.takeIf { it.isFinite() && it in -180.0..180.0 } ?: return null
        val name = prefs.getString(KEY_TEMP_NAME, null)?.takeIf { it.isNotBlank() } ?: context.getString(R.string.trip_endpoint_name)
        return Destination(id, name, lat, lon, createdAt = prefs.getLong(KEY_TEMP_CREATED, System.currentTimeMillis()))
    }

    private fun isTripEndpoint(id: String) = id.startsWith(TRIP_ENDPOINT_PREFIX)

    private fun onGps(g: GpsState) {
        val d = destination
        val location = g.location
        val fix = g.isUsable && location != null && !location.isFromMockProvider
        if (!fix) {
            publish(NavState(destination = d, gps = g, hasFix = false,
                distanceCoveredM = covered, maxSpeedMps = maxSpeed, arrived = arrived, arrivalEventId = arrival.eventId,
                arrowAngle = arrow.angle, arrowMode = arrow.mode))
            return
        }

        // --- distance covered: only confirmed GPS movement can advance this session total.
        // Raw fixes and exact geodesic math remain available; stationary jitter never accumulates.
        val gpsMotion = g.provider == LocationManager.GPS_PROVIDER && g.motion.hasPosition && g.motion.isMoving
        if (d != null && gpsMotion) {
            if (anchorLat.isNaN() || anchorMotionSegment != g.motion.segmentId) {
                anchorLat = g.motion.movementStartLatitude
                anchorLon = g.motion.movementStartLongitude
                anchorMotionSegment = g.motion.segmentId
            }
            val step = GeoMath.between(anchorLat, anchorLon, g.motion.latitude, g.motion.longitude).distanceM
            if (step >= 0.5) {
                covered += step
                anchorLat = g.motion.latitude; anchorLon = g.motion.longitude
            }
            if (g.accuracy <= 50f && g.speed > maxSpeed) maxSpeed = g.speed
        } else if (d != null) {
            anchorLat = Double.NaN; anchorLon = Double.NaN; anchorMotionSegment = 0L
        }

        if (d == null) {
            publish(NavState(gps = g, hasFix = true, speedMps = g.speed))
            return
        }

        val measureLat = if (g.provider == LocationManager.GPS_PROVIDER && g.motion.hasPosition) g.motion.latitude else g.latitude
        val measureLon = if (g.provider == LocationManager.GPS_PROVIDER && g.motion.hasPosition) g.motion.longitude else g.longitude
        val displayGeo = GeoMath.between(measureLat, measureLon, d.latitude, d.longitude)
        val rawGeo = GeoMath.between(g.latitude, g.longitude, d.latitude, d.longitude)
        val angle = arrow.update(displayGeo.initialBearing, g)

        // Arrival keeps its exact raw-fix calculation; the stable reference is for distance display.
        arrived = arrival.update(rawGeo.distanceM, g.accuracy)

        // --- ETA from a calm speed average and the jitter-stable display distance
        etaSpeed = if (etaSpeed <= 0) g.speed else etaSpeed + 0.15 * (g.speed - etaSpeed)
        val eta = if (!arrived && etaSpeed >= 1.0) System.currentTimeMillis() + (displayGeo.distanceM / etaSpeed * 1000).toLong() else -1L

        persist(force = false)
        publish(NavState(d, g, true, displayGeo.distanceM, displayGeo.initialBearing, angle, arrow.mode,
            covered, maxSpeed, g.speed, arrived, eta, arrival.eventId, g.accuracy))
    }

    private fun persist(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPersist < 5000) return
        lastPersist = now
        prefs.edit()
            .putString(KEY_DEST, destination?.id)
            .putBoolean(KEY_TEMP, destination?.let { isTripEndpoint(it.id) } == true)
            .putString(KEY_TEMP_NAME, destination?.takeIf { isTripEndpoint(it.id) }?.name)
            .putString(KEY_TEMP_LAT, destination?.takeIf { isTripEndpoint(it.id) }?.latitude?.toString())
            .putString(KEY_TEMP_LON, destination?.takeIf { isTripEndpoint(it.id) }?.longitude?.toString())
            .putLong(KEY_TEMP_CREATED, destination?.takeIf { isTripEndpoint(it.id) }?.createdAt ?: 0L)
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
        const val TRIP_ENDPOINT_PREFIX = "trip-endpoint:"
        private const val KEY_DEST = "dest_id"
        private const val KEY_COVERED = "covered"
        private const val KEY_MAX = "max_speed"
        private const val KEY_TEMP = "trip_endpoint"
        private const val KEY_TEMP_NAME = "trip_endpoint_name"
        private const val KEY_TEMP_LAT = "trip_endpoint_lat"
        private const val KEY_TEMP_LON = "trip_endpoint_lon"
        private const val KEY_TEMP_CREATED = "trip_endpoint_created"
    }
}
