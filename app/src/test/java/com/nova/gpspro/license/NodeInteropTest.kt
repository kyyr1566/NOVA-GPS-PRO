package com.nova.gpspro.license

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Kotlin verifier must accept what the Node issuer/server produce, and refuse their tampered variants. */
class NodeInteropTest {
    private val F = NodeInteropFixture
    private val verifier = Ed25519LicenseVerifier(
        LicenseConfig.decodeKey(F.LICENSE_PUBLIC_KEY), LicenseConfig.decodeKey(F.ACTIVATION_PUBLIC_KEY), appVersionCode = 1)

    @Test fun nodeIssuedLicenseVerifies() {
        val r = verifier.verifyLicense(F.LICENSE_CODE)
        assertTrue(r.toString(), r is Verification.Valid)
        val c = (r as Verification.Valid).value
        assertEquals(F.LICENSE_ID, c.licenseId)
        assertEquals("LIFETIME", c.licenseType)
        assertEquals("NOVA_GPS_PRO", c.product)
        assertEquals(1790000000L, c.issuedAtEpochSec)
    }

    @Test fun nodeReceiptVerifiesForTheRightDeviceOnly() {
        val lic = (verifier.verifyLicense(F.LICENSE_CODE) as Verification.Valid).value
        val ok = verifier.verifyActivation(F.RECEIPT, lic, F.DEVICE_HASH)
        assertTrue(ok.toString(), ok is Verification.Valid)
        assertEquals(1790000123L, (ok as Verification.Valid).value.firstActivatedAtEpochSec)
        assertEquals(Verification.Invalid(LicenseError.LICENSE_ALREADY_BOUND), verifier.verifyActivation(F.RECEIPT, lic, F.OTHER_DEVICE_HASH))
        assertEquals(Verification.Invalid(LicenseError.LICENSE_ALREADY_BOUND), verifier.verifyActivation(F.RECEIPT_OTHER_DEVICE, lic, F.DEVICE_HASH))
        assertEquals(Verification.Invalid(LicenseError.INVALID_ACTIVATION), verifier.verifyActivation(F.RECEIPT_WRONG_SIGNER, lic, F.DEVICE_HASH))
    }

    @Test fun nodeLicenseTamperedIsRefused() {
        val len = java.util.Base64.getUrlDecoder().decode(F.LICENSE_CODE.split('.')[1]).size
        for (i in 0 until len) {
            val r = verifier.verifyLicense(mutatePart(F.LICENSE_CODE, 1) { b -> b.also { it[i] = (it[i].toInt() xor 1).toByte() } })
            assertTrue("payload byte $i: $r", r is Verification.Invalid)
        }
        for (i in 0 until 64) {
            assertEquals(Verification.Invalid(LicenseError.INVALID_SIGNATURE),
                verifier.verifyLicense(mutatePart(F.LICENSE_CODE, 2) { b -> b.also { it[i] = (it[i].toInt() xor 1).toByte() } }))
        }
    }

    @Test fun nodeLicenseNeedingNewerAppIsRefused() =
        assertEquals(Verification.Invalid(LicenseError.UNSUPPORTED_VERSION), verifier.verifyLicense(F.LICENSE_REQUIRING_APP_99))

    @Test fun deviceHashMatchesTheNodeDefinition() =
        assertEquals(F.DEVICE_HASH, AndroidDeviceIdentity.computeHash("NOVA_GPS_PRO", F.RAW_ANDROID_ID))

    @Test fun managerAcceptsNodeActivation() {
        val storage = InMemoryStorage()
        val svc = object : ActivationService {
            override fun activate(request: ActivationRequest) = ActivationResult.Success(F.RECEIPT)
        }
        val m = LicenseManager(FixedIdentity(F.DEVICE_HASH), verifier, storage, svc, 1)
        assertTrue(m.activate(F.LICENSE_CODE) is LicenseState.Activated)
        assertTrue(LicenseManager(FixedIdentity(F.DEVICE_HASH), verifier, storage, svc, 1).check() is LicenseState.Activated)
    }
}
