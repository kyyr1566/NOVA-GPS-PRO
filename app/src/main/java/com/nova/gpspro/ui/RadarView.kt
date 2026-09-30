package com.nova.gpspro.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A north-up (true-geographic) radar presentation. It deliberately consumes only coordinates
 * calculated by GPS coordinates only; it never consumes orientation or motion input.
 * A target's angle is its initial geodesic bearing; overlap handling only moves it radially.
 */
data class RadarTarget(
    val id: String,
    val name: String,
    val distanceM: Double,
    val bearing: Float
)

private data class PlottedRadarTarget(
    val target: RadarTarget,
    val x: Float,
    val y: Float
)

class RadarView(ctx: Context) : View(ctx) {
    var onTargetClick: ((RadarTarget) -> Unit)? = null

    private var rangeM = 100.0
    private var scanning = true
    private var sweepStartedAt = SystemClock.uptimeMillis()
    private var targets: List<RadarTarget> = emptyList()
    private var plottedTargets: List<PlottedRadarTarget> = emptyList()

    private var hasGpsFix = false
    private var satellitesUsed = 0
    private var accuracyLabel = "—"
    private var fixStateLabel = "—"
    private var waitingLabel = ""
    private var gpsFixLabel = "GPS FIX"
    private var satellitesLabel = "SAT USED"
    private var accuracyTitle = "ACCURACY"
    private val accuracyHistory = ArrayDeque<Float>()

    private val circle = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Fonts.medium
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()

    private val navy = Color.rgb(5, 48, 84)
    private val cyan = Color.rgb(18, 220, 237)
    private val aqua = Color.rgb(47, 235, 185)
    private val point = Color.rgb(115, 247, 255)
    private val muted = Color.rgb(133, 179, 203)
    private val bad = Color.rgb(242, 177, 79)

    init {
        isClickable = true
        contentDescription = "Radar"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setRange(meters: Double) {
        if (rangeM == meters) return
        rangeM = meters
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
        invalidate()
    }

    /** Adds only readings produced by a real GPS fix; never manufactures a graph point. */
    fun updateTelemetry(
        gpsFix: Boolean,
        statusLabel: String,
        usedSatellites: Int,
        accuracy: Float?,
        formattedAccuracy: String?,
        labels: RadarLabels,
        addAccuracySample: Boolean
    ) {
        hasGpsFix = gpsFix
        fixStateLabel = statusLabel
        satellitesUsed = usedSatellites.coerceAtLeast(0)
        accuracyLabel = formattedAccuracy ?: "—"
        waitingLabel = labels.waiting
        gpsFixLabel = labels.gpsFix
        satellitesLabel = labels.satellites
        accuracyTitle = labels.accuracy
        if (addAccuracySample && gpsFix && accuracy != null && accuracy.isFinite() && accuracy > 0f) {
            accuracyHistory.addLast(accuracy)
            while (accuracyHistory.size > HISTORY_SIZE) accuracyHistory.removeFirst()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val pad = dp(6).toFloat()
        val radius = (min(width, height) / 2f - pad).coerceAtLeast(1f)
        val cx = width / 2f
        val cy = height / 2f
        circle.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // Deep cyan-blue HUD disc on the app's otherwise light surface.
        paint.shader = android.graphics.RadialGradient(
            cx, cy, radius,
            intArrayOf(Color.rgb(9, 77, 118), navy, Color.rgb(3, 37, 68)),
            floatArrayOf(0f, .62f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = null
        paint.color = Color.argb(72, 5, 198, 228)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2).toFloat()
        canvas.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.FILL

        drawGrid(canvas, cx, cy, radius)
        if (scanning) drawSweep(canvas, cx, cy, radius)

        plottedTargets = plotTargets(cx, cy, radius)
        drawTargets(canvas)
        drawCenter(canvas, cx, cy, radius)
        drawTelemetry(canvas, cx, cy, radius)

        if (scanning && isShown) postInvalidateOnAnimation()
    }

    private fun drawGrid(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        strokePaint.color = Color.argb(60, 81, 232, 247)
        strokePaint.strokeWidth = dp(1).toFloat()
        for (fraction in floatArrayOf(.25f, .5f, .75f)) canvas.drawCircle(cx, cy, r * fraction, strokePaint)

        // Fine radial lines make direction readable without adding cardinal-direction words.
        strokePaint.color = Color.argb(38, 87, 226, 243)
        for (i in 0 until 12) {
            val a = i * 30.0 * PI / 180.0
            canvas.drawLine(cx, cy, cx + (cos(a) * r).toFloat(), cy - (sin(a) * r).toFloat(), strokePaint)
        }
        strokePaint.color = Color.argb(88, 102, 240, 250)
        strokePaint.strokeWidth = dp(1.5f).toFloat()
        canvas.drawLine(cx - r, cy, cx + r, cy, strokePaint)
        canvas.drawLine(cx, cy - r, cx, cy + r, strokePaint)

        // Calibration ticks, intentionally unlabeled so the screen never implies a device heading.
        strokePaint.color = Color.argb(130, 106, 239, 248)
        strokePaint.strokeWidth = dp(1).toFloat()
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

    private fun drawSweep(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val elapsed = (SystemClock.uptimeMillis() - sweepStartedAt) % SWEEP_PERIOD_MS
        val head = elapsed.toFloat() / SWEEP_PERIOD_MS * 360f - 90f
        paint.color = Color.argb(35, 17, 235, 211)
        canvas.drawArc(circle, head - 62f, 62f, true, paint)

        // A narrow luminous leading line gives the sweep a premium scanner feel.
        strokePaint.shader = LinearGradient(
            cx, cy, cx + r, cy,
            intArrayOf(colorWithAlpha(aqua, 0), colorWithAlpha(cyan, 235)), null, Shader.TileMode.CLAMP
        )
        strokePaint.color = cyan
        strokePaint.strokeWidth = dp(2).toFloat()
        val radians = head.toDouble() * PI / 180.0
        canvas.drawLine(cx, cy, cx + (cos(radians) * r).toFloat(), cy + (sin(radians) * r).toFloat(), strokePaint)
        strokePaint.shader = null
    }

    /**
     * Keeps every target on its exact bearing. Near-coincident markers are assigned a nearby
     * radial ring instead of being offset sideways, so their geographical direction is retained.
     */
    private fun plotTargets(cx: Float, cy: Float, r: Float): List<PlottedRadarTarget> {
        // Reserve the centre marker so a destination saved at the current position remains tappable.
        val inner = dp(26).toFloat()
        val outer = (r - dp(12)).coerceAtLeast(inner)
        val usable = (outer - inner).coerceAtLeast(1f)
        val separation = dp(15).toFloat()
        val ringStep = dp(17).toFloat()
        val output = ArrayList<PlottedRadarTarget>(targets.size)

        for (target in targets.sortedWith(compareBy<RadarTarget> { it.distanceM }.thenBy { it.id })) {
            val relative = (target.distanceM / rangeM).toFloat().coerceIn(0f, 1f)
            val desired = inner + usable * relative
            val candidates = ArrayList<Float>()
            candidates += desired
            for (ring in 1..12) {
                val outward = desired + ring * ringStep
                val inward = desired - ring * ringStep
                if (outward <= outer) candidates += outward
                if (inward >= inner) candidates += inward
            }
            if (candidates.isEmpty()) candidates += desired.coerceIn(inner, outer)

            val rad = (target.bearing.toDouble() - 90.0) * PI / 180.0
            fun candidateAt(radial: Float) = Pair(
                cx + (cos(rad) * radial).toFloat(),
                cy + (sin(rad) * radial).toFloat()
            )
            fun clearance(x: Float, y: Float): Float = if (output.isEmpty()) Float.MAX_VALUE else
                output.minOf { distance(x, y, it.x, it.y) }

            var selected = candidates.first()
            var bestClearance = -1f
            for (candidate in candidates) {
                val (x, y) = candidateAt(candidate)
                val clear = clearance(x, y)
                if (clear >= separation) { selected = candidate; bestClearance = clear; break }
                if (clear > bestClearance) { selected = candidate; bestClearance = clear }
            }
            val (x, y) = candidateAt(selected)
            output += PlottedRadarTarget(target, x, y)
        }
        return output
    }

    private fun drawTargets(canvas: Canvas) {
        for (item in plottedTargets) {
            paint.color = Color.argb(70, 62, 245, 255)
            canvas.drawCircle(item.x, item.y, dp(8).toFloat(), paint)
            paint.color = point
            canvas.drawCircle(item.x, item.y, dp(3).toFloat(), paint)
            strokePaint.color = Color.WHITE
            strokePaint.strokeWidth = dp(1).toFloat()
            canvas.drawCircle(item.x, item.y, dp(3).toFloat(), strokePaint)
        }
    }

    private fun drawCenter(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val centerColor = if (hasGpsFix) aqua else muted
        paint.color = Color.argb(if (hasGpsFix) 50 else 28, Color.red(centerColor), Color.green(centerColor), Color.blue(centerColor))
        canvas.drawCircle(cx, cy, dp(18).toFloat(), paint)
        strokePaint.color = centerColor
        strokePaint.strokeWidth = dp(1.5f).toFloat()
        canvas.drawCircle(cx, cy, dp(10).toFloat(), strokePaint)
        paint.color = centerColor
        canvas.drawCircle(cx, cy, dp(4).toFloat(), paint)
        strokePaint.color = Color.argb(170, Color.red(centerColor), Color.green(centerColor), Color.blue(centerColor))
        canvas.drawLine(cx - dp(15), cy, cx - dp(7), cy, strokePaint)
        canvas.drawLine(cx + dp(7), cy, cx + dp(15), cy, strokePaint)
        canvas.drawLine(cx, cy - dp(15), cx, cy - dp(7), strokePaint)
        canvas.drawLine(cx, cy + dp(7), cx, cy + dp(15), strokePaint)

        if (!hasGpsFix) {
            textPaint.textSize = dp(10).toFloat()
            textPaint.color = Color.argb(220, 219, 240, 247)
            canvas.drawText(waitingLabel, cx, cy + dp(33), textPaint)
        }
    }

    private fun drawTelemetry(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val chipY = cy - r * .62f
        drawHudChip(canvas, cx - r * .43f, chipY, gpsFixLabel, fixStateLabel, if (hasGpsFix) aqua else bad)
        drawHudChip(canvas, cx + r * .43f, chipY, satellitesLabel, satellitesUsed.toString(), cyan)

        val chartW = r * .66f
        val chartH = max(dp(42).toFloat(), r * .23f)
        val chart = RectF(cx - chartW / 2f, cy + r * .53f, cx + chartW / 2f, cy + r * .53f + chartH)
        paint.color = Color.argb(72, 6, 28, 53)
        canvas.drawRoundRect(chart, dp(8).toFloat(), dp(8).toFloat(), paint)
        strokePaint.color = Color.argb(88, 94, 230, 244)
        strokePaint.strokeWidth = dp(1).toFloat()
        canvas.drawRoundRect(chart, dp(8).toFloat(), dp(8).toFloat(), strokePaint)

        textPaint.textSize = dp(9).toFloat()
        textPaint.color = Color.argb(190, 177, 232, 242)
        canvas.drawText(accuracyTitle, cx, chart.top + dp(11), textPaint)
        textPaint.textSize = dp(12).toFloat()
        textPaint.color = if (hasGpsFix) point else muted
        canvas.drawText(accuracyLabel, cx, chart.top + dp(25), textPaint)
        drawAccuracyLine(canvas, RectF(chart.left + dp(8), chart.top + dp(29), chart.right - dp(8), chart.bottom - dp(5)))
    }

    private fun drawHudChip(canvas: Canvas, x: Float, y: Float, title: String, value: String, color: Int) {
        textPaint.textSize = dp(9).toFloat()
        textPaint.color = Color.argb(175, 182, 232, 242)
        canvas.drawText(title, x, y, textPaint)
        textPaint.textSize = dp(13).toFloat()
        textPaint.color = color
        canvas.drawText(value, x, y + dp(15), textPaint)
    }

    private fun drawAccuracyLine(canvas: Canvas, bounds: RectF) {
        if (accuracyHistory.isEmpty() || bounds.width() <= 0f || bounds.height() <= 0f) return
        val readings = accuracyHistory.toList()
        val lowest = readings.minOrNull() ?: return
        val highest = readings.maxOrNull() ?: return
        val span = max(3f, highest - lowest)
        path.reset()
        readings.forEachIndexed { index, value ->
            val x = if (readings.size == 1) bounds.centerX() else bounds.left + bounds.width() * index / (readings.size - 1)
            val y = bounds.bottom - ((value - lowest) / span).coerceIn(0f, 1f) * bounds.height()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        strokePaint.color = aqua
        strokePaint.strokeWidth = dp(1.5f).toFloat()
        strokePaint.style = Paint.Style.STROKE
        canvas.drawPath(path, strokePaint)
        val last = readings.last()
        val lx = if (readings.size == 1) bounds.centerX() else bounds.right
        val ly = bounds.bottom - ((last - lowest) / span).coerceIn(0f, 1f) * bounds.height()
        paint.color = point
        canvas.drawCircle(lx, ly, dp(2).toFloat(), paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val hit = plottedTargets.minByOrNull { distance(event.x, event.y, it.x, it.y) }
                if (hit != null && distance(event.x, event.y, hit.x, hit.y) <= dp(20)) {
                    performClick()
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onTargetClick?.invoke(hit.target)
                    return true
                }
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
    private fun dp(value: Float): Int = context.dp(value)

    companion object {
        private const val HISTORY_SIZE = 36
        private const val SWEEP_PERIOD_MS = 3_800L
    }
}

data class RadarLabels(
    val gpsFix: String,
    val satellites: String,
    val accuracy: String,
    val waiting: String
)
