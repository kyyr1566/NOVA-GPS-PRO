package com.nova.gpspro.license

import com.nova.gpspro.BuildConfig
import java.util.Base64

/**
 * Where licensing is configured. Nothing here is secret and NOTHING can switch licensing off:
 * the three values come from build settings (see app/build.gradle.kts → `local.properties`, `-P…`
 * or environment variables):
 *
 *   LICENSE_SERVER_BASE_URL   https://… base URL of the activation server
 *   LICENSE_PUBLIC_KEY        raw 32-byte Ed25519 PUBLIC key (base64) of the license issuer
 *   ACTIVATION_PUBLIC_KEY     raw 32-byte Ed25519 PUBLIC key (base64) of the activation server
 *
 * Only PUBLIC keys can ever be placed here; the private keys live offline / on the server.
 * Anything missing or malformed leaves the build "not configured": every activation is refused.
 */
object LicenseConfig {
    /** Product identifier that every license / receipt must carry. */
    const val PRODUCT = "NOVA_GPS_PRO"

    fun createVerifier(appVersionCode: Long): LicenseVerifier = Ed25519LicenseVerifier(
        licensePublicKey = decodeKey(BuildConfig.LICENSE_PUBLIC_KEY),
        activationPublicKey = decodeKey(BuildConfig.ACTIVATION_PUBLIC_KEY),
        appVersionCode = appVersionCode
    )

    fun createActivationService(): ActivationService = HttpActivationService(BuildConfig.LICENSE_SERVER_BASE_URL)

    /** Base64 (standard or URL-safe) of exactly 32 bytes, else null (= not configured). */
    fun decodeKey(b64: String): ByteArray? {
        val s = b64.trim()
        if (s.isEmpty()) return null
        val raw = runCatching { Base64.getMimeDecoder().decode(s.replace('-', '+').replace('_', '/')) }.getOrNull()
        return raw?.takeIf { it.size == Ed25519.PUBLIC_KEY_SIZE }
    }
}
