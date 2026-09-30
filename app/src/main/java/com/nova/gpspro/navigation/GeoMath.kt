package com.nova.gpspro.navigation

import android.location.Location

/** Exact WGS-84 geodesic math (Vincenty via android.location.Location). Raw doubles only. */
object GeoMath {
    data class Result(val distanceM: Double, val initialBearing: Float)

    fun between(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Result {
        val r = FloatArray(2)
        Location.distanceBetween(lat1, lon1, lat2, lon2, r)
        return Result(r[0].toDouble(), norm360(r[1]))
    }

    fun norm360(a: Float): Float { var x = a % 360f; if (x < 0) x += 360f; return x }

    /** Normalize to (-180, 180] – guarantees the shortest rotation path (359→1 = +2°). */
    fun norm180(a: Float): Float {
        var x = a % 360f
        if (x > 180f) x -= 360f
        if (x <= -180f) x += 360f
        return x
    }
}
