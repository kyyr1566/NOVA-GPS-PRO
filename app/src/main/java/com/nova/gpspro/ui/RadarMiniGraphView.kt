package com.nova.gpspro.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/** Vertical scale behaviour for the three real radar measurements. */
enum class RadarGraphScale { CN0, SATELLITES_USED, GPS_ACCURACY }

/**
 * A small sparkline renderer. It draws only samples passed by the live Android GNSS/GPS
 * callbacks; grid lines and auto-scaling are presentation aids, never readings.
 */
class RadarMiniGraphView(
    context: Context,
    private val scale: RadarGraphScale
) : View(context) {

    private data class Track(val history: RadarMetricHistory, val color: Int)

    private val tracks = LinkedHashMap<String, Track>()
    private val plot = RectF()
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(0.65f)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(1.35f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * Adds one real point to [seriesId]. GNSS C/N0 uses one stable series per satellite;
     * count and accuracy each use a single series. Returns false for invalid/old samples.
     */
    fun addSample(
        seriesId: String,
        elapsedRealtimeMs: Long,
        value: Double,
        color: Int = seriesColor(seriesId)
    ): Boolean {
        val track = tracks.getOrPut(seriesId) { Track(RadarMetricHistory(), color) }
        val accepted = track.history.append(elapsedRealtimeMs, value)
        if (accepted) invalidate()
        return accepted
    }

    fun clearSamples() {
        tracks.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val insetX = dpF(3f)
        val insetY = dpF(1f)
        plot.set(insetX, insetY, (width - insetX).coerceAtLeast(insetX), (height - insetY).coerceAtLeast(insetY))
        if (plot.width() <= 0f || plot.height() <= 0f) return

        // Fine, unlabelled HUD guides are not measurements or fabricated axis values.
        gridPaint.color = C.RADAR_BORDER
        gridPaint.alpha = 105
        canvas.drawLine(plot.left, plot.top + plot.height() * .33f, plot.right, plot.top + plot.height() * .33f, gridPaint)
        canvas.drawLine(plot.left, plot.top + plot.height() * .66f, plot.right, plot.top + plot.height() * .66f, gridPaint)

        val now = SystemClock.elapsedRealtime()
        val iterator = tracks.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.history.visibleAt(now).isEmpty()) iterator.remove()
        }
        val visible = tracks.mapNotNull { (id, track) ->
            val points = track.history.visibleAt(now)
            if (points.isEmpty()) null else Triple(id, track, points)
        }
        if (visible.isEmpty()) return

        val allValues = visible.flatMap { it.third }.map { it.value }
        val dataMin = allValues.minOrNull() ?: return
        val dataMax = allValues.maxOrNull() ?: return
        val (low, high) = scaleBounds(dataMin, dataMax)
        val span = (high - low).takeIf { it > 0.0 } ?: 1.0
        val timeStart = now - RadarMetricHistory.DEFAULT_WINDOW_MS

        canvas.save()
        canvas.clipRect(plot)
        for ((_, track, points) in visible) {
            val path = Path()
            var previousTime: Long? = null
            var hasSegment = false
            for (point in points) {
                val x = plot.left + ((point.elapsedRealtimeMs - timeStart).toDouble() /
                    RadarMetricHistory.DEFAULT_WINDOW_MS).toFloat().coerceIn(0f, 1f) * plot.width()
                val fraction = ((point.value - low) / span).toFloat().coerceIn(0f, 1f)
                val y = plot.bottom - fraction * plot.height()
                val previous = previousTime
                if (previous == null || point.elapsedRealtimeMs - previous > MAX_CONNECT_GAP_MS) {
                    path.moveTo(x, y)
                    hasSegment = false
                } else {
                    path.lineTo(x, y)
                    hasSegment = true
                }
                previousTime = point.elapsedRealtimeMs
            }

            linePaint.color = track.color
            canvas.drawPath(path, linePaint)

            // Single real readings remain visible as a point; no flat line is synthesized.
            if (!hasSegment) {
                val last = points.last()
                val x = plot.left + ((last.elapsedRealtimeMs - timeStart).toDouble() /
                    RadarMetricHistory.DEFAULT_WINDOW_MS).toFloat().coerceIn(0f, 1f) * plot.width()
                val fraction = ((last.value - low) / span).toFloat().coerceIn(0f, 1f)
                val y = plot.bottom - fraction * plot.height()
                dotPaint.color = track.color
                canvas.drawCircle(x, y, dpF(1.25f), dotPaint)
            }
        }
        canvas.restore()

        // Age old samples out of the rolling time window without adding synthetic samples.
        if (isShown) postInvalidateDelayed(REFRESH_MS)
    }

    private fun scaleBounds(dataMin: Double, dataMax: Double): Pair<Double, Double> {
        if (scale == RadarGraphScale.SATELLITES_USED) {
            return 0.0 to max(1.0, dataMax)
        }
        val span = dataMax - dataMin
        val padding = max(if (scale == RadarGraphScale.CN0) 1.0 else 0.25, abs(span) * .12)
        val low = max(0.0, dataMin - padding)
        val high = max(low + 0.5, dataMax + padding)
        return low to high
    }

    private fun seriesColor(id: String): Int {
        val shade = id.hashCode() and 3
        val alpha = 150 + shade * 30
        val base = C.RADAR_CHIP_TEXT
        return Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))
    }

    private fun dpF(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private const val MAX_CONNECT_GAP_MS = 5_000L
        private const val REFRESH_MS = 1_000L
    }
}
