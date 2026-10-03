package com.nova.gpspro.location

import android.location.Location
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Direction of travel from GPS ONLY (no compass / no sensors / nothing invented).
 *
 * Sources, in priority order, evaluated on EVERY accepted GPS fix:
 *  1. Location.getBearing() with good bearing accuracy.
 *  2. Course Over Ground (COG): bearing of the actual displacement between recent accepted
 *     GPS fixes. The baseline adapts to walking/driving speed so slow movement is filtered
 *     without making the arrow unnecessarily laggy.
 *  3. Location.getBearing() with weaker accuracy (≤ 60°).
 *
 * Stationary GPS drift never creates a new direction. When NO valid direction can be derived
 * (stationary / GPS lost), the last reliable direction is held only briefly.
 */
class BearingEngine {

    enum class Source { NONE, GPS_BEARING, COURSE_OVER_GROUND }

    var bearing = 0f; private set
    var quality = 0f; private set
    var source = Source.NONE; private set
    private var lastValidNanos = 0L
    private var everValid = false

    private class Pt(val lat: Double, val lon: Double, val acc: Float, val nanos: Long)
    private val track = ArrayDeque<Pt>()
    private var movingFixes = 0

    fun reset() {
        bearing = 0f; quality = 0f; source = Source.NONE
        lastValidNanos = 0L; everValid = false; track.clear(); movingFixes = 0
    }

    fun update(loc: Location, reanchored: Boolean) {
        update(
            lat = loc.latitude, lon = loc.longitude, acc = loc.accuracy,
            hasBearing = loc.hasBearing(), rawBearing = loc.bearing,
            bearingAcc = if (loc.hasBearingAccuracy()) loc.bearingAccuracyDegrees else -1f,
            rawSpeed = if (loc.hasSpeed()) loc.speed.toDouble() else -1.0,
            nanos = loc.elapsedRealtimeNanos, reanchored = reanchored
        )
    }

    /** Pure core (unit-testable). Returns true when the direction was updated by this fix. */
    fun update(
        lat: Double, lon: Double, acc: Float,
        hasBearing: Boolean, rawBearing: Float, bearingAcc: Float, rawSpeed: Double,
        nanos: Long, reanchored: Boolean = false
    ): Boolean {
        if (reanchored) track.clear()

        movingFixes = if (rawSpeed < 0 || rawSpeed >= STATIONARY_MPS) movingFixes + 1 else 0
        track.addLast(Pt(lat, lon, acc, nanos))
        while (track.size > 1 && nanos - track.first().nanos > TRACK_WINDOW_NS) track.removeFirst()
        while (track.size > MAX_POINTS) track.removeFirst()

        val chipBearingOk = hasBearing && rawBearing.isFinite() &&
            (if (bearingAcc >= 0f) {
                bearingAcc <= MAX_BEARING_ACC
            } else {
                rawSpeed < 0 || rawSpeed >= STATIONARY_MPS
            })
        val chipGood = chipBearingOk && bearingAcc in 0f..GOOD_BEARING_ACC

        // 1) precise chipset bearing
        if (chipGood) {
            return accept(rawBearing, chipQuality(bearingAcc, rawSpeed), Source.GPS_BEARING, nanos)
        }

        // 2) course over ground from a real coordinate displacement
        val cog = courseOverGround(rawSpeed)
        if (cog != null) return accept(cog.first, cog.second, Source.COURSE_OVER_GROUND, nanos)

        // 3) weaker chipset bearing
        if (chipBearingOk) {
            return accept(rawBearing, chipQuality(bearingAcc, rawSpeed), Source.GPS_BEARING, nanos)
        }
        return false
    }

    private fun chipQuality(bearingAcc: Float, rawSpeed: Double): Float {
        val accQ = if (bearingAcc >= 0f) {
            (1f - bearingAcc / 90f).coerceIn(0.3f, 1f)
        } else {
            0.75f
        }
        val spdQ = if (rawSpeed >= 0) {
            (0.55 + rawSpeed / 4.0).coerceIn(0.55, 1.0).toFloat()
        } else {
            0.8f
        }
        return accQ * spdQ
    }

    /**
     * Derives direction from a real displacement between two accepted GPS fixes.
     *
     * The baseline is speed-adaptive:
     *  - faster movement: short baseline for quick turns;
     *  - slow walking: longer baseline to suppress GPS-position jitter.
     *
     * Only one baseline is selected per fix (the closest valid baseline to the target
     * duration), rather than averaging several differently-aged directions. This prevents
     * old directions from pulling the arrow backwards after a turn.
     */
    private fun courseOverGround(rawSpeed: Double): Pair<Float, Float>? {
        if (track.size < 2) return null

        // Doppler speed distinguishes standing from genuine movement. Do not manufacture
        // a direction from GPS position drift while stationary.
        if (rawSpeed >= 0 && rawSpeed < STATIONARY_MPS) return null
        if (movingFixes < 1) return null

        val cur = track.last()
        val targetDt = targetBaselineSeconds(rawSpeed)
        val minDt = targetDt * MIN_BASELINE_FRACTION

        var best: Pt? = null
        var bestDt = 0.0
        var bestDistance = 0.0
        var bestNeed = 0.0
        var bestScore = Double.POSITIVE_INFINITY

        for (i in track.size - 2 downTo 0) {
            val p = track[i]
            val dt = (cur.nanos - p.nanos) / 1e9
            if (dt < MIN_COG_DT_S) continue
            if (dt > TRACK_WINDOW_SECONDS) break

            // Prefer the target baseline. Before it exists, allow a shorter but still
            // meaningful baseline; never fall back to sub-second coordinate jitter.
            val d = distance(p.lat, p.lon, cur.lat, cur.lon)
            val need = if (rawSpeed >= MIN_MOVE_MPS) {
                ((p.acc + cur.acc) * 0.5f * SHORT_NOISE_FACTOR)
                    .coerceIn(SHORT_MIN_DISPLACEMENT_M, SHORT_MAX_DISPLACEMENT_M)
            } else {
                ((p.acc + cur.acc) * 0.5f * NOISE_FACTOR)
                    .coerceIn(MIN_DISPLACEMENT_M, MAX_DISPLACEMENT_M)
            }

            if (d < need || dt < minDt) continue

            val implied = d / dt
            if (implied < MIN_MOVE_MPS) continue

            // Coordinate jumps must remain plausible relative to reported speed.
            if (rawSpeed < 0 && dt < 2.0) continue
            if (rawSpeed >= 0 && implied > rawSpeed * 4.0 + 1.5) continue

            val score = kotlin.math.abs(dt - targetDt)
            if (score < bestScore) {
                best = p
                bestDt = dt
                bestDistance = d
                bestNeed = need
                bestScore = score
            }
        }

        val p = best ?: return null

        // initialBearing() is already normalized to [0, 360), so no second trig conversion
        // is needed here. This also keeps the COG path simple and deterministic.
        val direction = initialBearing(p.lat, p.lon, cur.lat, cur.lon)

        val distanceQ = (bestDistance / (bestNeed * 2.0)).coerceIn(0.3, 0.95)
        val durationQ = (bestDt / targetDt).coerceIn(0.65, 1.0)
        val q = (distanceQ * durationQ).toFloat().coerceIn(0.2f, 0.9f)

        return direction to q
    }

    private fun targetBaselineSeconds(rawSpeed: Double): Double {
        return when {
            rawSpeed >= FAST_BASELINE_MPS -> FAST_BASELINE_S
            rawSpeed >= WALK_BASELINE_MPS -> MEDIUM_BASELINE_S
            rawSpeed >= SLOW_WALK_MPS -> SLOW_BASELINE_S
            else -> VERY_SLOW_BASELINE_S
        }
    }

    private fun accept(b: Float, q: Float, src: Source, nanos: Long): Boolean {
        bearing = ((b % 360f) + 360f) % 360f
        quality = q; source = src
        lastValidNanos = nanos; everValid = true
        return true
    }

    fun isLive(nowNanos: Long) = everValid && nowNanos - lastValidNanos <= LIVE_NS

    /** Last direction kept while GPS fixes keep arriving (silent – normal slow movement). */
    fun isUsable(nowNanos: Long) = everValid && nowNanos - lastValidNanos <= HOLD_MAX_NS

    companion object {
        const val GOOD_BEARING_ACC = 45f
        const val MAX_BEARING_ACC = 60f

        // ~0.9 km/h: below this, position drift must not be interpreted as movement.
        const val STATIONARY_MPS = 0.25
        const val MIN_MOVE_MPS = 0.20

        const val MIN_COG_DT_S = 0.5
        const val MIN_BASELINE_FRACTION = 0.60

        const val FAST_BASELINE_MPS = 1.20       // ~4.3 km/h
        const val WALK_BASELINE_MPS = 0.70       // ~2.5 km/h
        const val SLOW_WALK_MPS = 0.45           // ~1.6 km/h

        const val FAST_BASELINE_S = 1.8
        const val MEDIUM_BASELINE_S = 2.4
        const val SLOW_BASELINE_S = 3.2
        const val VERY_SLOW_BASELINE_S = 4.5

        const val NOISE_FACTOR = 0.45f
        const val MIN_DISPLACEMENT_M = 1.5f
        const val MAX_DISPLACEMENT_M = 8f

        // Short real baseline gate used only while moving.
        const val SHORT_NOISE_FACTOR = 0.12f
        const val SHORT_MIN_DISPLACEMENT_M = 0.55f
        const val SHORT_MAX_DISPLACEMENT_M = 2.5f

        const val TRACK_WINDOW_NS = 12_000_000_000L
        const val TRACK_WINDOW_SECONDS = 12.0
        const val MAX_POINTS = 30
        const val LIVE_NS = 3_000_000_000L
        const val HOLD_MAX_NS = 10_000_000_000L

        private const val R = 6371008.8

        fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = p2 - p1
            val dl = Math.toRadians(lon2 - lon1)
            val a = sin(dp / 2) * sin(dp / 2) +
                cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * R * atan2(sqrt(a), sqrt(1 - a))
        }

        fun initialBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dl = Math.toRadians(lon2 - lon1)
            val y = sin(dl) * cos(p2)
            val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
            return ((Math.toDegrees(atan2(y, x)).toFloat() % 360f) + 360f) % 360f
        }
    }
}
