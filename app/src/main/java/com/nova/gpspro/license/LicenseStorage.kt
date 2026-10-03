package com.nova.gpspro.license

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Persists the signed license token locally. Contains NO trust: it is re-verified on every launch. */
interface LicenseStorage {
    fun load(): String?
    /** @return false if it could not be stored safely. */
    fun save(token: String): Boolean
    fun clear()
}

/**
 * AES-256-GCM with a NON-EXTRACTABLE Android Keystore key, kept in app-private preferences
 * (backup is disabled in the manifest). A copy of the data on another device can't be decrypted
 * (key is hardware/TEE-bound) and wouldn't match that device's id anyway.
 */
class KeystoreLicenseStorage(context: Context) : LicenseStorage {
    private val prefs = context.getSharedPreferences("nova_license", Context.MODE_PRIVATE)

    override fun load(): String? = try {
        val raw = prefs.getString(KEY_BLOB, null) ?: return null
        val blob = Base64.getDecoder().decode(raw)
        if (blob.size <= IV_SIZE) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(create = false) ?: return null, GCMParameterSpec(128, blob, 0, IV_SIZE))
        cipher.updateAAD(AAD)
        String(cipher.doFinal(blob, IV_SIZE, blob.size - IV_SIZE), Charsets.UTF_8)
    } catch (_: Exception) {
        null      // unreadable / tampered / key lost → behaves exactly like "not activated"
    }

    override fun save(token: String): Boolean = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(create = true)!!)
        cipher.updateAAD(AAD)
        val ct = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(KEY_BLOB, Base64.getEncoder().encodeToString(cipher.iv + ct)).commit()
    } catch (_: Exception) {
        false
    }

    override fun clear() {
        prefs.edit().remove(KEY_BLOB).commit()
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS) }
    }

    private fun secretKey(create: Boolean): SecretKey? {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "nova_license_key_v1"
        const val KEY_BLOB = "blob"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        val AAD = "nova-gps-pro:license:v1".toByteArray(Charsets.UTF_8)
    }
}
