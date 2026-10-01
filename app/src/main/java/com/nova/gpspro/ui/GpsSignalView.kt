package com.nova.gpspro.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.nova.gpspro.R

/**
 * Tiny SIM-style GPS signal indicator (📶): four bars of increasing height.
 *
 * Lit bars use the level colour of [GpsSignal] — 🔴 weak, 🟡 medium, 🔵 good, 🟢 excellent —
 * and unlit bars keep a dim track colour, exactly like the network indicator of a phone.
 * The level comes only from real GPS/GNSS data (see [GpsSignal]); the view never runs on a
 * timer and never animates a value of its own.
 */
class GpsSignalView(ctx: Context) : View(ctx) {

    var level: GpsSignal.Level = GpsSignal.Level.NONE
        set(value) {
            if (field == value) return
            field = value
            updateDescription()
            invalidate()
        }

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    /** Relative bar heights, left → right. */
    private val heights = floatArrayOf(0.42f, 0.62f, 0.82f, 1f)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updateDescription()
    }

    private fun updateDescription() {
        contentDescription = context.getString(
            R.string.gps_signal_fmt,
            context.getString(level.labelRes)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val gap = dpf(1.6f)
        val barW = ((w - gap * (heights.size - 1)) / heights.size).coerceAtLeast(dpf(1f))
        val radius = barW * 0.35f
        val lit = GpsSignal.barColor(level)
        for (i in heights.indices) {
            val left = i * (barW + gap)
            rect.set(left, h - h * heights[i], left + barW, h)
            val isLit = i < level.bars
            bar.color = if (isLit) lit else C.SIGNAL_TRACK
            bar.alpha = if (isLit) 255 else 115
            canvas.drawRoundRect(rect, radius, radius, bar)
        }
        bar.alpha = 255
    }
}
