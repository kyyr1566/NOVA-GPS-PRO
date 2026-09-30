package com.nova.gpspro

import com.nova.gpspro.ui.Fit
import org.junit.Assert.assertEquals
import org.junit.Test

/** Simulates a page: natural height = fixed + gaps·ratio + emblem(112..200dp). */
class FitTest {
    private fun height(f: Float) = 420 + 150 * Fit.ratio(f, 0.35f) + Fit.lerp(112, 200, f)

    @Test fun tallScreenKeepsOriginalDesign() = assertEquals(1f, Fit.solve { height(it) <= 800 }, 0f)
    @Test fun shortScreenCompressesUntilItFits() {
        for (avail in listOf(600, 640, 700, 760)) {
            val f = Fit.solve { height(it) <= avail }
            assert(height(f) <= avail) { "overflow at $avail" }
            assert(f == 1f || height(f + 0.02f) > avail - 3) { "not using space at $avail" }
        }
    }
    @Test fun tinyScreenFallsBackToMostCompact() = assertEquals(0f, Fit.solve { height(it) <= 500 }, 0f)
    @Test fun helpers() { assertEquals(0.35f, Fit.ratio(0f, 0.35f), 1e-6f); assertEquals(200, Fit.lerp(112, 200, 1f)) }
}
