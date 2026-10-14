package com.nova.gpspro.map

import android.content.Context
import android.content.SharedPreferences
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory

/**
 * Online professional styles – 8 real layers, no offline download.
 * All sources are open, free for commercial use with attribution, suitable for 25k users.
 * - Vector: OpenFreeMap (planet tiles, BSD + ODbL) – dark, streets, light, navigation
 * - Raster satellite: Esri World Imagery (free online, attribution, see docs) + EOX Sentinel-2 fallback
 * - Hybrid: Esri raster + transparent OpenFreeMap overlay for roads/labels
 * - Terrain: Terrarium DEM hillshade + vector overlay
 * - Topo: OpenTopoMap raster (CC BY-SA) + vector overlay
 *
 * 3D via FillExtrusion using OSM building heights (render_height/height/levels).
 * Switching preserves camera/route – pure Style URI swap.
 */
class MapStyleManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("nova_map_style", Context.MODE_PRIVATE)

    var is3DEnabled: Boolean
        get() = prefs.getBoolean("is3D", false)
        set(v) { prefs.edit().putBoolean("is3D", v).apply() }

    var currentLayer: String
        get() = prefs.getString("layer", "dark") ?: "dark"
        private set(v) { prefs.edit().putString("layer", v).apply() }

    // 3 layers only – as requested: light, dark, satellite HD
    val orderedLayers: List<String> = listOf(
        "light",       // 1. النهارية الفاتحة
        "dark",        // 2. الليلية السوداء والذهبية
        "satellite"    // 3. القمر الصناعي عالي الجودة
    )

    fun layerLabel(key: String): String = when (key) {
        "dark" -> "الليلية السوداء والذهبية"
        "light" -> "النهارية الفاتحة"
        "satellite" -> "القمر الصناعي"
        else -> key
    }

    fun setLayer(key: String) {
        if (key in orderedLayers) currentLayer = key
    }

    fun nextLayer(current: String): String {
        val idx = orderedLayers.indexOf(current).let { if (it == -1) 0 else it }
        return orderedLayers[(idx + 1) % orderedLayers.size]
    }

    fun currentStyleUrl(): String {
        return when (currentLayer) {
            "light" -> "asset://mapstyles/light.json"
            "dark" -> "asset://mapstyles/dark.json"
            "satellite" -> "asset://mapstyles/satellite.json"
            else -> "asset://mapstyles/light.json"
        }
    }

    fun styleUrlFor(key: String): String = when (key) {
        "light" -> "asset://mapstyles/light.json"
        "dark" -> "asset://mapstyles/dark.json"
        "satellite" -> "asset://mapstyles/satellite.json"
        else -> "asset://mapstyles/light.json"
    }

    fun applyStyle(style: Style, map: MapLibreMap) {
        try {
            val building = style.getLayer("building")
            val building3d = style.getLayer("building-3d")
            if (building != null && building3d != null) {
                if (is3DEnabled) {
                    try { building.setProperties(PropertyFactory.visibility(Property.NONE)) } catch (_: Exception) {}
                    try {
                        val b3 = building3d as? FillExtrusionLayer
                        b3?.setProperties(
                            PropertyFactory.visibility(Property.VISIBLE),
                            PropertyFactory.fillExtrusionOpacity(0.88f),
                            PropertyFactory.fillExtrusionColor("#2A3A4A"),
                            PropertyFactory.fillExtrusionBase(0f)
                        )
                    } catch (_: Exception) {
                        try { building3d.setProperties(PropertyFactory.visibility(Property.VISIBLE)) } catch (_: Exception) {}
                    }
                } else {
                    try { building.setProperties(PropertyFactory.visibility(Property.VISIBLE)) } catch (_: Exception) {}
                    try { building3d.setProperties(PropertyFactory.visibility(Property.NONE)) } catch (_: Exception) {}
                }
            } else {
                style.layers.forEach { layer ->
                    if (layer.id.contains("building", ignoreCase = true)) {
                        try {
                            if (layer.id == "building-3d") {
                                layer.setProperties(PropertyFactory.visibility(if (is3DEnabled) Property.VISIBLE else Property.NONE))
                            } else if (layer.id == "building") {
                                layer.setProperties(PropertyFactory.visibility(if (is3DEnabled) Property.NONE else Property.VISIBLE))
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
