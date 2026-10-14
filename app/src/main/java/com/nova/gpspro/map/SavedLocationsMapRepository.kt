package com.nova.gpspro.map

import com.nova.gpspro.data.DestinationRepository
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * Shows all saved destinations as circle markers (WGS84).
 * Uses GeoJsonSource + CircleLayer (core, no annotation plugin) for compatibility with MapLibre 11.
 */
class SavedLocationsMapRepository(private val repo: DestinationRepository) {

    private var map: MapLibreMap? = null
    private var selectedId: String? = null

    fun attach(map: MapLibreMap) {
        this.map = map
        showAll()
    }

    fun detach() {
        clear()
        map = null
    }

    fun showAll() {
        val m = map ?: return
        val style = m.style ?: return
        clear()
        val dests = repo.all()
        if (dests.isEmpty()) return
        try {
            // Build GeoJSON FeatureCollection with all destinations
            val features = dests.joinToString(",") { d ->
                val isSel = d.id == selectedId
                val color = if (isSel) "#2856BC" else "#D9A441"
                // Store color in properties for styling via data-driven? For simplicity use single color but enlarge selected via separate source
                // We'll create two collections: selected vs others for visual distinction
                """{"type":"Feature","geometry":{"type":"Point","coordinates":[${d.longitude},${d.latitude}]},"properties":{"id":"${d.id}","name":${jsonEscape(d.name)},"selected":$isSel}}"""
            }
            val geoJson = """{"type":"FeatureCollection","features":[$features]}"""
            val source = GeoJsonSource("nova-dest-source", geoJson)
            style.addSource(source)
            // Circle layer for all points
            val layer = CircleLayer("nova-dest-layer", "nova-dest-source")
            layer.setProperties(
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleColor("#D9A441"),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleOpacity(0.95f)
            )
            style.addLayer(layer)
            // Selected highlight as larger circle
            if (selectedId != null) {
                val selected = dests.find { it.id == selectedId }
                if (selected != null) {
                    val selJson = """{"type":"Feature","geometry":{"type":"Point","coordinates":[${selected.longitude},${selected.latitude}]},"properties":{}}"""
                    val selSource = GeoJsonSource("nova-dest-selected-source", selJson)
                    style.addSource(selSource)
                    val selLayer = CircleLayer("nova-dest-selected-layer", "nova-dest-selected-source")
                    selLayer.setProperties(
                        PropertyFactory.circleRadius(10f),
                        PropertyFactory.circleColor("#2856BC"),
                        PropertyFactory.circleStrokeWidth(2.5f),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                        PropertyFactory.circleOpacity(1f)
                    )
                    style.addLayer(selLayer)
                }
            }
        } catch (_: Exception) {}
    }

    fun highlight(id: String) {
        selectedId = id
        showAll()
    }

    fun clear() {
        val m = map ?: return
        val style = m.style ?: return
        try {
            style.removeLayer("nova-dest-selected-layer")
            style.removeSource("nova-dest-selected-source")
        } catch (_: Exception) {}
        try {
            style.removeLayer("nova-dest-layer")
            style.removeSource("nova-dest-source")
        } catch (_: Exception) {}
    }

    private fun jsonEscape(s: String): String {
        val esc = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return "\"$esc\""
    }
}
