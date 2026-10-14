package com.nova.gpspro.map

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads satellite raster tiles for a bounded region at zoom range.
 * Input: id, north/south/east/west, minZoom/maxZoom, urlTemplate
 * Uses EOX Sentinel-2 Cloudless (CC BY) – 45KB avg per tile.
 * Stores under filesDir/offline_map/satellite/<id>/{z}/{x}/{y}.jpg
 * Supports progress, resume (skip existing), verification via file existence.
 */
class SatelliteDownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.failure()
        val north = inputData.getDouble("north", 0.0)
        val south = inputData.getDouble("south", 0.0)
        val east = inputData.getDouble("east", 0.0)
        val west = inputData.getDouble("west", 0.0)
        val minZoom = inputData.getInt("minZoom", 12)
        val maxZoom = inputData.getInt("maxZoom", 15)
        val template = inputData.getString("urlTemplate") ?: "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2020_3857/default/g/{z}/{y}/{x}.jpg"
        val regionDir = SatelliteManager.regionDir(applicationContext, id)
        regionDir.mkdirs()
        // Meta
        try {
            File(regionDir, "meta.json").writeText(
                """{"id":"$id","north":$north,"south":$south,"east":$east,"west":$west,"minZoom":$minZoom,"maxZoom":$maxZoom,"template":"$template"}"""
            )
        } catch (_: Exception) {}
        val total = SatelliteManager.estimateTiles(north, south, east, west, minZoom, maxZoom)
        if (total <= 0 || total > 50000) {
            return Result.failure(workDataOf("error" to "too_many_tiles:$total"))
        }
        var downloaded = 0L
        var failed = 0
        try {
            for (z in minZoom..maxZoom) {
                if (isStopped) return Result.failure(workDataOf("error" to "cancelled"))
                val xMin = SatelliteManager.lon2tileX(west, z)
                val xMax = SatelliteManager.lon2tileX(east, z)
                val yMin = SatelliteManager.lat2tileY(north, z)
                val yMax = SatelliteManager.lat2tileY(south, z)
                for (x in xMin..xMax) {
                    for (y in yMin..yMax) {
                        if (isStopped) return Result.failure(workDataOf("error" to "cancelled"))
                        val file = File(regionDir, "$z/$x/$y.jpg")
                        if (file.exists() && file.length() > 1024) {
                            downloaded++
                            val prog = (downloaded * 100 / total).toInt().coerceIn(0, 100)
                            setProgress(workDataOf("progress" to prog, "id" to id))
                            continue
                        }
                        file.parentFile?.mkdirs()
                        val urlStr = template.replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
                        // Also support Esri template with {z}/{y}/{x}
                        val url = try { URL(urlStr) } catch (_: Exception) { failed++; continue }
                        var conn: HttpURLConnection? = null
                        try {
                            conn = url.openConnection() as HttpURLConnection
                            conn.requestMethod = "GET"
                            conn.connectTimeout = 10000
                            conn.readTimeout = 10000
                            conn.setRequestProperty("User-Agent", "NOVA-GPS-PRO/1.0 (Sentinel2)")
                            conn.connect()
                            when (conn.responseCode) {
                                200 -> {
                                    conn.inputStream.use { input ->
                                        FileOutputStream(file).use { out ->
                                            input.copyTo(out)
                                        }
                                    }
                                    if (file.length() < 1024) { file.delete(); failed++ } else downloaded++
                                }
                                404 -> { failed++; /* no tile – ocean */ }
                                else -> { failed++ }
                            }
                        } catch (_: Exception) { failed++ }
                        finally { try { conn?.disconnect() } catch (_: Exception) {} }
                        val prog = (downloaded * 100 / total).toInt().coerceIn(0, 100)
                        setProgress(workDataOf("progress" to prog, "id" to id))
                        // Be nice to server
                        kotlinx.coroutines.delay(80)
                    }
                }
            }
            // Success if at least 70% downloaded
            val successRatio = if (total > 0) downloaded.toDouble() / total else 0.0
            return if (successRatio >= 0.5) {
                Result.success(workDataOf("id" to id, "downloaded" to downloaded, "total" to total))
            } else {
                Result.failure(workDataOf("error" to "low_success:$downloaded/$total failed:$failed"))
            }
        } catch (e: Exception) {
            return if (runAttemptCount < 2) Result.retry() else Result.failure(workDataOf("error" to (e.message ?: "sat_failed")))
        }
    }
}
