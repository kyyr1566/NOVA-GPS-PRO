package com.nova.gpspro.ui

/**
 * Pure radar plotting geometry — no Android imports, sensors, randomness, collision offsets,
 * or animation. A plotted point keeps the exact GPS-derived distance fraction and bearing.
 */
data class RadarTarget(
    val id: String,
    val name: String,
    val distanceM: Double,
    val bearing: Float
)

object RadarPlot {

    /** Distance band retained for callers that need a real range-relative classification. */
    enum class Band { NEAR, MEDIUM, FAR }

    /** A destination placed on the radar: exact polar coordinates around the GPS fix. */
    class Plotted(val target: RadarTarget, val radius: Float, val bearing: Float, val band: Band)

    /** Classifies the actual distance relative to the selected range. */
    fun band(distanceM: Double, rangeM: Double): Band {
        val fraction = if (rangeM > 0.0 && rangeM.isFinite() && distanceM.isFinite()) {
            (distanceM / rangeM).coerceIn(0.0, 1.0)
        } else 0.0
        return when {
            fraction < NEAR_BELOW -> Band.NEAR
            fraction < MEDIUM_BELOW -> Band.MEDIUM
            else -> Band.FAR
        }
    }

    /**
     * Maps only destinations within [rangeM] to their exact position. A point at the current
     * location stays at radius zero, and overlapping destinations remain overlapped instead of
     * being shifted away from their true bearing or distance.
     */
    fun plan(targets: List<RadarTarget>, rangeM: Double, outer: Float): List<Plotted> {
        if (!rangeM.isFinite() || rangeM <= 0.0 || !outer.isFinite() || outer < 0f) return emptyList()
        return targets.mapNotNull { target ->
            val distance = target.distanceM
            val bearing = target.bearing
            if (!distance.isFinite() || distance < 0.0 || distance > rangeM || !bearing.isFinite()) {
                null
            } else {
                val radius = (outer.toDouble() * distance / rangeM).toFloat()
                Plotted(target, radius.coerceIn(0f, outer), norm360(bearing), band(distance, rangeM))
            }
        }
    }

    private fun norm360(value: Float): Float {
        var angle = value % 360f
        if (angle < 0f) angle += 360f
        return angle
    }

    const val NEAR_BELOW = 0.40
    const val MEDIUM_BELOW = 0.75
}
