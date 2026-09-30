package com.nova.gpspro.location

import android.location.Location
import kotlin.math.max
import kotlin.math.min

/**
 * Location Quality Gate.
 *
 * Studies accuracy, age (elapsedRealtime), ordering, reported speed, distance from the
 * previous accepted fix and physically plausible motion. Sudden jumps are held as
 * "suspect" and only adopted once confirmed by consistent follow-up fixes, or after a
 * short bounded time so GPS never freezes for long.
 */
class GpsQualityFilter {

    enum class Verdict { ACCEPT, ACCEPT_REANCHOR, SUSPECT, REJECT }

    private var last: Location? = null
    private var suspect: Location? = null
    private var suspectCount = 0
    private var firstSuspectNanos = 0L

    fun reset() {
        last = null; suspect = null; suspectCount = 0; firstSuspectNanos = 0L
    }

    fun evaluate(loc: Location, nowNanos: Long): Verdict {
        // 1) Basic sanity
        if (!loc.hasAccuracy() || loc.accuracy <= 0f || loc.accuracy > MAX_ACCURACY_M) return Verdict.REJECT
        val lat = loc.latitude; val lon = loc.longitude
        if (lat.isNaN() || lon.isNaN() || lat < -90 || lat > 90 || lon < -180 || lon > 180) return Verdict.REJECT
        if (lat == 0.0 && lon == 0.0) return Verdict.REJECT

        // 2) Stale fixes (cached / delivered late)
        val ageNs = nowNanos - loc.elapsedRealtimeNanos
        if (ageNs > MAX_AGE_NS) return Verdict.REJECT

        val prev = last
        if (prev == null) {
            adopt(loc); return Verdict.ACCEPT_REANCHOR
        }

        // 3) Ordering
        val dt = (loc.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9
        if (dt <= 0.0) return Verdict.REJECT

        // 4) After a long gap we cannot judge motion: re-anchor
        if (dt > REANCHOR_GAP_S) { adopt(loc); return Verdict.ACCEPT_REANCHOR }

        // 5) A fix much worse than a very recent precise one adds nothing
        if (loc.accuracy > prev.accuracy * 4f && loc.accuracy > 40f && dt < 3.0) return Verdict.REJECT

        // 6) Plausible-motion test
        val d = prev.distanceTo(loc).toDouble()
        if (d <= allowedDistance(prev, loc, dt)) {
            adopt(loc); return Verdict.ACCEPT
        }

        // 7) Jump → suspect handling
        val s = suspect
        if (s == null) {
            startSuspect(loc)
        } else {
            val dts = (loc.elapsedRealtimeNanos - s.elapsedRealtimeNanos) / 1e9
            val consistent = dts > 0 && s.distanceTo(loc) <= allowedDistance(s, loc, dts)
            if (consistent) { suspectCount++; suspect = loc } else startSuspect(loc)
        }
        val confirmed = suspectCount >= CONFIRM_COUNT
        val timedOut = (loc.elapsedRealtimeNanos - firstSuspectNanos) > SUSPECT_MAX_NS && suspectCount >= 2
        val starving = (loc.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) > STARVE_NS
        if (confirmed || timedOut || starving) {
            adopt(loc); return Verdict.ACCEPT_REANCHOR
        }
        return Verdict.SUSPECT
    }

    private fun allowedDistance(a: Location, b: Location, dt: Double): Double {
        val va = if (a.hasSpeed()) a.speed.toDouble() else 0.0
        val vb = if (b.hasSpeed()) b.speed.toDouble() else 0.0
        val vMax = min(max(va, vb) + MAX_ACCEL * dt, MAX_SPEED) + 3.0
        return vMax * dt + b.accuracy + a.accuracy * 0.5 + 5.0
    }

    private fun startSuspect(loc: Location) {
        suspect = loc; suspectCount = 1; firstSuspectNanos = loc.elapsedRealtimeNanos
    }

    private fun adopt(loc: Location) {
        last = loc; suspect = null; suspectCount = 0
    }

    companion object {
        const val MAX_ACCURACY_M = 150f
        const val MAX_AGE_NS = 10_000_000_000L
        const val REANCHOR_GAP_S = 12.0
        const val MAX_ACCEL = 8.0          // m/s² generous (sport car ~ 5)
        const val MAX_SPEED = 95.0         // m/s (~340 km/h)
        const val CONFIRM_COUNT = 3
        const val SUSPECT_MAX_NS = 4_000_000_000L
        const val STARVE_NS = 6_000_000_000L
    }
}
