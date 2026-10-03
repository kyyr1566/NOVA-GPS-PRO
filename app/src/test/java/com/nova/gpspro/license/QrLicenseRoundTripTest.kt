package com.nova.gpspro.license

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.nova.gpspro.portal.QrCodec
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "QR is not a different license": a license code encoded into a QR and scanned back (ZXing 3.5.3, the
 * decoder the app uses for camera/gallery scans) must be the identical code, and verify like pasted text.
 */
class QrLicenseRoundTripTest {
    private val issuer = TestSigner()

    private fun scanOf(text: String): String? {
        val scale = 4
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4))
        val w = m.width * scale; val h = m.height * scale
        val px = IntArray(w * h) { i -> if (m.get(i % w / scale, i / w / scale)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        return QrCodec.decodePixels(px, w, h)
    }

    @Test fun licenseCodeSurvivesQr() {
        val code = issuer.license()
        assertEquals(code, scanOf(code))
        val verifier = Ed25519LicenseVerifier(issuer.publicKey, null, 1)
        assertEquals(verifier.verifyLicense(code), verifier.verifyLicense(scanOf(code)!!))
    }

    @Test fun nodeGeneratedLicenseSurvivesQr() = assertEquals(NodeInteropFixture.LICENSE_CODE, scanOf(NodeInteropFixture.LICENSE_CODE))
}
