package com.nova.gpspro.map

import android.content.Context
import android.content.SharedPreferences
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/**
 * Manages map styles: dark/gold (default), streets, terrain, satellite, 3D buildings.
 * Keeps camera/center/zoom, markers and route when switching.
 */
class MapStyleManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("nova_map_style", Context.MODE_PRIVATE)

    var is3DEnabled: Boolean
        get() = prefs.getBoolean("is3D", false)
        set(v) { prefs.edit().putBoolean("is3D", v).apply() }

    private var currentLayer: String
        get() = prefs.getString("layer", "dark") ?: "dark"
        set(v) { prefs.edit().putString("layer", v).apply() }

    fun setLayer(key: String) {
        currentLayer = key
        if (key == "3d") is3DEnabled = true
    }

    fun currentStyleUrl(): String {
        // Use demo tiles for online; offline will use PMTiles via OfflineMapManager
        val offlineUrl = OfflineMapManager.getOfflineStyleUrl(context, currentLayer)
        if (offlineUrl != null) return offlineUrl

        return when (currentLayer) {
            "dark" -> "asset://mapstyles/dark.json"
            "streets" -> "asset://mapstyles/streets.json"
            "terrain" -> "asset://mapstyles/terrain.json"
            "satellite" -> "asset://mapstyles/satellite.json"
            "3d" -> "asset://mapstyles/dark.json" // 3D is dark + buildings
            else -> "asset://mapstyles/dark.json"
        }
    }

    fun applyStyle(style: Style, map: MapLibreMap) {
        // Apply 3D buildings if enabled and data available
        if (is3DEnabled) {
            try {
                // Find building layer and set extrusion
                // This is a placeholder for real 3D buildings — MapLibre supports fill-extrusion
                // We attempt to set paint properties if layer exists
                style.layers.forEach { layer ->
                    if (layer.id.contains("building", ignoreCase = true)) {
                        // Enable 3D extrusion via runtime styling (pseudo)
                        // In real implementation: (layer as FillExtrusionLayer).setProperties(...)
                    }
                }
            } catch (_: Exception) {}
        }
        // Apply terrain hillshade if layer is terrain
        if (currentLayer == "terrain") {
            // Hillshade is part of style JSON; no extra code needed
        }
        // Satellite is raster; already in style
    }
}
