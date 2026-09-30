package com.nova.gpspro.ui

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure radar plotting geometry — JVM-tested, no Android imports, no sensors, no randomness.
 *
 * Every marker position is derived ONLY from the real geodesic distance and bearing of a
 * saved destination relative to the current GPS fix:
 *  • radius = the destination's true fraction of the selected range;
 *  • angle  = the destination's true initial bearing (0° = up on the screen).
 *
 * Marker de-overlapping first moves a marker along its own bearing to a neighbouring ring
 * (direction stays exact); only when ring spreading alone cannot separate two markers is a
 * bounded angular nudge applied, so the geographic direction of every destination stays as
 * true as the screen allows. Nothing is ever dropped or invented.
 */
data class RadarTarget(
    val id: String,
    val name: String,
    val distanceM: Double,
    val bearing: Float
)

object RadarPlot {

    /** Marker colour band — derived from the REAL distance relative to the selected range. */
    enum class Band { NEAR, MEDIUM, FAR }

    /** A destination placed on the radar: polar coordinates around the phone's GPS position. */
    class Plotted(val target: RadarTarget, val radius: Float, val bearing: Float, val band: Band)

    /** Near = strong green, medium = yellow, far = red (real distance ÷ selected range). */
    fun band(distanceM: Double, rangeM: Double): Band {
        val r = if (rangeM > 0.0) rangeM else 1.0
        val d = if (distanceM.isFinite()) distanceM.coerceAtLeast(0.0) else 0.0
        val f = (d / r).coerceIn(0.0, 1.0)
        return when {
            f < NEAR_BELOW -> Band.NEAR
            f < MEDIUM_BELOW -> Band.MEDIUM
            else -> Band.FAR
        }
    }

    /**
     * Places every target that is already known to be inside the range. Deterministic and
     * lossless: the output always contains exactly one marker per input target, every marker
     * stays within [inner, outer], and its bearing never deviates more than [maxAngularDeg]
     * from the true one.
     */
    fun plan(
        targets: List<RadarTarget>,
        rangeM: Double,
        inner: Float,
        outer: Float,
        separation: Float,
        ringStep: Float,
        maxAngularDeg: Float = DEFAULT_MAX_ANGULAR_DEG
    ): List<Plotted> {
        val lo = minOf(inner, outer)
        val hi = maxOf(inner, outer)
        val usable = (hi - lo).coerceAtLeast(0f)
        val sep = separation.coerceAtLeast(0f)
        val step = ringStep.coerceAtLeast(1f)
        val maxDeg = maxAngularDeg.coerceAtLeast(0f)
        val out = ArrayList<Plotted>(targets.size)
        val placedX = ArrayList<Float>(targets.size)
        val placedY = ArrayList<Float>(targets.size)

        // Nearest first: the closest destination keeps its exact true place, farther ones yield.
        val ordered = targets.sortedWith(compareBy<RadarTarget> { it.distanceM }.thenBy { it.id })
        for (t in ordered) {
            val rel = if (rangeM > 0.0) (t.distanceM / rangeM).coerceIn(0.0, 1.0) else 0.0
            val desired = (lo + usable * rel).toFloat()
            val radials = radialCandidates(desired, lo, hi, step)
            val trueBearing = norm360(t.bearing)

            // Pass 1 — the exact true bearing is always preferred; only the ring may move.
            var best: Cand? = null
            for (radius in radials) {
                val clear = clearance(radius, trueBearing, placedX, placedY)
                val cand = Cand(radius, trueBearing, abs(radius - desired), clear)
                if (best == null || better(cand, best, sep)) best = cand
                if (cand.clearance >= sep) break   // first fitting ring = smallest radial move
            }
            // Pass 2 — bounded angular nudge, only when ring spreading alone cannot separate.
            if (maxDeg > 0f && (best == null || best!!.clearance < sep)) {
                for (k in 1..NUDGE_STEPS) {
                    val fraction = k.toFloat() / NUDGE_STEPS
                    val angularCost = fraction * step * ANGULAR_COST_FACTOR
                    for (sign in SIGNS) {
                        val bearing = norm360(trueBearing + sign * maxDeg * fraction)
                        for (radius in radials) {
                            val clear = clearance(radius, bearing, placedX, placedY)
                            val cand = Cand(radius, bearing, abs(radius - desired) + angularCost, clear)
                            if (best == null || better(cand, best, sep)) best = cand
                        }
                    }
                }
            }
            val win = best ?: Cand(desired.coerceIn(lo, hi), trueBearing, 0f, Float.MAX_VALUE)
            out.add(Plotted(t, win.radius, win.bearing, band(t.distanceM, rangeM)))
            val a = Math.toRadians(win.bearing.toDouble())
            placedX.add(win.radius * cos(a).toFloat())
            placedY.add(win.radius * sin(a).toFloat())
        }
        return out
    }

    /** [desired, desired±step, desired±2·step, …] — every candidate kept inside [lo, hi]. */
    private fun radialCandidates(desired: Float, lo: Float, hi: Float, step: Float): FloatArray {
        val list = ArrayList<Float>()
        val base = desired.coerceIn(lo, hi)
        list.add(base)
        var k = 1
        while (list.size < 64) {
            val up = base + step * k
            val down = base - step * k
            var added = false
            if (up <= hi) { list.add(up); added = true }
            if (down >= lo) { list.add(down); added = true }
            if (!added) break
            k++
        }
        return list.toFloatArray()
    }

    private class Cand(val radius: Float, val bearing: Float, val cost: Float, val clearance: Float)

    /** Separation wins first; then the cheapest move (rings before angle). */
    private fun better(a: Cand, b: Cand, sep: Float): Boolean {
        val aOk = a.clearance >= sep
        val bOk = b.clearance >= sep
        if (aOk != bOk) return aOk
        if (aOk) return a.cost < b.cost
        if (a.clearance != b.clearance) return a.clearance > b.clearance
        return a.cost < b.cost
    }

    /** Distance from a candidate polar position to the closest already-placed marker. */
    private fun clearance(radius: Float, bearing: Float, placedX: ArrayList<Float>, placedY: ArrayList<Float>): Float {
        if (placedX.isEmpty()) return Float.MAX_VALUE
        val a = Math.toRadians(bearing.toDouble())
        val x = radius * cos(a).toFloat()
        val y = radius * sin(a).toFloat()
        var min = Float.MAX_VALUE
        for (i in placedX.indices) {
            val dx = x - placedX[i]
            val dy = y - placedY[i]
            val d = sqrt(dx * dx + dy * dy)
            if (d < min) min = d
        }
        return min
    }

    private fun norm360(a: Float): Float { var x = a % 360f; if (x < 0f) x += 360f; return x }

    /** Near = strong green, medium = yellow, far = red (fractions of the selected range). */
    const val NEAR_BELOW = 0.40
    const val MEDIUM_BELOW = 0.75
    const val DEFAULT_MAX_ANGULAR_DEG = 9f
    private const val NUDGE_STEPS = 3
    private const val ANGULAR_COST_FACTOR = 1.6f
    private val SIGNS = intArrayOf(1, -1)
}
