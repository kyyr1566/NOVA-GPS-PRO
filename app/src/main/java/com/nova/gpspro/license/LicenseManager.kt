package com.nova.gpspro.license

import android.content.Context

/**
 * Single entry point for "is this device licensed?". The UI and [com.nova.gpspro.MainActivity] only talk to
 * this class; verification, device identity, storage and server activation are separate layers behind interfaces.
 *
 * Trust model — the app counts as licensed only if ALL of these verify locally (offline) right now:
 *   1. the stored license carries a valid issuer signature (Ed25519) and is acceptable to this app version;
 *   2. the stored receipt carries a valid activation-server signature for that same license id;
 *   3. the receipt's device hash equals THIS device's hash.
 * Stored data is never trusted by itself; it is re-verified once per process (result cached in memory only).
 */
class LicenseManager(
    private val identity: DeviceIdentity,
    private val verifier: LicenseVerifier,
    private val storage: LicenseStorage,
    private val activation: ActivationService,
    private val appVersionCode: Long
) {
    @Volatile private var verified: LicenseState.Activated? = null

    fun shortDeviceId(): String = identity.shortId()

    /** Local license check (never touches the network). Cheap after the first successful call in this process. */
    fun check(): LicenseState {
        verified?.let { return it }
        val raw = storage.load() ?: return LicenseState.NotActivated
        val record = ActivationRecord.decode(raw) ?: return LicenseState.Rejected(LicenseError.INVALID_ACTIVATION)
        return when (val r = verifyRecord(record)) {
            is Verification.Valid -> LicenseState.Activated(r.value).also { verified = it }
            is Verification.Invalid -> LicenseState.Rejected(r.error)
        }
    }

    /**
     * First activation / re-activation (needs the server). A license that fails the local signature check is
     * refused WITHOUT any network traffic. Nothing is stored unless the server's receipt verifies for this device.
     */
    fun activate(code: String): LicenseState {
        val license = when (val l = verifier.verifyLicense(code)) {
            is Verification.Invalid -> return LicenseState.Rejected(l.error)
            is Verification.Valid -> l.value
        }
        val licenseText = LicenseCodec.normalize(code)
        val deviceHash = identity.deviceHash()
        val receipt = when (val a = activation.activate(
            ActivationRequest(licenseText, deviceHash, LicenseConfig.PRODUCT, appVersionCode))) {
            is ActivationResult.Failure -> return LicenseState.Rejected(a.error)
            is ActivationResult.Success -> a.receipt
        }
        val record = ActivationRecord(licenseText, LicenseCodec.normalize(receipt))
        val info = when (val v = verifyRecord(record)) {
            is Verification.Invalid -> return LicenseState.Rejected(
                if (v.error == LicenseError.LICENSE_ALREADY_BOUND) v.error else LicenseError.INVALID_ACTIVATION)
            is Verification.Valid -> v.value
        }
        if (!storage.save(record.encode())) return LicenseState.Rejected(LicenseError.STORAGE_FAILED)
        return LicenseState.Activated(info).also { verified = it }
    }

    private fun verifyRecord(record: ActivationRecord): Verification<LicenseInfo> {
        val license = when (val l = verifier.verifyLicense(record.license)) {
            is Verification.Invalid -> return l
            is Verification.Valid -> l.value
        }
        val receipt = when (val a = verifier.verifyActivation(record.receipt, license, identity.deviceHash())) {
            is Verification.Invalid -> return a
            is Verification.Valid -> a.value
        }
        return Verification.Valid(LicenseInfo(license.licenseId, license.issuedAtEpochSec, receipt.firstActivatedAtEpochSec))
    }

    companion object {
        fun create(context: Context): LicenseManager {
            val versionCode = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
            }.getOrDefault(1L)
            return LicenseManager(
                identity = AndroidDeviceIdentity(context),
                verifier = LicenseConfig.createVerifier(versionCode),
                storage = KeystoreLicenseStorage(context),
                activation = LicenseConfig.createActivationService(),
                appVersionCode = versionCode
            )
        }
    }
}
