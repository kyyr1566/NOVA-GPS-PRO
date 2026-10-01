package com.nova.gpspro.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import com.nova.gpspro.navigation.GeoMath
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * Large Gold / Orange-Gold navigation arrow drawn on Canvas with a transparent background.
 * Only the arrow rotates around its own centre. Rotation always takes the shortest path
 * (the displayed angle is kept unwrapped so 359° → 1° is a +2° move, never 358°).
 */
class ArrowView(ctx: Context) : View(ctx) {

    private var displayed = 0f     // unwrapped
    private var target = 0f        // unwrapped
    private var lastFrame = 0L
    private var hasTarget = false
    var active = true
        set(v) { if (field != v) { field = v; invalidate() } }

    private val arrowPath = Path()
    private val leftHalf = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }

    /** angle in degrees (any range). */
    fun setAngle(angle: Float, immediate: Boolean = false) {
        val cur = GeoMath.norm180(displayed)
        val delta = GeoMath.norm180(angle - cur)
        target = displayed + delta
        if (!hasTarget || immediate) { displayed = target; hasTarget = true }
        lastFrame = 0L
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val s = min(w, h).toFloat()
        val cx = w / 2f; val cy = h / 2f
        // Pivot is the visual centre. Every vertex is within 0.95·r of the pivot, and
        // r = 0.49·s → the arrow stays inside its own view at ANY rotation (no overlap).
        val r = s * 0.49f
        val tipY = cy - r * 0.95f; val wingY = cy + r * 0.72f; val wingX = r * 0.62f; val notchY = cy + r * 0.34f
        arrowPath.reset()
        arrowPath.moveTo(cx, tipY)
        arrowPath.lineTo(cx + wingX, wingY)
        arrowPath.lineTo(cx, notchY)
        arrowPath.lineTo(cx - wingX, wingY)
        arrowPath.close()
        leftHalf.reset()
        leftHalf.moveTo(cx, tipY); leftHalf.lineTo(cx, notchY); leftHalf.lineTo(cx - wingX, wingY); leftHalf.close()
        fill.shader = LinearGradient(cx, tipY, cx, wingY, C.ARROW_A, C.ARROW_B, Shader.TileMode.CLAMP)
        shade.shader = LinearGradient(cx, tipY, cx, wingY, C.ARROW_SHADE_A, C.ARROW_SHADE_B, Shader.TileMode.CLAMP)
        edge.strokeWidth = s * 0.007f; edge.color = 0x55FFFFFF
        fill.setShadowLayer(s * 0.012f, 0f, s * 0.006f, 0x401A44B0)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val alphaMul = if (active) 255 else 90

        // animate towards target (time based, stops when settled)
        val now = SystemClock.uptimeMillis()
        if (lastFrame != 0L) {
            val dt = (now - lastFrame).coerceIn(0, 100) / 1000f
            val k = 1f - exp(-dt / 0.05f)   // ~150 ms to settle: fast, still smooth
            displayed += (target - displayed) * k
        }
        lastFrame = now
        val settled = abs(target - displayed) < 0.05f
        if (settled) { displayed = target; lastFrame = 0L }

        canvas.save()
        canvas.rotate(displayed, cx, cy)
        fill.alpha = alphaMul; shade.alpha = alphaMul
        canvas.drawPath(arrowPath, fill)
        canvas.drawPath(leftHalf, shade)
        canvas.drawPath(arrowPath, edge)
        canvas.restore()

        // keep normalized to avoid unbounded growth
        if (settled && abs(displayed) > 3600f) { displayed = GeoMath.norm180(displayed); target = displayed }
        if (!settled) postInvalidateOnAnimation()
    }
}
