package com.nova.gpspro.ptt

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec

/**
 * Persistent device identity for the Walkie-Talkie.
 *
 * The private key is generated inside the Android Keystore and never leaves it. Only the public key
 * (SPKI bytes) is exported, and its SHA-256 pin is what the host advertises through DNS-SD so a guest
 * can verify exactly which session/device it is joining. This replaces the removed channel code:
 * identity now comes from a hardware-backed key instead of a shared secret typed by the user.
 */
object PttHostIdentity {
    private const val KEY_ALIAS = "rmg_ptt_identity_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    private fun loadOrCreateKeyPair(): KeyPair? = try {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = ks.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
        if (existing != null) {
            KeyPair(existing.certificate.publicKey, existing.privateKey)
        } else {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            generator.initialize(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            generator.generateKeyPair()
        }
    } catch (_: Throwable) {
        null
    }

    /** Encoded public key (SubjectPublicKeyInfo) or null when the Keystore is unavailable. */
    fun publicKeyBytes(context: Context): ByteArray? = loadOrCreateKeyPair()?.public?.encoded

    /** Stable certificate pin advertised to guests (SHA-256 hex, never empty on a healthy device). */
    fun certificatePin(context: Context): String? =
        publicKeyBytes(context)?.takeIf { it.isNotEmpty() }
            ?.let { PttSecurity.computeCertificatePinIssuerFromEncoded(it) }
}
