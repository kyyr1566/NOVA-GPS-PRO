package com.nova.gpspro.license

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Ed25519 signature VERIFICATION (RFC 8032, section 5.1.7), pure Kotlin/JVM.
 *
 * Why not java.security: the platform provider only gained "Ed25519" in Android 13 (API 33)
 * while the app supports Android 11 (API 30), and the project must not pull in a new crypto
 * library for this phase. Only public data is processed here (public key, message, signature),
 * so a non-constant-time implementation is acceptable. There is deliberately NO signing code
 * and NO private key anywhere in the app.
 */
object Ed25519 {
    const val PUBLIC_KEY_SIZE = 32
    const val SIGNATURE_SIZE = 64

    private val P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val L = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
    private val TWO = BigInteger.valueOf(2)
    private val D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val D2 = D.multiply(TWO).mod(P)
    private val SQRT_M1 = TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)
    private val MASK_255 = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.ONE)

    /** Extended homogeneous coordinates (X, Y, Z, T) with x = X/Z, y = Y/Z, T = XY/Z. */
    private class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    private val BASE: Point by lazy {
        val by = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
        val bx = recoverX(by, 0) ?: error("invalid base point")
        Point(bx, by, BigInteger.ONE, bx.multiply(by).mod(P))
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != PUBLIC_KEY_SIZE || signature.size != SIGNATURE_SIZE) return false
        return try {
            val a = decodePoint(publicKey) ?: return false
            val rBytes = signature.copyOfRange(0, 32)
            val s = littleEndian(signature.copyOfRange(32, 64))
            if (s >= L) return false                                   // malleability check
            val md = MessageDigest.getInstance("SHA-512")
            md.update(rBytes); md.update(publicKey); md.update(message)
            val k = littleEndian(md.digest()).mod(L)
            // [S]B == R + [k]A   <=>   encode([S]B + [k](-A)) == R
            val negA = Point(P.subtract(a.x).mod(P), a.y, a.z, P.subtract(a.t).mod(P))
            val check = add(scalarMul(s, BASE), scalarMul(k, negA))
            MessageDigest.isEqual(encodePoint(check), rBytes)
        } catch (_: Exception) {
            false
        }
    }

    private fun add(p: Point, q: Point): Point {
        val a = p.y.subtract(p.x).multiply(q.y.subtract(q.x)).mod(P)
        val b = p.y.add(p.x).multiply(q.y.add(q.x)).mod(P)
        val c = p.t.multiply(D2).multiply(q.t).mod(P)
        val d = p.z.multiply(TWO).multiply(q.z).mod(P)
        val e = b.subtract(a); val f = d.subtract(c); val g = d.add(c); val h = b.add(a)
        return Point(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P))
    }

    private fun scalarMul(s: BigInteger, p: Point): Point {
        var r = IDENTITY
        for (i in s.bitLength() - 1 downTo 0) {
            r = add(r, r)
            if (s.testBit(i)) r = add(r, p)
        }
        return r
    }

    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        val yy = y.multiply(y).mod(P)
        val xx = yy.subtract(BigInteger.ONE).multiply(D.multiply(yy).add(BigInteger.ONE).modInverse(P)).mod(P)
        var x = xx.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
        if (x.multiply(x).subtract(xx).mod(P).signum() != 0) x = x.multiply(SQRT_M1).mod(P)
        if (x.multiply(x).subtract(xx).mod(P).signum() != 0) return null
        if (x.signum() == 0 && sign == 1) return null
        if ((if (x.testBit(0)) 1 else 0) != sign) x = P.subtract(x)
        return x
    }

    private fun decodePoint(b: ByteArray): Point? {
        val n = littleEndian(b)
        val sign = if (n.testBit(255)) 1 else 0
        val y = n.and(MASK_255)
        if (y >= P) return null
        val x = recoverX(y, sign) ?: return null
        return Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    private fun encodePoint(p: Point): ByteArray {
        val zInv = p.z.modInverse(P)
        val x = p.x.multiply(zInv).mod(P)
        val y = p.y.multiply(zInv).mod(P)
        val out = toLittleEndian(y, 32)
        if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    private fun littleEndian(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())

    private fun toLittleEndian(v: BigInteger, size: Int): ByteArray {
        val be = v.toByteArray()                       // big-endian, may carry a leading 0x00
        val out = ByteArray(size)
        for (i in 0 until minOf(size, be.size)) out[i] = be[be.size - 1 - i]
        return out
    }
}
