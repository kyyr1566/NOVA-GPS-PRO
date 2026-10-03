package com.nova.gpspro.trips

import android.content.Context
import android.content.res.Configuration
import android.location.LocationManager
import android.os.SystemClock
import com.nova.gpspro.R
import com.nova.gpspro.data.Destination
import com.nova.gpspro.data.DestinationRepository
import com.nova.gpspro.data.Trip
import com.nova.gpspro.data.TripPoint
import com.nova.gpspro.data.TripRepository
import com.nova.gpspro.location.GpsState
import com.nova.gpspro.location.LocationEngine
import com.nova.gpspro.navigation.GeoMath
import com.nova.gpspro.navigation.NavigationEngine
import com.nova.gpspro.settings.SettingsRepository
import java.io.File
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/** Manual/automatic GPS trip capture. Uses only accepted, non-mock GPS-provider fixes. */
class TripManager(
    context: Context,
    private val gps: LocationEngine,
    private val destinations: DestinationRepository,
    private val navigation: NavigationEngine,
    private val settings: SettingsRepository
) {
    enum class Mode { MANUAL, AUTOMATIC }
    enum class Phase { IDLE, RECORDING, READY_TO_SAVE }

    data class UiState(
        val mode: Mode,
        val phase: Phase,
        val startedAtMs: Long = 0L,
        val durationMs: Long = 0L,
        val distanceM: Double = 0.0,
        val averageSpeedMps: Double = 0.0,
        val maxSpeedMps: Double? = null,
        val currentSpeedMps: Double? = null,
        val pointCount: Int = 0,
        val gpsReady: Boolean = false,
        val moving: Boolean = false,
        val savedRevision: Long = 0L
    )

    fun interface Listener { fun onChanged(state: UiState) }

    private data class Draft(
        val id: String = UUID.randomUUID().toString(),
        val mode: Mode,
        var startedAtMs: Long,
        var startedElapsedMs: Long,
        var endedAtMs: Long = 0L,
        var endedElapsedMs: Long = 0L,
        var distanceM: Double = 0.0,
        var maxSpeedMps: Double? = null,
        var linkedDestination: Destination? = null,
        var sawMovement: Boolean = false,
        var lastMotionSegmentId: Long = 0L,
        val points: MutableList<TripPoint> = ArrayList(),
        var distanceAnchor: TripPoint? = null,
        var lastFixElapsedNanos: Long = 0L
    )

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val repository = TripRepository(File(appContext.filesDir, "trips"))
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val recentFixes = ArrayDeque<TripPoint>()
    private var mode = readMode()
    private var phase = Phase.IDLE
    private var draft: Draft? = null
    private var pending: Draft? = null
    private var lastGps: GpsState? = null
    private var lastHandledFixNanos = 0L
    private var savedRevision = 0L

    private val gpsListener = LocationEngine.Listener { onGps(it) }

    init { gps.addListener(gpsListener) }

    fun addListener(listener: Listener) { listeners.add(listener); listener.onChanged(snapshot()) }
    fun removeListener(listener: Listener) { listeners.remove(listener) }

    fun snapshot(): UiState {
        val item = draft ?: pending
        val now = SystemClock.elapsedRealtime()
        val duration = when {
            item == null -> 0L
            phase == Phase.RECORDING -> (now - item.startedElapsedMs).coerceAtLeast(0L)
            else -> (item.endedElapsedMs - item.startedElapsedMs).coerceAtLeast(0L)
        }
        val average = if (duration > 0L) item?.distanceM?.div(duration / 1000.0) ?: 0.0 else 0.0
        val current = lastGps?.takeIf { it.motion.isMoving && !it.motion.stoppingCandidate && validTripFix(it) }?.let { liveSpeed(it) }
        return UiState(
            mode = mode,
            phase = phase,
            startedAtMs = item?.startedAtMs ?: 0L,
            durationMs = duration,
            distanceM = item?.distanceM ?: 0.0,
            averageSpeedMps = average,
            maxSpeedMps = item?.maxSpeedMps,
            currentSpeedMps = current,
            pointCount = item?.points?.size ?: 0,
            gpsReady = lastGps?.let(::validTripFix) == true,
            moving = lastGps?.motion?.let { it.isMoving && !it.stoppingCandidate } == true,
            savedRevision = savedRevision
        )
    }

    fun trips(): List<Trip> = repository.all()

    fun clearAll(): Boolean {
        if (!repository.clearAll()) return false
        draft = null
        pending = null
        phase = Phase.IDLE
        savedRevision++
        publish()
        return true
    }

    fun suggestedName(): String = (pending ?: draft)?.let(::defaultName) ?: getLocalizedString(
        R.string.trip_auto_name,
        android.text.format.DateFormat.getTimeFormat(localizedContext()).format(Date(System.currentTimeMillis()))
    )

    fun setMode(next: Mode): Boolean {
        if (phase != Phase.IDLE) return false
        if (mode == next) return true
        mode = next
        prefs.edit().putString(KEY_MODE, next.name).apply()
        publish()
        return true
    }

    fun startManual(): Boolean {
        if (mode != Mode.MANUAL || phase != Phase.IDLE) return false
        val nowElapsed = SystemClock.elapsedRealtime()
        val item = Draft(
            mode = Mode.MANUAL,
            startedAtMs = System.currentTimeMillis(),
            startedElapsedMs = nowElapsed,
            linkedDestination = navigation.state.destination
        )
        draft = item
        phase = Phase.RECORDING
        lastGps?.takeIf(::validTripFix)?.let { state ->
            val point = toPoint(state) ?: return@let
            if (state.motion.isMoving && !state.motion.stoppingCandidate) seedMotionSegment(item, state, point)
            else appendPoint(item, point, countDistance = false)
        }
        publish()
        return true
    }

    fun stopManual(): Boolean {
        if (mode != Mode.MANUAL || phase != Phase.RECORDING) return false
        val item = draft ?: return false
        item.endedAtMs = System.currentTimeMillis()
        item.endedElapsedMs = SystemClock.elapsedRealtime()
        draft = null
        pending = item
        phase = Phase.READY_TO_SAVE
        publish()
        return true
    }

    /** Saves the stopped manual recording. A blank title gets a real destination/time-based name. */
    fun savePending(name: String): Boolean {
        if (phase != Phase.READY_TO_SAVE) return false
        val item = pending ?: return false
        val trip = buildTrip(item, name.trim().takeIf { it.isNotEmpty() } ?: defaultName(item)) ?: return false
        if (!repository.save(trip)) return false
        pending = null
        phase = Phase.IDLE
        savedRevision++
        publish()
        return true
    }

    fun discardPending(): Boolean {
        if (phase != Phase.READY_TO_SAVE) return false
        pending = null
        phase = Phase.IDLE
        publish()
        return true
    }

    fun rename(id: String, name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty()) return false
        val old = repository.get(id) ?: return false
        if (!repository.save(old.copy(name = clean))) return false
        savedRevision++
        publish()
        return true
    }

    fun delete(id: String): Boolean {
        if (!repository.delete(id)) return false
        savedRevision++
        publish()
        return true
    }

    private fun onGps(state: GpsState) {
        lastGps = state
        val point = if (validTripFix(state)) toPoint(state) else null
        if (point != null && point.elapsedRealtimeNanos > lastHandledFixNanos) {
            lastHandledFixNanos = point.elapsedRealtimeNanos
            recentFixes.addLast(point)
            val cutoff = point.elapsedRealtimeNanos - RECENT_FIX_WINDOW_NS
            while (recentFixes.isNotEmpty() && recentFixes.first().elapsedRealtimeNanos < cutoff) recentFixes.removeFirst()

            if (mode == Mode.AUTOMATIC) handleAutomatic(state, point)
            else if (phase == Phase.RECORDING) handleManual(state, point)
        }
        publish()
    }

    private fun handleAutomatic(state: GpsState, point: TripPoint) {
        if (phase == Phase.IDLE && state.motion.isMoving && !state.motion.stoppingCandidate) {
            val startElapsed = state.motion.movementStartedAtElapsedRealtimeMs.takeIf { it > 0L }
                ?: point.elapsedRealtimeNanos / 1_000_000L
            val item = Draft(
                mode = Mode.AUTOMATIC,
                startedAtMs = wallTimeForElapsed(startElapsed),
                startedElapsedMs = startElapsed,
                linkedDestination = navigation.state.destination,
                sawMovement = true
            )
            draft = item
            phase = Phase.RECORDING
            seedMotionSegment(item, state, point)
            return
        }
        val item = draft ?: return
        if (phase != Phase.RECORDING) return
        if (state.motion.isMoving && !state.motion.stoppingCandidate) {
            if (!item.sawMovement) seedMotionSegment(item, state, point)
            else appendPoint(item, point, countDistance = true)
        } else if (!state.motion.isMoving && item.sawMovement && state.motion.movementEndedAtElapsedRealtimeMs > item.startedElapsedMs) {
            val endElapsed = state.motion.movementEndedAtElapsedRealtimeMs
            item.endedElapsedMs = endElapsed
            item.endedAtMs = wallTimeForElapsed(endElapsed)
            val trip = buildTrip(item, defaultName(item))
            if (trip != null && repository.save(trip)) savedRevision++
            draft = null
            phase = Phase.IDLE
        }
    }

    private fun handleManual(state: GpsState, point: TripPoint) {
        val item = draft ?: return
        if (item.points.isEmpty() && (!state.motion.isMoving || state.motion.stoppingCandidate)) appendPoint(item, point, countDistance = false)
        if (state.motion.isMoving && !state.motion.stoppingCandidate) {
            if (!item.sawMovement || state.motion.segmentId != item.lastMotionSegmentId) {
                seedMotionSegment(item, state, point)
            } else appendPoint(item, point, countDistance = true)
        }
    }

    private fun seedMotionSegment(item: Draft, state: GpsState, current: TripPoint) {
        item.sawMovement = true
        item.lastMotionSegmentId = state.motion.segmentId
        val startNs = state.motion.movementStartedAtElapsedRealtimeMs * 1_000_000L
        // Keep the last actual GPS fix before motion as the start marker, then preserve every
        // actual GPS sample from the confirmed movement candidate onward.
        recentFixes.lastOrNull { it.elapsedRealtimeNanos < startNs }?.let {
            if (item.points.isEmpty()) appendPoint(item, it, countDistance = false)
        }
        recentFixes.filter { it.elapsedRealtimeNanos >= startNs }.forEach { appendPoint(item, it, countDistance = true) }
        appendPoint(item, current, countDistance = true)
    }

    private fun appendPoint(item: Draft, point: TripPoint, countDistance: Boolean) {
        if (item.lastFixElapsedNanos == point.elapsedRealtimeNanos ||
            item.points.lastOrNull()?.elapsedRealtimeNanos == point.elapsedRealtimeNanos) return
        if (item.points.isNotEmpty() && point.elapsedRealtimeNanos < item.points.last().elapsedRealtimeNanos) return
        if (item.distanceAnchor == null) item.distanceAnchor = point
        else if (countDistance) {
            val anchor = item.distanceAnchor!!
            val segment = GeoMath.between(anchor.latitude, anchor.longitude, point.latitude, point.longitude).distanceM
            val minStep = maxOf(1.0, minOf(point.accuracyM.toDouble() * 0.25, 2.5))
            if (segment >= minStep) {
                item.distanceM += segment
                item.distanceAnchor = point
            }
        }
        item.points.add(point)
        item.lastFixElapsedNanos = point.elapsedRealtimeNanos
        if (countDistance) point.speedMps?.let { speed ->
            if (speed >= 0.5 && speed <= MAX_TRIP_SPEED_MPS) item.maxSpeedMps = maxOf(item.maxSpeedMps ?: 0.0, speed)
        }
    }

    private fun buildTrip(item: Draft, title: String): Trip? {
        val last = item.points.lastOrNull() ?: return null
        if (item.points.isEmpty()) return null
        if (item.points.size < 2 || item.distanceM < MIN_SAVED_DISTANCE_M) return null
        val duration = (item.endedElapsedMs - item.startedElapsedMs).coerceAtLeast(0L)
        val endpoint = last
        val destination = item.linkedDestination ?: nearbyDestination(endpoint)
        val avg = if (duration > 0L) item.distanceM / (duration / 1000.0) else 0.0
        return Trip(
            id = item.id,
            name = title,
            startedAtMs = item.startedAtMs,
            endedAtMs = maxOf(item.startedAtMs, item.endedAtMs),
            durationMs = duration,
            distanceM = item.distanceM,
            averageSpeedMps = avg,
            maxSpeedMps = item.maxSpeedMps,
            destinationId = destination?.id,
            destinationName = destination?.name,
            destinationLatitude = destination?.latitude,
            destinationLongitude = destination?.longitude,
            endpointLatitude = endpoint.latitude,
            endpointLongitude = endpoint.longitude,
            points = item.points.toList()
        )
    }

    private fun nearbyDestination(endpoint: TripPoint): Destination? = destinations.all()
        .map { it to GeoMath.between(endpoint.latitude, endpoint.longitude, it.latitude, it.longitude).distanceM }
        .filter { it.second <= DESTINATION_MATCH_RADIUS_M }
        .minByOrNull { it.second }
        ?.first

    private fun defaultName(item: Draft): String {
        val locationName = item.linkedDestination?.name ?: item.points.lastOrNull()?.let { nearbyDestination(it)?.name }
        return if (!locationName.isNullOrBlank()) getLocalizedString(R.string.trip_to_name, locationName)
        else {
            val time = android.text.format.DateFormat.getTimeFormat(localizedContext()).format(Date(item.startedAtMs))
            getLocalizedString(R.string.trip_auto_name, time)
        }
    }

    private fun validTripFix(state: GpsState): Boolean {
        val location = state.location ?: return false
        return state.isUsable && state.provider == LocationManager.GPS_PROVIDER &&
            location.provider == LocationManager.GPS_PROVIDER && !location.isFromMockProvider &&
            location.accuracy.isFinite() && location.accuracy in 0f..MAX_TRIP_ACCURACY_M &&
            state.motion.hasPosition
    }

    private fun toPoint(state: GpsState): TripPoint? {
        val location = state.location ?: return null
        val elapsed = location.elapsedRealtimeNanos
        if (elapsed <= 0L) return null
        val speed = if (location.hasSpeed() && location.speed.isFinite() && location.speed >= 0f &&
            (!location.hasSpeedAccuracy() || location.speedAccuracyMetersPerSecond <= MAX_SPEED_ACCURACY_MPS)) {
            location.speed.toDouble()
        } else null
        return TripPoint(
            timestampMs = location.time.takeIf { it > 0L } ?: System.currentTimeMillis(),
            elapsedRealtimeNanos = elapsed,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyM = location.accuracy,
            speedMps = speed
        )
    }

    private fun liveSpeed(state: GpsState): Double? {
        val point = toPoint(state)
        return point?.speedMps ?: state.speed.takeIf { it.isFinite() && it in 0.0..MAX_TRIP_SPEED_MPS }
    }

    private fun wallTimeForElapsed(elapsedMs: Long): Long =
        (System.currentTimeMillis() - SystemClock.elapsedRealtime() + elapsedMs).coerceAtLeast(0L)

    private fun localizedContext(): Context {
        val config = Configuration(appContext.resources.configuration)
        val locale = Locale.forLanguageTag(settings.language)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return appContext.createConfigurationContext(config)
    }

    private fun getLocalizedString(id: Int, vararg args: Any): String = localizedContext().getString(id, *args)

    private fun readMode(): Mode = runCatching {
        Mode.valueOf(prefs.getString(KEY_MODE, Mode.MANUAL.name) ?: Mode.MANUAL.name)
    }.getOrDefault(Mode.MANUAL)

    private fun snapshotInternal() = snapshot()
    private fun publish() { val value = snapshotInternal(); listeners.forEach { it.onChanged(value) } }

    companion object {
        private const val PREFS = "nova_trip_settings"
        private const val KEY_MODE = "trip_mode"
        private const val RECENT_FIX_WINDOW_NS = 30_000_000_000L
        private const val DESTINATION_MATCH_RADIUS_M = 150.0
        private const val MAX_TRIP_ACCURACY_M = 50f
        private const val MAX_SPEED_ACCURACY_MPS = 3.0f
        private const val MAX_TRIP_SPEED_MPS = 100.0
        private const val MIN_SAVED_DISTANCE_M = 1.0
    }
}
