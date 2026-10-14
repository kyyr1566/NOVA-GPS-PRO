package com.nova.gpspro.map

import android.content.Context
import com.nova.gpspro.data.Destination
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Handles preview + navigation: draws route line, info bar, does not auto-start.
 * Uses GeoJson string (no org.maplibre.geojson dependency) for MapLibre 11 compatibility.
 */
class NavigationSessionManager(
    private val context: Context,
    private val routing: RoutingRepository,
    private val scope: CoroutineScope
) {
    private var map: MapLibreMap? = null
    private var currentRoute: RoutingRepository.RouteResult? = null
    private var destination: Destination? = null
    private var destLat: Double? = null
    private var destLon: Double? = null
    private var destName: String? = null

    var onRouteUpdated: ((RoutingRepository.RouteResult?) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun attach(map: MapLibreMap) { this.map = map }
    fun detach() { this.map = null; clearRoute() }

    fun previewDestination(d: Destination) {
        destination = d
        destLat = d.latitude
        destLon = d.longitude
        destName = d.name
    }

    fun previewLatLng(lat: Double, lon: Double, name: String? = null) {
        destination = null
        destLat = lat
        destLon = lon
        destName = name
    }

    fun requestRoute(fromLat: Double, fromLon: Double) {
        val toLat = destLat ?: return
        val toLon = destLon ?: return
        scope.launch {
            try {
                val r = routing.route(fromLat, fromLon, toLat, toLon)
                currentRoute = r
                drawRoute(r)
                onRouteUpdated?.invoke(r)
            } catch (e: Exception) {
                onError?.invoke(e.message ?: "تعذر حساب المسار")
            }
        }
    }

    fun drawRoute(route: RoutingRepository.RouteResult) {
        val m = map ?: return
        val style = m.style ?: return
        try {
            style.removeLayer("nova-route-glow")
            style.removeLayer("nova-route-layer")
            style.removeSource("nova-route-source")
            // Build LineString coordinates as [lon, lat]
            val coords = route.coordinates.joinToString(",") { (lat, lon) -> "[$lon,$lat]" }
            val geoJson = """{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coords]},"properties":{}}"""
            val source = GeoJsonSource("nova-route-source", geoJson)
            style.addSource(source)
            // Glow / halo outer (wider, soft) – draws beneath
            val glow = LineLayer("nova-route-glow", "nova-route-source")
            glow.setProperties(
                PropertyFactory.lineColor(if (route.isRoad) "#00B8D4" else "#D9A441"),
                PropertyFactory.lineWidth(if (route.isRoad) 12f else 8f),
                PropertyFactory.lineOpacity(if (route.isRoad) 0.22f else 0.35f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineBlur(1.2f)
            )
            style.addLayer(glow)
            // Main turquoise cyan – matches reference image
            val layer = LineLayer("nova-route-layer", "nova-route-source")
            layer.setProperties(
                PropertyFactory.lineColor(if (route.isRoad) "#00E5FF" else "#D9A441"),
                PropertyFactory.lineWidth(if (route.isRoad) 5.8f else 4.5f),
                PropertyFactory.lineOpacity(0.98f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
            style.addLayer(layer)
        } catch (_: Exception) {}
    }

    fun clearRoute() {
        val m = map ?: return
        val style = m.style ?: return
        try {
            style.removeLayer("nova-route-glow")
        } catch (_: Exception) {}
        try {
            style.removeLayer("nova-route-layer")
            style.removeSource("nova-route-source")
        } catch (_: Exception) {}
        try { style.removeLayer("nova-connector-layer") } catch (_: Exception) {}
        try { style.removeSource("nova-connector-source") } catch (_: Exception) {}
        currentRoute = null
    }

    fun drawConnector(gpsLat: Double, gpsLon: Double, roadLat: Double, roadLon: Double) {
        val m = map ?: return
        val style = m.style ?: return
        try {
            style.removeLayer("nova-connector-layer")
            style.removeSource("nova-connector-source")
            val geoJson = """{"type":"Feature","geometry":{"type":"LineString","coordinates":[[$gpsLon,$gpsLat],[$roadLon,$roadLat]]},"properties":{}}"""
            val src = GeoJsonSource("nova-connector-source", geoJson)
            style.addSource(src)
            val layer = LineLayer("nova-connector-layer", "nova-connector-source")
            layer.setProperties(
                PropertyFactory.lineColor("#FFA500"),
                PropertyFactory.lineWidth(2.5f),
                PropertyFactory.lineOpacity(0.85f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineDasharray(arrayOf(3f, 3f))
            )
            style.addLayer(layer)
        } catch (_: Exception) {}
    }

    fun currentDestination(): Triple<Double, Double, String?>? {
        val la = destLat ?: return null
        val lo = destLon ?: return null
        return Triple(la, lo, destName)
    }

    fun getRoute(): RoutingRepository.RouteResult? = currentRoute
}
