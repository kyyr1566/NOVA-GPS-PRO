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

            // Symbol layer with orange pin – larger, professional (96px bitmap, iconSize 1.0)
            val layer = SymbolLayer("nova-dest-layer", "nova-dest-source")
            layer.setProperties(
                PropertyFactory.iconImage("nova-pin-orange"),
                PropertyFactory.iconSize(1.05f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconAnchor("bottom"),
                PropertyFactory.textField(""), // no inline text, card will show
                PropertyFactory.symbolSortKey(1f)
            )
            style.addLayer(layer)

            // No blue halo around pin on selection – removed per spec.
            // Keep GPS accuracy circle (handled by LocationTrackingController separately).

            // Also listen for taps via Map click in MapActivity – this repo only renders.
        } catch (_: Exception) {}
    }

    fun highlight(id: String) {
        selectedId = id
        showAll()
    }

    fun clearHighlight() {
        selectedId = null
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
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // Outer orange circle – larger for clarity
        val orange = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFF7A00.toInt() // vivid orange matching reference
            style = Paint.Style.FILL
            setShadowLayer(4f, 0f, 3f, 0x33000000)
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
        val cy = size / 2f - 6f // shift up for pin point
        // Orange outer – 30dp radius (vs 22 before)
        c.drawCircle(cx, cy, 30f, orange)
        // White inner – 18dp
        c.drawCircle(cx, cy, 18f, white)
        // Black arrow – larger triangle
        val path = Path().apply {
            moveTo(cx, cy - 10f)
            lineTo(cx - 8.5f, cy + 7f)
            lineTo(cx + 8.5f, cy + 7f)
            close()
        }
        c.drawPath(path, black)
        // Small white highlight on orange for depth
        val hi = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x33FFFFFF
            style = Paint.Style.FILL
        }
        c.drawCircle(cx - 8f, cy - 9f, 5.5f, hi)
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
