package com.nova.gpspro.license

import java.util.Base64

/** Claims of the signed LIFETIME license (issued offline by the generator, binds to a device on first activation). */
data class LicenseClaims(
    val version: Int,
    val product: String,
    val licenseId: String,
    val licenseType: String,
    val issuedAtEpochSec: Long,
    val deviceBinding: String,
    val minVersion: Long,
    val nonce: String
)

/** Claims of the activation receipt signed by the activation server for exactly one (license, device) pair. */
data class ActivationClaims(
    val product: String,
    val licenseId: String,
    val deviceHash: String,
    val firstActivatedAtEpochSec: Long
)

/** The exact signed bytes (payload) + 64-byte signature of a license or receipt. Not trusted until verified. */
class SignedBlob(val payload: ByteArray, val signature: ByteArray)
class ParsedLicense(val blob: SignedBlob, val claims: LicenseClaims)
class ParsedReceipt(val blob: SignedBlob, val claims: ActivationClaims)

/**
 * Wire formats (identical to server/license-server and tools/license-generator – see their protocol.js):
 *
 *   license = NOVA1.<base64url(payload)>.<base64url(Ed25519 signature)>      (works as pasted text AND as QR content)
 *   receipt = NOVAACT1.<base64url(payload)>.<base64url(Ed25519 signature)>
 *
 * payload = UTF-8 text, one `key=value` per line. The signature covers DOMAIN + payload bytes, where DOMAIN
 * differs for licenses and receipts, so one can never be replayed as the other.
 *
 * license payload : v, product, licenseId, licenseType, issuedAt, deviceBinding, minVersion, nonce
 * receipt payload : v, product, licenseId, deviceHash, firstActivatedAt
 */
object LicenseCodec {
    const val LICENSE_PREFIX = "NOVA1"
    const val RECEIPT_PREFIX = "NOVAACT1"
    const val VERSION = 1
    const val TYPE_LIFETIME = "LIFETIME"
    const val BINDING_FIRST_ACTIVATION = "FIRST_ACTIVATION"

    val LICENSE_DOMAIN: ByteArray = "NOVA-LICENSE-V1\n".toByteArray(Charsets.UTF_8)
    val RECEIPT_DOMAIN: ByteArray = "NOVA-ACTIVATION-V1\n".toByteArray(Charsets.UTF_8)

    private val LICENSE_ID = Regex("^NL-[A-Z2-7]{26}$")
    private val NONCE = Regex("^[0-9a-f]{32}$")
    private val DEVICE_HASH = Regex("^[0-9a-f]{64}$")
    private val DIGITS = Regex("^[0-9]{1,15}$")
    private val BASE64URL = Regex("^[A-Za-z0-9_-]+$")

    /** Removes whitespace/zero-width characters that pasting or QR apps tend to add. */
    fun normalize(raw: String): String = raw.filter {
        !it.isWhitespace() && it != '\u200B' && it != '\u200C' && it != '\u200D' && it != '\uFEFF' && it != '\u200E' && it != '\u200F'
    }

    fun parseLicense(code: String): ParsedLicense? {
        val (blob, f) = parseSigned(LICENSE_PREFIX, code) ?: return null
        val licenseId = f["licenseId"]?.takeIf { LICENSE_ID.matches(it) } ?: return null
        val nonce = f["nonce"]?.takeIf { NONCE.matches(it) } ?: return null
        val claims = LicenseClaims(
            version = f["v"]?.let { number(it) }?.toInt() ?: return null,
            product = f["product"]?.takeIf { it.isNotEmpty() } ?: return null,
            licenseId = licenseId,
            licenseType = f["licenseType"]?.takeIf { it.isNotEmpty() } ?: return null,
            issuedAtEpochSec = f["issuedAt"]?.let { number(it) } ?: return null,
            deviceBinding = f["deviceBinding"]?.takeIf { it.isNotEmpty() } ?: return null,
            minVersion = f["minVersion"]?.let { number(it) } ?: return null,
            nonce = nonce
        )
        return ParsedLicense(blob, claims)
    }

    fun parseReceipt(receipt: String): ParsedReceipt? {
        val (blob, f) = parseSigned(RECEIPT_PREFIX, receipt) ?: return null
        if (f["v"]?.let { number(it) } != VERSION.toLong()) return null
        val claims = ActivationClaims(
            product = f["product"]?.takeIf { it.isNotEmpty() } ?: return null,
            licenseId = f["licenseId"]?.takeIf { LICENSE_ID.matches(it) } ?: return null,
            deviceHash = f["deviceHash"]?.takeIf { DEVICE_HASH.matches(it) } ?: return null,
            firstActivatedAtEpochSec = f["firstActivatedAt"]?.let { number(it) } ?: return null
        )
        return ParsedReceipt(blob, claims)
    }

    private fun number(s: String): Long? = if (DIGITS.matches(s)) s.toLong() else null

    /** Syntax only: `PREFIX.payload.signature` + the `key=value` lines. */
    private fun parseSigned(prefix: String, text: String): Pair<SignedBlob, Map<String, String>>? {
        val parts = normalize(text).split('.')
        if (parts.size != 3 || parts[0] != prefix) return null
        if (!BASE64URL.matches(parts[1]) || !BASE64URL.matches(parts[2])) return null
        return try {
            val payload = Base64.getUrlDecoder().decode(parts[1])
            val signature = Base64.getUrlDecoder().decode(parts[2])
            if (signature.size != Ed25519.SIGNATURE_SIZE || payload.isEmpty() || payload.size > 4096) return null
            val fields = HashMap<String, String>()
            for (line in String(payload, Charsets.UTF_8).split('\n')) {
                val l = line.trim()
                if (l.isEmpty()) continue
                val eq = l.indexOf('=')
                if (eq <= 0) return null
                if (fields.put(l.substring(0, eq), l.substring(eq + 1)) != null) return null   // duplicate key
            }
            Pair(SignedBlob(payload, signature), fields)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
