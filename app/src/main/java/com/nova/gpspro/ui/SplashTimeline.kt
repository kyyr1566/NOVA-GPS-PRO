package com.nova.gpspro.ui

/**
 * Pure, deterministic Splash timeline (unit-tested).
 * Total 4000 ms. Four messages, one per 1000 ms slot, sharing ONE text view:
 *   fade-in 180 ms → fully visible → fade-out 180 ms, so a message is completely gone
 *   before the next one starts (overlap impossible by construction).
 */
object SplashTimeline {
    const val TOTAL_MS = 4000L
    const val SLOT_MS = 1000L
    const val MESSAGES = 4
    const val FADE_IN_MS = 180L
    const val FADE_OUT_MS = 180L
    /** whole-screen fade to Home in the last 200 ms, ending exactly at 4.0 s */
    const val EXIT_FADE_MS = 200L

    /** index of the message shown at time t (0..3), or -1 after the end */
    fun messageIndex(t: Long): Int = if (t < 0 || t >= TOTAL_MS) -1 else (t / SLOT_MS).toInt().coerceAtMost(MESSAGES - 1)

    /** opacity of the current message at time t */
    fun messageAlpha(t: Long): Float {
        if (messageIndex(t) < 0) return 0f
        val local = t % SLOT_MS
        return when {
            local < FADE_IN_MS -> smooth(local / FADE_IN_MS.toFloat())
            local > SLOT_MS - FADE_OUT_MS -> smooth((SLOT_MS - local) / FADE_OUT_MS.toFloat())
            else -> 1f
        }
    }

    /** opacity of the whole splash (1 until the final exit fade) */
    fun screenAlpha(t: Long): Float = when {
        t <= TOTAL_MS - EXIT_FADE_MS -> 1f
        t >= TOTAL_MS -> 0f
        else -> smooth((TOTAL_MS - t) / EXIT_FADE_MS.toFloat())
    }

    /** message entrance/exit motion: rises 10dp while fading in, continues up while fading out */
    fun messageOffsetDp(t: Long): Float {
        if (messageIndex(t) < 0) return 0f
        val local = t % SLOT_MS
        return when {
            local < FADE_IN_MS -> 10f * (1f - smooth(local / FADE_IN_MS.toFloat()))
            local > SLOT_MS - FADE_OUT_MS -> -8f * smooth((local - (SLOT_MS - FADE_OUT_MS)) / FADE_OUT_MS.toFloat())
            else -> 0f
        }
    }

    /** subtle scale: 0.94 → 1.0 on entrance */
    fun messageScale(t: Long): Float {
        if (messageIndex(t) < 0) return 1f
        val local = t % SLOT_MS
        return if (local < FADE_IN_MS) 0.94f + 0.06f * smooth(local / FADE_IN_MS.toFloat()) else 1f
    }

    // ---------------- Dial (rotates around the FIXED arrow) ----------------
    /** Dial start offset for the opening sweep (deg). */
    const val DIAL_INTRO_OFFSET = 120f
    /** Smoothing time constant for the dial (s): fast yet silky. */
    const val DIAL_TAU_S = 0.28f

    /**
     * Target dial rotation. With a live GPS bearing (course over ground / getBearing) the dial is
     * rotated by −bearing so "N" points to true north relative to the direction of travel
     * (arrow = travel direction). Without a GPS bearing there is NO heading: dial rests north-up.
     * GPS only – no compass / sensors.
     */
    fun dialTarget(gpsBearing: Float?): Float = if (gpsBearing == null) 0f else norm180(-gpsBearing)

    /** Shortest-path circular smoothing step (frame-rate independent). */
    fun dialStep(current: Float, target: Float, dtS: Float): Float {
        val k = 1f - kotlin.math.exp(-dtS / DIAL_TAU_S)
        return current + norm180(target - current) * k
    }

    fun norm180(a: Float): Float { var x = a % 360f; if (x > 180f) x -= 360f; if (x <= -180f) x += 360f; return x }

    /** arrow rotation (deg): two elegant eased revolutions over 4 s – glides, never a mechanical spinner */
    fun arrowRotation(t: Long): Float {
        val rev = 2000f
        val k = (t.coerceIn(0, TOTAL_MS) / rev)
        val whole = kotlin.math.floor(k)
        return (whole + smooth(k - whole)) * 360f
    }

    private fun smooth(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }
}
