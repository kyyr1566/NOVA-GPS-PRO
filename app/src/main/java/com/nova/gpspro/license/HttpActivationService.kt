package com.nova.gpspro.license

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.SSLException

class HttpResponse(val code: Int, val body: String)

/** Tiny seam so the protocol logic is unit-testable without a network. */
interface HttpTransport {
    @Throws(IOException::class)
    fun postJson(url: String, json: String): HttpResponse
}

/** HTTPS POST with the platform stack (no extra library). Redirects are NOT followed (no silent downgrade). */
object UrlConnectionTransport : HttpTransport {
    private const val MAX_RESPONSE = 16 * 1024

    override fun postJson(url: String, json: String): HttpResponse {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return HttpResponse(code, stream?.use { readCapped(it) } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    private fun readCapped(input: InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(2048)
        while (out.size() < MAX_RESPONSE) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }
}

/**
 * Talks to the NOVA license server (server/license-server):
 *
 *   POST {base}/v1/activate   {"product","licenseCode","deviceHash","appVersion"}
 *   → {"status":"ACTIVATED","receipt":"NOVAACT1…"} | {"status":"LICENSE_ALREADY_BOUND"} | …
 *
 * [baseUrl] comes from the build setting LICENSE_SERVER_BASE_URL. Anything that is not an https:// URL
 * (including empty = not configured yet) is refused — the app never talks cleartext.
 */
class HttpActivationService(
    baseUrl: String,
    private val transport: HttpTransport = UrlConnectionTransport
) : ActivationService {

    private val endpoint: String? = normalizeBase(baseUrl)?.let { "$it/v1/activate" }

    override fun activate(request: ActivationRequest): ActivationResult {
        val url = endpoint ?: return ActivationResult.Failure(LicenseError.NOT_CONFIGURED)
        val response = try {
            transport.postJson(url, requestJson(request))
        } catch (_: SSLException) {
            return ActivationResult.Failure(LicenseError.SERVER_ERROR)          // bad certificate / TLS problem ≠ "no internet"
        } catch (_: IOException) {
            return ActivationResult.Failure(LicenseError.NETWORK_REQUIRED)      // offline, DNS, timeout, refused…
        } catch (_: Exception) {
            return ActivationResult.Failure(LicenseError.SERVER_ERROR)
        }
        return parseResponse(response)
    }

    companion object {
        private val STATUS = Regex("\"status\"\\s*:\\s*\"([A-Z_]{1,40})\"")
        private val RECEIPT = Regex("\"receipt\"\\s*:\\s*\"([A-Za-z0-9._-]{1,4096})\"")

        /** Trimmed https:// base without trailing slash, or null if not usable. */
        fun normalizeBase(raw: String): String? {
            val s = raw.trim().trimEnd('/')
            if (s.length <= "https://".length || !s.startsWith("https://", ignoreCase = true)) return null
            if (s.any { it.isWhitespace() || it == '"' || it == '\\' }) return null
            return s
        }

        fun requestJson(r: ActivationRequest): String =
            "{\"product\":${quote(r.product)},\"licenseCode\":${quote(r.licenseCode)}," +
                "\"deviceHash\":${quote(r.deviceHash)},\"appVersion\":${r.appVersion}}"

        private fun quote(s: String): String {
            val sb = StringBuilder("\"")
            for (ch in s) when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch < ' ' -> sb.append(String.format("\\u%04x", ch.code))
                else -> sb.append(ch)
            }
            return sb.append('"').toString()
        }

        /** Maps the server's answer to a result. Unknown / garbled answers are SERVER_ERROR, never success. */
        fun parseResponse(r: HttpResponse): ActivationResult {
            val status = STATUS.find(r.body)?.groupValues?.get(1)
            return when (status) {
                "ACTIVATED" -> {
                    val receipt = RECEIPT.find(r.body)?.groupValues?.get(1)
                    if (r.code == 200 && receipt != null) ActivationResult.Success(receipt)
                    else ActivationResult.Failure(LicenseError.SERVER_ERROR)
                }
                "LICENSE_ALREADY_BOUND" -> ActivationResult.Failure(LicenseError.LICENSE_ALREADY_BOUND)
                "LICENSE_NOT_FOUND" -> ActivationResult.Failure(LicenseError.LICENSE_NOT_FOUND)
                "LICENSE_REVOKED" -> ActivationResult.Failure(LicenseError.LICENSE_REVOKED)
                "INVALID_LICENSE" -> ActivationResult.Failure(LicenseError.INVALID_SIGNATURE)
                else -> ActivationResult.Failure(LicenseError.SERVER_ERROR)   // RATE_LIMITED, INVALID_REQUEST, 5xx, proxies…
            }
        }
    }
}
