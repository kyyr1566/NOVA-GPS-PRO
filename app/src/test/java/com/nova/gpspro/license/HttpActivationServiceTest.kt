package com.nova.gpspro.license

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class HttpActivationServiceTest {
    private val req = ActivationRequest("NOVA1.aaa.bbb", sha256Hex("d"), "NOVA_GPS_PRO", 3)
    private val ok = HttpResponse(200, """{"status":"ACTIVATED","receipt":"NOVAACT1.abc_-.def_-"}""")

    private fun call(reply: HttpResponse, base: String = "https://license.example.com/"): Pair<ActivationResult, FakeTransport> {
        val t = FakeTransport { _, _ -> reply }
        return HttpActivationService(base, t).activate(req) to t
    }

    @Test fun postsExactlyTheAgreedFieldsToV1Activate() {
        val (r, t) = call(ok)
        assertEquals(ActivationResult.Success("NOVAACT1.abc_-.def_-"), r)
        assertEquals("https://license.example.com/v1/activate", t.lastUrl)
        assertEquals("""{"product":"NOVA_GPS_PRO","licenseCode":"NOVA1.aaa.bbb","deviceHash":"${req.deviceHash}","appVersion":3}""", t.lastBody)
    }

    @Test fun onlyHttpsServersAreUsed() {
        for (base in listOf("", "   ", "http://license.example.com", "ftp://x.y", "https://", "license.example.com", "https://a b.com", "https://x\"y")) {
            val t = FakeTransport { _, _ -> ok }
            assertEquals(base, ActivationResult.Failure(LicenseError.NOT_CONFIGURED), HttpActivationService(base, t).activate(req))
            assertNull("no request may be sent: $base", t.lastUrl)
        }
        assertEquals("HTTPS://h.example.com/api", HttpActivationService.normalizeBase(" HTTPS://h.example.com/api// "))
    }

    @Test fun serverAnswersAreMapped() {
        fun status(code: Int, s: String) = call(HttpResponse(code, """{"status":"$s"}""")).first
        assertEquals(ActivationResult.Failure(LicenseError.LICENSE_ALREADY_BOUND), status(409, "LICENSE_ALREADY_BOUND"))
        assertEquals(ActivationResult.Failure(LicenseError.LICENSE_NOT_FOUND), status(404, "LICENSE_NOT_FOUND"))
        assertEquals(ActivationResult.Failure(LicenseError.LICENSE_REVOKED), status(403, "LICENSE_REVOKED"))
        assertEquals(ActivationResult.Failure(LicenseError.INVALID_SIGNATURE), status(400, "INVALID_LICENSE"))
        assertEquals(ActivationResult.Failure(LicenseError.SERVER_ERROR), status(429, "RATE_LIMITED"))
        assertEquals(ActivationResult.Failure(LicenseError.SERVER_ERROR), status(400, "INVALID_REQUEST"))
        assertEquals(ActivationResult.Failure(LicenseError.SERVER_ERROR), status(500, "SERVER_ERROR"))
    }

    @Test fun garbledOrIncompleteAnswersNeverActivate() {
        for (r in listOf(
            HttpResponse(200, ""), HttpResponse(200, "<html>captive portal</html>"), HttpResponse(502, "Bad gateway"),
            HttpResponse(200, """{"status":"ACTIVATED"}"""),                                   // no receipt
            HttpResponse(500, """{"status":"ACTIVATED","receipt":"NOVAACT1.a.b"}"""),         // wrong HTTP code
            HttpResponse(200, """{"status":"activated","receipt":"NOVAACT1.a.b"}""")
        )) assertEquals(r.body, ActivationResult.Failure(LicenseError.SERVER_ERROR), call(r).first)
    }

    @Test fun transportFailures() {
        fun fail(e: IOException) = HttpActivationService("https://x.example.com", ThrowingTransport(e)).activate(req)
        assertEquals(ActivationResult.Failure(LicenseError.NETWORK_REQUIRED), fail(UnknownHostException("x")))
        assertEquals(ActivationResult.Failure(LicenseError.NETWORK_REQUIRED), fail(java.net.SocketTimeoutException()))
        assertEquals(ActivationResult.Failure(LicenseError.NETWORK_REQUIRED), fail(java.net.ConnectException()))
        assertEquals(ActivationResult.Failure(LicenseError.SERVER_ERROR), fail(SSLHandshakeException("bad cert")))
    }

    @Test fun requestJsonEscapes() {
        val j = HttpActivationService.requestJson(ActivationRequest("a\"b\\c\n", "h", "p", 1))
        assertTrue(j, j.contains("\"licenseCode\":\"a\\\"b\\\\c\\u000a\""))
    }
}
