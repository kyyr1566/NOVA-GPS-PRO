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
        // Try OSRM public demo (free, open)
        val road = tryOsrm(fromLat, fromLon, toLat, toLon)
        if (road != null) return@withContext road
        // Try alternative: Valhalla or OSRM fallback
        val road2 = tryOsrmFallback(fromLat, fromLon, toLat, toLon)
        if (road2 != null) return@withContext road2
        // Fallback: straight line (as crow flies)
        val dist = haversine(fromLat, fromLon, toLat, toLon)
        val coords = listOf(fromLat to fromLon, toLat to toLon)
        // Assume 50 km/h avg for time estimate
        val duration = if (dist > 0) dist / (50 * 1000 / 3600.0) else 0.0
        RouteResult(coords, dist, duration, isRoad = false)
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
