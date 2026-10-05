package com.nova.gpspro.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Fallback parser for external location files (Arrow GPS Lite, GPX, KML, CSV, JSON, XML, plain text).
 * Keeps NOVA .locx as primary (LocxCodec.decodeAll must be tried first).
 * Returns stable Destination list from any valid lat/lon found, ignoring invalid records.
 */
object ExternalLocationParser {

    fun parse(text: String, fallbackPrefix: String = "ext_", fallbackTime: Long = System.currentTimeMillis()): List<Destination> {
        val clean = text.removePrefix("\uFEFF").trim()
        if (clean.isEmpty()) return emptyList()
        // Don't re-parse NOVA format
        if (clean.contains("NOVA-LOCX")) return emptyList()
        val out = mutableListOf<Destination>()
        // 1) JSON
        if ((clean.startsWith("{") || clean.startsWith("[")) && tryParseJson(clean, fallbackPrefix, fallbackTime, out)) {
            if (out.isNotEmpty()) return out
            out.clear()
        }
        // 2) XML / GPX / KML
        if (clean.contains("<") && clean.contains(">") && tryParseXml(clean, fallbackPrefix, fallbackTime, out)) {
            if (out.isNotEmpty()) return out
            out.clear()
        }
        // 3) CSV / delimited text / plain
        tryParseCsvText(clean, fallbackPrefix, fallbackTime, out)
        return out
    }

    private fun makeDest(latStr: String, lonStr: String, name: String?, fallbackPrefix: String, idx: Int, fallbackTime: Long): Destination? {
        val lat = Destination.parseCoordinate(latStr) ?: return null
        val lon = Destination.parseCoordinate(lonStr) ?: return null
        if (!Destination.validLat(lat) || !Destination.validLon(lon)) return null
        val safeName = name?.trim()?.takeIf { it.isNotEmpty() } ?: "Location ${idx + 1}"
        return Destination(
            id = fallbackPrefix + idx,
            name = safeName.take(80),
            latitude = lat,
            longitude = lon,
            photoPath = null,
            notes = null,
            createdAt = fallbackTime + idx
        )
    }

    // ---------------- JSON ----------------
    private fun tryParseJson(text: String, prefix: String, time: Long, out: MutableList<Destination>): Boolean {
        return try {
            val jsonText = text.trim()
            if (jsonText.startsWith("[")) {
                val arr = JSONArray(jsonText)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    extractFromJsonObject(obj, prefix, out.size, time)?.let { out.add(it) }
                }
            } else {
                val obj = JSONObject(jsonText)
                var foundArray = false
                val it = obj.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val v = obj.opt(k)
                    if (v is JSONArray) {
                        for (i in 0 until v.length()) {
                            val o = v.optJSONObject(i) ?: continue
                            extractFromJsonObject(o, prefix, out.size, time)?.let { out.add(it) }
                        }
                        foundArray = true
                    }
                }
                if (!foundArray) {
                    extractFromJsonObject(obj, prefix, 0, time)?.let { out.add(it) }
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun extractFromJsonObject(obj: JSONObject, prefix: String, idx: Int, time: Long): Destination? {
        fun find(keys: List<String>): String? {
            val it = obj.keys()
            while (it.hasNext()) {
                val k = it.next()
                if (k.lowercase(Locale.US) in keys) {
                    val v = obj.opt(k)?.toString()?.trim() ?: continue
                    if (v.isNotEmpty() && v != "null") return v
                }
            }
            return null
        }
        val latKeys = listOf("latitude", "lat", "y", "latitud", "lattitude")
        val lonKeys = listOf("longitude", "lon", "lng", "long", "x", "longitud")
        val nameKeys = listOf("name", "title", "label", "displayname", "description", "id")

        var latStr = find(latKeys)
        var lonStr = find(lonKeys)
        val name = find(nameKeys)

        // If lat/lon not found, try coordinates array [lon,lat] or "lon,lat" string
        if (latStr == null || lonStr == null) {
            val coordKeys = listOf("coordinates", "coord", "position", "location")
            val it = obj.keys()
            while (it.hasNext()) {
                val k = it.next()
                if (k.lowercase(Locale.US) in coordKeys) {
                    val v = obj.opt(k)
                    if (v is JSONArray && v.length() >= 2) {
                        val a = v.opt(0).toString(); val b = v.opt(1).toString()
                        // KML order lon,lat; try both
                        makeDest(b, a, name, prefix, idx, time)?.let { return it }
                        makeDest(a, b, name, prefix, idx, time)?.let { return it }
                    } else if (v is String) {
                        val parts = v.split(Regex("[,\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }
                        if (parts.size >= 2) {
                            makeDest(parts[1], parts[0], name, prefix, idx, time)?.let { return it }
                            makeDest(parts[0], parts[1], name, prefix, idx, time)?.let { return it }
                        }
                    } else if (v is JSONObject) {
                        // nested object {"lat":..., "lon":...}
                        val la = v.opt("lat")?.toString() ?: v.opt("latitude")?.toString()
                        val lo = v.opt("lon")?.toString() ?: v.opt("longitude")?.toString() ?: v.opt("lng")?.toString()
                        if (la != null && lo != null) {
                            makeDest(la, lo, name, prefix, idx, time)?.let { return it }
                        }
                    }
                }
            }
            // still no lat/lon -> invalid record
            if (latStr == null || lonStr == null) return null
        }
        return makeDest(latStr, lonStr, name, prefix, idx, time)
    }

    // ---------------- XML / GPX / KML ----------------
    private fun tryParseXml(text: String, prefix: String, time: Long, out: MutableList<Destination>): Boolean {
        return try {
            var added = false
            // GPX <wpt lat=".." lon=".."> <name>...</name>
            val wptRegex = Regex("""<wpt[^>]*lat\s*=\s*["']([^"']+)["'][^>]*lon\s*=\s*["']([^"']+)["'][^>]*>(.*?)</wpt>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            for (m in wptRegex.findAll(text)) {
                val latStr = m.groupValues[1]; val lonStr = m.groupValues[2]; val inner = m.groupValues[3]
                val name = Regex("""<name[^>]*>(.*?)</name>""", RegexOption.IGNORE_CASE).find(inner)?.groupValues?.get(1)?.trim()?.let { stripXml(it) }
                makeDest(latStr, lonStr, name, prefix, out.size, time)?.let { out.add(it); added = true }
            }
            val trkptRegex = Regex("""<trkpt[^>]*lat\s*=\s*["']([^"']+)["'][^>]*lon\s*=\s*["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)
            for (m in trkptRegex.findAll(text)) {
                val latStr = m.groupValues[1]; val lonStr = m.groupValues[2]
                makeDest(latStr, lonStr, null, prefix, out.size, time)?.let { out.add(it); added = true }
            }
            // KML <Placemark> ... <coordinates>lon,lat,alt</coordinates> ... <name>
            val placemarkRegex = Regex("""<Placemark[^>]*>(.*?)</Placemark>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            for (pm in placemarkRegex.findAll(text)) {
                val inner = pm.groupValues[1]
                val coordText = Regex("""<coordinates[^>]*>(.*?)</coordinates>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(inner)?.groupValues?.get(1) ?: continue
                val name = Regex("""<name[^>]*>(.*?)</name>""", RegexOption.IGNORE_CASE).find(inner)?.groupValues?.get(1)?.trim()?.let { stripXml(it) }
                val tuples = coordText.trim().split(Regex("""\s+"""))
                for (t in tuples) {
                    val parts = t.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    if (parts.size >= 2) {
                        val lonStr = parts[0]; val latStr = parts[1]
                        makeDest(latStr, lonStr, name, prefix, out.size, time)?.let { out.add(it); added = true }
                    }
                }
            }
            // Generic <coordinates> outside placemark
            if (!added) {
                val coordGeneric = Regex("""<coordinates[^>]*>(.*?)</coordinates>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                for (m in coordGeneric.findAll(text)) {
                    val coordText = m.groupValues[1].trim()
                    if (coordText.contains(",")) {
                        val tuples = coordText.split(Regex("""\s+"""))
                        for (t in tuples) {
                            val parts = t.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            if (parts.size >= 2) {
                                makeDest(parts[1], parts[0], null, prefix, out.size, time)?.let { out.add(it); added = true }
                            }
                        }
                    }
                }
            }
            // Generic <lat> / <latitude> and <lon> / <longitude> pairs
            val latTags = Regex("""<(?:lat|latitude)[^>]*>(.*?)</(?:lat|latitude)>""", RegexOption.IGNORE_CASE).findAll(text).map { stripXml(it.groupValues[1].trim()) }.toList()
            val lonTags = Regex("""<(?:lon|lng|long|longitude)[^>]*>(.*?)</(?:lon|lng|long|longitude)>""", RegexOption.IGNORE_CASE).findAll(text).map { stripXml(it.groupValues[1].trim()) }.toList()
            if (latTags.isNotEmpty() && lonTags.isNotEmpty() && latTags.size == lonTags.size) {
                for (i in latTags.indices) {
                    val latStr = latTags[i]; val lonStr = lonTags[i]
                    makeDest(latStr, lonStr, null, prefix, out.size, time)?.let { out.add(it); added = true }
                }
            } else if (latTags.isNotEmpty() && lonTags.isNotEmpty()) {
                val pairRegex = Regex("""<(?:lat|latitude)[^>]*>(.*?)</(?:lat|latitude)>[^<]*<(?:lon|lng|longitude)[^>]*>(.*?)</(?:lon|lng|longitude)>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                for (m in pairRegex.findAll(text)) {
                    makeDest(m.groupValues[1].trim(), m.groupValues[2].trim(), null, prefix, out.size, time)?.let { out.add(it); added = true }
                }
                val revPair = Regex("""<(?:lon|lng|longitude)[^>]*>(.*?)</(?:lon|lng|longitude)>[^<]*<(?:lat|latitude)[^>]*>(.*?)</(?:lat|latitude)>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                for (m in revPair.findAll(text)) {
                    makeDest(m.groupValues[2].trim(), m.groupValues[1].trim(), null, prefix, out.size, time)?.let { out.add(it); added = true }
                }
            }
            // Any lat/lon attribute pair in any tag
            if (!added) {
                val anyLatLon = Regex("""lat\s*=\s*["']([^"']+)["'][^>]*lon\s*=\s*["']([^"']+)["']|lon\s*=\s*["']([^"']+)["'][^>]*lat\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                for (m in anyLatLon.findAll(text)) {
                    val latStr = m.groupValues[1].ifEmpty { m.groupValues[4] }
                    val lonStr = m.groupValues[2].ifEmpty { m.groupValues[3] }
                    if (latStr.isNotEmpty() && lonStr.isNotEmpty()) {
                        makeDest(latStr, lonStr, null, prefix, out.size, time)?.let { out.add(it); added = true }
                    }
                }
            }
            added || out.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    private fun stripXml(s: String): String {
        return s.replace(Regex("""<[^>]*>"""), "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").trim()
    }

    // ---------------- CSV / TEXT ----------------
    private fun tryParseCsvText(text: String, prefix: String, time: Long, out: MutableList<Destination>) {
        val lines = text.split(Regex("""\r?\n""")).map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return
        var startIdx = 0
        val header = lines.first().lowercase(Locale.US)
        if ((header.contains("lat") && header.contains("lon")) || header.contains("latitude") || header.contains("longitude")) {
            if (header.any { it.isLetter() } && Regex("""lat|lon|name""").containsMatchIn(header)) startIdx = 1
        }
        for (idx in startIdx until lines.size) {
            val line = lines[idx]
            if (line.contains("<") && line.contains(">")) continue
            if (line.length > 500) continue
            val parsed = parseLineForCoords(line, prefix, out.size, time)
            if (parsed != null) out.add(parsed)
            else {
                // Find any two numbers that could be lat/lon — Arabic-Indic aware via manual extraction using Destination.parseCoordinate
                // Use regex with actual Arabic digits: ٠-٩ and ۰-۹ plus Arabic decimal separators ٫ and ، and minus −
                val numRegex = Regex("""[+\-]?[0-9٠-٩۰-۹]+(?:[.,٫،٬][0-9٠-٩۰-۹]+)?""")
                val nums = numRegex.findAll(line).map { it.value }.toList()
                if (nums.size >= 2) {
                    for (i in 0 until nums.size - 1) {
                        val a = nums[i]; val b = nums[i + 1]
                        var dest = makeDest(a, b, extractNameFromLine(line, a, b), prefix, out.size, time)
                        if (dest == null) dest = makeDest(b, a, extractNameFromLine(line, a, b), prefix, out.size, time)
                        if (dest != null) { out.add(dest); break }
                    }
                }
            }
        }
    }

    private fun parseLineForCoords(line: String, prefix: String, idx: Int, time: Long): Destination? {
        val delimiters = listOf(",", ";", "\t", " ", "|")
        for (delim in delimiters) {
            if (!line.contains(delim)) continue
            val parts = line.split(delim).map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size < 2) continue
            val nums = parts.mapNotNull { it to Destination.parseCoordinate(it) }.filter { Destination.validLat(it.second) || Destination.validLon(it.second) }
            if (nums.size >= 2) {
                for (i in 0 until nums.size - 1) {
                    val aStr = nums[i].first; val bStr = nums[i + 1].first
                    val a = Destination.parseCoordinate(aStr)!!; val b = Destination.parseCoordinate(bStr)!!
                    if (Destination.validLat(a) && Destination.validLon(b)) {
                        val name = extractNameFromLine(line, aStr, bStr)
                        return makeDest(aStr, bStr, name, prefix, idx, time)
                    }
                    if (Destination.validLat(b) && Destination.validLon(a)) {
                        val name = extractNameFromLine(line, aStr, bStr)
                        return makeDest(bStr, aStr, name, prefix, idx, time)
                    }
                }
            }
            if (parts.size >= 3) {
                val nameCandidates = parts.filter { Destination.parseCoordinate(it) == null && it.length in 2..60 }
                val name = nameCandidates.firstOrNull()
                val numericParts = parts.filter { Destination.parseCoordinate(it) != null }
                if (numericParts.size >= 2) {
                    val aStr = numericParts[0]; val bStr = numericParts[1]
                    var d = makeDest(aStr, bStr, name, prefix, idx, time)
                    if (d == null) d = makeDest(bStr, aStr, name, prefix, idx, time)
                    if (d != null) return d
                }
            }
        }
        return null
    }

    private fun extractNameFromLine(line: String, latStr: String, lonStr: String): String? {
        var tmp = line
        tmp = tmp.replace(latStr, " ").replace(lonStr, " ")
        tmp = tmp.replace(Regex("""[,\t;|]+"""), " ").trim()
        tmp = tmp.replace(Regex("""\s+"""), " ").trim()
        val tokens = tmp.split(" ").filter { it.isNotEmpty() && Destination.parseCoordinate(it) == null && it.length in 2..60 }
        if (tokens.isEmpty()) return null
        val name = tokens.joinToString(" ").trim().trim(',', ' ', '"', '\'')
        return name.takeIf { it.length in 2..60 && it.any { ch -> ch.isLetter() } }
    }
}
