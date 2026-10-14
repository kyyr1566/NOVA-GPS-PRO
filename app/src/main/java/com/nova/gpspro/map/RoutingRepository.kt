package com.nova.gpspro.map

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*

/**
 * Real road routing via public OSRM/BRouter-compatible endpoint.
 * Falls back to straight line if offline or no road network downloaded.
 * No Google/Mapbox paid APIs.
 */
class RoutingRepository(private val context: Context) {

    data class RouteResult(
        val coordinates: List<Pair<Double, Double>>, // lat, lon
        val distanceMeters: Double,
        val durationSeconds: Double,
        val isRoad: Boolean
    )

    suspend fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): RouteResult = withContext(Dispatchers.IO) {
        // Check offline routing data first if offline and available – honest attempt
        val hasOfflineRouting = try { OfflineRoutingManager.hasRoutingData(context) } catch (_: Exception) { false }
        val isOnline = isOnline()
        // Try online OSRM if connected
        if (isOnline) {
            val road = tryOsrm(fromLat, fromLon, toLat, toLon)
            if (road != null) return@withContext road
            val road2 = tryOsrmFallback(fromLat, fromLon, toLat, toLon)
            if (road2 != null) return@withContext road2
        }
        // Try offline BRouter if data present (engine stub – will be implemented with brouter-core)
        if (hasOfflineRouting) {
            val offline = tryOfflineBRouter(fromLat, fromLon, toLat, toLon)
            if (offline != null) return@withContext offline
            // If offline data exists but engine not yet integrated, do NOT silently return straight line as road
            // Fall through to straight line with isRoad=false so caller shows honest message
        }
        // Fallback: straight line (as crow flies) – MUST be isRoad=false, caller must not treat as road
        val dist = haversine(fromLat, fromLon, toLat, toLon)
        val coords = listOf(fromLat to fromLon, toLat to toLon)
        val duration = if (dist > 0) dist / (50 * 1000 / 3600.0) else 0.0
        RouteResult(coords, dist, duration, isRoad = false)
    }

    private fun isOnline(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) { true }
    }

    private fun tryOfflineBRouter(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): RouteResult? {
        // Stub for BRouter offline engine – requires brouter-core integration + local rd5 segments
        // Current status: segments download works, engine integration pending airplane-mode test
        // Returning null ensures caller treats straight line as NOT road and shows honest offline message
        // TODO: integrate brouter-codec: BRouter().doRouting(start, end, segmentsDir) → parse GeoJSON
        return null
    }

    /**
     * Road snapping via OSRM nearest (Map Matching lite). Returns nearest drivable road point
     * within 75m. Uses driving profile so footways/service not returned. Keeps original GPS unchanged.
     * Returns null if not reachable or distance >75m or offline without data.
     */
    suspend fun snapToRoad(lat: Double, lon: Double): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        if (!isOnline()) return@withContext null
        try {
            val urlStr = "https://router.project-osrm.org/nearest/v1/driving/${lon},${lat}?number=1"
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "NOVA-GPS-PRO/1.0 (OSM)")
            if (conn.responseCode != 200) return@withContext null
            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            if (json.optString("code") != "Ok") return@withContext null
            val wps = json.getJSONArray("waypoints")
            if (wps.length() == 0) return@withContext null
            val wp = wps.getJSONObject(0)
            val dist = wp.optDouble("distance", 9999.0)
            if (dist > 75.0) return@withContext null // too far – mid-house, don't snap to unreachable
            val loc = wp.getJSONArray("location") // [lon, lat]
            val snapLon = loc.getDouble(0)
            val snapLat = loc.getDouble(1)
            // Ensure not null island
            if (snapLat == 0.0 && snapLon == 0.0) return@withContext null
            snapLat to snapLon
        } catch (_: Exception) { null }
    }

    private fun tryOsrm(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): RouteResult? {
        return try {
            // OSRM demo server — router.project-osrm.org (open, free, OSM data)
            val urlStr = "https://router.project-osrm.org/route/v1/driving/${fromLon},${fromLat};${toLon},${toLat}?overview=full&geometries=geojson"
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "NOVA-GPS-PRO/1.0 (OSM)")
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().readText()
            parseOsrm(body)
        } catch (_: Exception) { null }
    }

    private fun tryOsrmFallback(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): RouteResult? {
        return try {
            // Alternative: OSRM via routing.openstreetmap.de (ROUTE)
            val urlStr = "https://routing.openstreetmap.de/routed-car/route/v1/driving/${fromLon},${fromLat};${toLon},${toLat}?overview=full&geometries=geojson"
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "NOVA-GPS-PRO/1.0 (OSM)")
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().readText()
            parseOsrm(body)
        } catch (_: Exception) { null }
    }

    private fun parseOsrm(jsonStr: String): RouteResult? {
        return try {
            val json = JSONObject(jsonStr)
            if (json.optString("code") != "Ok") return null
            val routes = json.getJSONArray("routes")
            if (routes.length() == 0) return null
            val r = routes.getJSONObject(0)
            val dist = r.getDouble("distance")
            val dur = r.getDouble("duration")
            val geom = r.getJSONObject("geometry")
            val coordsArr = geom.getJSONArray("coordinates") // [lon, lat]
            val coords = mutableListOf<Pair<Double, Double>>()
            for (i in 0 until coordsArr.length()) {
                val c = coordsArr.getJSONArray(i)
                val lon = c.getDouble(0)
                val lat = c.getDouble(1)
                coords.add(lat to lon)
            }
            if (coords.size < 2) return null
            RouteResult(coords, dist, dur, isRoad = true)
        } catch (_: Exception) { null }
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }
}
