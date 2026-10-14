package com.nova.gpspro.map

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.security.MessageDigest

/**
 * Manages offline PMTiles for Iraqi governorates + road network + satellite manifest.
 * Stored under filesDir/offline_map/
 * Supports pmtiles://file:// style for MapLibre.
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

    fun getOfflineStyleUrl(context: Context, layer: String): String? {
        // If we have a local PMTiles, build a local style URL
        // MapLibre style JSON can reference pmtiles:// URIs; we keep style JSON locally but swap sources
        val f = pmtilesFile(context, "iraq_pmtiles")
        if (!f.exists()) return null
        // Return pmtiles file URI to be used in style builder
        // Caller will use this to replace tile URL
        return "pmtiles://file://${f.absolutePath}"
    }

    fun listPackages(context: Context): List<MapPackageManifest.PackageInfo> {
        val manifest = MapPackageManifest.load(context) ?: return emptyList()
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
