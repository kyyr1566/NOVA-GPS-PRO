package com.nova.gpspro

import com.nova.gpspro.ui.RadarMetricHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarMetricHistoryTest {

    @Test fun storesOnlySamplesThatWereActuallyProvided() {
        val history = RadarMetricHistory(windowMs = 10_000L)
        assertTrue(history.append(1_000L, 28.5))
        assertTrue(history.append(4_000L, 0.0)) // zero is a valid real used-satellite count
        assertEquals(listOf(28.5, 0.0), history.visibleAt(4_000L).map { it.value })
        assertEquals(listOf(1_000L, 4_000L), history.visibleAt(4_000L).map { it.elapsedRealtimeMs })
    }

    @Test fun rejectsInvalidAndOutOfOrderReadingsWithoutFillingGaps() {
        val history = RadarMetricHistory(windowMs = 60_000L)
        assertTrue(history.append(1_000L, 12.0))
        assertFalse(history.append(2_000L, Double.NaN))
        assertFalse(history.append(500L, 13.0))
        assertEquals(listOf(1_000L), history.visibleAt(2_000L).map { it.elapsedRealtimeMs })
    }

    @Test fun removesSamplesOutsideItsRollingWindow() {
        val history = RadarMetricHistory(windowMs = 5_000L)
        history.append(1_000L, 4.0)
        history.append(3_000L, 7.0)
        history.append(7_000L, 9.0)
        assertEquals(listOf(3_000L, 7_000L), history.visibleAt(7_000L).map { it.elapsedRealtimeMs })
        assertEquals(listOf(7_000L), history.visibleAt(8_001L).map { it.elapsedRealtimeMs })
    }

    @Test fun keepsEqualTimestampMeasurementsInsteadOfInventingOrReplacingOne() {
        val history = RadarMetricHistory(windowMs = 60_000L)
        history.append(2_000L, 31.0)
        history.append(2_000L, 34.0)
        assertEquals(listOf(31.0, 34.0), history.visibleAt(2_000L).map { it.value })
    }

    @Test fun boundsTheNumberOfStoredMeasurements() {
        val history = RadarMetricHistory(windowMs = 100_000L, maxSamples = 3)
        (1L..4L).forEach { history.append(it * 1_000L, it.toDouble()) }
        assertEquals(listOf(2.0, 3.0, 4.0), history.visibleAt(4_000L).map { it.value })
    }
}
