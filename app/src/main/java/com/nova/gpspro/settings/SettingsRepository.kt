package com.nova.gpspro.settings

import android.content.Context
import java.util.Locale

enum class DistanceUnit { KM, MILE }
enum class SpeedUnit { KMH, MPH }

/** Persistent settings (SharedPreferences, committed to disk). */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var language: String
        get() = peekLanguage(prefs)
        set(v) { prefs.edit().putString(KEY_LANG, v).commit() }

    var distanceUnit: DistanceUnit
        get() = runCatching { DistanceUnit.valueOf(prefs.getString(KEY_DIST, "KM")!!) }.getOrDefault(DistanceUnit.KM)
        set(v) { prefs.edit().putString(KEY_DIST, v.name).apply() }

    var speedUnit: SpeedUnit
        get() = runCatching { SpeedUnit.valueOf(prefs.getString(KEY_SPEED, "KMH")!!) }.getOrDefault(SpeedUnit.KMH)
        set(v) { prefs.edit().putString(KEY_SPEED, v.name).apply() }

    /** Navigation speedometer style: false = circular (default), true = digital. */
    var digitalSpeedometer: Boolean
        get() = prefs.getBoolean(KEY_SPEEDO, false)
        set(v) { prefs.edit().putBoolean(KEY_SPEEDO, v).apply() }

    companion object {
        private const val KEY_SPEEDO = "speedometer_digital"
        const val FILE = "nova_settings"
        private const val KEY_LANG = "language"
        private const val KEY_DIST = "distance_unit"
        private const val KEY_SPEED = "speed_unit"

        fun peekLanguage(context: Context) =
            peekLanguage(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

        private fun peekLanguage(p: android.content.SharedPreferences): String =
            p.getString(KEY_LANG, null) ?: if (Locale.getDefault().language == "ar") "ar" else "en"
    }
}
