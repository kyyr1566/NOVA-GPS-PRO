package com.nova.gpspro.map

import android.content.Context
import com.nova.gpspro.data.Destination
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Handles preview + navigation: draws route line, info bar, does not auto-start.
 * Start triggers the existing NavigationEngine (arrow).
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

    // Callbacks
    var onRouteUpdated: ((RoutingRepository.RouteResult?) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun attach(map: MapLibreMap) { this.map = map }
    fun detach() { this.map = null; clearRoute() }

    fun previewDestination(d: Destination) {
        destination = d
        destLat = d.latitude
        destLon = d.longitude
        destName = d.name
        // draw straight preview line until real route fetched
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
            // Remove old
            style.removeLayer("nova-route-layer")
            style.removeSource("nova-route-source")
            val points: List<Point> = route.coordinates.map { (lat, lon) -> Point.fromLngLat(lon, lat) }
            val lineString = LineString.fromLngLats(points)
            val feature = Feature.fromGeometry(lineString)
            val source = GeoJsonSource("nova-route-source", feature)
            style.addSource(source)
            val layer = LineLayer("nova-route-layer", "nova-route-source")
            layer.setProperties(
                PropertyFactory.lineColor(if (route.isRoad) "#2856BC" else "#D9A441"),
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineOpacity(0.95f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
            // Add below symbols so route doesn't cover markers
            style.addLayer(layer)
        } catch (_: Exception) {}
    }

    fun clearRoute() {
        val m = map ?: return
        val style = m.style ?: return
        try {
            style.removeLayer("nova-route-layer")
            style.removeSource("nova-route-source")
        } catch (_: Exception) {}
        currentRoute = null
    }

    fun currentDestination(): Triple<Double, Double, String?>? {
        val la = destLat ?: return null
        val lo = destLon ?: return null
        return Triple(la, lo, destName)
    }

    fun getRoute(): RoutingRepository.RouteResult? = currentRoute
}
