package com.nova.gpspro.portal

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.nova.gpspro.data.Destination
import com.nova.gpspro.data.LocxCodec
import java.util.Locale

/** Compact QR text payload (pure, JVM-testable). */
object QrPayload {
    const val HEADER = "NOVA-QR1"

    /** NOVA-QR1 \n lat;lon;name;notes;savedAtMs  (one line per location) */
    fun encode(list: List<Destination>): String = buildString {
        append(HEADER)
        for (d in list) {
            append('\n')
            append(String.format(Locale.US, "%.7f;%.7f;", d.latitude, d.longitude))
            append(esc(d.name)).append(';').append(esc(d.notes ?: "")).append(';').append(d.createdAt)
        }
    }

    /** Accepts NOVA-QR1, a full .locx text, or a standard geo: URI. Empty list = not a location QR. */
    fun decode(text: String, now: Long, geoName: String): List<Destination> {
        val t = text.trim().removePrefix("\uFEFF")
        if (t.startsWith(HEADER)) {
            return t.split('\n').drop(1).mapIndexedNotNull { i, raw ->
                val f = raw.trimEnd('\r').split(';')
                if (f.size < 3) return@mapIndexedNotNull null
                val lat = Destination.parseCoordinate(f[0]); val lon = Destination.parseCoordinate(f[1])
                val name = unesc(f[2]).trim()
                if (!Destination.validLat(lat) || !Destination.validLon(lon) || name.isEmpty()) return@mapIndexedNotNull null
                Destination("qr_$i", name, lat!!, lon!!, null,
                    f.getOrNull(3)?.let { unesc(it).trim() }?.ifEmpty { null },
                    f.getOrNull(4)?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: now)
            }
        }
        if (t.startsWith("NOVA-LOCX")) return LocxCodec.decodeAll(t, "qr_", now).map { it.copy(photoPath = null) }
        Regex("^geo:([+-]?[0-9.]+),([+-]?[0-9.]+)(.*)$", RegexOption.IGNORE_CASE).find(t)?.let { m ->
            val lat = Destination.parseCoordinate(m.groupValues[1]); val lon = Destination.parseCoordinate(m.groupValues[2])
            if (!Destination.validLat(lat) || !Destination.validLon(lon)) return emptyList()
            val label = Regex("\\(([^)]*)\\)").find(m.groupValues[3])?.groupValues?.get(1)
                ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }?.trim()
            return listOf(Destination("qr_0", label?.ifEmpty { null } ?: geoName, lat!!, lon!!, null, null, now))
        }
        return emptyList()
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\s").replace("\n", "\\n").replace("\r", "")
    private fun unesc(s: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) { 's' -> sb.append(';'); 'n' -> sb.append('\n'); '\\' -> sb.append('\\'); else -> sb.append(s[i + 1]) }
                i += 2
            } else { sb.append(s[i]); i++ }
        }
        return sb.toString()
    }
}

/** Duplicate prevention for imports (pure). */
object ImportPlanner {
    class Plan(val add: List<Destination>, val duplicates: Int)

    /** Same place = same coordinates to 6 decimals (~0.1 m), or the same location id. */
    fun key(d: Destination) = String.format(Locale.US, "%.6f,%.6f", d.latitude, d.longitude)

    fun plan(existing: List<Destination>, incoming: List<Destination>): Plan {
        val ids = existing.map { it.id }.toHashSet()
        val keys = existing.map { key(it) }.toHashSet()
        val add = ArrayList<Destination>(); var dup = 0
        for (d in incoming) {
            val k = key(d)
            if (d.id in ids || k in keys) { dup++; continue }
            ids.add(d.id); keys.add(k); add.add(d)
        }
        return Plan(add, dup)
    }
}

/** Offline QR encode/decode via ZXing core (pure Java). */
object QrCodec {
    /** Throws WriterException if the text doesn't fit a QR code. */
    fun encode(text: String, size: Int = 0): BitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))

    private val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8",
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))

    fun decode(src: LuminanceSource): String? {
        val r = QRCodeReader()
        for (s in listOf(src, src.invert())) {
            try { return r.decode(BinaryBitmap(HybridBinarizer(s)), hints).text } catch (_: Exception) { r.reset() }
        }
        return null
    }

    fun decodePixels(argb: IntArray, w: Int, h: Int): String? = decode(RGBLuminanceSource(w, h, argb))
}
