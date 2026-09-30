package com.nova.gpspro.location

import android.location.Location
import kotlin.math.max

/**
 * Speed in m/s kept as Double. Prefers Location.getSpeed() when valid (Doppler based,
 * very accurate), falls back to displacement/time between accepted fixes.
 */
class SpeedEngine {
    var speedMps: Double = 0.0
        private set
    private var prev: Location? = null

    fun reset() { speedMps = 0.0; prev = null }

    fun update(loc: Location, reanchored: Boolean): Double {
        var raw: Double? = null
        if (loc.hasSpeed()) {
            val ok = !loc.hasSpeedAccuracy() ||
                loc.speedAccuracyMetersPerSecond <= max(3f, loc.speed * 0.6f)
            if (ok) raw = loc.speed.toDouble()
        }
        val p = prev
        if (raw == null && p != null && !reanchored) {
            val dt = (loc.elapsedRealtimeNanos - p.elapsedRealtimeNanos) / 1e9
            if (dt >= 0.5) {
                val d = p.distanceTo(loc).toDouble()
                val noise = (loc.accuracy + p.accuracy) * 0.5
                raw = if (d > noise) d / dt else 0.0
            }
        }
        prev = loc
        if (raw == null) return speedMps
        if (raw < STATIONARY_MPS) raw = 0.0
        // Light asymmetric smoothing: quick to rise, slightly calmer to fall.
        val alpha = if (raw > speedMps) 0.7 else 0.55
        speedMps += alpha * (raw - speedMps)
        if (speedMps < 0.05) speedMps = 0.0
        return speedMps
    }

    fun zero() { speedMps = 0.0 }

    companion object { const val STATIONARY_MPS = 0.35 }
}
