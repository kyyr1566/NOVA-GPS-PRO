package com.nova.gpspro.location

import android.location.Location
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Direction of travel from GPS ONLY (no compass / no sensors / nothing invented).
 *
 * Arrow GPS Lite lesson (MyArrowView + GpsDataListener + FragmentNavigation):
 *  • Real bearing = Location.getBearing() only when bearingAccuracy is reliable and speed indicates motion.
 *    Arrow Lite trusts chip bearing when accuracy ≤ 25° at >1 m/s, up to 45° at higher speeds.
 *  • Otherwise course-over-ground (COG) from displacement of accepted fixes — short real baseline,
 *    vector-averaged, never invented heading when stationary.
 *  • While moving 1 km/h on Realme 11, a 1.5-8m gate appears frozen for seconds → Arrow uses
 *    short 0.6-2.5m baseline with noise factor 0.12, so 1 km/h responds within first fix.
 *  • When stopped, COG needs consecutive moving fixes; otherwise bearing is held briefly (≤10s)
 *    then NO_BEARING — prevents random spin from GPS drift (Arrow stops after ~10s).
 *
 * Sources, in priority order, evaluated on EVERY accepted GPS fix:
 *  1. Location.getBearing() with good bearing accuracy.
 *  2. Course Over Ground (COG): bearing of the actual displacement between recent accepted fixes.
 *  3. Location.getBearing() with weaker accuracy (≤ 60°).
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
    private var movingFixes = 0   // consecutive fixes whose Doppler speed indicates movement

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
        // Arrow: needs Doppler indication of motion to start COG — prevents drift when standing.
        movingFixes = if (rawSpeed < 0 || rawSpeed >= STATIONARY_MPS) movingFixes + 1 else 0
        track.addLast(Pt(lat, lon, acc, nanos))
        while (track.size > 1 && nanos - track.first().nanos > TRACK_WINDOW_NS) track.removeFirst()
        while (track.size > MAX_POINTS) track.removeFirst()

        // Chip bearing usable when accuracy is decent OR when speed proves motion (for devices where chip doesn't report bearingAccuracy)
        val chipBearingOk = hasBearing && rawBearing.isFinite() &&
            (if (bearingAcc >= 0f) bearingAcc <= MAX_BEARING_ACC else rawSpeed < 0 || rawSpeed >= STATIONARY_MPS)
        val chipGood = chipBearingOk && bearingAcc in 0f..GOOD_BEARING_ACC

        // 1) precise chipset bearing — Arrow prefers this when bearingAccuracy ≤ 25° at walking speed, ≤45° otherwise
        if (chipGood) return accept(rawBearing, chipQuality(bearingAcc, rawSpeed), Source.GPS_BEARING, nanos)

        // 2) course over ground from real displacement — Arrow's fallback for Realme class without good bearingAcc
        val cog = courseOverGround(rawSpeed)
        if (cog != null) return accept(cog.first, cog.second, Source.COURSE_OVER_GROUND, nanos)

        // 3) weaker chipset bearing (≤60°) — still better than nothing when speed is honest
        if (chipBearingOk) return accept(rawBearing, chipQuality(bearingAcc, rawSpeed), Source.GPS_BEARING, nanos)
        return false
    }

    private fun chipQuality(bearingAcc: Float, rawSpeed: Double): Float {
        // Arrow-like: accuracy 0-25° => 1.0 … 0.75, speed 0.5-4 m/s => 0.55-1.0
        val accQ = if (bearingAcc >= 0f) (1f - bearingAcc / 90f).coerceIn(0.3f, 1f) else 0.75f
        val spdQ = if (rawSpeed >= 0) (0.55 + rawSpeed / 4.0).coerceIn(0.55, 1.0).toFloat() else 0.8f
        return accQ * spdQ
    }

    /** Uses the MOST RECENT earlier fix that is far enough away → minimum lag (Arrow: shortest baseline with consistency). */
    private fun courseOverGround(rawSpeed: Double): Pair<Float, Float>? {
        if (track.size < 2) return null
        if (rawSpeed >= 0 && rawSpeed < STATIONARY_MPS) return null // standing → no COG (Arrow stops arrow)
        if (movingFixes < 1) return null // need at least one moving Doppler indication
        val cur = track.last()
        var sx = 0.0; var sy = 0.0; var wsum = 0.0; var bestD = 0.0
        var firstDt = -1.0
        for (i in track.size - 2 downTo 0) {
            val p = track[i]
            val dt = (cur.nanos - p.nanos) / 1e9
            if (firstDt >= 0 && dt > firstDt + AVG_SPAN_S) break
            val d = distance(p.lat, p.lon, cur.lat, cur.lon)
            val need = if (rawSpeed >= MIN_MOVE_MPS) {
                // Arrow short factor for walking: 0.12*acc, 0.55-2.5m → responsive at 1 km/h without jitter
                ((p.acc + cur.acc) * 0.5f * SHORT_NOISE_FACTOR).coerceIn(SHORT_MIN_DISPLACEMENT_M, SHORT_MAX_DISPLACEMENT_M)
            } else {
                ((p.acc + cur.acc) * 0.5f * NOISE_FACTOR).coerceIn(MIN_DISPLACEMENT_M, MAX_DISPLACEMENT_M)
            }
            if (d < need || dt < MIN_COG_DT_S) continue
            val implied = d / dt
            if (implied < MIN_MOVE_MPS) continue
            if (rawSpeed < 0 && dt < 2.0) continue
            if (rawSpeed >= 0 && implied > rawSpeed * 5.0 + 1.5) continue // wildly inconsistent vs Doppler
            val br = Math.toRadians(initialBearing(p.lat, p.lon, cur.lat, cur.lon).toDouble())
            val w = d / need
            sx += Math.sin(br) * w; sy += Math.cos(br) * w; wsum += w
            if (firstDt < 0) firstDt = dt
            bestD = maxOf(bestD, d / need)
        }
        if (wsum <= 0.0) return null
        val mean = ((Math.toDegrees(atan2(sx, sy)).toFloat() % 360f) + 360f) % 360f
        val coherence = sqrt(sx * sx + sy * sy) / wsum          // 1 = all baselines agree (Arrow coherence)
        val q = ((bestD / 2.0).coerceIn(0.3, 0.9) * coherence).toFloat().coerceIn(0.2f, 0.9f)
        return mean to q
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
        const val STATIONARY_MPS = 0.15
        const val MIN_MOVE_MPS = 0.15
        const val MIN_COG_DT_S = 0.5
        const val AVG_SPAN_S = 2.0
        const val NOISE_FACTOR = 0.45f
        const val MIN_DISPLACEMENT_M = 1.5f
        const val MAX_DISPLACEMENT_M = 8f
        const val SHORT_NOISE_FACTOR = 0.12f
        const val SHORT_MIN_DISPLACEMENT_M = 0.55f
        const val SHORT_MAX_DISPLACEMENT_M = 2.5f
        const val TRACK_WINDOW_NS = 12_000_000_000L
        const val MAX_POINTS = 30
        const val LIVE_NS = 3_000_000_000L
        const val HOLD_MAX_NS = 10_000_000_000L

        private const val R = 6371008.8
        fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
            val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * R * atan2(sqrt(a), sqrt(1 - a))
        }
        fun initialBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
            val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
            val y = sin(dl) * cos(p2)
            val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
            return ((Math.toDegrees(atan2(y, x)).toFloat() % 360f) + 360f) % 360f
        }
    }
}
