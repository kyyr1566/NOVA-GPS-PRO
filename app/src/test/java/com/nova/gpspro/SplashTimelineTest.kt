package com.nova.gpspro

import com.nova.gpspro.ui.SplashTimeline as T
import org.junit.Assert.*
import org.junit.Test

class SplashTimelineTest {
    @Test fun totalIsExactly4s() {
        assertEquals(4000L, T.TOTAL_MS)
        assertEquals(1f, T.screenAlpha(3799), 0f)
        assertEquals(0f, T.screenAlpha(4000), 0f)
        assertEquals(-1, T.messageIndex(4000))
    }

    @Test fun eachMessageInItsSecond() {
        assertEquals(0, T.messageIndex(0)); assertEquals(0, T.messageIndex(999))
        assertEquals(1, T.messageIndex(1000)); assertEquals(1, T.messageIndex(1999))
        assertEquals(2, T.messageIndex(2000)); assertEquals(2, T.messageIndex(2999))
        assertEquals(3, T.messageIndex(3000)); assertEquals(3, T.messageIndex(3999))
    }

    @Test fun allFourReadable_noOverlap() {
        val fullyVisibleMs = IntArray(4)
        var prevIdx = -1
        for (t in 0L until 4000L) {
            val i = T.messageIndex(t); val a = T.messageAlpha(t)
            if (a >= 0.999f && T.screenAlpha(t) >= 0.999f) fullyVisibleMs[i]++
            if (i != prevIdx && prevIdx >= 0) {
                // at every switch the previous message is invisible and the new one starts invisible
                assertEquals("prev not gone at $t", 0f, T.messageAlpha(t - 1), 0.02f)
                assertEquals("new not from 0 at $t", 0f, a, 0.02f)
            }
            prevIdx = i
        }
        println("fully visible ms per message: ${fullyVisibleMs.toList()}")
        for (i in 0..2) assertTrue("message $i too short", fullyVisibleMs[i] >= 600)
        assertTrue("message 3 too short", fullyVisibleMs[3] >= 550)
    }

    @Test fun arrowRotatesSmoothly_twoRevolutions() {
        assertEquals(0f, T.arrowRotation(0), 0.01f)
        assertEquals(360f, T.arrowRotation(2000), 0.01f)
        assertEquals(720f, T.arrowRotation(4000), 0.01f)
        var prev = 0f
        for (t in 1L..4000L) { val r = T.arrowRotation(t); assertTrue(r >= prev); assertTrue(r - prev < 1.5f); prev = r }
    }

    @Test fun dial_followsGpsBearing_shortestPath_andRestsWithoutBearing() {
        assertEquals(0f, T.dialTarget(null), 0f)                 // no GPS bearing → north-up, no invented heading
        assertEquals(-90f, T.dialTarget(90f), 0.01f)             // travelling east → N drawn on the left
        assertEquals(10f, T.dialTarget(350f), 0.01f)
        // opening sweep settles smoothly within ~1.3 s at 60 fps, never overshoots
        var d = T.DIAL_INTRO_OFFSET; var prev = d
        for (f in 0 until 80) { d = T.dialStep(d, 0f, 1 / 60f); assertTrue(d <= prev + 1e-4f); prev = d }
        assertTrue("dial did not settle: $d", kotlin.math.abs(d) < 2f)
        // crossing ±180: rotates the short way (170 → -170 is +20°, not -340°)
        val step = T.dialStep(170f, -170f, 1 / 60f)
        assertTrue(step > 170f)
        // responsive: reaches 63 % of a GPS change in 0.28 s
        var x = 0f; repeat(17) { x = T.dialStep(x, -60f, 1 / 60f) }
        assertTrue(x < -35f)
    }

    @Test fun messageMotion_fadeScaleSlide_inAndOut() {
        assertEquals(0.94f, T.messageScale(1000), 0.001f); assertEquals(1f, T.messageScale(1500), 0f)
        assertEquals(10f, T.messageOffsetDp(2000), 0.01f); assertEquals(0f, T.messageOffsetDp(2500), 0f)
        assertTrue(T.messageOffsetDp(2990) < -6f)
    }
}
