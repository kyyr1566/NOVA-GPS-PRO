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
 * North-up radar presentation driven by GPS coordinates ONLY — no compass, no sensors,
 * no motion input. The circle's centre is the phone's current GPS position; every marker
 * is placed by [RadarPlot] from real distance/bearing values recomputed on each valid GPS
 * fix, so markers simply appear where the geography puts them (no fake motion, no animation).
 * The rotating sweep is a purely visual scanner effect: it never moves a marker or invents
 * data. GPS readouts (fix / satellites / accuracy) live OUTSIDE the circle, on the page.
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

    private class PlotPoint(val target: RadarTarget, val x: Float, val y: Float, val band: RadarPlot.Band)

    /** Recomputed only when the data really changed — the sweep never re-places markers. */
    private var plotted: List<PlotPoint> = emptyList()
    private var plotDirty = true
    private var sweepShader: Shader? = null
    private val haloShaders = HashMap<RadarPlot.Band, Shader>()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Fonts.medium
    }

    // Deep navy/cyan HUD palette (inside the disc) — the page around it stays light.
    private val navy = Color.rgb(5, 48, 84)
    private val aqua = Color.rgb(47, 235, 185)
    private val muted = Color.rgb(133, 179, 203)
    private val sweepGreen = Color.rgb(72, 245, 148)
    private val nearGreen = Color.rgb(43, 233, 132)
    private val mediumYellow = Color.rgb(255, 214, 77)
    private val farRed = Color.rgb(255, 96, 84)

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
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val radius = (min(width, height) / 2f - dp(5)).coerceAtLeast(1f)
        val cx = width / 2f
        val cy = height / 2f
        ensurePlotted(cx, cy, radius)

        drawDisc(canvas, cx, cy, radius)
        drawGrid(canvas, cx, cy, radius)
        if (scanning) drawSweep(canvas, cx, cy, radius)
        drawTargets(canvas)
        drawCenter(canvas, cx, cy)
        if (!hasGpsFix && waitingLabel.isNotEmpty()) drawWaiting(canvas, cx, cy)

        if (scanning && isShown) postInvalidateOnAnimation()
    }

    /** Dark blue/cyan scanner disc with a luminous rim — as large as the view allows. */
    private fun drawDisc(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        paint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(Color.rgb(11, 78, 118), navy, Color.rgb(3, 33, 62)),
            floatArrayOf(0f, .58f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null

        strokePaint.strokeWidth = dpf(4.5f)
        strokePaint.color = Color.argb(24, 5, 198, 228)
        canvas.drawCircle(cx, cy, r + dpf(1.2f), strokePaint)
        strokePaint.strokeWidth = dpf(1.6f)
        strokePaint.color = Color.argb(78, 5, 198, 228)
        canvas.drawCircle(cx, cy, r, strokePaint)
    }

    private fun drawGrid(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        strokePaint.strokeWidth = dpf(1f)
        strokePaint.color = Color.argb(58, 81, 232, 247)
        for (fraction in floatArrayOf(.25f, .5f, .75f)) canvas.drawCircle(cx, cy, r * fraction, strokePaint)

        strokePaint.color = Color.argb(34, 87, 226, 243)
        for (i in 0 until 12) {
            val a = i * 30.0 * PI / 180.0
            canvas.drawLine(cx, cy, cx + (cos(a) * r).toFloat(), cy - (sin(a) * r).toFloat(), strokePaint)
        }
        strokePaint.color = Color.argb(80, 102, 240, 250)
        strokePaint.strokeWidth = dpf(1.4f)
        canvas.drawLine(cx - r, cy, cx + r, cy, strokePaint)
        canvas.drawLine(cx, cy - r, cx, cy + r, strokePaint)

        // Unlabelled calibration bezel — deliberately no direction names anywhere.
        strokePaint.color = Color.argb(120, 106, 239, 248)
        strokePaint.strokeWidth = dpf(1f)
        for (i in 0 until 48) {
            val a = i * 7.5 * PI / 180.0
            val longTick = i % 4 == 0
            val inner = r - dp(if (longTick) 9 else 5)
            canvas.drawLine(
                cx + (cos(a) * inner).toFloat(), cy - (sin(a) * inner).toFloat(),
                cx + (cos(a) * r).toFloat(), cy - (sin(a) * r).toFloat(), strokePaint
            )
        }
    }

    /** Purely decorative scanner sweep — green, glowing, always rotating while scanning. */
    private fun drawSweep(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val elapsed = (SystemClock.uptimeMillis() - sweepStartedAt) % SWEEP_PERIOD_MS
        val head = elapsed.toFloat() / SWEEP_PERIOD_MS * 360f

        var shader = sweepShader
        if (shader == null) {
            shader = SweepGradient(
                cx, cy,
                intArrayOf(
                    colorWithAlpha(sweepGreen, 0), colorWithAlpha(sweepGreen, 0),
                    colorWithAlpha(sweepGreen, 46), colorWithAlpha(sweepGreen, 122)
                ),
                floatArrayOf(0f, .70f, .90f, 1f)
            )
            sweepShader = shader
        }
        paint.shader = shader
        canvas.save()
        canvas.rotate(head, cx, cy)
        canvas.drawCircle(cx, cy, r, paint)
        canvas.restore()
        paint.shader = null

        strokePaint.shader = LinearGradient(
            cx, cy, cx + r, cy,
            colorWithAlpha(sweepGreen, 0), colorWithAlpha(sweepGreen, 240), Shader.TileMode.CLAMP
        )
        strokePaint.strokeWidth = dpf(2f)
        canvas.save()
        canvas.rotate(head, cx, cy)
        canvas.drawLine(cx, cy, cx + r, cy, strokePaint)
        canvas.restore()
        strokePaint.shader = null

        val hr = Math.toRadians(head.toDouble())
        paint.color = sweepGreen
        canvas.drawCircle(cx + (cos(hr) * r).toFloat(), cy + (sin(hr) * r).toFloat(), dpf(2.2f), paint)
    }

    /** Small, crisp, glowing markers: strong green near, yellow medium, red far. */
    private fun drawTargets(canvas: Canvas) {
        val coreR = dpf(2.6f)
        val rimR = dpf(3.4f)
        val haloR = dpf(7f)
        val selR = dpf(6.4f)
        for (p in plotted) {
            val color = bandColor(p.band)
            val selected = p.target.id == selectedId
            canvas.save()
            canvas.translate(p.x, p.y)
            paint.shader = haloShader(p.band, haloR)
            canvas.drawCircle(0f, 0f, haloR, paint)
            if (selected) canvas.drawCircle(0f, 0f, haloR, paint)   // double glow for the picked marker
            paint.shader = null
            paint.color = color
            canvas.drawCircle(0f, 0f, coreR, paint)
            strokePaint.strokeWidth = dpf(.9f)
            strokePaint.color = Color.argb(225, 255, 255, 255)
            canvas.drawCircle(0f, 0f, rimR, strokePaint)
            if (selected) {
                strokePaint.strokeWidth = dpf(1.4f)
                strokePaint.color = color
                canvas.drawCircle(0f, 0f, selR, strokePaint)
                strokePaint.strokeWidth = dpf(1f)
                strokePaint.color = Color.argb(130, 255, 255, 255)
                canvas.drawCircle(0f, 0f, selR + dpf(2.6f), strokePaint)
            }
            canvas.restore()
        }
    }

    /** The phone itself — the radar's fixed centre (its GPS position). */
    private fun drawCenter(canvas: Canvas, cx: Float, cy: Float) {
        val c = if (hasGpsFix) aqua else muted
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        paint.color = Color.argb(if (hasGpsFix) 48 else 26, r, g, b)
        canvas.drawCircle(cx, cy, dpf(16f), paint)
        strokePaint.strokeWidth = dpf(1.4f)
        strokePaint.color = Color.argb(205, r, g, b)
        canvas.drawCircle(cx, cy, dpf(8.5f), strokePaint)
        paint.color = c
        canvas.drawCircle(cx, cy, dpf(3.2f), paint)
        strokePaint.strokeWidth = dpf(1.1f)
        strokePaint.color = Color.argb(150, r, g, b)
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

    /** Re-derives marker positions from the real data — only when that data changed. */
    private fun ensurePlotted(cx: Float, cy: Float, radius: Float) {
        if (!plotDirty) return
        val inner = dpf(26f)                                   // keep the centre marker clear
        val outer = (radius - dp(11)).coerceAtLeast(inner)     // never past the range ring
        val planned = RadarPlot.plan(
            targets, rangeM, inner, outer,
            separation = dpf(14f), ringStep = dpf(16f)
        )
        plotted = planned.map { p ->
            val a = Math.toRadians((p.bearing - 90f).toDouble())
            PlotPoint(p.target, cx + (cos(a) * p.radius).toFloat(), cy + (sin(a) * p.radius).toFloat(), p.band)
        }
        plotDirty = false
    }

    private fun haloShader(band: RadarPlot.Band, radius: Float): Shader = haloShaders.getOrPut(band) {
        val c = bandColor(band)
        RadialGradient(
            0f, 0f, radius,
            Color.argb(135, Color.red(c), Color.green(c), Color.blue(c)),
            Color.argb(0, Color.red(c), Color.green(c), Color.blue(c)),
            Shader.TileMode.CLAMP
        )
    }

    private fun bandColor(band: RadarPlot.Band): Int = when (band) {
        RadarPlot.Band.NEAR -> nearGreen
        RadarPlot.Band.MEDIUM -> mediumYellow
        RadarPlot.Band.FAR -> farRed
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                var hit: PlotPoint? = null
                var best = Float.MAX_VALUE
                for (p in plotted) {
                    val d = distance(event.x, event.y, p.x, p.y)
                    if (d < best) { best = d; hit = p }
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

    companion object {
        private const val SWEEP_PERIOD_MS = 3_800L
    }
}
