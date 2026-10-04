package com.nova.gpspro.license

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Locally cached activation data. No license code or server credential is stored here.
 * New: expiresAt supports Supabase admin columns expires_at / blocked_at / block_reason
 * and license types 1_DAY, 7_DAYS, 30_DAYS, 90_DAYS, 1_YEAR, LIFETIME.
 * LIFETIME => expiresAt == null (never expires). Duration starts at first successful activation.
 */
data class StoredLicense(
    val licenseId: String,
    val licenseType: String,
    val activatedAt: String,
    val expiresAt: String? = null
)

sealed class LicenseActivationResult {
    data class Success(val license: StoredLicense) : LicenseActivationResult()
    object InvalidCode : LicenseActivationResult()
    object UsedOnAnotherDevice : LicenseActivationResult()
    object NetworkError : LicenseActivationResult()
    object TemporaryServerError : LicenseActivationResult()
    object Cancelled : LicenseActivationResult()
    object Blocked : LicenseActivationResult()
    object Expired : LicenseActivationResult()
}

/** Stops a pending activation from persisting locally when the user leaves the activation screen. */
class ActivationCancellation {
    @Volatile private var cancelled = false

    fun isCancelled(): Boolean = cancelled

    @Synchronized
    fun cancel() { cancelled = true }

    @Synchronized
    internal fun commitIfActive(commit: () -> Boolean): Boolean = if (cancelled) false else commit()
}

/**
 * One-time activation client. Normal app startup reads SharedPreferences only; this class never
 * contacts Supabase unless [activate] is explicitly called from the activation screen.
 * Offline after activation: no periodic checks. Expiry is enforced locally via stored expires_at.
 */
class LicenseManager(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns a cached activation only when all required local fields are present and not expired/blocked. */
    fun cachedLicense(): StoredLicense? {
        if (preferences.getString(KEY_ACTIVATION_STATE, null) != STATE_ACTIVATED) return null
        val id = preferences.getString(KEY_LICENSE_ID, null)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val type = preferences.getString(KEY_LICENSE_TYPE, null)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val activatedAt = preferences.getString(KEY_ACTIVATED_AT, null)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val expiresAtRaw = preferences.getString(KEY_EXPIRES_AT, null)?.trim()?.takeIf { it.isNotEmpty() }
        // Backward compatibility: old LIFETIME licenses stored without expires_at remain valid (never expire).
        // For time-limited types, if expires_at is present and in the past -> treat as expired (return null to show activation screen).
        if (isExpired(expiresAtRaw)) return null
        return StoredLicense(id, type, activatedAt, expiresAtRaw)
    }

    fun isActivated(): Boolean = cachedLicense() != null

    /** True if locally stored license has expires_at in the past (used to show expired message on activation screen). */
    fun isLocallyExpired(): Boolean {
        if (preferences.getString(KEY_ACTIVATION_STATE, null) != STATE_ACTIVATED) return false
        val expiresAt = preferences.getString(KEY_EXPIRES_AT, null)?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return isExpired(expiresAt)
    }

    /** True if expiresAt is non-null and Instant is before now. LIFETIME (null) never expires. */
    private fun isExpired(expiresAt: String?): Boolean {
        if (expiresAt.isNullOrBlank()) return false
        return try {
            val instant = Instant.parse(expiresAt)
            instant.isBefore(Instant.now())
        } catch (_: DateTimeParseException) {
            // Try alternative format: some Supabase returns without Z, try to parse as ISO
            try {
                // Fallback: try Instant.parse with trimmed, replace space with T
                val normalized = expiresAt.trim().replace(" ", "T")
                Instant.parse(normalized).isBefore(Instant.now())
            } catch (_: Exception) {
                false // unparseable -> don't block valid license (backward compat)
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Performs the only online request used by the app: the user's explicit first activation. */
    fun activate(
        code: String,
        cancellation: ActivationCancellation = ActivationCancellation()
    ): LicenseActivationResult {
        val licenseCode = code.trim()
        if (licenseCode.isEmpty()) return LicenseActivationResult.InvalidCode
        if (cancellation.isCancelled()) return LicenseActivationResult.Cancelled

        var connection: HttpURLConnection? = null
        try {
            // Generate/reuse the Android Keystore key before making the request. Only its public
            // key is used; the private key never leaves Android Keystore. Device ID = SHA256(pubKey).
            val deviceId = deviceIdFromKeystorePublicKey()
            val body = JSONObject()
                .put("code", licenseCode)
                .put("deviceId", deviceId)
                .put("deviceModel", Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android Device")
                .put("androidVersion", Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString())
                .toString()
                .toByteArray(Charsets.UTF_8)

            connection = (URL(FUNCTION_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("apikey", PUBLISHABLE_KEY)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setFixedLengthStreamingMode(body.size)
            }
            connection.outputStream.use { output -> output.write(body) }

            val status = connection.responseCode
            val responseBody = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()

            if (cancellation.isCancelled()) return LicenseActivationResult.Cancelled
            if (status !in 200..299) return classifyFailure(status, responseBody)

            val json = try {
                JSONObject(responseBody)
            } catch (_: Exception) {
                return LicenseActivationResult.TemporaryServerError
            }
            val explicitlyFailed = (json.opt("success") as? Boolean == false) || (json.opt("ok") as? Boolean == false)
            if (explicitlyFailed || json.has("error")) return classifyFailure(status, responseBody)

            val recordObject = json.optJSONObject("license")
                ?: json.optJSONObject("data")?.let { it.optJSONObject("license") ?: it.optJSONObject("data") ?: it }
                ?: json.optJSONObject("result")
                ?: json

            val id = findString(recordObject, "licenseId", "license_id", "id")
                ?: findString(json, "licenseId", "license_id", "id")
                ?: return classifyFailure(status, responseBody)
            val type = findString(recordObject, "licenseType", "license_type", "type")
                ?: findString(json, "licenseType", "license_type")
                ?: return LicenseActivationResult.TemporaryServerError
            val activatedAt = findString(recordObject, "activatedAt", "activated_at")
                ?: findString(json, "activatedAt", "activated_at")
                ?: Instant.now().toString()
            // New columns: expires_at (null for LIFETIME), blocked_at etc. Only expires_at is stored locally.
            val expiresAt = findString(recordObject, "expiresAt", "expires_at", "expiresAt", "expires_at")
                ?: findString(json, "expiresAt", "expires_at")

            // If server returns status BLOCKED/EXPIRED even with 200, treat as blocked/expired.
            val statusString = findString(recordObject, "status") ?: findString(json, "status")
            if (statusString != null) {
                when (statusString.lowercase(Locale.US)) {
                    "blocked" -> return LicenseActivationResult.Blocked
                    "expired" -> return LicenseActivationResult.Expired
                }
            }

            // Also check expiresAt already past (server may return already expired)
            if (!expiresAt.isNullOrBlank() && isExpired(expiresAt)) {
                return LicenseActivationResult.Expired
            }

            val stored = StoredLicense(id, type, activatedAt, expiresAt?.takeIf { it.isNotBlank() })

            // Commit atomically before reporting success, so a process exit after the request
            // cannot leave the user locked out. The backend permits a same-device retry if needed (idempotent for BURNED same device).
            val saved = cancellation.commitIfActive {
                val editor = preferences.edit()
                    .putString(KEY_LICENSE_ID, stored.licenseId)
                    .putString(KEY_LICENSE_TYPE, stored.licenseType)
                    .putString(KEY_ACTIVATED_AT, stored.activatedAt)
                    .putString(KEY_ACTIVATION_STATE, STATE_ACTIVATED)
                if (stored.expiresAt != null) editor.putString(KEY_EXPIRES_AT, stored.expiresAt)
                else editor.remove(KEY_EXPIRES_AT)
                editor.commit()
            }
            if (cancellation.isCancelled()) return LicenseActivationResult.Cancelled
            return if (saved) LicenseActivationResult.Success(stored)
            else LicenseActivationResult.TemporaryServerError
        } catch (_: UnknownHostException) {
            return LicenseActivationResult.NetworkError
        } catch (_: ConnectException) {
            return LicenseActivationResult.NetworkError
        } catch (_: SocketTimeoutException) {
            return LicenseActivationResult.NetworkError
        } catch (_: IOException) {
            return LicenseActivationResult.NetworkError
        } catch (_: Exception) {
            // Includes local Keystore/setup failures. Never expose implementation details in UI.
            return LicenseActivationResult.TemporaryServerError
        } finally {
            connection?.disconnect()
        }
    }

    @Synchronized
    private fun deviceIdFromKeystorePublicKey(): String {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val publicKey = keyStore.getCertificate(KEY_ALIAS)?.publicKey ?: run {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE_P256))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            generator.generateKeyPair().public
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
        return buildString(digest.size * 2) {
            digest.forEach { byte -> append(HEX[(byte.toInt() ushr 4) and 0x0f]); append(HEX[byte.toInt() and 0x0f]) }
        }
    }

    private fun classifyFailure(status: Int, body: String): LicenseActivationResult {
        val message = body.lowercase(Locale.US)

        // Blocked must be checked before used_on_another_device / invalid to give precise message
        val blocked = listOf(
            "blocked", "license_blocked", "blocked_at", "block_reason", "license is blocked", "this license has been blocked", "has been blocked", "is blocked", "blocked license"
        ).any(message::contains)
        if (blocked) return LicenseActivationResult.Blocked

        val expired = listOf(
            "expired", "license_expired", "license is expired", "has expired", "expired license", "license_expired", "expires_at", "license has expired"
        ).any(message::contains)
        // Be careful: "expires_at" alone is not enough, but if message says expired, we return Expired
        // Also check status field explicitly: if body contains "\"status\":\"expired\"" or similar
        if (expired && (message.contains("expired") || message.contains("expire"))) {
            // Guard: don't misclassify generic messages that mention expires_at without expired status
            if (message.contains("expired") || message.contains("is expired") || message.contains("\"expired\"") || message.contains("status") && message.contains("expired")) {
                return LicenseActivationResult.Expired
            }
        }
        // More precise: if body explicitly contains status expired/blocked
        if (message.contains("\"status\"") && message.contains("expired")) return LicenseActivationResult.Expired
        if (message.contains("\"status\"") && message.contains("blocked")) return LicenseActivationResult.Blocked

        val usedOnAnotherDevice = listOf(
            "used_on_another_device", "already_used_on_another_device", "already_activated_on_another_device",
            "activated_on_another_device", "already activated on another device", "already activated on a different device",
            "already burned", "already_burned", "license_burned", "license_burned_by_another_device", "burned",
            "license_used", "license_already_used", "already_used", "different device", "another device",
            "bound_to_another_device", "bound to another device", "device mismatch", "device_id_mismatch",
            "device_conflict", "already_bound", "claimed_by_other_device"
        ).any(message::contains)
        if (usedOnAnotherDevice || status == HttpURLConnection.HTTP_CONFLICT) {
            return LicenseActivationResult.UsedOnAnotherDevice
        }

        val invalidCode = listOf(
            "invalid license", "invalid_license", "invalid code", "invalid_code", "license_code_invalid", "code_invalid",
            "license not found", "license_not_found", "license_does_not_exist", "no_license_found", "code_not_found",
            "code does not exist", "unknown license"
        ).any(message::contains)
        if (invalidCode || status == HttpURLConnection.HTTP_BAD_REQUEST || status == 404 || status == 422) {
            return LicenseActivationResult.InvalidCode
        }

        // If status is 403 and body mentions blocked/expired, we already handled above; otherwise generic server error
        if (status == 403 && message.contains("block")) return LicenseActivationResult.Blocked
        if (status == 410 && message.contains("expire")) return LicenseActivationResult.Expired

        return LicenseActivationResult.TemporaryServerError
    }

    private fun findString(json: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = json.opt(key)
            when (value) {
                is String -> value.trim().takeIf { it.isNotEmpty() }?.let { return it }
                is Number -> return value.toString()
                // Supabase may return null JSON null, skip
                JSONObject.NULL -> continue
            }
        }
        return null
    }

    private companion object {
        const val PREFS_NAME = "nova_license"
        const val KEY_LICENSE_ID = "license_id"
        const val KEY_LICENSE_TYPE = "license_type"
        const val KEY_ACTIVATED_AT = "activated_at"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_ACTIVATION_STATE = "activation_state"
        const val STATE_ACTIVATED = "activated"

        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "nova_gps_pro_device_key_p256"
        const val EC_CURVE_P256 = "secp256r1"
        const val HEX = "0123456789abcdef"

        const val FUNCTION_URL = "https://ozzeuhykwskwvgauetlg.supabase.co/functions/v1/activate-license"
        const val PUBLISHABLE_KEY = "sb_publishable_-poCrqnG4f6Yg2cryqMBIg_WKotVUwj"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
    }
}
