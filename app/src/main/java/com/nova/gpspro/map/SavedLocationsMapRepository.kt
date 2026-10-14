package com.nova.gpspro.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.nova.gpspro.data.DestinationRepository
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * Shows all saved destinations as orange pin markers (WGS84).
 * Uses GeoJsonSource + SymbolLayer with custom orange pin bitmap
 * (orange outer, white inner, black arrow) — matches reference image.
 * Coordinates are used exactly as stored (no modification).
 */
class SavedLocationsMapRepository(private val repo: DestinationRepository) {

    private var map: MapLibreMap? = null
    private var selectedId: String? = null
    private var context: Context? = null

    fun attach(map: MapLibreMap) {
        this.map = map
        showAll()
    }

    fun attachWithContext(map: MapLibreMap, ctx: Context) {
        this.context = ctx
        attach(map)
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
            // Ensure pin image exists
            ensurePinImage(style)

            // Build GeoJSON FeatureCollection with all destinations
            val features = dests.joinToString(",") { d ->
                """{"type":"Feature","geometry":{"type":"Point","coordinates":[${d.longitude},${d.latitude}]},"properties":{"id":"${d.id}","name":${jsonEscape(d.name)}} }"""
            }
            val geoJson = """{"type":"FeatureCollection","features":[$features]}"""
            val source = GeoJsonSource("nova-dest-source", geoJson)
            style.addSource(source)

            // Symbol layer with orange pin
            val layer = SymbolLayer("nova-dest-layer", "nova-dest-source")
            layer.setProperties(
                PropertyFactory.iconImage("nova-pin-orange"),
                PropertyFactory.iconSize(0.85f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconAnchor("bottom"),
                PropertyFactory.textField(""), // no inline text, card will show
                PropertyFactory.symbolSortKey(1f)
            )
            style.addLayer(layer)

            // Selected highlight as larger blue halo circle beneath pin
            if (selectedId != null) {
                val selected = dests.find { it.id == selectedId }
                if (selected != null) {
                    val selJson = """{"type":"Feature","geometry":{"type":"Point","coordinates":[${selected.longitude},${selected.latitude}]},"properties":{}}"""
                    val selSource = GeoJsonSource("nova-dest-selected-source", selJson)
                    style.addSource(selSource)
                    val selLayer = CircleLayer("nova-dest-selected-layer", "nova-dest-selected-source")
                    selLayer.setProperties(
                        PropertyFactory.circleRadius(18f),
                        PropertyFactory.circleColor("#1A3A8F"),
                        PropertyFactory.circleOpacity(0.18f),
                        PropertyFactory.circleStrokeWidth(2f),
                        PropertyFactory.circleStrokeColor("#2856BC")
                    )
                    // Add below pin layer for halo effect
                    try {
                        style.addLayerBelow(selLayer, "nova-dest-layer")
                    } catch (_: Exception) {
                        style.addLayer(selLayer)
                    }
                }
            }

            // Also listen for taps via Map click in MapActivity – this repo only renders.
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

    fun getDestinationById(id: String) = repo.get(id)

    fun findNearest(lat: Double, lon: Double, thresholdMeters: Double = 80.0): com.nova.gpspro.data.Destination? {
        // Simple linear search for nearest within threshold – Map clicks use queryRenderedFeatures, this is fallback
        var best: com.nova.gpspro.data.Destination? = null
        var bestDist = Double.MAX_VALUE
        for (d in repo.all()) {
            val dist = haversine(lat, lon, d.latitude, d.longitude)
            if (dist < bestDist && dist <= thresholdMeters) {
                bestDist = dist
                best = d
            }
        }
        return best
    }

    private fun ensurePinImage(style: org.maplibre.android.maps.Style) {
        try {
            if (style.getImage("nova-pin-orange") != null) return
        } catch (_: Exception) {}
        try {
            val bmp = createOrangePinBitmap()
            style.addImage("nova-pin-orange", bmp)
        } catch (_: Exception) {}
    }

    private fun createOrangePinBitmap(): Bitmap {
        val size = 72
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // Outer orange circle
        val orange = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFF7A00.toInt() // vivid orange matching reference
            style = Paint.Style.FILL
            setShadowLayer(3f, 0f, 2f, 0x33000000)
        }
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.FILL
        }
        val black = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1A1A1A.toInt()
            style = Paint.Style.FILL
        }
        val cx = size / 2f
        val cy = size / 2f - 4f // shift up for pin point
        // Orange outer
        c.drawCircle(cx, cy, 22f, orange)
        // White inner
        c.drawCircle(cx, cy, 13.5f, white)
        // Black arrow (triangle pointing north-east slightly)
        val path = Path().apply {
            moveTo(cx, cy - 8f)
            lineTo(cx - 6.5f, cy + 5f)
            lineTo(cx + 6.5f, cy + 5f)
            close()
        }
        c.drawPath(path, black)
        // Small white highlight on orange for depth
        val hi = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x33FFFFFF
            style = Paint.Style.FILL
        }
        c.drawCircle(cx - 6f, cy - 7f, 4f, hi)
        return bmp
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return R * c
    }

    private fun jsonEscape(s: String): String {
        val esc = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return "\"$esc\""
    }
}
