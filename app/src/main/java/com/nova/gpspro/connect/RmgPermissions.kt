package com.nova.gpspro.connect

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build

/**
 * Safe runtime permission & Android 14–15 ForegroundService type resolver for RMG CONNECT.
 * Handles SDK 30 (Android 11) through SDK 35+ (Android 15/16) gracefully.
 */
object RmgPermissions {

    /** Runtime permissions needed before scanning/connecting nearby devices via Wi-Fi Direct & Bluetooth. */
    fun nearbyPermissionsForSdk(sdkInt: Int): List<String> = when {
        sdkInt >= 33 -> listOf(
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )
        sdkInt >= 31 -> listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        else -> listOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    fun missingNearbyPermissions(ctx: Context, sdkInt: Int = Build.VERSION.SDK_INT): List<String> {
        val needed = ArrayList<String>()
        for (p in nearbyPermissionsForSdk(sdkInt)) {
            if (ctx.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p)
            }
        }
        return needed
    }

    fun hasWifiDirectPermission(ctx: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        if (sdkInt >= 33) {
            ctx.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED ||
                hasLocationPermission(ctx)
        } else {
            hasLocationPermission(ctx)
        }

    fun hasBluetoothConnectPermission(ctx: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        if (sdkInt >= 31) {
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true

    fun hasBluetoothScanPermission(ctx: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        if (sdkInt >= 31) {
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else hasLocationPermission(ctx)

    fun hasRecordAudioPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun hasLocationPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun needsPostNotificationPrompt(ctx: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    /**
     * Pure, Android 14–15 compliant ForegroundService type bitmask calculator.
     * Prevents SecurityException by never requesting `location` or `microphone` types unless
     * the corresponding runtime permission is granted and the capability is eligible.
     */
    fun resolveForegroundServiceType(
        sdkInt: Int,
        hasLocationPerm: Boolean,
        isSharingLocation: Boolean,
        hasRecordAudioPerm: Boolean,
        includeMicrophoneType: Boolean
    ): Int {
        if (sdkInt < 29) return 0
        var mask = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (isSharingLocation && hasLocationPerm) {
            mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (sdkInt >= 30 && includeMicrophoneType && hasRecordAudioPerm) {
            mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return mask
    }
}
