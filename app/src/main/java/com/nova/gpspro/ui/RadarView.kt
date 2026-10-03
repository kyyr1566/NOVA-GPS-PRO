package com.nova.gpspro.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * GPS-only radar presentation — no compass, sensors, motion input, randomness, or marker
 * animation. The circle keeps its existing bounds and centre; each destination's polar
 * position is computed from its real GPS distance and bearing.
 */
class RadarView(ctx: Context) : View(ctx) {

    var onTargetClick: ((RadarTarget) -> Unit)? = null
    var onBackgroundClick: (() -> Unit)? = null

    private var rangeM = 100.0
    private var scanning = true
    private var sweepStartedAt = SystemClock.uptimeMillis()
    private var targets: List<RadarTarget> = emptyList()
    private var selectedId: String? = null
    private var hasGpsFix = false
    private var waitingLabel = ""

    private class PlotPoint(val target: RadarTarget, val x: Float, val y: Float)

    /** Recomputed only when range, dimensions, or real GPS-derived targets change. */
    private var plotted: List<PlotPoint> = emptyList()
    private var plotDirty = true
    private var sweepShader: Shader? = null
    private var discShader: Shader? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Fonts.medium
    }

    // Fixed-color artwork palette inside the dark radar disc.
    private val navy = Color.rgb(4, 20, 36)
    private val deepBlue = Color.rgb(5, 43, 67)
    private val aqua = Color.rgb(80, 236, 246)
    private val muted = Color.rgb(133, 179, 203)
    private val sweepGreen = Color.rgb(93, 255, 184)
    private val targetRed = Color.rgb(255, 45, 64)
    private val targetHalo = Color.rgb(255, 56, 70)
    private val targetHaloShader: Shader by lazy {
        val r = dpf(9f)
        RadialGradient(
            0f, 0f, r,
            Color.argb(175, Color.red(targetHalo), Color.green(targetHalo), Color.blue(targetHalo)),
            Color.argb(0, Color.red(targetHalo), Color.green(targetHalo), Color.blue(targetHalo)),
            Shader.TileMode.CLAMP
        )
    }

    init {
        isClickable = true
        contentDescription = "Radar"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setRange(meters: Double) {
        if (rangeM == meters) return
        rangeM = meters
        plotDirty = true
        invalidate()
    }

    /** This toggle controls the decorative sweep only; live GPS targets keep updating. */
    fun setScanning(enabled: Boolean) {
        if (scanning == enabled) return
        scanning = enabled
        if (enabled) sweepStartedAt = SystemClock.uptimeMillis()
        invalidate()
    }

    fun setTargets(value: List<RadarTarget>) {
        targets = value
        plotDirty = true
        invalidate()
    }

    fun setSelected(id: String?) {
        if (selectedId == id) return
        selectedId = id
        invalidate()
    }

    fun setFixState(hasFix: Boolean, waitingText: String) {
        val changed = hasGpsFix != hasFix || waitingLabel != waitingText
        hasGpsFix = hasFix
        waitingLabel = waitingText
        if (changed) invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        plotDirty = true
        sweepShader = null
        discShader = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        // Do not change this radius or centre: they are the current radar's exact geometry.
        val radius = (min(width, height) / 2f - dp(5)).coerceAtLeast(1f)
        val cx = width / 2f
        val cy = height / 2f
        ensurePlotted(cx, cy, radius)

        drawDisc(canvas, cx, cy, radius)
        drawGrid(canvas, cx, cy, radius)
        if (scanning) drawSweep(canvas, cx, cy, radius)
        drawCenter(canvas, cx, cy)
        drawTargets(canvas)
        if (!hasGpsFix && waitingLabel.isNotEmpty()) drawWaiting(canvas, cx, cy)

        if (scanning && isShown) postInvalidateOnAnimation()
    }

    /** Layered navy fill and a restrained luminous rim contained by the existing disc. */
    private fun drawDisc(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        var fill = discShader
        if (fill == null) {
            fill = RadialGradient(
                cx, cy, r,
                intArrayOf(Color.rgb(10, 64, 86), deepBlue, navy),
                floatArrayOf(0f, .57f, 1f),
                Shader.TileMode.CLAMP
            )
            discShader = fill
        }
        paint.shader = fill
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null

        // Preserve the previous outer glow footprint exactly; added detail stays inside it.
        strokePaint.strokeWidth = dpf(4.5f)
        strokePaint.color = Color.argb(24, 5, 198, 228)
        canvas.drawCircle(cx, cy, r + dpf(1.2f), strokePaint)
        strokePaint.strokeWidth = dpf(1.6f)
        strokePaint.color = Color.argb(88, 59, 222, 237)
        canvas.drawCircle(cx, cy, r, strokePaint)
        strokePaint.strokeWidth = dpf(.7f)
        strokePaint.color = Color.argb(60, 162, 249, 250)
        canvas.drawCircle(cx, cy, r - dpf(3.5f), strokePaint)
    }

    /** Fine concentric rings, hairline radial guides and unlabelled bezel graduations. */
    private fun drawGrid(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        strokePaint.strokeWidth = dpf(.7f)
        for ((index, fraction) in floatArrayOf(.14f, .26f, .38f, .50f, .62f, .74f, .86f, .96f).withIndex()) {
            val alpha = when {
                index == 7 -> 100
                index % 2 == 1 -> 57
                else -> 32
            }
            strokePaint.color = Color.argb(alpha, 79, 223, 236)
            canvas.drawCircle(cx, cy, r * fraction, strokePaint)
        }

        for (i in 0 until 24) {
            val a = i * 15.0 * PI / 180.0
            strokePaint.color = if (i % 3 == 0) Color.argb(48, 82, 223, 235) else Color.argb(23, 82, 223, 235)
            strokePaint.strokeWidth = if (i % 3 == 0) dpf(.8f) else dpf(.55f)
            canvas.drawLine(
                cx, cy,
                cx + (cos(a) * (r - dp(3))).toFloat(),
                cy - (sin(a) * (r - dp(3))).toFloat(),
                strokePaint
            )
        }

        // Quiet central crosshair, with no direction labels, compass, or range numbers.
        strokePaint.color = Color.argb(73, 105, 236, 243)
        strokePaint.strokeWidth = dpf(.75f)
        canvas.drawLine(cx - r * .88f, cy, cx + r * .88f, cy, strokePaint)
        canvas.drawLine(cx, cy - r * .88f, cx, cy + r * .88f, strokePaint)

        strokePaint.color = Color.argb(142, 112, 239, 245)
        strokePaint.strokeWidth = dpf(.85f)
        for (i in 0 until 72) {
            val a = i * 5.0 * PI / 180.0
            val major = i % 6 == 0
            val mediumTick = i % 3 == 0
            val length = dp(when { major -> 8; mediumTick -> 5; else -> 3 })
            val inner = r - length
            canvas.drawLine(
                cx + (cos(a) * inner).toFloat(), cy - (sin(a) * inner).toFloat(),
                cx + (cos(a) * (r - dp(1))).toFloat(), cy - (sin(a) * (r - dp(1))).toFloat(),
                strokePaint
            )
        }
    }

    /** Thin, bright, purely decorative sweep. It never moves or creates a destination. */
    private fun drawSweep(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val elapsed = (SystemClock.uptimeMillis() - sweepStartedAt) % SWEEP_PERIOD_MS
        val head = elapsed.toFloat() / SWEEP_PERIOD_MS * 360f

        var shader = sweepShader
        if (shader == null) {
            shader = SweepGradient(
                cx, cy,
                intArrayOf(
                    colorWithAlpha(sweepGreen, 0),
                    colorWithAlpha(sweepGreen, 0),
                    colorWithAlpha(sweepGreen, 10),
                    colorWithAlpha(sweepGreen, 24),
                    colorWithAlpha(sweepGreen, 68),
                    colorWithAlpha(sweepGreen, 178),
                    colorWithAlpha(sweepGreen, 225)
                ),
                floatArrayOf(0f, .936f, .973f, .986f, .994f, .998f, 1f)
            )
            sweepShader = shader
        }
        paint.shader = shader
        canvas.save()
        canvas.rotate(head, cx, cy)
        canvas.drawCircle(cx, cy, r - dpf(1.5f), paint)
        canvas.restore()
        paint.shader = null

        val endX = cx + r * cos(Math.toRadians(head.toDouble())).toFloat()
        val endY = cy + r * sin(Math.toRadians(head.toDouble())).toFloat()
        canvas.save()
        canvas.rotate(head, cx, cy)
        strokePaint.shader = LinearGradient(
            cx, cy, cx + r - dpf(2f), cy,
            colorWithAlpha(sweepGreen, 22), colorWithAlpha(sweepGreen, 220), Shader.TileMode.CLAMP
        )
        strokePaint.strokeWidth = dpf(3f)
        canvas.drawLine(cx, cy, cx + r - dpf(2f), cy, strokePaint)
        strokePaint.shader = null
        strokePaint.color = Color.argb(235, Color.red(sweepGreen), Color.green(sweepGreen), Color.blue(sweepGreen))
        strokePaint.strokeWidth = dpf(1f)
        canvas.drawLine(cx, cy, cx + r - dpf(2f), cy, strokePaint)
        canvas.restore()

        paint.color = sweepGreen
        canvas.drawCircle(endX, endY, dpf(1.7f), paint)
    }

    /** All saved destinations are the same luminous red, independent of distance band. */
    private fun drawTargets(canvas: Canvas) {
        val coreR = dpf(2.8f)
        val rimR = dpf(4.1f)
        val haloR = dpf(9f)
        val selectedR = dpf(6.8f)
        for (point in plotted) {
            val selected = point.target.id == selectedId
            canvas.save()
            canvas.translate(point.x, point.y)

            paint.shader = targetHaloShader
            canvas.drawCircle(0f, 0f, haloR, paint)
            paint.shader = null

            paint.color = targetRed
            canvas.drawCircle(0f, 0f, coreR, paint)
            strokePaint.color = Color.argb(245, 255, 190, 195)
            strokePaint.strokeWidth = dpf(.8f)
            canvas.drawCircle(0f, 0f, rimR, strokePaint)
            paint.color = Color.WHITE
            canvas.drawCircle(0f, 0f, dpf(.75f), paint)

            if (selected) {
                strokePaint.color = targetRed
                strokePaint.strokeWidth = dpf(1.5f)
                canvas.drawCircle(0f, 0f, selectedR, strokePaint)
                strokePaint.color = Color.argb(150, 255, 222, 224)
                strokePaint.strokeWidth = dpf(.8f)
                canvas.drawCircle(0f, 0f, selectedR + dpf(2.4f), strokePaint)
            }
            canvas.restore()
        }
    }

    /** The receiver's GPS position at the exact centre of the radar. */
    private fun drawCenter(canvas: Canvas, cx: Float, cy: Float) {
        val c = if (hasGpsFix) aqua else muted
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        paint.color = Color.argb(if (hasGpsFix) 43 else 24, r, g, b)
        canvas.drawCircle(cx, cy, dpf(16f), paint)
        strokePaint.strokeWidth = dpf(1.2f)
        strokePaint.color = Color.argb(210, r, g, b)
        canvas.drawCircle(cx, cy, dpf(8.5f), strokePaint)
        paint.color = c
        canvas.drawCircle(cx, cy, dpf(3.1f), paint)
        strokePaint.strokeWidth = dpf(1f)
        strokePaint.color = Color.argb(144, r, g, b)
        canvas.drawLine(cx - dpf(14f), cy, cx - dpf(6.5f), cy, strokePaint)
        canvas.drawLine(cx + dpf(6.5f), cy, cx + dpf(14f), cy, strokePaint)
        canvas.drawLine(cx, cy - dpf(14f), cx, cy - dpf(6.5f), strokePaint)
        canvas.drawLine(cx, cy + dpf(6.5f), cx, cy + dpf(14f), strokePaint)
    }

    private fun drawWaiting(canvas: Canvas, cx: Float, cy: Float) {
        textPaint.textSize = dp(10).toFloat()
        textPaint.color = Color.argb(215, 214, 238, 247)
        canvas.drawText(waitingLabel, cx, cy + dp(34).toFloat(), textPaint)
    }

    /** Exact range-relative distance and true GPS bearing; no overlap nudging. */
    private fun ensurePlotted(cx: Float, cy: Float, radius: Float) {
        if (!plotDirty) return
        val outer = (radius - dp(11)).coerceAtLeast(0f)
        plotted = RadarPlot.plan(targets, rangeM, outer).map { plotted ->
            val bearing = Math.toRadians(plotted.bearing.toDouble())
            PlotPoint(
                plotted.target,
                cx + (sin(bearing) * plotted.radius).toFloat(),
                cy - (cos(bearing) * plotted.radius).toFloat()
            )
        }
        plotDirty = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                var hit: PlotPoint? = null
                var best = Float.MAX_VALUE
                for (point in plotted) {
                    val d = distance(event.x, event.y, point.x, point.y)
                    if (d < best) { best = d; hit = point }
                }
                performClick()
                if (hit != null && best <= dp(19)) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onTargetClick?.invoke(hit.target)
                } else {
                    onBackgroundClick?.invoke()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        Math.hypot((x1 - x2).toDouble(), (y1 - y2).toDouble()).toFloat()

    private fun colorWithAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(value: Int): Int = context.dp(value)
    private fun dpf(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private const val SWEEP_PERIOD_MS = 3_800L
    }
}
