package com.nova.gpspro.map

import android.content.Context
import android.location.Location
import com.nova.gpspro.location.LocationEngine
import com.nova.gpspro.NovaApp
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Tracks user location via existing LocationEngine, provides last location and fresh fix.
 */
class LocationTrackingController(private val context: Context) {

    private val app: NovaApp
        get() = context.applicationContext as NovaApp

    fun lastLocation(): Location? {
        val state = app.gps.lastState()
        return state.location?.takeIf { state.isUsable }
    }

    suspend fun awaitFreshLocation(timeoutMs: Long = 8000L): Location? {
        val last = lastLocation()
        if (last != null && System.currentTimeMillis() - (last.time) < 5000) return last
        return suspendCancellableCoroutine { cont ->
            var resumed = false
            val listener = object : LocationEngine.Listener {
                override fun onLocation(state: com.nova.gpspro.location.GpsState) {
                    if (state.isUsable && state.location != null && !resumed) {
                        resumed = true
                        app.gps.removeListener(this)
                        if (cont.isActive) cont.resume(state.location)
                    }
                }
            }
            app.gps.addListener(listener)
            cont.invokeOnCancellation { app.gps.removeListener(listener) }
            // Timeout
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!resumed && cont.isActive) {
                    app.gps.removeListener(listener)
                    cont.resume(lastLocation())
                }
            }, timeoutMs)
        }
    }
}
