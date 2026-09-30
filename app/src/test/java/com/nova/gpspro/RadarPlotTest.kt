package com.nova.gpspro

import com.nova.gpspro.ui.RadarPlot
import com.nova.gpspro.ui.RadarTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure geometry tests for the radar marker placement. Everything here is deterministic:
 * positions come only from real distance/bearing values, never from randomness.
 */
class RadarPlotTest {

    private fun t(id: String, dist: Double, bearing: Float) = RadarTarget(id, id, dist, bearing)

    private fun plan(vararg targets: RadarTarget) =
        RadarPlot.plan(targets.toList(), RANGE, INNER, OUTER, SEP, STEP)

    private fun distanceMm(a: RadarPlot.Plotted, b: RadarPlot.Plotted): Double {
        val ar = Math.toRadians(a.bearing.toDouble()); val br = Math.toRadians(b.bearing.toDouble())
        val dx = a.radius * cos(ar) - b.radius * cos(br)
        val dy = a.radius * sin(ar) - b.radius * sin(br)
        return sqrt(dx * dx + dy * dy)
    }

    @Test fun band_followsRealDistanceNotRandomness() {
        assertEquals(RadarPlot.Band.NEAR, RadarPlot.band(0.0, 1000.0))
        assertEquals(RadarPlot.Band.NEAR, RadarPlot.band(399.9, 1000.0))
        assertEquals(RadarPlot.Band.MEDIUM, RadarPlot.band(400.0, 1000.0))
        assertEquals(RadarPlot.Band.MEDIUM, RadarPlot.band(749.9, 1000.0))
        assertEquals(RadarPlot.Band.FAR, RadarPlot.band(750.0, 1000.0))
        assertEquals(RadarPlot.Band.FAR, RadarPlot.band(1000.0, 1000.0))
        // the very same physical distance changes band only because the selected range changed
        assertEquals(RadarPlot.Band.FAR, RadarPlot.band(400.0, 500.0))
        assertEquals(RadarPlot.Band.NEAR, RadarPlot.band(400.0, 2000.0))
    }

    @Test fun singleTargetKeepsTrueBearingAndRadius() {
        val p = plan(t("a", 500.0, 90f))
        assertEquals(1, p.size)
        assertEquals(90f, p[0].bearing, 1e-3f)          // exact true bearing
        assertEquals(60f, p[0].radius, 1e-3f)           // INNER + (OUTER-INNER) * 500/1000
        assertEquals(RadarPlot.Band.MEDIUM, p[0].band)  // 0.5 of the range
    }

    @Test fun coincidentTargetsSpreadRingsOnTheExactBearing() {
        val p = plan(t("a", 500.0, 0f), t("b", 502.0, 0f))
        assertEquals(2, p.size)
        assertTrue(p.all { abs(it.bearing) < 1e-3f })               // direction preserved exactly
        assertTrue(abs(p[0].radius - p[1].radius) >= SEP - 1e-3f)   // separated on different rings
        assertTrue(p.all { it.radius in INNER..OUTER })
    }

    @Test fun nearestTargetKeepsItsExactRadius() {
        val p = plan(t("far", 300.0, 45f), t("near", 295.0, 45f))
        val near = p.first { it.target.id == "near" }
        val far = p.first { it.target.id == "far" }
        assertEquals(43.6f, near.radius, 1e-3f)                     // 20 + 80 * 0.295 — untouched
        assertTrue(abs(far.radius - near.radius) >= SEP - 1e-3f)    // the farther one yields
        assertEquals(45f, near.bearing, 1e-3f)
        assertEquals(45f, far.bearing, 1e-3f)
    }

    @Test fun zeroDistanceStaysClearOfTheCentreMarker() {
        val p = plan(t("here", 0.0, 123f))
        assertTrue(p[0].radius >= INNER - 1e-3f)
    }

    @Test fun targetAtRangeEdgeStaysInsideTheDisc() {
        val p = plan(t("edge", RANGE, 250f))
        assertTrue(p[0].radius <= OUTER + 1e-3f)
    }

    @Test fun crowdedFieldNeverLeavesBoundsOrInventsDirection() {
        val targets = (0 until 20).map { t("t$it", 700.0, 137f) }   // 20 destinations, same spot
        val p = RadarPlot.plan(targets, RANGE, INNER, OUTER, SEP, STEP)
        assertEquals(20, p.size)                                    // nothing dropped
        assertTrue(p.all { it.radius in INNER..OUTER })             // never outside the range ring
        assertTrue(p.all { abs(it.bearing - 137f) <= RadarPlot.DEFAULT_MAX_ANGULAR_DEG + 1e-3f })
        // distinct positions for every marker (no two markers stacked on one pixel)
        for (i in p.indices) for (j in i + 1 until p.size) assertTrue(distanceMm(p[i], p[j]) > 1e-3)
    }

    @Test fun fourQuadrantsLandOnTheirTrueSides() {
        val p = plan(t("n", 500.0, 0f), t("e", 500.0, 90f), t("s", 500.0, 180f), t("w", 500.0, 270f))
        // screen frame: 0°=up, 90°=right, 180°=down, 270°=left (x right, y down)
        fun x(m: RadarPlot.Plotted) = m.radius * cos(Math.toRadians((m.bearing - 90f).toDouble()))
        fun y(m: RadarPlot.Plotted) = m.radius * sin(Math.toRadians((m.bearing - 90f).toDouble()))
        val n = p.first { it.target.id == "n" }; val e = p.first { it.target.id == "e" }
        val s = p.first { it.target.id == "s" }; val w = p.first { it.target.id == "w" }
        assertTrue(y(n) < 0 && abs(x(n)) < 1e-3)   // north marker above the centre
        assertTrue(x(e) > 0 && abs(y(e)) < 1e-3)   // east marker right of the centre
        assertTrue(y(s) > 0 && abs(x(s)) < 1e-3)   // south marker below the centre
        assertTrue(x(w) < 0 && abs(y(w)) < 1e-3)   // west marker left of the centre
        assertEquals(60f, n.radius, 1e-3f)
    }

    @Test fun planningIsOrderIndependent() {
        val a = listOf(t("a", 500.0, 10f), t("b", 480.0, 12f), t("c", 900.0, 200f), t("d", 50.0, 310f))
        val forward = RadarPlot.plan(a, RANGE, INNER, OUTER, SEP, STEP)
        val reversed = RadarPlot.plan(a.reversed(), RANGE, INNER, OUTER, SEP, STEP)
        assertEquals(forward.size, reversed.size)
        forward.sortedBy { it.target.id }.zip(reversed.sortedBy { it.target.id }).forEach { (f, r) ->
            assertEquals(f.target.id, r.target.id)
            assertEquals(f.radius, r.radius, 1e-4f)
            assertEquals(f.bearing, r.bearing, 1e-4f)
        }
    }

    companion object {
        private const val RANGE = 1000.0
        private const val INNER = 20f
        private const val OUTER = 100f
        private const val SEP = 14f
        private const val STEP = 15f
    }
}
