package com.nova.gpspro.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pure (JVM-testable) encoder/decoder for the human-readable `.locx` format.
 *
 *   NOVA-LOCX 1
 *   id=…
 *   name=…
 *   latitude=33.3152000
 *   longitude=44.3661000
 *   saved_at=2026-09-29T14:05:12+0300
 *   saved_at_ms=1790683512000
 *   notes=…
 *   photo=/path/or/empty
 *
 * Values are escaped (\\ \n \r) so any text survives a round trip.
 */
object LocxCodec {
    const val HEADER = "NOVA-LOCX 1"
    const val EXT = ".locx"
    const val FOLDER = "GPSarrow"
    private const val MAX_BASE = 80

    fun encode(d: Destination, tz: TimeZone = TimeZone.getDefault()): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).apply { timeZone = tz }.format(Date(d.createdAt))
        return buildString {
            append(HEADER).append('\n')
            kv("id", d.id); kv("name", d.name)
            kv("latitude", String.format(Locale.US, "%.7f", d.latitude))
            kv("longitude", String.format(Locale.US, "%.7f", d.longitude))
            kv("saved_at", iso); kv("saved_at_ms", d.createdAt.toString())
            kv("notes", d.notes ?: ""); kv("photo", d.photoPath ?: "")
        }
    }

    private fun StringBuilder.kv(k: String, v: String) { append(k).append('=').append(esc(v)).append('\n') }

    /** Returns null if the text is not a valid .locx document. [fallbackId] is used when no id line exists. */
    fun decode(text: String, fallbackId: String, fallbackTime: Long = 0L): Destination? {
        val lines = text.removePrefix("\uFEFF").split('\n').map { it.trimEnd('\r') }
        if (lines.firstOrNull()?.trim()?.startsWith("NOVA-LOCX") != true) return null
        val m = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val i = l.indexOf('='); if (i <= 0) continue
            m[l.substring(0, i).trim()] = unesc(l.substring(i + 1))
        }
        val name = m["name"]?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        val lat = m["latitude"]?.let { Destination.parseCoordinate(it) }
        val lon = m["longitude"]?.let { Destination.parseCoordinate(it) }
        if (!Destination.validLat(lat) || !Destination.validLon(lon)) return null
        val time = m["saved_at_ms"]?.trim()?.toLongOrNull()
            ?: m["saved_at"]?.let { runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).parse(it.trim())?.time }.getOrNull() }
            ?: fallbackTime
        return Destination(
            id = m["id"]?.trim().takeUnless { it.isNullOrEmpty() } ?: fallbackId,
            name = name, latitude = lat!!, longitude = lon!!,
            photoPath = m["photo"]?.trim().takeUnless { it.isNullOrEmpty() },
            notes = m["notes"]?.trim().takeUnless { it.isNullOrEmpty() },
            createdAt = time
        )
    }

    /** Several locations in one .locx (multi-location / full backup): records concatenated. */
    fun encodeMany(list: List<Destination>, tz: TimeZone = TimeZone.getDefault()): String =
        list.joinToString("") { encode(it, tz) }

    /** Decodes every record of a single- or multi-location .locx. Invalid records are skipped. */
    fun decodeAll(text: String, fallbackPrefix: String = "imp_", fallbackTime: Long = 0L): List<Destination> {
        val blocks = ArrayList<StringBuilder>()
        for (line in text.removePrefix("\uFEFF").split('\n')) {
            if (line.trim().startsWith("NOVA-LOCX")) blocks.add(StringBuilder())
            blocks.lastOrNull()?.append(line)?.append('\n')
        }
        return blocks.mapIndexedNotNull { i, b -> decode(b.toString(), fallbackPrefix + i, fallbackTime) }
    }

    /** File-system–safe base name derived from the location name (Arabic etc. kept). */
    fun safeBaseName(name: String): String {
        val sb = StringBuilder()
        for (ch in name.trim()) {
            sb.append(when {
                ch in "\\/:*?\"<>|" || ch.code < 32 || ch.code == 127 -> '_'
                ch == '\n' || ch == '\t' -> ' '
                else -> ch
            })
        }
        var s = sb.toString().replace(Regex("\\s+"), " ").replace(Regex("_+"), "_").trim(' ', '.', '_')
        if (s.length > MAX_BASE) s = s.substring(0, MAX_BASE).trimEnd(' ', '.', '_')
        return s.ifEmpty { "location" }
    }

    /** "<base>.locx", or "<base> (2).locx" … when [taken] (case-insensitive) already contains it. */
    fun uniqueFileName(name: String, taken: Set<String>): String {
        val base = safeBaseName(name)
        val lower = taken.map { it.lowercase(Locale.ROOT) }.toSet()
        var n = 1
        while (true) {
            val cand = if (n == 1) "$base$EXT" else "$base ($n)$EXT"
            if (cand.lowercase(Locale.ROOT) !in lower) return cand
            n++
        }
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")
    private fun unesc(s: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) { 'n' -> sb.append('\n'); 'r' -> sb.append('\r'); '\\' -> sb.append('\\'); else -> { sb.append(c); sb.append(s[i + 1]) } }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }
}
