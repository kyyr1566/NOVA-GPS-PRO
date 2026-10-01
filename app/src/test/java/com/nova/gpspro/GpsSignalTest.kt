package com.nova.gpspro

import com.nova.gpspro.location.GpsState
import com.nova.gpspro.location.GpsStatus
import com.nova.gpspro.ui.GpsSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure logic of the Home-card indicators: GPS signal bars (📶) and the location pulse. */
class GpsSignalTest {

    private fun fix(sats: Int, acc: Float, status: GpsStatus) =
        GpsSignal.level(status, sats, acc, hasFix = true)

    // ------------------------------------------------------------------ signal bars

    @Test fun noFixMeansNoBars() {
        val noFixStatuses = listOf(
            GpsStatus.NO_GPS_PERMISSION, GpsStatus.GPS_DISABLED,
            GpsStatus.SEARCHING, GpsStatus.GPS_LOST
        )
        for (s in noFixStatuses) {
            assertEquals(GpsSignal.Level.NONE, fix(12, 3f, s))
            assertEquals(GpsSignal.Level.NONE, GpsSignal.level(GpsStatus.GPS_CONNECTED, 12, 3f, hasFix = false))
        }
        assertEquals(0, GpsSignal.Level.NONE.bars)
    }

    @Test fun excellentWhenAccuracyAndSatellitesAreHigh() {
        assertEquals(GpsSignal.Level.EXCELLENT, fix(12, 5f, GpsStatus.GPS_CONNECTED))
        assertEquals(4, GpsSignal.Level.EXCELLENT.bars)
    }

    @Test fun goodWhenBothAreModerateHigh() {
        assertEquals(GpsSignal.Level.GOOD, fix(7, 12f, GpsStatus.GPS_CONNECTED))
        assertEquals(3, GpsSignal.Level.GOOD.bars)
    }

    @Test fun mediumWhenBothAreCoarse() {
        assertEquals(GpsSignal.Level.MEDIUM, fix(4, 22f, GpsStatus.GPS_CONNECTED))
        assertEquals(2, GpsSignal.Level.MEDIUM.bars)
    }

    @Test fun weakWhenAccuracyOrSatellitesArePoor() {
        assertEquals(GpsSignal.Level.WEAK, fix(2, 5f, GpsStatus.GPS_CONNECTED))    // few satellites
        assertEquals(GpsSignal.Level.WEAK, fix(9, 40f, GpsStatus.GPS_CONNECTED))   // coarse accuracy
        assertEquals(GpsSignal.Level.WEAK, fix(0, 0f, GpsStatus.GPS_CONNECTED))    // nothing reported
        assertEquals(1, GpsSignal.Level.WEAK.bars)
    }

    @Test fun weakestSideWins() {
        // excellent accuracy but only 6 satellites used → never more than GOOD
        assertEquals(GpsSignal.Level.GOOD, fix(6, 4f, GpsStatus.GPS_CONNECTED))
        // many satellites but coarse accuracy → never more than MEDIUM
        assertEquals(GpsSignal.Level.MEDIUM, fix(14, 20f, GpsStatus.GPS_CONNECTED))
    }

    @Test fun engineWeakAccuracyNeverShowsBetterThanMedium() {
        // network fallback / accuracy above the weak threshold: the engine says WEAK_ACCURACY
        assertEquals(GpsSignal.Level.MEDIUM, fix(14, 2f, GpsStatus.WEAK_ACCURACY))
        assertEquals(GpsSignal.Level.WEAK, fix(3, 60f, GpsStatus.WEAK_ACCURACY))
    }

    @Test fun stateOverloadUsesRealFields() {
        val connected = GpsState(gpsStatus = GpsStatus.GPS_CONNECTED, satellitesUsed = 11, accuracy = 4f)
        assertEquals(GpsSignal.Level.NONE, GpsSignal.level(connected))   // no location → no fix at all
        val lost = GpsState(gpsStatus = GpsStatus.GPS_LOST, satellitesUsed = 11, accuracy = 4f)
        assertEquals(GpsSignal.Level.NONE, GpsSignal.level(lost))
    }

    // ------------------------------------------------------------------ location pulse

    @Test fun pulseRunsOnlyWithALiveFix() {
        assertTrue(GpsSignal.liveFix(GpsStatus.GPS_CONNECTED, hasLocation = true))
        assertTrue(GpsSignal.liveFix(GpsStatus.WEAK_ACCURACY, hasLocation = true))
        assertFalse(GpsSignal.liveFix(GpsStatus.SEARCHING, hasLocation = true))
        assertFalse(GpsSignal.liveFix(GpsStatus.GPS_LOST, hasLocation = true))
        assertFalse(GpsSignal.liveFix(GpsStatus.GPS_DISABLED, hasLocation = true))
        assertFalse(GpsSignal.liveFix(GpsStatus.NO_GPS_PERMISSION, hasLocation = true))
        assertFalse(GpsSignal.liveFix(GpsStatus.GPS_CONNECTED, hasLocation = false))
    }

    @Test fun pulseStopsForEveryLostState() {
        val lost = listOf(
            GpsStatus.NO_GPS_PERMISSION, GpsStatus.GPS_DISABLED,
            GpsStatus.SEARCHING, GpsStatus.GPS_LOST
        )
        for (s in lost) assertFalse("pulse must stop for $s", GpsSignal.liveFix(GpsState(gpsStatus = s)))
    }
}
