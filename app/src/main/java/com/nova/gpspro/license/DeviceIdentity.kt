package com.nova.gpspro.license

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

/** Stable identifier of THIS device that licenses are bound to. */
interface DeviceIdentity {
    /** `SHA-256(product : ANDROID_ID : app-constant)` as 64 lower-case hex chars. The only identity that leaves the device. */
    fun deviceHash(): String

    /** Short human-readable form of [deviceHash] (`NVD-XXXX-XXXX-XXXX-XXXX`) for the screen / support. */
    fun shortId(): String
}

/**
 * Built on ANDROID_ID, which survives app restarts, phone reboots and app re-installs (it only changes on a
 * factory reset or when the app is signed with a different key). Deliberately NOT based on Android Keystore,
 * whose keys are lost when the app is uninstalled.
 *
 * The raw ANDROID_ID never leaves this class: it is never displayed, stored by the app, or sent to the server —
 * only the salted hash is. If the platform returns no ANDROID_ID (very rare) a random id kept in app storage is
 * used instead; that one does not survive a re-install.
 */
class AndroidDeviceIdentity(private val context: Context) : DeviceIdentity {
    private val hash: String by lazy { computeHash(LicenseConfig.PRODUCT, rawId()) }

    override fun deviceHash(): String = hash
    override fun shortId(): String = shortIdOf(hash)

    private fun rawId(): String {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        if (!androidId.isNullOrBlank()) return androidId
        val prefs = context.getSharedPreferences("nova_device", Context.MODE_PRIVATE)
        prefs.getString("fallback", null)?.let { return it }
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString("fallback", fresh).apply()
        return fresh
    }

    companion object {
        /** App-specific salt; part of the hash definition, so it must never change after release. */
        private const val SALT = "nova-gps-pro/device-binding/v1"
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"   // RFC 4648 base32

        /** Pure & deterministic (unit-tested): hex(SHA-256(product + ":" + rawId + ":" + SALT)). */
        fun computeHash(product: String, rawId: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest("$product:$rawId:$SALT".toByteArray(Charsets.UTF_8))
            return d.joinToString("") { "%02x".format(it) }
        }

        /** First 80 bits of the hash → base32 → NVD-XXXX-XXXX-XXXX-XXXX. */
        fun shortIdOf(hashHex: String): String {
            val sb = StringBuilder()
            var buffer = 0; var bits = 0
            for (i in 0 until 10) {
                buffer = (buffer shl 8) or hashHex.substring(i * 2, i * 2 + 2).toInt(16); bits += 8
                while (bits >= 5) { sb.append(ALPHABET[(buffer shr (bits - 5)) and 31]); bits -= 5 }
                buffer = buffer and ((1 shl bits) - 1)
            }
            return "NVD-" + sb.chunked(4).joinToString("-")
        }
    }
}
