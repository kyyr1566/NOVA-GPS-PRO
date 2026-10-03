package com.nova.gpspro.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.nova.gpspro.R
import java.util.Locale

/** Small square card containing a real, rolling radar metric graph. */
class RadarMetricCard(
    context: Context,
    title: String,
    scale: RadarGraphScale,
    private val valueFormatter: (Double) -> String,
    private val singleSeriesColor: Int = C.RADAR_CHIP_TEXT
) : LinearLayout(context) {

    private val chart = RadarMiniGraphView(context, scale)
    private val valueView: TextView
    private val main = Handler(Looper.getMainLooper())
    private var clearValueRunnable: Runnable? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        background = roundRect(C.RADAR_SOFT_BG, context.dp(9).toFloat(), C.RADAR_BORDER, context.dp(1))
        setPadding(context.dp(2), context.dp(2), context.dp(2), context.dp(2))
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES

        val titleView = context.text(title, 7f, C.RADAR_TEXT2, Fonts.medium).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        addView(titleView, lp())

        addView(chart, LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).margins(t = context.dp(1), b = context.dp(1)))

        valueView = context.text(context.getString(R.string.radar_graph_unavailable), 7.5f, C.RADAR_CHIP_TEXT, Fonts.medium).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        addView(valueView, lp())
        contentDescription = title
    }

    /** Appends one actual sample and updates the displayed real value if accepted. */
    fun addSample(
        elapsedRealtimeMs: Long,
        value: Double,
        seriesId: String = SINGLE_SERIES,
        seriesColor: Int = singleSeriesColor
    ): Boolean {
        if (!chart.addSample(seriesId, elapsedRealtimeMs, value, seriesColor)) return false
        showCurrent(valueFormatter(value), elapsedRealtimeMs)
        return true
    }

    /** Adds a multi-satellite C/N0 line without changing the card's summary value. */
    fun addSeriesSample(seriesId: String, elapsedRealtimeMs: Long, value: Double): Boolean =
        chart.addSample(seriesId, elapsedRealtimeMs, value)

    /** A value is visible only briefly after its actual measurement, then returns to unavailable. */
    fun showCurrent(text: String?, sampledAtElapsedRealtimeMs: Long = SystemClock.elapsedRealtime()) {
        clearValueRunnable?.let { main.removeCallbacks(it) }
        clearValueRunnable = null
        val actual = text?.takeIf { it.isNotBlank() }
        val shown = actual ?: context.getString(R.string.radar_graph_unavailable)
        valueView.text = shown
        contentDescription = "${(getChildAt(0) as? TextView)?.text ?: ""}, $shown"
        if (actual == null || sampledAtElapsedRealtimeMs < 0L) return

        val age = (SystemClock.elapsedRealtime() - sampledAtElapsedRealtimeMs).coerceAtLeast(0L)
        val remaining = (CURRENT_VALUE_TIMEOUT_MS - age).coerceAtLeast(0L)
        val expiration = Runnable {
            valueView.text = context.getString(R.string.radar_graph_unavailable)
            contentDescription = "${(getChildAt(0) as? TextView)?.text ?: ""}, ${valueView.text}"
            clearValueRunnable = null
        }
        clearValueRunnable = expiration
        main.postDelayed(expiration, remaining)
    }

    fun clearCurrent() = showCurrent(null)

    companion object {
        private const val SINGLE_SERIES = "radar-live"
        private const val CURRENT_VALUE_TIMEOUT_MS = 5_000L

        fun cn0Summary(meanDbHz: Double): String = String.format(Locale.US, "%.0f dB-Hz", meanDbHz)
    }
}
