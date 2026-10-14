package com.nova.gpspro.map

import android.content.Context
import android.content.SharedPreferences
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory

/**
 * Manages map styles: dark/gold (default), streets, terrain, satellite, 3D buildings.
 * Real 3D via FillExtrusion using OSM heights (render_height / height / levels).
 */
class MapStyleManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("nova_map_style", Context.MODE_PRIVATE)

    var is3DEnabled: Boolean
        get() = prefs.getBoolean("is3D", false)
        set(v) { prefs.edit().putBoolean("is3D", v).apply() }

    var currentLayer: String
        get() = prefs.getString("layer", "dark") ?: "dark"
        private set(v) { prefs.edit().putString("layer", v).apply() }

    fun setLayer(key: String) {
        currentLayer = key
        if (key == "3d") is3DEnabled = true
    }

    fun currentStyleUrl(): String {
        // Check offline PMTiles first – buildOfflineStyleJson will handle actual style generation in MapActivity
        val offline = OfflineMapManager.getOfflineStyleUrl(context, currentLayer)
        if (offline != null) return offline
        return when (currentLayer) {
            "dark" -> "asset://mapstyles/dark.json"
            "streets" -> "asset://mapstyles/streets.json"
            "terrain" -> "asset://mapstyles/terrain.json"
            "satellite" -> "asset://mapstyles/satellite.json"
            "3d" -> "asset://mapstyles/dark.json"
            else -> "asset://mapstyles/dark.json"
        }
    }

    fun applyStyle(style: Style, map: MapLibreMap) {
        try {
            // Find building layers
            val building = style.getLayer("building")
            val building3d = style.getLayer("building-3d")
            if (building != null && building3d != null) {
                if (is3DEnabled) {
                    // Show 3D extrusion, hide 2D fill slightly (keep but lower opacity)
                    try { building.setProperties(PropertyFactory.visibility(Property.NONE)) } catch (_: Exception) {}
                    try {
                        val b3 = building3d as? FillExtrusionLayer
                        b3?.setProperties(
                            PropertyFactory.visibility(Property.VISIBLE),
                            PropertyFactory.fillExtrusionOpacity(0.88f),
                            // Keep style-defined height (coalesce render_height/height/levels*3.5) – opacity+color only here
                            PropertyFactory.fillExtrusionColor("#2A3A4A"),
                            PropertyFactory.fillExtrusionBase(0f)
                        )
                        // Ensure height uses render_height if present, else estimate – style already defines identity, so this keeps it
                    } catch (_: Exception) {
                        try { building3d.setProperties(PropertyFactory.visibility(Property.VISIBLE)) } catch (_: Exception) {}
                    }
                } else {
                    // 2D mode
                    try { building.setProperties(PropertyFactory.visibility(Property.VISIBLE)) } catch (_: Exception) {}
                    try { building3d.setProperties(PropertyFactory.visibility(Property.NONE)) } catch (_: Exception) {}
                }
            } else {
                // Fallback: iterate layers containing building
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
