package com.nova.gpspro.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Luxury royal-navy North-up radar driven strictly by real GPS coordinates — no compass,
 * no orientation sensors, no fake points, no synthetic motion.
 *
 * Visual specification:
 *  • Same full-space circle size as before, deep navy radial backdrop, fine concentric blue rings
 *    and crisp perpendicular crosshairs, framed by a luminous royal-blue border (#2457D6 / #1C74FF).
 *  • Smoothly rotating cyan scanner beam with a soft trailing fan sector when scanning is Auto.
 *  • Distinct user centre marker (soft sky-blue halo + crisp white core).
 *  • Very small, sharp location dots without exaggerated glow:
 *      - Red (#F43F3F) for normal destinations within range;
 *      - Green (#22C55E) for the currently selected destination.
 *  • Tapping a dot opens an elegant compact callout card right beside that dot displaying its
 *    real name and live GPS distance (instead of showing the name below the radar).
 */
class RadarView(ctx: Context) : View(ctx) {

    var onTargetClick: ((RadarTarget) -> Unit)? = null
    var onBackgroundClick: (() -> Unit)? = null
    var distanceFormatter: (Double) -> String = { m ->
        if (m >= 1000.0) String.format(java.util.Locale.US, "%.2f km", m / 1000.0)
        else "${Math.round(m)} m"
    }

    private var rangeM = 100.0
    private var scanning = true
    private var sweepStartedAt = SystemClock.uptimeMillis()
    private var targets: List<RadarTarget> = emptyList()
    private var selectedId: String? = null
    private var hasGpsFix = false
    private var waitingLabel = ""
    private var calloutShownAt = 0L

    private class PlotPoint(
        val target: RadarTarget,
        val x: Float,
        val y: Float,
        val band: RadarPlot.Band
    )

    /** Recomputed only when real GPS/target/range data changes — sweep never moves markers. */
    private var plotted: List<PlotPoint> = emptyList()
    private var plotDirty = true

    private var discShader: Shader? = null
    private var sweepShader: Shader? = null
    private var beamShader: Shader? = null
    private var cachedCx = -1f
    private var cachedCy = -1f
    private var cachedRadius = -1f
    private val discClipPath = Path()
    private val calloutRect = RectF()
    private val shadowRect = RectF()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val waitingTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Fonts.medium
    }
    private val calloutNamePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.bold
        color = Color.rgb(248, 250, 252)
    }
    private val calloutDistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.medium
        color = Color.rgb(103, 232, 249)
    }

    // Royal-navy radar palette
    private val navyCenter = Color.rgb(3, 38, 102)     // #032666
    private val navyMid = Color.rgb(1, 26, 86)         // #011A56
    private val navyOuter = Color.rgb(1, 16, 62)       // #01103E
    private val navyEdge = Color.rgb(1, 11, 44)        // #010B2C

    private val royalBase = Color.rgb(36, 87, 214)     // #2457D6
    private val royalLuminous = Color.rgb(28, 116, 255)// #1C74FF
    private val cyanBeam = Color.rgb(94, 248, 255)     // #5EF8FF
    private val cyanSector = Color.rgb(56, 218, 255)   // #38DAFF

    // Crisp minimal dot colours (no exaggerated glow)
    private val dotNormalRed = Color.rgb(244, 63, 63)      // #F43F3F
    private val dotSelectedGreen = Color.rgb(34, 197, 94)  // #22C55E

    init {
        isClickable = true
        contentDescription = "Radar"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
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
        if (id != null) calloutShownAt = SystemClock.uptimeMillis()
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
        invalidateShaders()
    }

    private fun invalidateShaders() {
        discShader = null
        sweepShader = null
        beamShader = null
        cachedRadius = -1f
    }

    private fun ensureGeometryCache(cx: Float, cy: Float, r: Float) {
        if (cx == cachedCx && cy == cachedCy && r == cachedRadius && discShader != null) return
        cachedCx = cx
        cachedCy = cy
        cachedRadius = r

        discShader = RadialGradient(
            cx, cy, r,
            intArrayOf(navyCenter, navyMid, navyOuter, navyEdge),
            floatArrayOf(0f, 0.55f, 0.86f, 1f),
            Shader.TileMode.CLAMP
        )
        sweepShader = SweepGradient(
            cx, cy,
            intArrayOf(
                colorWithAlpha(cyanSector, 0),
                colorWithAlpha(cyanSector, 0),
                colorWithAlpha(cyanSector, 24),
                colorWithAlpha(cyanBeam, 68)
            ),
            floatArrayOf(0f, 0.845f, 0.935f, 1f)
        )
        beamShader = LinearGradient(
            cx, cy, cx + r, cy,
            intArrayOf(Color.WHITE, cyanBeam, colorWithAlpha(cyanBeam, 235)),
            floatArrayOf(0f, 0.25f, 1f),
            Shader.TileMode.CLAMP
        )
        discClipPath.reset()
        discClipPath.addCircle(cx, cy, (r - dpf(1.4f)).coerceAtLeast(1f), Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        // Exact same circle sizing as the original radar
        val radius = (min(width, height) / 2f - dp(5)).coerceAtLeast(1f)
        val cx = width / 2f
        val cy = height / 2f

        ensureGeometryCache(cx, cy, radius)
        ensurePlotted(cx, cy, radius)

        drawDiscBackdrop(canvas, cx, cy, radius)
        drawFineGrid(canvas, cx, cy, radius)
        if (scanning) drawCyanSweep(canvas, cx, cy, radius)
        drawRoyalRim(canvas, cx, cy, radius)
        drawUserCenter(canvas, cx, cy)
        val selectedPoint = drawTargets(canvas)
        if (!hasGpsFix && waitingLabel.isNotEmpty()) {
            drawWaiting(canvas, cx, cy)
        } else if (selectedPoint != null) {
            drawSelectedCallout(canvas, cx, cy, selectedPoint)
        }

        if (scanning && isShown) {
            postInvalidateOnAnimation()
        } else if (selectedPoint != null && SystemClock.uptimeMillis() - calloutShownAt < CALLOUT_ANIM_MS) {
            postInvalidateOnAnimation()
        }
    }

    /** Deep royal-navy backdrop inside the radar circle. */
    private fun drawDiscBackdrop(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // Subtle ambient royal glow just outside the border
        strokePaint.strokeWidth = dpf(5.2f)
        strokePaint.color = Color.argb(40, 28, 104, 255)
        canvas.drawCircle(cx, cy, r + dpf(1.2f), strokePaint)

        fillPaint.shader = discShader
        canvas.drawCircle(cx, cy, r, fillPaint)
        fillPaint.shader = null
    }

    /** Clean, fine concentric blue rings and perpendicular crosshairs matching the reference style. */
    private fun drawFineGrid(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        strokePaint.strokeWidth = dpf(1.05f)
        strokePaint.color = Color.argb(138, 62, 144, 255)
        canvas.drawCircle(cx, cy, r * 0.35f, strokePaint)
        canvas.drawCircle(cx, cy, r * 0.68f, strokePaint)

        // Subtle outer calibration ring for fine range judgment
        strokePaint.strokeWidth = dpf(0.8f)
        strokePaint.color = Color.argb(54, 62, 144, 255)
        canvas.drawCircle(cx, cy, r * 0.86f, strokePaint)

        // Crisp perpendicular axes stopping neatly inside the royal-blue border
        val axisReach = (r - dpf(7f)).coerceAtLeast(0f)
        strokePaint.strokeWidth = dpf(1.1f)
        strokePaint.color = Color.argb(150, 74, 158, 255)
        canvas.drawLine(cx - axisReach, cy, cx + axisReach, cy, strokePaint)
        canvas.drawLine(cx, cy - axisReach, cx, cy + axisReach, strokePaint)
    }

    /** Smoothly rotating cyan scanner sweep with soft trailing fan & delicate radial rays. */
    private fun drawCyanSweep(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val elapsed = (SystemClock.uptimeMillis() - sweepStartedAt) % SWEEP_PERIOD_MS
        val headDeg = elapsed.toFloat() / SWEEP_PERIOD_MS * 360f
        val beamReach = (r - dpf(2.2f)).coerceAtLeast(1f)

        canvas.save()
        canvas.clipPath(discClipPath)
        canvas.rotate(headDeg, cx, cy)

        // 1) Soft trailing cyan fan sector
        fillPaint.shader = sweepShader
        canvas.drawCircle(cx, cy, r, fillPaint)
        fillPaint.shader = null

        // 2) Delicate radial rays inside the trailing fan (matching the reference radar look)
        strokePaint.strokeWidth = dpf(0.8f)
        for (i in 1..7) {
            val rayAngle = -i * 6.8
            val rad = rayAngle * (PI / 180.0)
            val alpha = (34 - i * 4).coerceAtLeast(6)
            strokePaint.color = Color.argb(alpha, 92, 232, 255)
            canvas.drawLine(
                cx, cy,
                cx + (cos(rad) * beamReach).toFloat(),
                cy + (sin(rad) * beamReach).toFloat(),
                strokePaint
            )
        }

        // 3) Soft cyan glow under the leading scanner arm
        strokePaint.strokeWidth = dpf(4.2f)
        strokePaint.color = Color.argb(68, 62, 245, 255)
        canvas.drawLine(cx, cy, cx + beamReach, cy, strokePaint)

        // 4) Crisp luminous cyan scanner beam
        strokePaint.shader = beamShader
        strokePaint.strokeWidth = dpf(2.1f)
        canvas.drawLine(cx, cy, cx + beamReach, cy, strokePaint)
        strokePaint.shader = null

        canvas.restore()
    }

    /** Luminous royal-blue border framing the radar disc. */
    private fun drawRoyalRim(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        strokePaint.strokeWidth = dpf(3.1f)
        strokePaint.color = royalBase
        canvas.drawCircle(cx, cy, r, strokePaint)

        strokePaint.strokeWidth = dpf(1.6f)
        strokePaint.color = royalLuminous
        canvas.drawCircle(cx, cy, r, strokePaint)

        strokePaint.strokeWidth = dpf(0.85f)
        strokePaint.color = Color.argb(135, 118, 192, 255)
        canvas.drawCircle(cx, cy, (r - dpf(1.5f)).coerceAtLeast(1f), strokePaint)
    }

    /** Distinct user centre marker (soft sky-blue halo + crisp white core). */
    private fun drawUserCenter(canvas: Canvas, cx: Float, cy: Float) {
        if (hasGpsFix) {
            fillPaint.color = Color.argb(74, 56, 189, 255)
            canvas.drawCircle(cx, cy, dpf(9.5f), fillPaint)

            strokePaint.strokeWidth = dpf(0.9f)
            strokePaint.color = Color.argb(120, 96, 212, 255)
            canvas.drawCircle(cx, cy, dpf(9.5f), strokePaint)

            fillPaint.color = Color.WHITE
            canvas.drawCircle(cx, cy, dpf(4.0f), fillPaint)
        } else {
            fillPaint.color = Color.argb(38, 128, 168, 214)
            canvas.drawCircle(cx, cy, dpf(8.5f), fillPaint)

            fillPaint.color = Color.argb(200, 198, 218, 242)
            canvas.drawCircle(cx, cy, dpf(3.6f), fillPaint)
        }
    }

    /**
     * Very small, sharp location dots without exaggerated glow:
     *  - Red (#F43F3F) for normal locations
     *  - Green (#22C55E) for the selected location
     * Returns the selected [PlotPoint] if present so its callout can be drawn on top.
     */
    private fun drawTargets(canvas: Canvas): PlotPoint? {
        var selectedPoint: PlotPoint? = null

        // Pass 1: all unselected markers in crisp small red
        val normalBackR = dpf(4.5f)
        val normalCoreR = dpf(2.8f)
        val normalPinR = dpf(0.95f)
        for (p in plotted) {
            if (p.target.id == selectedId) {
                selectedPoint = p
                continue
            }
            fillPaint.color = Color.argb(44, 244, 63, 63)
            canvas.drawCircle(p.x, p.y, normalBackR, fillPaint)

            fillPaint.color = dotNormalRed
            canvas.drawCircle(p.x, p.y, normalCoreR, fillPaint)

            fillPaint.color = Color.WHITE
            canvas.drawCircle(p.x, p.y, normalPinR, fillPaint)
        }

        // Pass 2: selected marker on top in crisp small green with subtle precision ring
        if (selectedPoint != null) {
            val sx = selectedPoint.x
            val sy = selectedPoint.y
            val selRingR = dpf(5.8f)
            val selCoreR = dpf(3.2f)

            fillPaint.color = Color.argb(40, 34, 197, 94)
            canvas.drawCircle(sx, sy, selRingR, fillPaint)

            strokePaint.strokeWidth = dpf(1.15f)
            strokePaint.color = Color.argb(215, 34, 197, 94)
            canvas.drawCircle(sx, sy, selRingR, strokePaint)

            fillPaint.color = dotSelectedGreen
            canvas.drawCircle(sx, sy, selCoreR, fillPaint)

            fillPaint.color = Color.WHITE
            canvas.drawCircle(sx, sy, dpf(1.15f), fillPaint)
        }

        return selectedPoint
    }

    /**
     * Elegant compact floating callout card right beside the selected green dot,
     * showing the destination's name and real live GPS distance.
     */
    private fun drawSelectedCallout(canvas: Canvas, cx: Float, cy: Float, p: PlotPoint) {
        calloutNamePaint.textSize = dpf(11.5f)
        calloutDistPaint.textSize = dpf(10.5f)

        val maxInnerTextW = dpf(126f)
        val rawName = p.target.name.ifBlank { "—" }
        val nameStr = TextUtils.ellipsize(rawName, calloutNamePaint, maxInnerTextW, TextUtils.TruncateAt.END).toString()
        val distStr = distanceFormatter(p.target.distanceM)

        val padH = dpf(9.5f)
        val padV = dpf(6.5f)
        val dotDiameter = dpf(5.0f)
        val dotGap = dpf(5.5f)
        val lineGap = dpf(2.2f)

        val nameRowW = dotDiameter + dotGap + calloutNamePaint.measureText(nameStr)
        val distRowW = calloutDistPaint.measureText(distStr)
        val cardW = (maxOf(nameRowW, distRowW) + padH * 2f).coerceIn(dpf(68f), dpf(156f))

        val nameFm = calloutNamePaint.fontMetrics
        val distFm = calloutDistPaint.fontMetrics
        val nameH = nameFm.descent - nameFm.ascent
        val distH = distFm.descent - distFm.ascent
        val cardH = padV * 2f + nameH + lineGap + distH

        val bounds = RadarCalloutLayout.place(
            viewWidth = width.toFloat(),
            viewHeight = height.toFloat(),
            centerX = cx,
            centerY = cy,
            pointX = p.x,
            pointY = p.y,
            cardWidth = cardW,
            cardHeight = cardH,
            gap = dpf(10.5f),
            margin = dpf(6f)
        )

        val animElapsed = (SystemClock.uptimeMillis() - calloutShownAt).coerceAtLeast(0L)
        val t = (animElapsed.toFloat() / CALLOUT_ANIM_MS).coerceIn(0f, 1f)
        val ease = 1f - (1f - t) * (1f - t)
        val scale = 0.92f + 0.08f * ease

        val saveCount = canvas.save()
        if (ease < 0.999f) {
            canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (ease * 255f).toInt().coerceIn(0, 255))
            canvas.scale(scale, scale, p.x, p.y)
        }

        // Delicate connector tick from the green ring edge to the card's nearest edge
        val anchorX = if (bounds.left >= p.x) bounds.left else bounds.right
        val anchorY = p.y.coerceIn(bounds.top + dpf(8f), bounds.bottom - dpf(8f))
        val dx = anchorX - p.x
        val dy = anchorY - p.y
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val ringEdge = dpf(6.2f)
        if (dist > ringEdge + dpf(1.5f)) {
            val startX = p.x + dx / dist * ringEdge
            val startY = p.y + dy / dist * ringEdge
            strokePaint.strokeWidth = dpf(1.1f)
            strokePaint.color = Color.argb(175, 34, 197, 94)
            canvas.drawLine(startX, startY, anchorX, anchorY, strokePaint)
        }

        val cornerR = dpf(10.5f)
        calloutRect.set(bounds.left, bounds.top, bounds.right, bounds.bottom)
        shadowRect.set(bounds.left, bounds.top + dpf(2.2f), bounds.right, bounds.bottom + dpf(2.2f))

        // Soft drop shadow
        fillPaint.color = Color.argb(115, 0, 5, 20)
        canvas.drawRoundRect(shadowRect, cornerR, cornerR, fillPaint)

        // Deep frosted navy card surface
        fillPaint.color = Color.argb(242, 5, 20, 56)
        canvas.drawRoundRect(calloutRect, cornerR, cornerR, fillPaint)

        // Refined royal-blue & emerald border accent
        strokePaint.strokeWidth = dpf(1.05f)
        strokePaint.color = Color.argb(190, 58, 134, 255)
        canvas.drawRoundRect(calloutRect, cornerR, cornerR, strokePaint)

        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val row1CenterY = bounds.top + padV + nameH / 2f
        val nameBaseline = bounds.top + padV - nameFm.ascent
        val distBaseline = bounds.top + padV + nameH + lineGap - distFm.ascent

        if (rtl) {
            val dotCx = bounds.right - padH - dotDiameter / 2f
            fillPaint.color = dotSelectedGreen
            canvas.drawCircle(dotCx, row1CenterY, dotDiameter / 2f, fillPaint)

            calloutNamePaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(nameStr, dotCx - dotDiameter / 2f - dotGap, nameBaseline, calloutNamePaint)

            calloutDistPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(distStr, bounds.right - padH, distBaseline, calloutDistPaint)
        } else {
            val dotCx = bounds.left + padH + dotDiameter / 2f
            fillPaint.color = dotSelectedGreen
            canvas.drawCircle(dotCx, row1CenterY, dotDiameter / 2f, fillPaint)

            calloutNamePaint.textAlign = Paint.Align.LEFT
            canvas.drawText(nameStr, dotCx + dotDiameter / 2f + dotGap, nameBaseline, calloutNamePaint)

            calloutDistPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(distStr, bounds.left + padH, distBaseline, calloutDistPaint)
        }

        canvas.restoreToCount(saveCount)
    }

    private fun drawWaiting(canvas: Canvas, cx: Float, cy: Float) {
        waitingTextPaint.textSize = dp(10).toFloat()
        waitingTextPaint.color = Color.argb(215, 214, 234, 255)
        canvas.drawText(waitingLabel, cx, cy + dp(30).toFloat(), waitingTextPaint)
    }

    /** Re-derives marker positions from real GPS geodesic data — only when data changed. */
    private fun ensurePlotted(cx: Float, cy: Float, radius: Float) {
        if (!plotDirty) return
        val inner = dpf(18f)                                   // keep the user centre marker clear
        val outer = (radius - dp(9)).coerceAtLeast(inner)      // keep dots cleanly inside royal rim
        val planned = RadarPlot.plan(
            targets, rangeM, inner, outer,
            separation = dpf(11f), ringStep = dpf(13f)
        )
        plotted = planned.map { p ->
            val a = Math.toRadians((p.bearing - 90f).toDouble())
            PlotPoint(
                p.target,
                cx + (cos(a) * p.radius).toFloat(),
                cy + (sin(a) * p.radius).toFloat(),
                p.band
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
                for (p in plotted) {
                    val d = distance(event.x, event.y, p.x, p.y)
                    if (d < best) {
                        best = d
                        hit = p
                    }
                }
                performClick()
                if (hit != null && best <= dp(20)) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    if (hit.target.id == selectedId) {
                        onBackgroundClick?.invoke()
                    } else {
                        onTargetClick?.invoke(hit.target)
                    }
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
        hypot((x1 - x2).toDouble(), (y1 - y2).toDouble()).toFloat()

    private fun colorWithAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(value: Int): Int = context.dp(value)

    companion object {
        private const val SWEEP_PERIOD_MS = 3_800L
        private const val CALLOUT_ANIM_MS = 140L
    }
}

/**
 * Pure JVM-testable geometry helper for placing the compact callout card right beside
 * the selected radar dot without overflowing the view or covering the dot itself.
 */
object RadarCalloutLayout {
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    fun place(
        viewWidth: Float,
        viewHeight: Float,
        centerX: Float,
        centerY: Float,
        pointX: Float,
        pointY: Float,
        cardWidth: Float,
        cardHeight: Float,
        gap: Float,
        margin: Float
    ): Box {
        val maxLeft = (viewWidth - cardWidth - margin).coerceAtLeast(margin)
        val maxTop = (viewHeight - cardHeight - margin).coerceAtLeast(margin)

        val placeOnLeft = pointX > centerX + gap * 0.5f || (pointX + gap + cardWidth > viewWidth - margin && pointX - gap - cardWidth >= margin)
        val rawLeft = if (placeOnLeft) pointX - gap - cardWidth else pointX + gap
        val left = rawLeft.coerceIn(margin, maxLeft)

        // Vertically centre beside the dot; if that would overlap the user centre marker, nudge away slightly
        var rawTop = pointY - cardHeight / 2f
        val overlapsCenterHoriz = centerX in (left - gap * 0.6f)..(left + cardWidth + gap * 0.6f)
        val overlapsCenterVert = centerY in (rawTop - gap * 0.6f)..(rawTop + cardHeight + gap * 0.6f)
        if (overlapsCenterHoriz && overlapsCenterVert) {
            rawTop = if (pointY < centerY) pointY - cardHeight - gap * 0.5f else pointY + gap * 0.5f
        }
        val top = rawTop.coerceIn(margin, maxTop)
        return Box(left, top, left + cardWidth, top + cardHeight)
    }
}
