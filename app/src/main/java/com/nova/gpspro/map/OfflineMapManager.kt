package com.nova.gpspro.map

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Manages offline PMTiles for Iraqi governorates.
 * Stored under filesDir/offline_map/
 * Supports pmtiles://file:// for MapLibre.
 *
 * PMTiles generation (required for offline):
 *   - Source: OSM Geofabrik Iraq extract (https://download.geofabrik.de/asia/iraq-latest.osm.pbf) – ODbL licensed
 *   - Tool: planetiler (https://github.com/onthegomap/planetiler) or tippecanoe
 *   - Example: java -jar planetiler.jar --area=iraq --bounds=... --output=iraq.pmtiles --nodemap-type=sparsearray
 *   - Governorate splits: osmium extract by bbox from Geofabrik, then planetiler per bbox
 *   - Host: GitHub Releases (kyyr1566/NOVA-GPS-PRO/releases/download/maps-v1/<gov>.pmtiles) – free, licensed
 *   - Verification: SHA-256 per file, sizeBytes, tested in airplane mode
 */
object OfflineMapManager {

    private const val PREFS = "nova_map_mode"
    private const val KEY_MODE = "mode" // online | smart | offline

    enum class MapMode { ONLINE, SMART, OFFLINE }

    fun getMode(context: Context): MapMode {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return when (p.getString(KEY_MODE, "smart")) {
            "online" -> MapMode.ONLINE
            "offline" -> MapMode.OFFLINE
            else -> MapMode.SMART
        }
    }

    fun setMode(context: Context, mode: MapMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, when (mode) {
                MapMode.ONLINE -> "online"
                MapMode.OFFLINE -> "offline"
                else -> "smart"
            }).apply()
    }

    fun offlineDir(context: Context): File = File(context.filesDir, "offline_map").apply { mkdirs() }

    fun pmtilesFile(context: Context, id: String): File = File(offlineDir(context), "$id.pmtiles")

    fun hasOffline(context: Context, id: String): Boolean {
        val f = pmtilesFile(context, id)
        return f.exists() && f.length() > 1024
    }

    fun listOfflineFiles(context: Context): List<File> {
        val dir = offlineDir(context)
        return dir.listFiles()?.filter { it.extension == "pmtiles" && it.length() > 1024 } ?: emptyList()
    }

    /** Returns a style JSON that uses the first available PMTiles file offline (vector). Returns null if none. */
    fun buildOfflineStyleJson(context: Context): String? {
        val files = listOfflineFiles(context)
        if (files.isEmpty()) return null
        // Prefer iraq_pmtiles combined, else first governorate
        val f = files.firstOrNull { it.name == "iraq_pmtiles.pmtiles" } ?: files[0]
        val pmtilesUri = "pmtiles://file://${f.absolutePath}"
        // Load dark.json asset and replace openmaptiles URL with pmtiles
        return try {
            val assetJson = context.assets.open("mapstyles/dark.json").bufferedReader().readText()
            // Replace the tiles.openfreemap URL with pmtiles URI – keep other sources
            assetJson.replace(
                "\"url\": \"https://tiles.openfreemap.org/planet\"",
                "\"url\": \"$pmtilesUri\""
            ).replace(
                "\"url\":\"https://tiles.openfreemap.org/planet\"",
                "\"url\":\"$pmtilesUri\""
            )
        } catch (_: Exception) {
            null
        }
    }

    fun getOfflineStyleUrl(context: Context, layer: String): String? {
        // Legacy: if any pmtiles exists, indicate offline available
        if (listOfflineFiles(context).isNotEmpty()) {
            val f = listOfflineFiles(context).first()
            return "pmtiles://file://${f.absolutePath}"
        }
        return null
    }

    fun listPackages(context: Context): List<MapPackageManifest.PackageInfo> {
        val manifest = MapPackageManifest.load(context) ?: return MapPackageManifest.defaultPackages().packages
        return manifest.packages
    }

    fun verifySha256(file: File, expected: String?): Boolean {
        if (expected.isNullOrBlank()) return true
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } != -1) md.update(buf, 0, n)
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            hex.equals(expected.trim(), ignoreCase = true)
        } catch (_: Exception) { false }
    }

    fun deletePackage(context: Context, id: String) {
        try { pmtilesFile(context, id).delete() } catch (_: Exception) {}
    }

    fun availableGovernorates(): List<Pair<String,String>> = listOf(
        "baghdad" to "بغداد",
        "basra" to "البصرة",
        "nineveh" to "نينوى",
        "erbil" to "أربيل",
        "najaf" to "النجف",
        "karbala" to "كربلاء",
        "kirkuk" to "كركوك",
        "anbar" to "الأنبار",
        "diyala" to "ديالى",
        "dhiqar" to "ذي قار",
        "sulaymaniyah" to "السليمانية",
        "babylon" to "بابل",
        "wasit" to "واسط",
        "maysan" to "ميسان",
        "muthanna" to "المثنى",
        "qadisiyah" to "القادسية",
        "dohuk" to "دهوك",
        "saladin" to "صلاح الدين",
        "halabja" to "حلبجة"
    )
}
