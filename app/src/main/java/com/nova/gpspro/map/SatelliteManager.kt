package com.nova.gpspro.map

import android.content.Context
import java.io.File
import kotlin.math.*

/**
 * Satellite offline manager – selection rectangle, resolution, estimated size, download, offline display.
 * Uses licensed source: EOX Sentinel-2 Cloudless 2020 (CC BY 4.0, Contains Copernicus Sentinel-2)
 * WMTS: https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2020_3857/default/g/{z}/{y}/{x}.jpg
 * Also supports Esri World Imagery as fallback: https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}
 *
 * Storage: filesDir/offline_map/satellite/<regionId>/{z}/{x}/{y}.jpg  + meta.json
 * Display: raster source with tiles file://...
 */
object SatelliteManager {

    data class Region(
        val id: String, // e.g. sat_20261014_123456
        val north: Double, val south: Double, val east: Double, val west: Double,
        val minZoom: Int, val maxZoom: Int,
        val urlTemplate: String = "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2020_3857/default/g/{z}/{y}/{x}.jpg"
    )

    fun satelliteDir(context: Context): File = File(context.filesDir, "offline_map/satellite").apply { mkdirs() }

    fun regionDir(context: Context, id: String): File = File(satelliteDir(context), id).apply { mkdirs() }

    fun hasRegion(context: Context, id: String): Boolean {
        val dir = regionDir(context, id)
        return dir.exists() && (dir.listFiles()?.any { it.isDirectory } == true)
    }

    fun listRegions(context: Context): List<File> {
        val dir = satelliteDir(context)
        return dir.listFiles()?.filter { it.isDirectory && it.listFiles()?.isNotEmpty() == true } ?: emptyList()
    }

    fun estimateTiles(north: Double, south: Double, east: Double, west: Double, minZoom: Int, maxZoom: Int): Long {
        var total = 0L
        for (z in minZoom..maxZoom) {
            val xMin = lon2tileX(west, z)
            val xMax = lon2tileX(east, z)
            val yMin = lat2tileY(north, z)
            val yMax = lat2tileY(south, z)
            val w = abs(xMax - xMin) + 1
            val h = abs(yMax - yMin) + 1
            total += w.toLong() * h.toLong()
        }
        return total
    }

    fun estimateSizeMB(tiles: Long, avgKB: Int = 45): Double {
        return tiles * avgKB / 1024.0
    }

    fun deleteRegion(context: Context, id: String) {
        try { regionDir(context, id).deleteRecursively() } catch (_: Exception) {}
    }

    fun lon2tileX(lon: Double, z: Int): Int {
        val n = 2.0.pow(z)
        return ((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, (n-1).toInt())
    }

    fun lat2tileY(lat: Double, z: Int): Int {
        val latRad = Math.toRadians(lat)
        val n = 2.0.pow(z)
        val y = (1.0 - ln(tan(latRad) + 1 / cos(latRad)) / PI) / 2.0 * n
        return y.toInt().coerceIn(0, (n-1).toInt())
    }

    fun enqueueDownload(context: Context, region: Region) {
        val data = androidx.work.Data.Builder()
            .putString("id", region.id)
            .putDouble("north", region.north)
            .putDouble("south", region.south)
            .putDouble("east", region.east)
            .putDouble("west", region.west)
            .putInt("minZoom", region.minZoom)
            .putInt("maxZoom", region.maxZoom)
            .putString("urlTemplate", region.urlTemplate)
            .build()
        val req = androidx.work.OneTimeWorkRequestBuilder<SatelliteDownloadWorker>()
            .setInputData(data)
            .addTag("satellite_download")
            .addTag(region.id)
            .build()
        androidx.work.WorkManager.getInstance(context).enqueue(req)
    }
}
