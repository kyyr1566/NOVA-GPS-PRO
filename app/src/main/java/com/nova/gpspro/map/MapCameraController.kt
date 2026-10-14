package com.nova.gpspro.map

import android.content.Context
import android.content.SharedPreferences
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

/**
 * Controls camera: save/restore, north, follow, tilt.
 */
class MapCameraController {

    private var savedPosition: CameraPosition? = null
    private var prefs: SharedPreferences? = null

    fun attach(map: MapLibreMap) {
        // No-op for now, but could listen to camera changes
    }

    fun init(context: Context) {
        prefs = context.getSharedPreferences("nova_map_camera", Context.MODE_PRIVATE)
        // Restore
        val lat = prefs?.getFloat("lat", Float.NaN) ?: Float.NaN
        val lon = prefs?.getFloat("lon", Float.NaN) ?: Float.NaN
        val zoom = prefs?.getFloat("zoom", Float.NaN) ?: Float.NaN
        val tilt = prefs?.getFloat("tilt", 0f) ?: 0f
        val bearing = prefs?.getFloat("bearing", 0f) ?: 0f
        if (!lat.isNaN() && !lon.isNaN() && !zoom.isNaN()) {
            savedPosition = CameraPosition.Builder()
                .target(LatLng(lat.toDouble(), lon.toDouble()))
                .zoom(zoom.toDouble())
                .tilt(tilt.toDouble())
                .bearing(bearing.toDouble())
                .build()
        }
    }

    fun saveCamera(pos: CameraPosition) {
        savedPosition = pos
        prefs?.edit()?.apply {
            putFloat("lat", pos.target.latitude.toFloat())
            putFloat("lon", pos.target.longitude.toFloat())
            putFloat("zoom", pos.zoom.toFloat())
            putFloat("tilt", pos.tilt.toFloat())
            putFloat("bearing", pos.bearing.toFloat())
            apply()
        }
    }

    fun restoreCamera(): CameraPosition? = savedPosition

    fun bearingNorth(map: MapLibreMap) {
        val current = map.cameraPosition
        val north = CameraPosition.Builder(current).bearing(0.0).build()
        map.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(north), 400)
    }
}
