package com.nova.gpspro.license

/** Outcome of a verification step. */
sealed class Verification<out T> {
    data class Valid<out T>(val value: T) : Verification<T>()
    data class Invalid(val error: LicenseError) : Verification<Nothing>()
}

/**
 * The two cryptographic questions the app asks, both answered offline:
 *  1. [verifyLicense]    – is this code a genuine license signed by the issuer (and acceptable to this app)?
 *  2. [verifyActivation] – did the activation server bind exactly this license to exactly THIS device?
 * Pure functions of their inputs (no UI, no storage, no network), so implementations are swappable.
 */
interface LicenseVerifier {
    fun verifyLicense(code: String): Verification<LicenseClaims>
    fun verifyActivation(receipt: String, license: LicenseClaims, deviceHash: String): Verification<ActivationClaims>
}

/**
 * Real Ed25519 verification against two PUBLIC keys:
 *  - [licensePublicKey]    the offline issuer's key (signs licenses)
 *  - [activationPublicKey] the activation server's key (signs per-device receipts)
 * A null key means "this build is not configured": every check fails closed with NOT_CONFIGURED.
 * There is no code path that returns Valid without a successful signature check.
 */
class Ed25519LicenseVerifier(
    private val licensePublicKey: ByteArray?,
    private val activationPublicKey: ByteArray?,
    private val appVersionCode: Long
) : LicenseVerifier {

    override fun verifyLicense(code: String): Verification<LicenseClaims> {
        val key = licensePublicKey ?: return Verification.Invalid(LicenseError.NOT_CONFIGURED)
        if (LicenseCodec.normalize(code).isEmpty()) return Verification.Invalid(LicenseError.EMPTY_CODE)
        val parsed = LicenseCodec.parseLicense(code) ?: return Verification.Invalid(LicenseError.MALFORMED)
        // 1) authenticity first – nothing inside the payload is trusted before this passes
        if (!Ed25519.verify(key, LicenseCodec.LICENSE_DOMAIN + parsed.blob.payload, parsed.blob.signature))
            return Verification.Invalid(LicenseError.INVALID_SIGNATURE)
        // 2) meaning of the (now authentic) claims
        val c = parsed.claims
        if (c.product != LicenseConfig.PRODUCT) return Verification.Invalid(LicenseError.WRONG_PRODUCT)
        if (c.version != LicenseCodec.VERSION || c.licenseType != LicenseCodec.TYPE_LIFETIME ||
            c.deviceBinding != LicenseCodec.BINDING_FIRST_ACTIVATION || c.minVersion > appVersionCode)
            return Verification.Invalid(LicenseError.UNSUPPORTED_VERSION)
        return Verification.Valid(c)
    }

    override fun verifyActivation(receipt: String, license: LicenseClaims, deviceHash: String): Verification<ActivationClaims> {
        val key = activationPublicKey ?: return Verification.Invalid(LicenseError.NOT_CONFIGURED)
        val parsed = LicenseCodec.parseReceipt(receipt) ?: return Verification.Invalid(LicenseError.INVALID_ACTIVATION)
        if (!Ed25519.verify(key, LicenseCodec.RECEIPT_DOMAIN + parsed.blob.payload, parsed.blob.signature))
            return Verification.Invalid(LicenseError.INVALID_ACTIVATION)
        val c = parsed.claims
        if (c.product != LicenseConfig.PRODUCT || c.licenseId != license.licenseId)
            return Verification.Invalid(LicenseError.INVALID_ACTIVATION)
        // authentic receipt, but issued for another device → the license belongs elsewhere
        if (!c.deviceHash.equals(deviceHash, ignoreCase = true)) return Verification.Invalid(LicenseError.LICENSE_ALREADY_BOUND)
        return Verification.Valid(c)
    }
}
