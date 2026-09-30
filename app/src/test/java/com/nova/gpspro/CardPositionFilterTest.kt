package com.nova.gpspro

import com.nova.gpspro.ui.CardPositionFilter
import org.junit.Assert.*
import org.junit.Test

class CardPositionFilterTest {
    private val lat0 = 32.0; private val lon0 = 44.3
    private val mPerDegLat = 111_320.0
    private fun d(f: CardPositionFilter) = CardPositionFilter.approxM(f.lat, f.lon, lat0, lon0)

    @Test fun startsNearZeroAtSavedSpot() {
        val f = CardPositionFilter()
        // first unstable fix (80 m accuracy, 60 m off) must be ignored
        f.update(lat0 + 60 / mPerDegLat, lon0, 80f, usable = false, nowMs = 0)
        assertFalse(f.hasFix)
        // good fixes jittering ±2 m around the saved point
        var t = 1000L
        for (j in listOf(1.5, -2.0, 0.8, -1.0, 2.0, -0.5)) { f.update(lat0 + j / mPerDegLat, lon0, 5f, true, t); t += 1000 }
        assertTrue(d(f) < 2.0)
    }

    @Test fun singleSpikeRejected() {
        val f = CardPositionFilter(); f.update(lat0, lon0, 5f, true, 0)
        f.update(lat0 + 900 / mPerDegLat, lon0, 8f, true, 1000)   // 900 m in 1 s
        assertTrue(d(f) < 0.5)
    }

    @Test fun realWalkRisesGradually() {
        val f = CardPositionFilter(); f.update(lat0, lon0, 4f, true, 0)
        var prev = 0.0
        for (s in 1..30) {                                         // 1.4 m/s walk
            f.update(lat0 + 1.4 * s / mPerDegLat, lon0, 4f, true, s * 1000L)
            val now = d(f)
            assertTrue("not monotone at $s", now >= prev - 1e-6)
            assertTrue("jumped at $s", now - prev < 3.0)
            prev = now
        }
        assertTrue(prev > 35 && prev <= 42.0)                      // follows the real 42 m with a small lag
    }

    @Test fun relocationAcceptedAfterConsistentFixes() {
        val f = CardPositionFilter(); f.update(lat0, lon0, 5f, true, 0)
        for (i in 1..3) f.update(lat0 + 5000 / mPerDegLat, lon0, 5f, true, i * 1000L)
        assertTrue(CardPositionFilter.approxM(f.lat, f.lon, lat0 + 5000 / mPerDegLat, lon0) < 1.0)
    }

    @Test fun fallbackWhenAccuracyNeverGood() {
        val f = CardPositionFilter()
        f.update(lat0, lon0, 30f, true, 0); assertFalse(f.hasFix)
        f.update(lat0, lon0, 30f, true, 6000); assertTrue(f.hasFix)
    }
}
