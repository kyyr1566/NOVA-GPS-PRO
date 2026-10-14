package com.nova.gpspro.map

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local manifest for offline map packages — stores metadata for WorkManager downloads.
 * JSON structure: { "packages": [ {id, nameAr, nameEn, url, sizeBytes, sha256, bounds{...}, version} ] }
 *
 * Real PMTiles are generated from OSM Geofabrik Iraq extract (ODbL) via planetiler:
 *   planetiler --area=iraq --output=iraq.pmtiles
 *   Governorate splits via osmium + planetiler per bbox.
 * Hosted at GitHub Releases (kyyr1566/NOVA-GPS-PRO) – free licensed mirror.
 */
object MapPackageManifest {

    data class Bounds(val north: Double, val south: Double, val east: Double, val west: Double)
    data class PackageInfo(
        val id: String,
        val nameAr: String,
        val nameEn: String,
        val url: String,
        val sizeBytes: Long,
        val sha256: String?,
        val bounds: Bounds?,
        val version: String
    )

    private fun manifestFile(context: Context): File = File(context.filesDir, "offline_map/manifest.json")

    fun load(context: Context): Manifest? {
        val f = manifestFile(context)
        if (!f.exists()) return null
        return try {
            val json = JSONObject(f.readText())
            val arr = json.optJSONArray("packages") ?: JSONArray()
            val list = mutableListOf<PackageInfo>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val b = o.optJSONObject("bounds")
                val bounds = if (b != null) Bounds(
                    b.optDouble("north"), b.optDouble("south"), b.optDouble("east"), b.optDouble("west")
                ) else null
                list.add(PackageInfo(
                    id = o.getString("id"),
                    nameAr = o.optString("nameAr", o.getString("id")),
                    nameEn = o.optString("nameEn", o.getString("id")),
                    url = o.getString("url"),
                    sizeBytes = o.optLong("sizeBytes", 0L),
                    sha256 = o.optString("sha256").takeIf { it.isNotEmpty() },
                    bounds = bounds,
                    version = o.optString("version", "1")
                ))
            }
            Manifest(list)
        } catch (_: Exception) { null }
    }

    fun save(context: Context, manifest: Manifest) {
        try {
            val f = manifestFile(context)
            f.parentFile?.mkdirs()
            val json = JSONObject()
            val arr = JSONArray()
            for (p in manifest.packages) {
                val o = JSONObject()
                o.put("id", p.id)
                o.put("nameAr", p.nameAr)
                o.put("nameEn", p.nameEn)
                o.put("url", p.url)
                o.put("sizeBytes", p.sizeBytes)
                if (p.sha256 != null) o.put("sha256", p.sha256)
                if (p.bounds != null) {
                    val b = JSONObject()
                    b.put("north", p.bounds.north)
                    b.put("south", p.bounds.south)
                    b.put("east", p.bounds.east)
                    b.put("west", p.bounds.west)
                    o.put("bounds", b)
                }
                o.put("version", p.version)
                arr.put(o)
            }
            json.put("packages", arr)
            f.writeText(json.toString())
        } catch (_: Exception) {}
    }

    data class Manifest(val packages: List<PackageInfo>)

    fun defaultPackages(): Manifest {
        // Real hosted PMTiles (GitHub Releases) – OSM data ODbL, vector PMTiles 0-14
        // Generated via planetiler from Geofabrik Iraq. Sizes are estimates; sha256 populated after generation.
        // Hosting: https://github.com/kyyr1566/NOVA-GPS-PRO/releases/download/maps-v1/<id>.pmtiles
        val base = "https://github.com/kyyr1566/NOVA-GPS-PRO/releases/download/maps-v1"
        fun pkg(id: String, ar: String, sizeMb: Int, bounds: Bounds? = null) = PackageInfo(
            id = id, nameAr = ar, nameEn = id,
            url = "$base/$id.pmtiles",
            sizeBytes = sizeMb * 1024L * 1024L,
            sha256 = null, // to be filled after generation; verification skips if null
            bounds = bounds, version = "1"
        )
        val pkgs = listOf(
            pkg("baghdad", "بغداد", 42, Bounds(33.65, 32.95, 45.2, 43.9)),
            pkg("basra", "البصرة", 28, Bounds(31.1, 29.8, 48.2, 46.8)),
            pkg("nineveh", "نينوى", 35, Bounds(37.1, 35.2, 43.8, 41.6)),
            pkg("erbil", "أربيل", 22, Bounds(37.0, 35.6, 44.8, 43.2)),
            pkg("najaf", "النجف", 18, Bounds(32.45, 30.9, 45.0, 43.5)),
            pkg("karbala", "كربلاء", 12, Bounds(33.2, 32.4, 44.6, 43.4)),
            pkg("kirkuk", "كركوك", 16, Bounds(35.8, 34.8, 44.9, 43.6)),
            pkg("anbar", "الأنبار", 38, Bounds(34.7, 32.3, 43.5, 38.9)),
            pkg("diyala", "ديالى", 20, Bounds(34.8, 33.5, 45.7, 44.3)),
            pkg("dhiqar", "ذي قار", 18, Bounds(31.8, 30.6, 47.0, 45.5)),
            pkg("sulaymaniyah", "السليمانية", 20, Bounds(36.2, 35.0, 46.0, 44.5)),
            pkg("babylon", "بابل", 15, Bounds(33.0, 32.1, 44.9, 44.0)),
            pkg("wasit", "واسط", 14, Bounds(33.1, 32.0, 46.5, 44.8)),
            pkg("maysan", "ميسان", 12, Bounds(32.2, 31.2, 47.6, 46.4)),
            pkg("muthanna", "المثنى", 14, Bounds(31.5, 29.6, 46.2, 43.8)),
            pkg("qadisiyah", "القادسية", 10, Bounds(32.2, 31.3, 45.3, 44.3)),
            pkg("dohuk", "دهوك", 10, Bounds(37.4, 36.6, 43.5, 42.5)),
            pkg("saladin", "صلاح الدين", 30, Bounds(35.5, 33.8, 44.5, 42.8)),
            pkg("halabja", "حلبجة", 5, Bounds(35.4, 35.0, 46.1, 45.4)),
            // Combined Iraq for users who want full offline
            PackageInfo("iraq_pmtiles", "العراق (كامل)", "iraq", "$base/iraq.pmtiles", 380L*1024*1024, null, Bounds(37.4, 29.5, 48.5, 38.8), "1")
        )
        return Manifest(pkgs)
    }
}
