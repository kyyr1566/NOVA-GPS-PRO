package com.nova.gpspro.onboarding

import android.content.Context

/**
 * Persistent flag for onboarding. Uses SharedPreferences (commit) — survives process kill and reboot.
 * Only cleared on app data clear / reinstall.
 */
object OnboardingManager {
    const val FILE = "nova_onboarding"
    const val KEY_COMPLETED = "onboarding_completed"

    fun isCompleted(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_COMPLETED, false)

    fun setCompleted(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_COMPLETED, true).commit()
    }

    /** For testing only */
    fun reset(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().remove(KEY_COMPLETED).commit()
    }

    // Overloads for unit testing without Context (SharedPreferences injection)
    fun isCompleted(prefs: android.content.SharedPreferences): Boolean =
        prefs.getBoolean(KEY_COMPLETED, false)

    fun setCompleted(prefs: android.content.SharedPreferences) {
        prefs.edit().putBoolean(KEY_COMPLETED, true).commit()
    }

    fun reset(prefs: android.content.SharedPreferences) {
        prefs.edit().remove(KEY_COMPLETED).commit()
    }
}
