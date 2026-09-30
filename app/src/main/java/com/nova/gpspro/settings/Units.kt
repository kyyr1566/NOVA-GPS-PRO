package com.nova.gpspro.settings

import android.content.Context
import com.nova.gpspro.R
import java.util.Locale

/** All user-facing unit formatting. Internal values stay in meters and m/s. */
class Units(private val ctx: Context, private val settings: SettingsRepository) {

    fun speedValue(mps: Double): Double = when (settings.speedUnit) {
        SpeedUnit.KMH -> mps * 3.6
        SpeedUnit.MPH -> mps * 2.2369362920544
    }

    fun speedUnitLabel(): String = ctx.getString(if (settings.speedUnit == SpeedUnit.KMH) R.string.u_kmh else R.string.u_mph)

    fun speed(mps: Double): String = "${displayInt(speedValue(mps))} ${speedUnitLabel()}"

    fun distance(m: Double): String {
        if (m < 0 || m.isNaN()) return "—"
        return when (settings.distanceUnit) {
            DistanceUnit.KM -> when {
                Math.round(m) < 1000 -> "${Math.round(m)} ${ctx.getString(R.string.u_m)}"   // whole metres only
                m < 100_000 -> String.format(Locale.US, "%.2f %s", m / 1000, ctx.getString(R.string.u_km))
                else -> String.format(Locale.US, "%.0f %s", m / 1000, ctx.getString(R.string.u_km))
            }
            DistanceUnit.MILE -> {
                val ft = m * 3.28083989501
                val mi = m / 1609.344
                when {
                    ft < 1000 -> "${ft.toInt()} ${ctx.getString(R.string.u_ft)}"
                    mi < 100 -> String.format(Locale.US, "%.2f %s", mi, ctx.getString(R.string.u_mi))
                    else -> String.format(Locale.US, "%.0f %s", mi, ctx.getString(R.string.u_mi))
                }
            }
        }
    }

    fun accuracy(m: Float): String = when (settings.distanceUnit) {
        DistanceUnit.KM -> String.format(Locale.US, "±%d %s", Math.round(m), ctx.getString(R.string.u_m))
        DistanceUnit.MILE -> String.format(Locale.US, "±%.0f %s", m * 3.28083989501, ctx.getString(R.string.u_ft))
    }

    companion object {
        /** Display-only rounding; internal values remain Double. */
        fun displayInt(v: Double): Int = if (v.isFinite() && v > 0) Math.round(v).toInt() else 0
    }

    fun coord(v: Double): String = String.format(Locale.US, "%.6f", v)
}
