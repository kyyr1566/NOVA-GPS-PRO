package com.nova.gpspro.license

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LicenseVerifierTest {
    private val issuer = TestSigner()
    private val server = TestSigner()
    private val verifier = Ed25519LicenseVerifier(issuer.publicKey, server.publicKey, appVersionCode = 5)
    private val device = sha256Hex("device-A")
    private val licenseId = "NL-ABCDEFGHIJKLMNOPQRSTUVWXY2"

    private fun lic(code: String): Verification<LicenseClaims> = verifier.verifyLicense(code)
    private fun err(code: String): LicenseError? = (lic(code) as? Verification.Invalid)?.error
    private fun claims() = (lic(issuer.license()) as Verification.Valid).value

    @Test fun validLicense() {
        val r = lic(issuer.license())
        assertTrue(r is Verification.Valid)
        val c = (r as Verification.Valid).value
        assertEquals(licenseId, c.licenseId)
        assertEquals("LIFETIME", c.licenseType)
    }

    @Test fun toleratesPastedWhitespace() {
        val code = issuer.license()
        assertTrue(lic("  " + code.substring(0, 30) + "\n" + code.substring(30) + " \n").let { it is Verification.Valid })
    }

    @Test fun modifiedCodeFails() {
        val code = issuer.license()
        assertEquals(LicenseError.MALFORMED, err(code.dropLast(5)))                     // truncated signature
        assertEquals(LicenseError.MALFORMED, err(code.substringBeforeLast('.')))        // signature missing
    }

    @Test fun anyModifiedPayloadByteFails() {
        val code = issuer.license()
        val len = java.util.Base64.getUrlDecoder().decode(code.split('.')[1]).size
        for (i in 0 until len) {
            // either the structure breaks (MALFORMED) or the signature no longer matches – never Valid
            val r = lic(mutatePart(code, 1) { b -> b.also { it[i] = (it[i].toInt() xor 1).toByte() } })
            assertTrue("payload byte $i: $r", r is Verification.Invalid)
        }
        // claiming another license id while keeping the signature → signature check must catch it
        val swapped = mutatePart(code, 1) { String(it).replace(licenseId, "NL-ZZZZZZZZZZZZZZZZZZZZZZZZZ2").toByteArray() }
        assertEquals(LicenseError.INVALID_SIGNATURE, err(swapped))
    }

    @Test fun anyModifiedSignatureByteFails() {
        val code = issuer.license()
        for (i in 0 until 64) {
            assertEquals("signature byte $i", LicenseError.INVALID_SIGNATURE,
                err(mutatePart(code, 2) { b -> b.also { it[i] = (it[i].toInt() xor 1).toByte() } }))
        }
    }

    @Test fun foreignSignerFails() = assertEquals(LicenseError.INVALID_SIGNATURE, err(TestSigner().license()))

    @Test fun garbage() {
        assertEquals(LicenseError.EMPTY_CODE, err("  \n "))
        assertEquals(LicenseError.MALFORMED, err("hello"))
        assertEquals(LicenseError.MALFORMED, err("NOVA1.abc.def"))
        assertEquals(LicenseError.MALFORMED, err(issuer.license().replaceFirst("NOVA1", "NOVA2")))
        assertEquals(LicenseError.MALFORMED, err(issuer.signed("NOVA1", LicenseCodec.LICENSE_DOMAIN, "v=1\nproduct=x\n".toByteArray())))
        assertEquals(LicenseError.MALFORMED, err(issuer.license(licenseId = "short")))
    }

    @Test fun authenticButUnacceptable() {
        assertEquals(LicenseError.WRONG_PRODUCT, err(issuer.license(product = "com.nova.gpspro")))
        assertEquals(LicenseError.UNSUPPORTED_VERSION, err(issuer.license(version = 2)))
        assertEquals(LicenseError.UNSUPPORTED_VERSION, err(issuer.license(type = "MONTHLY")))
        assertEquals(LicenseError.UNSUPPORTED_VERSION, err(issuer.license(binding = "NONE")))
        assertEquals(LicenseError.UNSUPPORTED_VERSION, err(issuer.license(minVersion = 6)))   // app is 5
        assertTrue(lic(issuer.license(minVersion = 5)) is Verification.Valid)
    }

    @Test fun aReceiptIsNotALicenseAndViceVersa() {
        assertEquals(LicenseError.MALFORMED, err(server.receipt(licenseId, device)))
        val r = verifier.verifyActivation(issuer.license(), claims(), device)
        assertEquals(Verification.Invalid(LicenseError.INVALID_ACTIVATION), r)
    }

    @Test fun domainSeparation() {
        // the issuer key signing a receipt-shaped payload with the LICENSE domain must not pass as a receipt
        val v = Ed25519LicenseVerifier(issuer.publicKey, issuer.publicKey, 5)
        val badReceipt = issuer.signed(LicenseCodec.RECEIPT_PREFIX, LicenseCodec.LICENSE_DOMAIN,
            "v=1\nproduct=NOVA_GPS_PRO\nlicenseId=$licenseId\ndeviceHash=$device\nfirstActivatedAt=1\n".toByteArray())
        assertEquals(Verification.Invalid(LicenseError.INVALID_ACTIVATION), v.verifyActivation(badReceipt, claims(), device))
    }

    @Test fun receiptChecks() {
        val c = claims()
        assertTrue(verifier.verifyActivation(server.receipt(licenseId, device), c, device) is Verification.Valid)
        assertEquals(Verification.Invalid(LicenseError.LICENSE_ALREADY_BOUND),
            verifier.verifyActivation(server.receipt(licenseId, device), c, sha256Hex("device-B")))
        assertEquals(Verification.Invalid(LicenseError.INVALID_ACTIVATION),
            verifier.verifyActivation(TestSigner().receipt(licenseId, device), c, device))
        assertEquals(Verification.Invalid(LicenseError.INVALID_ACTIVATION),
            verifier.verifyActivation(server.receipt("NL-ZZZZZZZZZZZZZZZZZZZZZZZZZ2", device), c, device))
        assertEquals(Verification.Invalid(LicenseError.INVALID_ACTIVATION),
            verifier.verifyActivation(server.receipt(licenseId, device, product = "OTHER"), c, device))
        assertEquals(Verification.Invalid(LicenseError.INVALID_ACTIVATION),
            verifier.verifyActivation(mutatePart(server.receipt(licenseId, device), 1) { b -> b.also { it[12] = (it[12].toInt() xor 1).toByte() } }, c, device))
        assertEquals(Verification.Invalid(LicenseError.INVALID_ACTIVATION), verifier.verifyActivation("garbage", c, device))
    }

    @Test fun missingKeysFailClosed() {
        val none = Ed25519LicenseVerifier(null, null, 5)
        assertEquals(Verification.Invalid(LicenseError.NOT_CONFIGURED), none.verifyLicense(issuer.license()))
        assertEquals(Verification.Invalid(LicenseError.NOT_CONFIGURED), none.verifyActivation(server.receipt(licenseId, device), claims(), device))
    }

    @Test fun shippedDefaultConfigurationIsNotConfigured() {
        // no keys are baked into the repository: a build without LICENSE_PUBLIC_KEY / ACTIVATION_PUBLIC_KEY accepts nothing
        assertEquals(null, LicenseConfig.decodeKey(""))
        assertEquals(null, LicenseConfig.decodeKey("not base64!!"))
        assertEquals(null, LicenseConfig.decodeKey("AAAA"))                 // wrong length
        assertEquals(32, LicenseConfig.decodeKey(java.util.Base64.getEncoder().encodeToString(ByteArray(32)))!!.size)
    }
}
