package com.nova.gpspro.license

import java.io.IOException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

private val enc = Base64.getUrlEncoder().withoutPadding()

/** Test-only signer (a throw-away JDK Ed25519 key per instance). Production code has no signing capability. */
class TestSigner {
    private val pair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    /** Raw 32-byte public key = last 32 bytes of the X.509 SubjectPublicKeyInfo. */
    val publicKey: ByteArray = pair.public.encoded.let { it.copyOfRange(it.size - 32, it.size) }

    fun sign(message: ByteArray): ByteArray =
        Signature.getInstance("Ed25519").apply { initSign(pair.private); update(message) }.sign()

    fun signed(prefix: String, domain: ByteArray, payload: ByteArray): String =
        "$prefix.${enc.encodeToString(payload)}.${enc.encodeToString(sign(domain + payload))}"

    fun license(
        licenseId: String = "NL-ABCDEFGHIJKLMNOPQRSTUVWXY2",
        product: String = LicenseConfig.PRODUCT,
        type: String = "LIFETIME",
        version: Int = 1,
        binding: String = "FIRST_ACTIVATION",
        minVersion: Long = 1,
        nonce: String = "0123456789abcdef0123456789abcdef"
    ): String = signed(
        LicenseCodec.LICENSE_PREFIX, LicenseCodec.LICENSE_DOMAIN,
        ("v=$version\nproduct=$product\nlicenseId=$licenseId\nlicenseType=$type\nissuedAt=1790000000\n" +
            "deviceBinding=$binding\nminVersion=$minVersion\nnonce=$nonce\n").toByteArray()
    )

    fun receipt(licenseId: String, deviceHash: String, product: String = LicenseConfig.PRODUCT): String = signed(
        LicenseCodec.RECEIPT_PREFIX, LicenseCodec.RECEIPT_DOMAIN,
        "v=1\nproduct=$product\nlicenseId=$licenseId\ndeviceHash=$deviceHash\nfirstActivatedAt=1790000123\n".toByteArray()
    )
}

fun sha256Hex(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

/** Re-encodes one dot-separated part of a code (0 prefix, 1 payload, 2 signature) with [f] applied to its bytes. */
fun mutatePart(code: String, index: Int, f: (ByteArray) -> ByteArray): String {
    val parts = code.split('.').toMutableList()
    parts[index] = enc.encodeToString(f(Base64.getUrlDecoder().decode(parts[index])))
    return parts.joinToString(".")
}

class InMemoryStorage(var failSave: Boolean = false) : LicenseStorage {
    var data: String? = null
    override fun load(): String? = data
    override fun save(token: String): Boolean { if (failSave) return false; data = token; return true }
    override fun clear() { data = null }
}

class FixedIdentity(private val hash: String) : DeviceIdentity {
    override fun deviceHash(): String = hash
    override fun shortId(): String = AndroidDeviceIdentity.shortIdOf(hash)
}

/**
 * In-memory model of the real license server's decision table (server/license-server/lib/activate.js),
 * used to exercise the app's activation flow end-to-end without a network. TEST ONLY.
 */
class FakeServer(private val activationSigner: TestSigner, private val registered: Set<String>) : ActivationService {
    val bound = HashMap<String, String>()          // licenseId -> deviceHash
    var online = true
    var requests = 0
    var lastRequest: ActivationRequest? = null

    override fun activate(request: ActivationRequest): ActivationResult {
        requests++; lastRequest = request
        if (!online) return ActivationResult.Failure(LicenseError.NETWORK_REQUIRED)
        val claims = LicenseCodec.parseLicense(request.licenseCode)!!.claims
        if (claims.licenseId !in registered) return ActivationResult.Failure(LicenseError.LICENSE_NOT_FOUND)
        val owner = bound.getOrPut(claims.licenseId) { request.deviceHash }
        if (owner != request.deviceHash) return ActivationResult.Failure(LicenseError.LICENSE_ALREADY_BOUND)
        return ActivationResult.Success(activationSigner.receipt(claims.licenseId, request.deviceHash))
    }
}

class FakeTransport(private val reply: (String, String) -> HttpResponse) : HttpTransport {
    var lastUrl: String? = null
    var lastBody: String? = null
    override fun postJson(url: String, json: String): HttpResponse { lastUrl = url; lastBody = json; return reply(url, json) }
}

class ThrowingTransport(private val e: IOException) : HttpTransport {
    override fun postJson(url: String, json: String): HttpResponse = throw e
}

fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
