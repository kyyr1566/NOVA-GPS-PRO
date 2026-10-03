package com.nova.gpspro.ui

/**
 * A bounded time series for the radar's live charts. Only explicitly supplied samples are
 * stored: this class never interpolates, fills gaps, or manufactures zero/default readings.
 * Values are in the metric's real unit and timestamps use Android elapsed-realtime millis.
 */
data class RadarMetricSample(val elapsedRealtimeMs: Long, val value: Double)

class RadarMetricHistory(
    val windowMs: Long = DEFAULT_WINDOW_MS,
    private val maxSamples: Int = DEFAULT_MAX_SAMPLES
) {
    private val samples = ArrayDeque<RadarMetricSample>()

    init {
        require(windowMs > 0L)
        require(maxSamples > 0)
    }

    /** Adds one real measurement. Invalid or out-of-order samples are rejected. */
    fun append(elapsedRealtimeMs: Long, value: Double): Boolean {
        if (elapsedRealtimeMs < 0L || !value.isFinite()) return false
        val last = samples.lastOrNull()
        if (last != null && elapsedRealtimeMs < last.elapsedRealtimeMs) return false

        samples.addLast(RadarMetricSample(elapsedRealtimeMs, value))
        prune(elapsedRealtimeMs)
        while (samples.size > maxSamples) samples.removeFirst()
        return true
    }

    /** A snapshot containing only actual samples inside the visible chart window. */
    fun visibleAt(nowElapsedRealtimeMs: Long): List<RadarMetricSample> {
        if (nowElapsedRealtimeMs < 0L) return emptyList()
        val cutoff = nowElapsedRealtimeMs - windowMs
        return samples.filter { it.elapsedRealtimeMs in cutoff..nowElapsedRealtimeMs }
    }

    private fun prune(nowElapsedRealtimeMs: Long) {
        val cutoff = nowElapsedRealtimeMs - windowMs
        while (samples.isNotEmpty() && samples.first().elapsedRealtimeMs < cutoff) samples.removeFirst()
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 60_000L
        const val DEFAULT_MAX_SAMPLES = 180
    }
}
