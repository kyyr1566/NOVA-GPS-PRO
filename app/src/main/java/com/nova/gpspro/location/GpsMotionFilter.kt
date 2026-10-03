package com.nova.gpspro.location

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A display/movement classifier built from successive real GPS fixes only.
 *
 * Raw coordinates remain untouched in [GpsState] and continue to feed all exact geodesic
 * calculations. This separate reference holds its position while the receiver is stationary,
 * then follows the real fixes after sustained movement is confirmed. It is deliberately not
 * used to rewrite location accuracy or to replace the GPS engine.
 */
class GpsMotionFilter(
    private val stationaryRadiusM: Double = 9.5,
    private val movementProgressM: Double = 4.0,
    private val noSpeedStopMs: Long = 20_000L,
    private val speedStopMs: Long = 10_000L
) {
    data class Fix(
        val latitude: Double,
        val longitude: Double,
        val accuracyM: Float,
        val elapsedRealtimeMs: Long,
        val reportedSpeedMps: Double? = null,
        val speedAccuracyMps: Float? = null
    )

    data class Snapshot(
        val latitude: Double = 0.0,
        val longitude: Double = 0.0,
        val hasPosition: Boolean = false,
        val isMoving: Boolean = false,
        /** A credible low-speed stationary candidate; movement is not ended until debounce passes. */
        val stoppingCandidate: Boolean = false,
        /** Monotonic start of the currently confirmed movement segment. */
        val movementStartedAtElapsedRealtimeMs: Long = 0L,
        /** Last stationary reference, before movement was confirmed. */
        val movementStartLatitude: Double = 0.0,
        val movementStartLongitude: Double = 0.0,
        /** Set only on a debounced movement stop; zero on a GPS re-anchor. */
        val movementEndedAtElapsedRealtimeMs: Long = 0L,
        val segmentId: Long = 0L
    )

    private val candidates = ArrayList<Fix>()
    private var initialized = false
    private var moving = false
    private var stableLat = 0.0
    private var stableLon = 0.0
    private var lastFixMs = 0L
    private var candidateSpeedCount = 0
    private var startedAtMs = 0L
    private var startedLat = 0.0
    private var startedLon = 0.0
    private var endedAtMs = 0L
    private var segmentId = 0L
    private var stopSinceMs = 0L
    private var stopSpeedLow = false
    private var stopAnchorLat = 0.0
    private var stopAnchorLon = 0.0

    fun reset() {
        candidates.clear()
        initialized = false
        moving = false
        stableLat = 0.0
        stableLon = 0.0
        lastFixMs = 0L
        candidateSpeedCount = 0
        startedAtMs = 0L
        startedLat = 0.0
        startedLon = 0.0
        endedAtMs = 0L
        segmentId = 0L
        stopSinceMs = 0L
        stopSpeedLow = false
        stopAnchorLat = 0.0
        stopAnchorLon = 0.0
    }

    fun update(fix: Fix, reanchored: Boolean = false): Snapshot {
        if (!valid(fix)) return snapshot()
        if (!initialized || reanchored || (lastFixMs > 0 && fix.elapsedRealtimeMs - lastFixMs > REANCHOR_GAP_MS)) {
            reanchor(fix)
            return snapshot()
        }
        if (fix.elapsedRealtimeMs <= lastFixMs) return snapshot()
        lastFixMs = fix.elapsedRealtimeMs

        if (!moving) {
            considerMovement(fix)
        } else {
            followMovement(fix)
        }
        return snapshot()
    }

    private fun reanchor(fix: Fix) {
        candidates.clear()
        moving = false
        initialized = true
        stableLat = fix.latitude
        stableLon = fix.longitude
        lastFixMs = fix.elapsedRealtimeMs
        candidateSpeedCount = 0
        startedAtMs = 0L
        startedLat = fix.latitude
        startedLon = fix.longitude
        endedAtMs = 0L
        stopSinceMs = 0L
        stopSpeedLow = false
        stopAnchorLat = fix.latitude
        stopAnchorLon = fix.longitude
    }

    private fun considerMovement(fix: Fix) {
        val radius = radiusFor(fix.accuracyM)
        val fromAnchor = approxM(stableLat, stableLon, fix.latitude, fix.longitude)
        if (fromAnchor <= radius) {
            candidates.clear()
            candidateSpeedCount = 0
            return
        }

        if (candidates.isEmpty() || fix.elapsedRealtimeMs - candidates.last().elapsedRealtimeMs > CANDIDATE_GAP_MS) {
            candidates.clear()
            candidateSpeedCount = 0
        }
        candidates.add(fix)
        if (candidates.size > MAX_CANDIDATES) candidates.removeAt(0)

        val speed = reliableSpeed(fix)
        candidateSpeedCount = if (speed != null && speed >= START_SPEED_MPS && fromAnchor >= MIN_SPEED_DISPLACEMENT_M) {
            candidateSpeedCount + 1
        } else 0

        if (candidateSpeedCount >= SPEED_CONFIRM_FIXES || hasConsistentOutwardProgress()) {
            moving = true
            startedAtMs = candidates.first().elapsedRealtimeMs
            startedLat = stableLat
            startedLon = stableLon
            segmentId++
            endedAtMs = 0L
            stableLat = fix.latitude
            stableLon = fix.longitude
            candidates.clear()
            candidateSpeedCount = 0
            stopSinceMs = 0L
            stopSpeedLow = false
            stopAnchorLat = fix.latitude
            stopAnchorLon = fix.longitude
        }
    }

    private fun hasConsistentOutwardProgress(): Boolean {
        if (candidates.size < MIN_TREND_FIXES) return false
        val first = candidates.first()
        val last = candidates.last()
        if (last.elapsedRealtimeMs - first.elapsedRealtimeMs < MIN_TREND_SPAN_MS) return false

        val radii = candidates.map { approxM(stableLat, stableLon, it.latitude, it.longitude) }
        if (radii.last() < stationaryRadiusM + movementProgressM) return false
        if (radii.last() - radii.first() < movementProgressM) return false

        // GPS drift tends to wander around one radius. A walk/drive shows sustained outward
        // progress and a coherent bearing from the stationary anchor across several fixes.
        var outwardSteps = 0
        for (i in 1 until radii.size) if (radii[i] >= radii[i - 1] - RADIAL_TOLERANCE_M) outwardSteps++
        if (outwardSteps < radii.size - 2) return false

        val firstBearing = bearingRadians(stableLat, stableLon, first.latitude, first.longitude)
        val lastBearing = bearingRadians(stableLat, stableLon, last.latitude, last.longitude)
        return angularDifference(firstBearing, lastBearing) <= MAX_COURSE_DRIFT_RAD
    }

    private fun followMovement(fix: Fix) {
        val speed = reliableSpeed(fix)
        val lowSpeed = speed != null && speed <= STOP_SPEED_MPS
        val radius = radiusFor(fix.accuracyM)
        val stopRadius = maxOf(stationaryRadiusM, radius)

        val mayStop = lowSpeed || speed == null
        if (!mayStop) {
            stopSinceMs = 0L
            stopSpeedLow = false
        } else if (stopSinceMs == 0L) {
            stopSinceMs = fix.elapsedRealtimeMs
            stopSpeedLow = lowSpeed
            stopAnchorLat = fix.latitude
            stopAnchorLon = fix.longitude
        } else if (approxM(stopAnchorLat, stopAnchorLon, fix.latitude, fix.longitude) > stopRadius + STOP_RADIUS_EXTRA_M) {
            stopSinceMs = fix.elapsedRealtimeMs
            stopSpeedLow = lowSpeed
            stopAnchorLat = fix.latitude
            stopAnchorLon = fix.longitude
        } else {
            stopSpeedLow = lowSpeed
        }

        val requiredStopMs = if (lowSpeed) speedStopMs else noSpeedStopMs
        if (mayStop && stopSinceMs > 0L && fix.elapsedRealtimeMs - stopSinceMs >= requiredStopMs) {
            moving = false
            endedAtMs = stopSinceMs
            candidates.clear()
            candidateSpeedCount = 0
            stopSinceMs = 0L
            stopSpeedLow = false
            return
        }
        if (lowSpeed) return // hold the display reference during the stationary debounce window

        // Follow real motion promptly, with a small display-only smoothing step. Coordinates in
        // GpsState.location and all internal geodesic inputs are never changed by this filter.
        val displacement = approxM(stableLat, stableLon, fix.latitude, fix.longitude)
        if (displacement >= FOLLOW_DEADBAND_M) {
            stableLat += (fix.latitude - stableLat) * FOLLOW_ALPHA
            stableLon += wrappedLongitudeDelta(stableLon, fix.longitude) * FOLLOW_ALPHA
        }
    }

    fun snapshot() = Snapshot(
        latitude = stableLat,
        longitude = stableLon,
        hasPosition = initialized,
        isMoving = moving,
        stoppingCandidate = moving && stopSpeedLow,
        movementStartedAtElapsedRealtimeMs = startedAtMs,
        movementStartLatitude = startedLat,
        movementStartLongitude = startedLon,
        movementEndedAtElapsedRealtimeMs = endedAtMs,
        segmentId = segmentId
    )

    private fun radiusFor(accuracyM: Float): Double {
        if (!accuracyM.isFinite() || accuracyM <= 0f) return stationaryRadiusM
        return maxOf(stationaryRadiusM, (accuracyM * ACCURACY_RADIUS_FACTOR).coerceAtMost(MAX_STATIONARY_RADIUS_M))
    }

    private fun reliableSpeed(fix: Fix): Double? {
        val speed = fix.reportedSpeedMps ?: return null
        if (!speed.isFinite() || speed < 0.0) return null
        val accuracy = fix.speedAccuracyMps
        if (accuracy != null && (!accuracy.isFinite() || accuracy < 0f || accuracy > MAX_SPEED_ACCURACY_MPS)) return null
        return speed
    }

    private fun valid(fix: Fix) = fix.latitude.isFinite() && fix.longitude.isFinite() &&
        fix.latitude in -90.0..90.0 && fix.longitude in -180.0..180.0 &&
        fix.elapsedRealtimeMs >= 0L

    private fun approxM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(wrappedLongitudeDelta(lon1, lon2))
        val meanLat = Math.toRadians((lat1 + lat2) / 2.0)
        val north = dLat * EARTH_RADIUS_M
        val east = dLon * EARTH_RADIUS_M * cos(meanLat)
        return sqrt(east * east + north * north)
    }

    private fun bearingRadians(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(wrappedLongitudeDelta(lon1, lon2))
        return kotlin.math.atan2(sin(dl) * cos(p2), cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl))
    }

    private fun angularDifference(a: Double, b: Double): Double {
        var d = (a - b) % (2.0 * PI)
        if (d > PI) d -= 2.0 * PI
        if (d < -PI) d += 2.0 * PI
        return kotlin.math.abs(d)
    }

    private fun wrappedLongitudeDelta(from: Double, to: Double): Double {
        var d = to - from
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    companion object {
        private const val EARTH_RADIUS_M = 6_371_008.8
        private const val ACCURACY_RADIUS_FACTOR = 1.0
        private const val MAX_STATIONARY_RADIUS_M = 14.0
        private const val FOLLOW_DEADBAND_M = 1.4
        private const val FOLLOW_ALPHA = 0.75
        private const val STOP_RADIUS_EXTRA_M = 1.5
        private const val START_SPEED_MPS = 1.2
        private const val MIN_SPEED_DISPLACEMENT_M = 4.0
        private const val STOP_SPEED_MPS = 0.55
        private const val MAX_SPEED_ACCURACY_MPS = 2.5f
        private const val SPEED_CONFIRM_FIXES = 2
        private const val MIN_TREND_FIXES = 4
        private const val MIN_TREND_SPAN_MS = 1_500L
        private const val MAX_COURSE_DRIFT_RAD = Math.PI / 4.0
        private const val RADIAL_TOLERANCE_M = 1.5
        private const val CANDIDATE_GAP_MS = 5_000L
        private const val MAX_CANDIDATES = 20
        private const val REANCHOR_GAP_MS = 15_000L
    }
}
