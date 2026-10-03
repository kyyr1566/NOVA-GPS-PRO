package com.nova.gpspro

import com.nova.gpspro.ui.RadarPlot
import com.nova.gpspro.ui.RadarTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Exact GPS-derived geometry: no random placement, collision nudges, or invented movement. */
class RadarPlotTest {

    private fun t(id: String, distance: Double, bearing: Float) = RadarTarget(id, id, distance, bearing)

    private fun plan(vararg targets: RadarTarget) =
        RadarPlot.plan(targets.toList(), RANGE, OUTER)

    @Test fun bandFollowsActualRangeFraction() {
        assertEquals(RadarPlot.Band.NEAR, RadarPlot.band(0.0, 1000.0))
        assertEquals(RadarPlot.Band.NEAR, RadarPlot.band(399.9, 1000.0))
        assertEquals(RadarPlot.Band.MEDIUM, RadarPlot.band(400.0, 1000.0))
        assertEquals(RadarPlot.Band.MEDIUM, RadarPlot.band(749.9, 1000.0))
        assertEquals(RadarPlot.Band.FAR, RadarPlot.band(750.0, 1000.0))
        assertEquals(RadarPlot.Band.FAR, RadarPlot.band(1000.0, 1000.0))
    }

    @Test fun positionUsesExactDistanceFractionAndTrueBearing() {
        val point = plan(t("east", 500.0, 90f)).single()
        assertEquals(50f, point.radius, 1e-4f)
        assertEquals(90f, point.bearing, 1e-4f)
        assertEquals(RadarPlot.Band.MEDIUM, point.band)
    }

    @Test fun negativeBearingIsNormalizedWithoutChangingDirection() {
        val point = plan(t("northwest", 250.0, -45f)).single()
        assertEquals(25f, point.radius, 1e-4f)
        assertEquals(315f, point.bearing, 1e-4f)
    }

    @Test fun samePlaceStaysAtTheGpsCentre() {
        val point = plan(t("here", 0.0, 123f)).single()
        assertEquals(0f, point.radius, 0f)
        assertEquals(123f, point.bearing, 1e-4f)
    }

    @Test fun destinationsOutsideTheChosenRangeAreNotPlotted() {
        val plotted = plan(t("inside", 999.0, 10f), t("edge", RANGE, 20f), t("outside", RANGE + 0.1, 30f))
        assertEquals(listOf("inside", "edge"), plotted.map { it.target.id })
        assertEquals(OUTER, plotted.last().radius, 1e-4f)
    }

    @Test fun overlappingDestinationsAreNotMovedAwayFromTheirRealPosition() {
        val points = plan(t("a", 500.0, 45f), t("b", 500.0, 45f))
        assertEquals(2, points.size)
        points.forEach {
            assertEquals(50f, it.radius, 1e-4f)
            assertEquals(45f, it.bearing, 1e-4f)
        }
    }

    @Test fun fourBearingsMapToTheirTrueScreenQuadrants() {
        val points = plan(t("n", 500.0, 0f), t("e", 500.0, 90f), t("s", 500.0, 180f), t("w", 500.0, 270f))
        fun x(bearing: Float, radius: Float) = radius * sin(Math.toRadians(bearing.toDouble()))
        fun y(bearing: Float, radius: Float) = -radius * cos(Math.toRadians(bearing.toDouble()))
        val n = points.first { it.target.id == "n" }
        val e = points.first { it.target.id == "e" }
        val s = points.first { it.target.id == "s" }
        val w = points.first { it.target.id == "w" }
        assertTrue(y(n.bearing, n.radius) < 0 && abs(x(n.bearing, n.radius)) < 1e-4)
        assertTrue(x(e.bearing, e.radius) > 0 && abs(y(e.bearing, e.radius)) < 1e-4)
        assertTrue(y(s.bearing, s.radius) > 0 && abs(x(s.bearing, s.radius)) < 1e-4)
        assertTrue(x(w.bearing, w.radius) < 0 && abs(y(w.bearing, w.radius)) < 1e-4)
    }

    @Test fun invalidCoordinatesAndRangesDoNotCreatePoints() {
        assertTrue(RadarPlot.plan(listOf(t("bad-distance", Double.NaN, 0f)), RANGE, OUTER).isEmpty())
        assertTrue(RadarPlot.plan(listOf(t("bad-bearing", 10.0, Float.NaN)), RANGE, OUTER).isEmpty())
        assertTrue(RadarPlot.plan(listOf(t("x", 10.0, 0f)), 0.0, OUTER).isEmpty())
    }

    companion object {
        private const val RANGE = 1000.0
        private const val OUTER = 100f
    }
}
