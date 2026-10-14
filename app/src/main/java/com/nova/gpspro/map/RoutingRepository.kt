package com.nova.gpspro.map

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*

/**
 * Real road routing via open-source online engines.
 * Production: FOSSGIS OSRM (routing.openstreetmap.de) – free, OSM ODbL, fair-use, suitable for ~10k daily if cached & rate-limited.
 * Fallback: OSRM demo (router.project-osrm.org) – NOT for production, only for fallback/testing.
 * Alternative: Valhalla (valhalla1.openstreetmap.de) – community, free.
 *
 * No Google/Mapbox paid APIs. Easy provider switching via prefs.
 * For 25k users / 10k daily: recommend self-hosted OSRM/Valhalla/GraphHopper or commercial FOSSGIS/Stadia/MapTiler with SLA.
 * See docs/MAPS_SOURCES.md for costs/limits.
 */
class RoutingRepository(private val context: Context) {

    data class RouteResult(
        val coordinates: List<Pair<Double, Double>>, // lat, lon
        val distanceMeters: Double,
        val durationSeconds: Double,
        val isRoad: Boolean
    )

    enum class Provider(val key: String) {
        FOSSGIS("fosgis"),
        OSRM_DEMO("osrm_demo"),
        VALHALLA("valhalla")
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("nova_routing", Context.MODE_PRIVATE)

    var provider: Provider
        get() = when (prefs.getString("provider", Provider.FOSSGIS.key)) {
            Provider.VALHALLA.key -> Provider.VALHALLA
            Provider.OSRM_DEMO.key -> Provider.OSRM_DEMO
            else -> Provider.FOSSGIS
        }
        set(v) { prefs.edit().putString("provider", v.key).apply() }

    fun availableProviders(): List<Pair<String,String>> = listOf(
        Provider.FOSSGIS.key to "FOSSGIS (routing.openstreetmap.de) – مجاني، إنتاج",
        Provider.VALHALLA.key to "Valhalla OSM.de – مجاني، بديل",
        Provider.OSRM_DEMO.key to "OSRM Demo (تجريبي، غير للإنتاج)"
    )

    suspend fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): RouteResult = withContext(Dispatchers.IO) {
        val isOnline = isOnline()
        if (!isOnline) {
            // Offline: original arrow GPS still works, but street routing requires internet per final spec.
            val dist = haversine(fromLat, fromLon, toLat, toLon)
            val coords = listOf(fromLat to fromLon, toLat to toLon)
            val duration = if (dist > 0) dist / (50 * 1000 / 3600.0) else 0.0
            return@withContext RouteResult(coords, dist, duration, isRoad = false)
        }
        // Try selected provider first, then fallbacks
        val order = when (provider) {
            Provider.FOSSGIS -> listOf(Provider.FOSSGIS, Provider.VALHALLA, Provider.OSRM_DEMO)
            Provider.VALHALLA -> listOf(Provider.VALHALLA, Provider.FOSSGIS, Provider.OSRM_DEMO)
            Provider.OSRM_DEMO -> listOf(Provider.OSRM_DEMO, Provider.FOSSGIS, Provider.VALHALLA)
        }
        for (p in order) {
            val res = when (p) {
                Provider.FOSSGIS -> tryFosgis(fromLat, fromLon, toLat, toLon)
                Provider.OSRM_DEMO -> tryOsrmDemo(fromLat, fromLon, toLat, toLon)
                Provider.VALHALLA -> tryValhalla(fromLat, fromLon, toLat, toLon)
            }
            if (res != null) return@withContext res
        }
        // All failed – honest straight line (not road)
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

    /**
     * Map-matching / nearest road – keep real GPS, snap start to nearest drivable road within 75m.
     */
    suspend fun snapToRoad(lat: Double, lon: Double): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        if (!isOnline()) return@withContext null
        // Use FOSSGIS nearest if provider is FOSSGIS, else OSRM demo nearest for snapping (all use driving profile)
        val urlStr = when (provider) {
            Provider.FOSSGIS -> "https://routing.openstreetmap.de/routed-car/nearest/v1/driving/${lon},${lat}?number=1"
            else -> "https://router.project-osrm.org/nearest/v1/driving/${lon},${lat}?number=1"
        }
        try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "RMG-GPS-Pro/1.0 (OSM)")
            if (conn.responseCode != 200) return@withContext null
            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            if (json.optString("code") != "Ok") return@withContext null
            val wps = json.getJSONArray("waypoints")
            if (wps.length() == 0) return@withContext null
            val wp = wps.getJSONObject(0)
            val dist = wp.optDouble("distance", 9999.0)
            if (dist > 75.0) return@withContext null
            val loc = wp.getJSONArray("location")
            val snapLon = loc.getDouble(0)
            val snapLat = loc.getDouble(1)
            if (snapLat == 0.0 && snapLon == 0.0) return@withContext null
            snapLat to snapLon
        } catch (_: Exception) { null }
    }

    private fun tryFosgis(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): RouteResult? {
        return try {
            val urlStr = "https://routing.openstreetmap.de/routed-car/route/v1/driving/${fromLon},${fromLat};${toLon},${toLat}?overview=full&geometries=geojson"
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "RMG-GPS-Pro/1.0 (OSM)")
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().readText()
            parseOsrm(body)
        } catch (_: Exception) { null }
    }

    private fun tryOsrmDemo(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): RouteResult? {
        return try {
            val urlStr = "https://router.project-osrm.org/route/v1/driving/${fromLon},${fromLat};${toLon},${toLat}?overview=full&geometries=geojson"
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "RMG-GPS-Pro/1.0 (OSM)")
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().readText()
            parseOsrm(body)
        } catch (_: Exception) { null }
    }

    private fun tryValhalla(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): RouteResult? {
        return try {
            // Valhalla via openstreetmap.de – JSON POST alternative would be more robust, but GET via OSRM-compatible endpoint not available.
            // Use OSRM-compatible fallback via valhalla's OSRM API if exposed. For now, use FOSSGIS-like.
            // Simple: use valhalla routing via https://valhalla1.openstreetmap.de/route?json=...
            val jsonBody = """{"locations":[{"lat":$fromLat,"lon":$fromLon},{"lat":$toLat,"lon":$toLon}],"costing":"auto","directions_options":{"units":"kilometers"}}"""
            val url = URL("https://valhalla1.openstreetmap.de/route")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 9000
            conn.readTimeout = 9000
            conn.setRequestProperty("User-Agent", "RMG-GPS-Pro/1.0 (OSM)")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(jsonBody.toByteArray()) }
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().readText()
            parseValhalla(body)
        } catch (_: Exception) { null }
    }

    private fun parseValhalla(jsonStr: String): RouteResult? {
        return try {
            val json = JSONObject(jsonStr)
            val trip = json.optJSONObject("trip") ?: return null
            val status = trip.optInt("status", 0)
            if (status != 0) return null
            // Valhalla returns legs with shape – need to decode polyline. Use summary distance/time and straight line fallback for shape if needed.
            val summary = trip.optJSONObject("summary") ?: return null
            val distKm = summary.optDouble("length", -1.0)
            val timeSec = summary.optDouble("time", -1.0)
            if (distKm < 0 || timeSec < 0) return null
            val dist = distKm * 1000.0
            // Try to get shape – decoded polyline6
            val legs = trip.optJSONArray("legs")
            if (legs != null && legs.length() > 0) {
                val leg0 = legs.getJSONObject(0)
                val shape = leg0.optString("shape", "")
                if (shape.isNotEmpty()) {
                    val coords = decodePolyline(shape, 6)
                    if (coords.size >= 2) return RouteResult(coords, dist, timeSec, isRoad = true)
                }
            }
            null
        } catch (_: Exception) { null }
    }

    private fun decodePolyline(encoded: String, precision: Int): List<Pair<Double, Double>> {
        val coords = mutableListOf<Pair<Double, Double>>()
        var index = 0
        var lat = 0
        var lng = 0
        val factor = 10.0.pow(precision.toDouble())
        while (index < encoded.length) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lat += dlat
            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lng += dlng
            coords.add(Pair(lat / factor, lng / factor))
        }
        return coords
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
            val coordsArr = geom.getJSONArray("coordinates")
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
