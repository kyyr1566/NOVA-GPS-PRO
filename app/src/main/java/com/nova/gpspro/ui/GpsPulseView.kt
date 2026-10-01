package com.nova.gpspro.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import com.nova.gpspro.R
import kotlin.math.min

/**
 * Very small «location pulse» indicator placed on the left of the satellite emblem.
 *
 * The pulse is driven ONLY by the real GPS state: while a usable fix is being delivered
 * ([GpsSignal.liveFix]) two soft rings expand out of the dot and fade; the moment GPS is lost
 * the animation stops on the next state change and only a static dot remains — nothing is
 * animated between state changes, so it can never keep beating without a real fix.
 */
class GpsPulseView(ctx: Context) : View(ctx) {

    /** true only while a real, usable fix exists. */
    var live: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            started = SystemClock.uptimeMillis()
            invalidate()
        }

    /** Dot colour: green locked, amber coarse, grey stopped. */
    var tint: Int = C.TEXT3
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private var started = SystemClock.uptimeMillis()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.gps_pulse)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        if (r <= 0f) return
        val dotR = (r * 0.30f).coerceAtLeast(dpf(1.6f))

        if (live) {
            val t = (SystemClock.uptimeMillis() - started) % PERIOD_MS / PERIOD_MS.toFloat()
            ring.color = tint
            for (k in 0 until RINGS) {
                val p = (t + k.toFloat() / RINGS) % 1f
                val rr = dotR * 1.25f + p * (r - dotR * 1.25f)
                ring.alpha = ((1f - p) * 190f).toInt().coerceIn(0, 255)
                ring.strokeWidth = (r * 0.16f).coerceAtLeast(dpf(0.8f))
                canvas.drawCircle(cx, cy, rr, ring)
            }
            paint.color = tint
            paint.alpha = 255
            canvas.drawCircle(cx, cy, dotR, paint)
            if (isShown) postInvalidateOnAnimation()
        } else {
            // stopped: a single static dot, clearly dimmer than a beating one
            paint.color = tint
            paint.alpha = 130
            canvas.drawCircle(cx, cy, dotR * 0.9f, paint)
            paint.alpha = 255
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && live) invalidate()
    }

    companion object {
        private const val RINGS = 2
        private const val PERIOD_MS = 1500L
    }
}
