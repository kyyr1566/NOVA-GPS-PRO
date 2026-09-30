package com.nova.gpspro.ui

import com.nova.gpspro.settings.DistanceUnit
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Precise distance text for the Destinations list (pure, JVM-testable).
 * Input: exact meters from Location.distanceBetween. No integer rounding of kilometres.
 *   KM  : only m / km. < 1000 m → whole metres, no decimals (236.53 → 237 m)
 *         < 10 km  → up to 2 decimals (2.35 km) ; ≥ 10 km → up to 1 decimal (138.5 km)
 *   MILE: < 1000 ft → feet, up to 1 decimal ; < 10 mi → up to 2 decimals ; ≥ 10 mi → 1 decimal
 * Trailing zeros are dropped ("138 km" only when the value really is 138.0).
 */
object DestDistance {
    enum class U { M, KM, FT, MI }
    class Parts(val number: String, val unit: U)

    fun format(meters: Double, unit: DistanceUnit): Parts? {
        if (!meters.isFinite() || meters < 0) return null
        return when (unit) {
            DistanceUnit.KM -> when {
                Math.round(meters) < 1000 -> Parts(num(meters, 0), U.M)   // whole metres only (236.53 → 237)
                meters < 10_000 -> Parts(num(meters / 1000, 2), U.KM)
                else -> Parts(num(meters / 1000, 1), U.KM)
            }
            DistanceUnit.MILE -> {
                val ft = meters * 3.28083989501; val mi = meters / 1609.344
                when {
                    ft < 10 -> Parts(num(ft, 0), U.FT)
                    ft < 1000 -> Parts(num(ft, 1), U.FT)
                    mi < 10 -> Parts(num(mi, 2), U.MI)
                    else -> Parts(num(mi, 1), U.MI)
                }
            }
        }
    }

    fun num(v: Double, decimals: Int): String =
        BigDecimal(v).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().let {
            if (it.scale() < 0) it.setScale(0) else it
        }.toPlainString()
}
