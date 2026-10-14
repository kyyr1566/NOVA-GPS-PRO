package com.nova.gpspro.map

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local manifest for offline map packages — stores metadata for WorkManager downloads.
 * JSON structure: { "packages": [ {id, nameAr, nameEn, url, sizeBytes, sha256, bounds{...}, version} ] }
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
        // Default empty — user can add governorate packages; real URLs should be Protomaps/OSM pmtiles
        val pkgs = OfflineMapManager.availableGovernorates().map { (id, ar) ->
            PackageInfo(
                id = id,
                nameAr = ar,
                nameEn = id,
                url = "https://example.com/pmtiles/${id}.pmtiles",
                sizeBytes = 0L,
                sha256 = null,
                bounds = null,
                version = "1"
            )
        }
        return Manifest(pkgs)
    }
}
