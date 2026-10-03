package com.nova.gpspro

import com.nova.gpspro.location.GpsMotionFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class GpsMotionFilterTest {
    private val lat0 = 32.0
    private val lon0 = 44.3
    private val metersPerLonDegree = 111_320.0 * cos(Math.toRadians(lat0))
    private fun fix(eastM: Double, t: Long, speed: Double? = null, speedAccuracy: Float? = null) =
        GpsMotionFilter.Fix(lat0, lon0 + eastM / metersPerLonDegree, 5f, t, speed, speedAccuracy)

    @Test fun stationaryThreeFiveAndEightMeterGpsJitterDoesNotMoveReference() {
        val filter = GpsMotionFilter()
        val first = filter.update(fix(0.0, 1_000L))
        var state = first
        var t = 2_000L
        for (offset in listOf(3.0, -5.0, 8.0, -4.0, 7.0, -1.0, 5.0, 0.5)) {
            state = filter.update(fix(offset, t))
            t += 1_000L
            assertFalse("stationary jitter was classified as movement", state.isMoving)
            assertEquals(first.longitude, state.longitude, 1e-10)
        }
        assertEquals(0.0, state.latitude - first.latitude, 1e-10)
    }

    @Test fun oneLargeButUncorroboratedFixIsNotMovement() {
        val filter = GpsMotionFilter()
        filter.update(fix(0.0, 1_000L))
        assertFalse(filter.update(fix(25.0, 2_000L)).isMoving)
        assertFalse(filter.update(fix(0.5, 3_000L)).isMoving)
    }

    @Test fun sustainedWalkingConfirmsAndThenFollowsRealMovement() {
        val filter = GpsMotionFilter()
        filter.update(fix(0.0, 1_000L))
        var state = filter.snapshot()
        for (second in 1..35) {
            state = filter.update(fix(second * 0.9, 1_000L + second * 1_000L))
        }
        assertTrue("sustained outward walking fixes should confirm motion", state.isMoving)
        val followed = (state.longitude - lon0) * metersPerLonDegree
        assertTrue("reference should follow real movement after confirmation: $followed", followed > 22.0)
        assertTrue(state.movementStartedAtElapsedRealtimeMs > 1_000L)
        assertTrue(state.segmentId > 0)
    }

    @Test fun reportedGpsSpeedConfirmsMotionAndDebouncedStop() {
        val filter = GpsMotionFilter()
        filter.update(fix(0.0, 1_000L, speed = 0.0, speedAccuracy = 0.3f))
        filter.update(fix(5.0, 2_000L, speed = 4.0, speedAccuracy = 0.5f))
        var state = filter.update(fix(10.0, 3_000L, speed = 4.0, speedAccuracy = 0.5f))
        assertFalse(state.isMoving)
        state = filter.update(fix(15.0, 4_000L, speed = 4.0, speedAccuracy = 0.5f))
        assertTrue(state.isMoving)
        state = filter.update(fix(20.0, 5_000L, speed = 4.0, speedAccuracy = 0.5f))
        assertTrue(state.isMoving)
        for (second in 6..16) state = filter.update(fix(20.0, second * 1_000L, speed = 0.0, speedAccuracy = 0.3f))
        assertFalse(state.isMoving)
        assertTrue("stop event carries its debounced monotonic time", state.movementEndedAtElapsedRealtimeMs > 0L)
    }
}
