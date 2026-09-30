package com.nova.gpspro.navigation

import com.nova.gpspro.location.GpsState
import kotlin.math.abs

/**
 * GPS bearing → target → normalize → circular smoothing → ArrowView.
 *
 *   arrowAngle = norm180(destinationBearing − currentGpsBearing)
 *
 * All smoothing is done on the SHORTEST signed angular difference in [-180°, 180°],
 * so 359° → 0° is a 1° step and 0° → 359° is −1°, never a full turn.
 * Smoothing is light: a reliable bearing moves the target 80–100 % toward the new
 * value on the very first fix; the final visual easing is done by ArrowView (~90 ms).
 */
class ArrowEngine {

    enum class Mode {
        /** fresh GPS bearing */
        TRACKING,
        /** bearing briefly missing – last reliable bearing held (≤ 5 s) */
        HOLD,
        /** no GPS bearing at all – arrow is NOT rotated with invented data */
        NO_BEARING
    }

    var angle = 0f; private set
    var mode = Mode.NO_BEARING; private set
    private var initialized = false
    private var lastNanos = 0L
    private var lastOutlierTarget: Float? = null

    fun reset() { angle = 0f; mode = Mode.NO_BEARING; initialized = false; lastNanos = 0L; lastOutlierTarget = null }

    fun update(destBearing: Float, gps: GpsState): Float {
        mode = when {
            !gps.hasBearing -> Mode.NO_BEARING
            gps.bearingLive -> Mode.TRACKING
            else -> Mode.HOLD
        }
        if (mode == Mode.NO_BEARING) { lastNanos = gps.elapsedRealtimeNanos; return angle }

        val target = GeoMath.norm180(destBearing - gps.bearing)
        val dt = if (lastNanos == 0L) REF_DT else ((gps.elapsedRealtimeNanos - lastNanos) / 1e9).coerceIn(0.05, 3.0)
        lastNanos = gps.elapsedRealtimeNanos

        if (!initialized) { angle = target; initialized = true; return angle }

        val delta = GeoMath.norm180(target - angle)
        // anti-shimmer: ignore sub-degree noise from weak bearings only
        if (abs(delta) < 0.8f && gps.bearingQuality < 0.6f) return angle

        var alpha = when (mode) {
            Mode.TRACKING -> (0.9f + 0.1f * gps.bearingQuality).coerceIn(0.9f, 1f)
            else -> 0.95f  // HOLD: heading fixed, destination bearing changes slowly – follow it
        }
        // Outlier guard (never a freeze): ONE low-quality reading that contradicts the current
        // direction by > 90° moves the arrow gently. If the next reading confirms it (real
        // turn), the arrow follows at full speed.
        val contradicts = mode == Mode.TRACKING && abs(delta) > 90f && gps.bearingQuality < 0.6f
        val confirmed = contradicts && lastOutlierTarget?.let { abs(GeoMath.norm180(target - it)) < 45f } == true
        lastOutlierTarget = if (contradicts) target else null
        if (contradicts && !confirmed) alpha = 0.25f
        val a = (1.0 - Math.pow(1.0 - alpha, dt / REF_DT)).toFloat().coerceIn(0.1f, 1f)
        angle = GeoMath.norm180(angle + delta * a)
        return angle
    }

    companion object { const val REF_DT = 0.5 }   // GPS interval 500 ms
}
