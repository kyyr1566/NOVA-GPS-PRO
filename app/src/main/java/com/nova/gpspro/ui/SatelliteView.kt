package com.nova.gpspro.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.min

/**
 * Large, fixed 🛰️ in the centre. While (and only while) GPS is really connected, soft
 * transmission waves are emitted from the satellite toward the ground and fade out as they
 * travel. The animation is driven purely by [look], which HomePage sets from the real
 * GpsStatus: leaving CONNECTED stops the waves on the very next frame (no fade-out delay).
 */
class SatelliteView(ctx: Context) : View(ctx) {
    enum class Look { CONNECTED, SEARCHING, WEAK, OFF }

    var look = Look.OFF
        set(v) {
            if (field == v) return
            field = v
            if (v == Look.CONNECTED) startNanos = SystemClock.uptimeMillis()  // waves start fresh
            invalidate()                                                         // stop/start immediately
        }

    private var startNanos = 0L
    private val emoji = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = RectF()
    private val grayFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        val cx = width / 2f
        val satCy = height * 0.36f            // satellite sits a little high, waves travel below it
        val connected = look == Look.CONNECTED

        // ---- transmission waves (connected only)
        if (connected) {
            val t = (SystemClock.uptimeMillis() - startNanos) / WAVE_PERIOD_MS.toFloat()
            val originY = satCy + s * 0.17f       // just below the (larger) satellite
            val maxR = height - originY - s * 0.02f
            wave.color = C.GREEN
            for (k in 0 until WAVES) {
                val p = ((t + k.toFloat() / WAVES) % 1f)
                val r = s * 0.08f + p * (maxR - s * 0.08f)
                val fade = if (p < 0.12f) p / 0.12f else 1f - (p - 0.12f) / 0.88f   // ease in, fade out
                wave.alpha = (fade * 220).toInt().coerceIn(0, 255)
                wave.strokeWidth = s * (0.022f - 0.010f * p)
                arc.set(cx - r, originY - r, cx + r, originY + r)
                canvas.drawArc(arc, 90f - SWEEP / 2f, SWEEP, false, wave)       // downward beam
            }
        }

        // ---- soft halo behind the satellite
        glow.color = when (look) { Look.CONNECTED -> C.GREEN; Look.WEAK -> C.ORANGE_GOLD; Look.SEARCHING -> C.AMBER; Look.OFF -> C.TEXT3 }
        glow.alpha = if (connected) 34 else 22
        canvas.drawCircle(cx, satCy, s * 0.31f, glow)

        // ---- the satellite itself (fixed, never moves)
        emoji.textSize = s * 0.49f   // ≈1.6× previous on-screen size (with 200dp view)
        emoji.colorFilter = if (look == Look.OFF) grayFilter else null
        emoji.alpha = when (look) { Look.CONNECTED -> 255; Look.WEAK -> 235; Look.SEARCHING -> 190; Look.OFF -> 110 }
        val fm = emoji.fontMetrics
        canvas.drawText(SAT, cx, satCy - (fm.ascent + fm.descent) / 2f, emoji)

        if (connected && isShown) postInvalidateOnAnimation()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && look == Look.CONNECTED) invalidate()
    }

    companion object {
        private const val SAT = "\uD83D\uDEF0\uFE0F"   // 🛰️
        private const val WAVES = 3
        private const val SWEEP = 110f
        private const val WAVE_PERIOD_MS = 2100L
    }
}
