package com.nova.gpspro.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * RadarView — visual only. Logic preserved:
 *  - markers derived from REAL distance/bearing via RadarPlot (range filter already in RadarPage)
 *  - compass rim (degrees + cardinal Arabic) rotates with REAL device azimuth (sensor)
 *  - sweep + central arrow rotate continuously (visual only, no effect on marker positions)
 *  - center = phone GPS position; azimuth only rotates the compass rose & marker angular position
 *    around center to keep north true. Markers do not drift randomly.
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

    // Real compass azimuth: 0 = north up, 90 = east up (device heading)
    private var azimuthDeg = 0f

    private class PlotPoint(val target: RadarTarget, val x: Float, val y: Float, val band: RadarPlot.Band)

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
    private val cardinalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Fonts.medium
    }
    private val degreePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Fonts.regular
    }

    // Deep navy/cyan HUD palette (inside the disc)
    private val navy = Color.rgb(5, 48, 84)
    private val aqua = Color.rgb(47, 235, 185)
    private val muted = Color.rgb(133, 179, 203)
    private val sweepGreen = Color.rgb(72, 245, 148)
    private val brightCyan = Color.rgb(6, 213, 255)
    private val nearGreen = Color.rgb(43, 233, 132)
    private val mediumYellow = Color.rgb(255, 214, 77)
    private val farRed = Color.rgb(255, 96, 84)
    private val cardinalGreen = Color.rgb(110, 255, 195)
    private val degreeCyan = Color.rgb(180, 240, 255)

    // For world-map faint texture (optional, very low alpha)
    private val landPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(18, 90, 200, 210)
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

    /** Called from RadarPage sensor listener with true heading 0..360 */
    fun setAzimuth(degrees: Float) {
        var v = degrees % 360f
        if (v < 0) v += 360f
        if (kotlin.math.abs(v - azimuthDeg) < 0.4f) return
        azimuthDeg = v
        plotDirty = true
        // Sweep shader does not depend on azimuth, keep
        if (isShown) postInvalidateOnAnimation() else invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        plotDirty = true
        sweepShader = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val radius = (min(width, height) / 2f - dp(22)).coerceAtLeast(1f)
        // leave room for outer degree labels
        val cx = width / 2f
        val cy = height / 2f
        ensurePlotted(cx, cy, radius)

        // sweep head for this frame (shared with center arrow)
        val headDeg = if (scanning) {
            val elapsed = (SystemClock.uptimeMillis() - sweepStartedAt) % SWEEP_PERIOD_MS
            elapsed.toFloat() / SWEEP_PERIOD_MS * 360f
        } else 0f

        drawDisc(canvas, cx, cy, radius)
        drawFaintLand(canvas, cx, cy, radius)
        drawGrid(canvas, cx, cy, radius)
        drawCompassLabels(canvas, cx, cy, radius)
        if (scanning) drawSweep(canvas, cx, cy, radius, headDeg)
        drawTargets(canvas) // after sweep so markers stay on top and not tinted
        drawCenter(canvas, cx, cy, headDeg)
        if (!hasGpsFix && waitingLabel.isNotEmpty()) drawWaiting(canvas, cx, cy, radius)

        if (scanning && isShown) postInvalidateOnAnimation()
        // also keep invalidating slowly for compass smoothness even when not scanning (sensor drives it)
        else if (isShown && azimuthDeg != 0f) { /* sensor will invalidate */ }
    }

    /** Dark blue/cyan scanner disc with luminous rim — as large as view allows. */
    private fun drawDisc(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // Outer glow
        paint.shader = RadialGradient(
            cx, cy, r + dpf(10f),
            intArrayOf(Color.argb(0, 6, 213, 255), Color.argb(28, 6, 213, 255), Color.argb(0, 6, 213, 255)),
            floatArrayOf(0.78f, 0.92f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r + dpf(6f), paint)
        paint.shader = null

        // Main disc gradient
        paint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(Color.rgb(11, 78, 118), navy, Color.rgb(3, 33, 62)),
            floatArrayOf(0f, .60f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null

        // Subtle inner vignette
        paint.color = Color.argb(14, 0, 0, 0)
        canvas.drawCircle(cx, cy, r * 0.96f, paint)

        // Luminous outer rim – double stroke for luxury
        strokePaint.shader = null
        strokePaint.strokeWidth = dpf(3.2f)
        strokePaint.color = Color.argb(26, 6, 213, 255)
        canvas.drawCircle(cx, cy, r + dpf(1.2f), strokePaint)
        strokePaint.strokeWidth = dpf(1.8f)
        strokePaint.color = Color.argb(88, 6, 213, 255)
        canvas.drawCircle(cx, cy, r, strokePaint)
        // inner thin rim
        strokePaint.strokeWidth = dpf(0.9f)
        strokePaint.color = Color.argb(40, 180, 240, 255)
        canvas.drawCircle(cx, cy, r * 0.995f, strokePaint)
    }

    /** Very faint synthetic land / grid texture to mimic reference image without heavy bitmap */
    private fun drawFaintLand(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // Soft blobs as abstract continents – ultra low alpha, clipped manually by staying inside radius
        // Horizontal faint grid lines (only inside circle)
        strokePaint.color = Color.argb(10, 110, 255, 210)
        strokePaint.strokeWidth = dpf(0.7f)
        for (i in -4..4) {
            val y = cy + i * r * 0.32f
            if (kotlin.math.abs(y - cy) < r) canvas.drawLine(cx - r, y, cx + r, y, strokePaint)
            val x = cx + i * r * 0.32f
            if (kotlin.math.abs(x - cx) < r) canvas.drawLine(x, cy - r, x, cy + r, strokePaint)
        }
        // Abstract land blobs well inside circle
        val blobs = arrayOf(
            floatArrayOf(cx - r*0.55f, cy - r*0.35f, r*0.32f, r*0.18f),
            floatArrayOf(cx + r*0.48f, cy - r*0.22f, r*0.26f, r*0.16f),
            floatArrayOf(cx - r*0.38f, cy + r*0.48f, r*0.30f, r*0.22f),
            floatArrayOf(cx + r*0.42f, cy + r*0.42f, r*0.28f, r*0.20f)
        )
        for (b in blobs) {
            canvas.drawOval(b[0]-b[2], b[1]-b[3], b[0]+b[2], b[1]+b[3], landPaint)
            landPaint.color = Color.argb(10, 90, 200, 210)
            canvas.drawOval(b[0]-b[2]*0.6f, b[1]-b[3]*0.6f, b[0]+b[2]*0.6f, b[1]+b[3]*0.6f, landPaint)
            landPaint.color = Color.argb(18, 90, 200, 210)
        }
    }

    private fun drawGrid(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // Inner rings – 3 divisions
        strokePaint.strokeWidth = dpf(0.9f)
        strokePaint.color = Color.argb(60, 81, 232, 247)
        for (fraction in floatArrayOf(.25f, .50f, .75f)) canvas.drawCircle(cx, cy, r * fraction, strokePaint)
        // Dashed additional inner ring for luxury
        strokePaint.color = Color.argb(26, 180, 240, 255)
        strokePaint.strokeWidth = dpf(0.7f)
        canvas.drawCircle(cx, cy, r * 0.92f, strokePaint)

        // Radial lines – every 30°, rotated by azimuth
        strokePaint.strokeWidth = dpf(0.85f)
        strokePaint.color = Color.argb(36, 87, 226, 243)
        for (deg in 0 until 360 step 30) {
            val eff = norm360(deg.toFloat() - azimuthDeg)
            val rad = Math.toRadians(eff.toDouble())
            val ex = cx + (sin(rad) * r).toFloat()
            val ey = cy - (cos(rad) * r).toFloat()
            canvas.drawLine(cx, cy, ex, ey, strokePaint)
        }
        // Crosshair stronger
        strokePaint.color = Color.argb(82, 102, 240, 250)
        strokePaint.strokeWidth = dpf(1.3f)
        // North-south & east-west are also part of 30° set but draw thicker
        for (deg in arrayOf(0, 90, 180, 270)) {
            val eff = norm360(deg.toFloat() - azimuthDeg)
            val rad = Math.toRadians(eff.toDouble())
            val ex = cx + (sin(rad) * r).toFloat()
            val ey = cy - (cos(rad) * r).toFloat()
            val sx = cx - (sin(rad) * r).toFloat()
            val sy = cy + (cos(rad) * r).toFloat()
            canvas.drawLine(sx, sy, ex, ey, strokePaint)
        }

        // Bezel ticks – 48 ticks every 7.5°
        strokePaint.strokeWidth = dpf(1f)
        for (i in 0 until 48) {
            val deg = i * 7.5f
            val eff = norm360(deg - azimuthDeg)
            val rad = Math.toRadians(eff.toDouble())
            val isDegree = i % 4 == 0 // every 30°
            val isCardinal = deg % 90f == 0f
            val inner = r - dp(if (isCardinal) 11 else if (isDegree) 9 else 5)
            val outer = r
            // color intensity
            strokePaint.color = when {
                isCardinal -> Color.argb(140, 110, 255, 195)
                isDegree -> Color.argb(120, 106, 239, 248)
                else -> Color.argb(70, 106, 239, 248)
            }
            strokePaint.strokeWidth = if (isDegree) dpf(1.1f) else dpf(0.7f)
            val sx = cx + (sin(rad) * inner).toFloat()
            val sy = cy - (cos(rad) * inner).toFloat()
            val ex = cx + (sin(rad) * outer).toFloat()
            val ey = cy - (cos(rad) * outer).toFloat()
            canvas.drawLine(sx, sy, ex, ey, strokePaint)
        }

        // Outer bezel ring markers at cardinal points – small luminous ticks
        strokePaint.color = Color.argb(100, 6, 213, 255)
        strokePaint.strokeWidth = dpf(1f)
        canvas.drawCircle(cx, cy, r, strokePaint)
    }

    private fun drawCompassLabels(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // Degree numbers around perimeter – every 30°
        degreePaint.color = degreeCyan
        degreePaint.textSize = dp(9).toFloat()
        degreePaint.typeface = Fonts.regular
        cardinalPaint.color = cardinalGreen
        cardinalPaint.textSize = dp(11).toFloat()
        cardinalPaint.typeface = Fonts.medium

        val degreeRadius = r + dp(13)
        val cardinalRadius = r - dp(18)
        // also small triangle indicators at cardinal points

        for (deg in 0 until 360 step 30) {
            val eff = norm360(deg.toFloat() - azimuthDeg)
            val rad = Math.toRadians(eff.toDouble())
            val x = cx + (sin(rad) * degreeRadius).toFloat()
            val y = cy - (cos(rad) * degreeRadius).toFloat() + dp(3) // baseline offset
            // Do not draw degree number where cardinal label will be (0,90,180,270) – we draw both but offset
            if (deg % 90 == 0) {
                // Draw degree outside, cardinal inside – both
                degreePaint.textSize = dp(9).toFloat()
                canvas.drawText("${deg}°", x, y, degreePaint)
            } else {
                canvas.drawText("${deg}°", x, y, degreePaint)
            }
        }

        // Cardinal Arabic labels with small triangle arrows
        val cardinals = arrayOf(
            Triple(0f, "شمال", 0f),
            Triple(90f, "شرق", 90f),
            Triple(180f, "جنوب", 180f),
            Triple(270f, "غرب", 270f)
        )
        for ((deg, label, _) in cardinals) {
            val eff = norm360(deg - azimuthDeg)
            val rad = Math.toRadians(eff.toDouble())
            val lx = cx + (sin(rad) * cardinalRadius).toFloat()
            val ly = cy - (cos(rad) * cardinalRadius).toFloat() + dp(4)
            // Draw label upright (no rotation)
            cardinalPaint.textSize = dp(12).toFloat()
            // Shadow for readability
            cardinalPaint.setShadowLayer(dpf(4f), 0f, 0f, Color.argb(120, 0, 0, 0))
            canvas.drawText(label, lx, ly, cardinalPaint)
            cardinalPaint.clearShadowLayer()

            // Small triangle arrow pointing outward at the bezel edge (north arrow)
            val arrowR = r + dp(1)
            val ax = cx + (sin(rad) * arrowR).toFloat()
            val ay = cy - (cos(rad) * arrowR).toFloat()
            drawCardinalTriangle(canvas, ax, ay, eff)
        }
    }

    private fun drawCardinalTriangle(canvas: Canvas, cx: Float, cy: Float, degreeEff: Float) {
        // degreeEff is effective screen angle (0 at top). Draw small filled triangle pointing outward.
        // Outward direction is same as degreeEff
        val rad = Math.toRadians(degreeEff.toDouble())
        val size = dpf(7f)
        // tip outward
        val tipX = cx + (sin(rad) * size).toFloat()
        val tipY = cy - (cos(rad) * size).toFloat()
        // base points perpendicular
        val perp = degreeEff + 90f
        val prad = Math.toRadians(perp.toDouble())
        val baseDist = dpf(2.5f)
        val bx1 = cx + (sin(prad) * dpf(4f)).toFloat() - (sin(rad) * baseDist).toFloat()
        val by1 = cy - (cos(prad) * dpf(4f)).toFloat() + (cos(rad) * baseDist).toFloat()
        val bx2 = cx - (sin(prad) * dpf(4f)).toFloat() - (sin(rad) * baseDist).toFloat()
        val by2 = cy + (cos(prad) * dpf(4f)).toFloat() + (cos(rad) * baseDist).toFloat()

        val path = Path()
        path.moveTo(tipX, tipY)
        path.lineTo(bx1, by1)
        path.lineTo(bx2, by2)
        path.close()
        paint.style = Paint.Style.FILL
        paint.color = cardinalGreen
        paint.shader = null
        canvas.drawPath(path, paint)
        // glow
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(40, 110, 255, 195)
        canvas.drawCircle(tipX, tipY, dpf(3f), paint)
        paint.style = Paint.Style.FILL
    }

    /** Purely decorative scanner sweep — green, glowing, always rotating while scanning. */
    private fun drawSweep(canvas: Canvas, cx: Float, cy: Float, r: Float, headDeg: Float) {
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
        // SweepGradient is defined with 0° at 3 o'clock, so rotate to align headDeg (0° north)
        // headDeg 0 = north (up). SweepGradient 0 = east, so offset by -90
        val sweepRotation = headDeg - 90f
        canvas.save()
        canvas.rotate(sweepRotation, cx, cy)
        canvas.drawCircle(cx, cy, r, paint)
        canvas.restore()
        paint.shader = null

        // Bright beam line
        strokePaint.shader = LinearGradient(
            cx, cy, cx + r, cy,
            colorWithAlpha(sweepGreen, 0), colorWithAlpha(sweepGreen, 230), Shader.TileMode.CLAMP
        )
        strokePaint.strokeWidth = dpf(2f)
        canvas.save()
        canvas.rotate(sweepRotation, cx, cy)
        canvas.drawLine(cx, cy, cx + r, cy, strokePaint)
        canvas.restore()
        strokePaint.shader = null

        // Head dot – on the rim at head angle
        val hr = Math.toRadians((headDeg - 90f).toDouble()) // convert north-based head to canvas east-based
        // headDeg north-based -> canvas math: x = cos(hr)*r, y = sin(hr)*r but we use sin/cos north base
        // Use north-based directly
        val hRad = Math.toRadians(headDeg.toDouble())
        val hx = cx + (sin(hRad) * r).toFloat()
        val hy = cy - (cos(hRad) * r).toFloat()
        paint.color = sweepGreen
        canvas.drawCircle(hx, hy, dpf(2.4f), paint)
        paint.color = Color.argb(60, 72, 245, 148)
        canvas.drawCircle(hx, hy, dpf(6f), paint)
    }

    /** Red markers — always pure red as per design reference, but keep band for HUD logic */
    private fun drawTargets(canvas: Canvas) {
        // All markers are pure luxurious red (as in image) — ignore band halo color except for selection halo
        val coreR = dpf(4.2f)
        val rimR = dpf(5.4f)
        val haloR = dpf(11f)
        val selR = dpf(8.5f)
        for (p in plotted) {
            val isSelected = p.target.id == selectedId
            canvas.save()
            canvas.translate(p.x, p.y)
            // Halo – soft red glow
            paint.shader = haloShaderForRed(haloR)
            canvas.drawCircle(0f, 0f, haloR, paint)
            if (isSelected) canvas.drawCircle(0f, 0f, haloR * 1.25f, paint)
            paint.shader = null
            // Outer white rim for contrast
            paint.color = Color.argb(235, 255, 255, 255)
            canvas.drawCircle(0f, 0f, rimR, paint)
            // Core pure red
            paint.color = Color.rgb(255, 58, 58) // vivid red
            canvas.drawCircle(0f, 0f, coreR, paint)
            // Inner highlight
            paint.color = Color.argb(150, 255, 255, 255)
            canvas.drawCircle(-dpf(0.8f), -dpf(0.8f), dpf(1.1f), paint)

            if (isSelected) {
                strokePaint.strokeWidth = dpf(1.6f)
                strokePaint.color = Color.rgb(255, 58, 58)
                canvas.drawCircle(0f, 0f, selR, strokePaint)
                strokePaint.strokeWidth = dpf(1f)
                strokePaint.color = Color.argb(140, 255, 255, 255)
                canvas.drawCircle(0f, 0f, selR + dpf(3f), strokePaint)
            }
            canvas.restore()
        }
    }

    /** The phone itself — center with sweep-synced small arrow */
    private fun drawCenter(canvas: Canvas, cx: Float, cy: Float, headDeg: Float) {
        val c = if (hasGpsFix) aqua else muted
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)

        // Outer soft pulse
        paint.color = Color.argb(if (hasGpsFix) 44 else 22, r, g, b)
        canvas.drawCircle(cx, cy, dpf(18f), paint)
        // Middle ring
        strokePaint.strokeWidth = dpf(1.5f)
        strokePaint.color = Color.argb(200, r, g, b)
        canvas.drawCircle(cx, cy, dpf(9.5f), strokePaint)
        // Inner dot
        paint.color = c
        canvas.drawCircle(cx, cy, dpf(3.4f), paint)

        // Crosshair lines (static, not rotating)
        strokePaint.strokeWidth = dpf(1.05f)
        strokePaint.color = Color.argb(130, r, g, b)
        canvas.drawLine(cx - dpf(15f), cy, cx - dpf(7f), cy, strokePaint)
        canvas.drawLine(cx + dpf(7f), cy, cx + dpf(15f), cy, strokePaint)
        canvas.drawLine(cx, cy - dpf(15f), cx, cy - dpf(7f), strokePaint)
        canvas.drawLine(cx, cy + dpf(7f), cx, cy + dpf(15f), strokePaint)

        // Small arrow synchronized with sweep – headDeg direction
        if (scanning) {
            drawSweepArrow(canvas, cx, cy, headDeg)
        } else {
            // When not scanning, arrow points north (or could be hidden) – keep static north arrow for reference
            drawSweepArrow(canvas, cx, cy, azimuthDeg) // or 0 – choose north
        }
    }

    private fun drawSweepArrow(canvas: Canvas, cx: Float, cy: Float, headDeg: Float) {
        // Arrow centered at cx,cy, pointing along headDeg (north-based)
        val rad = Math.toRadians(headDeg.toDouble())
        val len = dpf(10f)
        val tipX = cx + (sin(rad) * len).toFloat()
        val tipY = cy - (cos(rad) * len).toFloat()
        // Base perpendicular
        val perp = headDeg + 90f
        val prad = Math.toRadians(perp.toDouble())
        val baseW = dpf(5.2f)
        val baseBack = dpf(2f)
        val bx1 = cx + (sin(prad) * baseW).toFloat() - (sin(rad) * baseBack).toFloat()
        val by1 = cy - (cos(prad) * baseW).toFloat() + (cos(rad) * baseBack).toFloat()
        val bx2 = cx - (sin(prad) * baseW).toFloat() - (sin(rad) * baseBack).toFloat()
        val by2 = cy + (cos(prad) * baseW).toFloat() + (cos(rad) * baseBack).toFloat()

        val path = Path()
        path.moveTo(tipX, tipY)
        path.lineTo(bx1, by1)
        path.lineTo(bx2, by2)
        path.close()

        // Shadow / glow
        paint.color = Color.argb(70, 6, 213, 255)
        canvas.drawCircle(cx, cy, dpf(7f), paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(170, 255, 240) // bright cyan-white arrow
        paint.shader = null
        canvas.drawPath(path, paint)

        // Outline for crispness
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = dpf(0.9f)
        strokePaint.color = Color.argb(160, 0, 40, 50)
        canvas.drawPath(path, strokePaint)
        strokePaint.style = Paint.Style.STROKE
    }

    private fun drawWaiting(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        textPaint.textSize = dp(10).toFloat()
        textPaint.color = Color.argb(215, 214, 238, 247)
        // Place waiting text below center, still inside disc but not covering
        canvas.drawText(waitingLabel, cx, cy + radius * 0.42f, textPaint)
    }

    /** Re-derives marker positions from the real data — only when that data changed. */
    private fun ensurePlotted(cx: Float, cy: Float, radius: Float) {
        if (!plotDirty) return
        val inner = dpf(24f)
        val outer = (radius - dp(8)).coerceAtLeast(inner)
        val planned = RadarPlot.plan(
            targets, rangeM, inner, outer,
            separation = dpf(16f), ringStep = dpf(17f)
        )
        plotted = planned.map { p ->
            // Bearing corrected by compass azimuth: geographic north stays true
            val effBearing = norm360(p.bearing - azimuthDeg)
            // Convert north-based bearing to canvas coordinates: 0° = up
            val a = Math.toRadians(effBearing.toDouble())
            val x = cx + (sin(a) * p.radius).toFloat()
            val y = cy - (cos(a) * p.radius).toFloat()
            PlotPoint(p.target, x, y, p.band)
        }
        plotDirty = false
    }

    private var redHaloShader: Shader? = null
    private fun haloShaderForRed(radius: Float): Shader {
        redHaloShader?.let { return it }
        val c = Color.rgb(255, 58, 58)
        val s = RadialGradient(
            0f, 0f, radius,
            Color.argb(140, Color.red(c), Color.green(c), Color.blue(c)),
            Color.argb(0, Color.red(c), Color.green(c), Color.blue(c)),
            Shader.TileMode.CLAMP
        )
        redHaloShader = s
        return s
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
                if (hit != null && best <= dp(20)) {
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
    private fun dpf(value: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
    private fun norm360(a: Float): Float { var x = a % 360f; if (x < 0f) x += 360f; return x }

    companion object {
        private const val SWEEP_PERIOD_MS = 3_400L
    }
}
