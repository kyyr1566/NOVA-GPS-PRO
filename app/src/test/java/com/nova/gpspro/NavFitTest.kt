package com.nova.gpspro

import com.nova.gpspro.ui.NavFit
import org.junit.Assert.*
import org.junit.Test

/** Units are dp-like px. fixed ≈ selector+info+stats+controls, gaps = 36, speedo = 180. */
class NavFitTest {
    private fun r(avail: Int) = NavFit.solve(avail, 250, 36, 5, 180, 260, 130, 80, 120)
    private fun total(x: NavFit.Result) = 250 + (36 * x.gapScale).toInt() + x.extraPerGap * 5 + x.arrow + x.speedo

    @Test fun alwaysFitsAndSpeedoKeptOnPhones() {
        for (avail in 560..1000 step 10) {
            val x = r(avail)
            assertTrue("overflow at $avail", total(x) <= avail)
            assertEquals("speedo shrunk at $avail", 180, x.speedo)
        }
    }
    @Test fun arrowShrinksBeforeGaps() {
        val x = r(620)                    // 620-250-36-180 = 154 ≥ 130 → only the arrow shrinks
        assertEquals(154, x.arrow); assertEquals(1f, x.gapScale, 0f)
        val y = r(580)                    // arrow at min, gaps compressed
        assertEquals(130, y.arrow); assertTrue(y.gapScale < 1f && y.gapScale >= 0.35f)
    }
    @Test fun tallScreenCapsArrow() { val x = r(1000); assertEquals(260, x.arrow); assertTrue(x.extraPerGap > 0) }
    @Test fun tinyLastResort() { val x = r(480); assertTrue(total(x) <= 480); assertTrue(x.speedo >= 120) }
}
