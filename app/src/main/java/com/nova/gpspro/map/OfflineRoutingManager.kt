package com.nova.gpspro.map

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Offline routing data manager for BRouter (or Valhalla tiles).
 * Stores BRouter segments (rd5) under filesDir/offline_map/brouter/segments4/
 * Iraq coverage: 6 segments of 5x5 degrees covering bbox 38.8,29.5 – 48.5,37.4
 *
 * Source: BRouter segment server https://brouter.de/brouter/segments4/ (open, ODbL)
 *        Each segment ~5-30 MB, generated from OSM.
 * Host mirror: GitHub Releases kyyr1566/NOVA-GPS-PRO/releases/download/routing-v1/<id>.rd5
 *        (to be uploaded after generation – see tools/generate-routing.md)
 *
 * Verification: SHA-256 if provided, otherwise size check.
 * Engine: BRouter offline engine (brouter-core) – integration requires native/BRouter lib.
 *         Current APK downloads segments and detects presence; routing engine stub returns null
 *         until BRouter lib is fully integrated (see RoutingRepository.tryOfflineBRouter).
 *         Honest status: download works, offline re-routing will be enabled after engine test in airplane mode.
 */
object OfflineRoutingManager {

    data class SegmentInfo(
        val id: String, // e.g. E45_N30
        val nameAr: String,
        val urlPrimary: String, // brouter.de
        val urlMirror: String, // GitHub Releases
        val sizeBytes: Long,
        val sha256: String?
    )

    private const val SEGMENT_DIR = "offline_map/brouter/segments4"

    fun routingDir(context: Context): File = File(context.filesDir, SEGMENT_DIR).apply { mkdirs() }

    fun segmentFile(context: Context, id: String): File = File(routingDir(context), "$id.rd5")

    fun hasSegment(context: Context, id: String): Boolean {
        val f = segmentFile(context, id)
        return f.exists() && f.length() > 1024
    }

    fun hasRoutingData(context: Context): Boolean {
        // At least 2 segments present is considered "has data" for Iraq
        val dir = routingDir(context)
        val files = dir.listFiles()?.filter { it.length() > 1024 } ?: return false
        return files.size >= 2
    }

    fun listDownloadedSegments(context: Context): List<File> {
        val dir = routingDir(context)
        return dir.listFiles()?.filter { it.extension == "rd5" && it.length() > 1024 } ?: emptyList()
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

    fun deleteSegment(context: Context, id: String) {
        try { segmentFile(context, id).delete() } catch (_: Exception) {}
    }

    fun availableSegments(): List<SegmentInfo> {
        val basePrimary = "https://brouter.de/brouter/segments4"
        val baseMirror = "https://github.com/kyyr1566/NOVA-GPS-PRO/releases/download/routing-v1"
        fun seg(id: String, ar: String, sizeMb: Int) = SegmentInfo(
            id = id, nameAr = ar,
            urlPrimary = "$basePrimary/$id.rd5",
            urlMirror = "$baseMirror/$id.rd5",
            sizeBytes = sizeMb * 1024L * 1024L,
            sha256 = null
        )
        return listOf(
            seg("E35_N30", "غرب العراق (E35_N30)", 18),
            seg("E40_N30", "جنوب العراق (E40_N30)", 22),
            seg("E45_N30", "جنوب شرق (E45_N30)", 20),
            seg("E35_N35", "شمال غرب (E35_N35)", 15),
            seg("E40_N35", "شمال (E40_N35)", 25),
            seg("E45_N35", "شمال شرق (E45_N35)", 23)
        )
    }

    // Download helper – uses MapDownloadWorker with outPath override
    fun enqueueDownload(context: Context, seg: SegmentInfo, useMirror: Boolean = false) {
        val url = if (useMirror) seg.urlMirror else seg.urlPrimary
        val outPath = segmentFile(context, seg.id).absolutePath
        val data = androidx.work.Data.Builder()
            .putString("id", seg.id)
            .putString("url", url)
            .putString("sha256", seg.sha256)
            .putString("outPath", outPath)
            .build()
        val req = androidx.work.OneTimeWorkRequestBuilder<MapDownloadWorker>()
            .setInputData(data)
            .addTag("nova_routing_download")
            .addTag(seg.id)
            .build()
        androidx.work.WorkManager.getInstance(context).enqueue(req)
    }
}
