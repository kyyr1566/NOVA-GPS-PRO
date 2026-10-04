package com.nova.gpspro.navigation

import com.nova.gpspro.location.GpsState
import kotlin.math.abs

/**
 * GPS bearing → target → normalize → circular smoothing → ArrowView.
 *
 * Arrow GPS Lite lesson (MyArrowView + FragmentNavigation):
 *  • Arrow = norm180(destinationBearing − gpsBearing) on shortest arc (359→0 = +2°)
 *  • Smoothing is light exponential with dt compensation (Arrow tau ~80-120ms, not 500ms),
 *    so real turn is followed in 1-2 fixes at high quality, 3-5 at medium (Realme 11).
 *  • Uses bearingQuality (from BearingEngine) to adapt alpha: high quality → 0.92-1.0,
 *    medium → ~0.9, outliers (>90° low quality) → 0.25 until confirmed.
 *  • Anti-shimmer: sub-degree noise with quality <0.6 is ignored (Arrow suppresses jitter while walking slowly).
 *  • When stopped, bearingLive false → HOLD, then NO_BEARING after 10s → arrow stops rotating (no invented compass).
 *
 *   arrowAngle = norm180(destinationBearing − currentGpsBearing)
 *
 * All smoothing is done on the SHORTEST signed angular difference in [-180°, 180°],
 * so 359° → 0° is a 1° step and 0° → 359° is −1°, never a full turn.
 */
class ArrowEngine {

    enum class Mode {
        /** fresh GPS bearing */
        TRACKING,
        /** bearing briefly missing – last reliable bearing held (≤ 5 s) — Arrow holds briefly then NO_BEARING */
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
        // Arrow anti-shimmer: ignore sub-degree noise from weak bearings only (prevents vibration on Realme 11)
        if (abs(delta) < 0.8f && gps.bearingQuality < 0.6f) return angle

        // Arrow alpha: high quality → near 1.0 (instant), medium → 0.90-0.94, hold → 0.95
        var alpha = when (mode) {
            Mode.TRACKING -> (0.92f + 0.08f * gps.bearingQuality).coerceIn(0.92f, 1f)
            else -> 0.95f  // HOLD: heading fixed, destination bearing changes slowly – follow it
        }
        // Outlier guard (Arrow: one low-quality 90°+ contradicting jump is damped to 0.25 unless next fix confirms turn)
        val contradicts = mode == Mode.TRACKING && abs(delta) > 90f && gps.bearingQuality < 0.6f
        val confirmed = contradicts && lastOutlierTarget?.let { abs(GeoMath.norm180(target - it)) < 45f } == true
        lastOutlierTarget = if (contradicts) target else null
        if (contradicts && !confirmed) alpha = 0.25f
        // dt-compensated exponential: same response at 1Hz or 2Hz (Arrow var interval)
        val a = (1.0 - Math.pow(1.0 - alpha, dt / REF_DT)).toFloat().coerceIn(0.1f, 1f)
        angle = GeoMath.norm180(angle + delta * a)
        return angle
    }

    companion object { const val REF_DT = 0.5 }   // GPS interval 500 ms (Arrow ~500ms)
}
