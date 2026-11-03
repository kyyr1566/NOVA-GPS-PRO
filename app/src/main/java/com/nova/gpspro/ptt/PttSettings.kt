package com.nova.gpspro.ptt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import java.util.Locale
import java.util.UUID

enum class TalkMode {
    /** Default: press and hold to transmit, release to stop. */
    HOLD_TO_TALK,
    /** Optional: tap once to start transmitting, tap again to stop. */
    TAP_TO_TOGGLE
}

interface PttSettingsStore {
    val deviceId: String
    var displayName: String
    var talkMode: TalkMode
    var rogerBeepEnabled: Boolean
    var permissionRationaleShown: Boolean
}

/** Persistent Walkie-Talkie preferences backed by SharedPreferences. */
class PttSettings(context: Context) : PttSettingsStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override val deviceId: String = prefs.getString(KEY_DEVICE_ID, null)
        ?: UUID.randomUUID().toString().replace("-", "").take(16).uppercase(Locale.US).also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    override var displayName: String
        get() = prefs.getString(KEY_DISPLAY_NAME, "")?.trim().orEmpty()
        set(value) {
            prefs.edit().putString(KEY_DISPLAY_NAME, value.trim().take(28)).apply()
        }

    override var talkMode: TalkMode
        get() = runCatching {
            TalkMode.valueOf(prefs.getString(KEY_TALK_MODE, TalkMode.HOLD_TO_TALK.name)!!)
        }.getOrDefault(TalkMode.HOLD_TO_TALK)
        set(value) {
            prefs.edit().putString(KEY_TALK_MODE, value.name).apply()
        }

    override var rogerBeepEnabled: Boolean
        get() = prefs.getBoolean(KEY_ROGER_BEEP, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ROGER_BEEP, value).apply()
        }

    override var permissionRationaleShown: Boolean
        get() = prefs.getBoolean(KEY_RATIONALE_SHOWN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_RATIONALE_SHOWN, value).apply()
        }

    companion object {
        const val PREFS_NAME = "nova_ptt_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_TALK_MODE = "talk_mode"
        private const val KEY_ROGER_BEEP = "roger_beep"
        private const val KEY_RATIONALE_SHOWN = "perm_rationale_shown"
    }
}

/** Pure in-memory settings implementation for deterministic JVM unit tests. */
class InMemoryPttSettings(
    override val deviceId: String = "DEVTEST0001",
    override var displayName: String = "Tester",
    override var talkMode: TalkMode = TalkMode.HOLD_TO_TALK,
    override var rogerBeepEnabled: Boolean = true,
    override var permissionRationaleShown: Boolean = false
) : PttSettingsStore

/**
 * Android 11–16 runtime permission & ForegroundService type policy for Walkie-Talkie.
 * Zero Bluetooth permissions. Never accesses GPS coordinates.
 */
object PttPermissions {

    /** Essential permissions required before creating or joining a Walkie-Talkie session. */
    fun requiredCorePermissions(sdkInt: Int): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (sdkInt >= 33) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    /** Optional notification permission requested alongside core permissions on Android 13+. */
    fun allPromptPermissions(sdkInt: Int): List<String> = buildList {
        addAll(requiredCorePermissions(sdkInt))
        if (sdkInt >= 33) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun missingCorePermissions(ctx: Context, sdkInt: Int = Build.VERSION.SDK_INT): List<String> =
        requiredCorePermissions(sdkInt).filter {
            ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

    fun hasRecordAudio(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun hasWifiDirectDiscoveryPermission(ctx: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        if (sdkInt >= 33) {
            ctx.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else {
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * On Android 11–12 (API 30–32), Android's WifiP2pManager requires system Location toggle ON
     * to discover Wi-Fi Direct peers, even though Walkie-Talkie never reads GPS coordinates.
     * On Android 13+ (API 33+) with NEARBY_WIFI_DEVICES + neverForLocation, Location toggle is NOT required.
     */
    fun isLocationToggleRequiredButOff(ctx: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        if (sdkInt >= 33) return false
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return false
        return !lm.isLocationEnabled
    }

    /**
     * Computes the Android 14–16 safe ForegroundService bitmask.
     * Never requests FOREGROUND_SERVICE_TYPE_MICROPHONE unless RECORD_AUDIO is granted AND
     * either the app is currently visible in the foreground or microphone FGS was already active.
     */
    fun resolveForegroundServiceType(
        sdkInt: Int,
        hasRecordAudioPerm: Boolean,
        isAppForegroundOrAlreadyMicEligible: Boolean
    ): Int {
        var mask = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (sdkInt >= 30 && hasRecordAudioPerm && isAppForegroundOrAlreadyMicEligible) {
            mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return mask
    }
}
