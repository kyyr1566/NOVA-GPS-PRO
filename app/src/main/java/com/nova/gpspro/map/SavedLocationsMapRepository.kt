package com.nova.gpspro.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.nova.gpspro.data.DestinationRepository
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

/**
 * Shows all saved destinations as markers (WGS84 latitude/longitude).
 */
class SavedLocationsMapRepository(private val repo: DestinationRepository) {

    private var markers: MutableList<Marker> = mutableListOf()
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
        clear()
        val dests = repo.all()
        for (d in dests) {
            try {
                val isSelected = d.id == selectedId
                val icon = createMarkerIcon(m, isSelected)
                val marker = m.addMarker(
                    MarkerOptions()
                        .position(LatLng(d.latitude, d.longitude))
                        .title(d.name)
                        .snippet(String.format("%.6f, %.6f", d.latitude, d.longitude))
                        .icon(icon)
                )
                markers.add(marker)
            } catch (_: Exception) {}
        }
    }

    fun highlight(id: String) {
        selectedId = id
        showAll()
    }

    fun clear() {
        val m = map ?: return
        for (mk in markers) {
            try { m.removeMarker(mk) } catch (_: Exception) {}
        }
        markers.clear()
    }

    private fun createMarkerIcon(m: MapLibreMap, selected: Boolean): Icon {
        val size = if (selected) 48 else 36
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = if (selected) 0xFF2856BC.toInt() else 0xFFD9A441.toInt()
        paint.style = Paint.Style.FILL
        val cx = size / 2f
        val cy = size / 2.8f
        val r = size / 3.2f
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.FILL
        paint.color = if (selected) 0xFF2856BC.toInt() else 0xFFD9A441.toInt()
        val tipPath = Path()
        tipPath.moveTo(cx - r * 0.6f, cy + r * 0.7f)
        tipPath.lineTo(cx + r * 0.6f, cy + r * 0.7f)
        tipPath.lineTo(cx, size - 4f)
        tipPath.close()
        canvas.drawPath(tipPath, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, r * 0.35f, paint)
        return IconFactory.getInstance(m.context).fromBitmap(bmp)
    }
}
