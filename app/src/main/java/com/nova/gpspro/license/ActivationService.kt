package com.nova.gpspro.license

/** What the app sends for activation: the license code + salted device hash + product. Nothing else. */
data class ActivationRequest(
    val licenseCode: String,
    val deviceHash: String,
    val product: String,
    val appVersion: Long
)

sealed class ActivationResult {
    /** [receipt] = server-signed `NOVAACT1…` proof for this license + device; it is verified locally before use. */
    data class Success(val receipt: String) : ActivationResult()
    data class Failure(val error: LicenseError) : ActivationResult()
}

/**
 * First activation (online). Whatever an implementation returns is STILL verified locally by
 * [LicenseVerifier.verifyActivation], so a fake / hijacked / patched service can never activate the app.
 */
interface ActivationService {
    fun activate(request: ActivationRequest): ActivationResult
}
