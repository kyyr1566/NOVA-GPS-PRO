package com.nova.gpspro

import com.nova.gpspro.data.Destination
import com.nova.gpspro.navigation.GeoMath
import com.nova.gpspro.ui.RadarCalloutLayout
import com.nova.gpspro.ui.RadarPage
import com.nova.gpspro.ui.RadarPlot
import com.nova.gpspro.ui.RadarTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure geometry, range filtering, live GPS movement, and callout placement tests for the radar.
 * Everything here is deterministic: positions come only from real distance/bearing values.
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
        assertEquals(RadarPlot.Band.FAR, RadarPlot.band(400.0, 500.0))
        assertEquals(RadarPlot.Band.NEAR, RadarPlot.band(400.0, 2000.0))
    }

    @Test fun singleTargetKeepsTrueBearingAndRadius() {
        val p = plan(t("a", 500.0, 90f))
        assertEquals(1, p.size)
        assertEquals(90f, p[0].bearing, 1e-3f)
        assertEquals(60f, p[0].radius, 1e-3f)
        assertEquals(RadarPlot.Band.MEDIUM, p[0].band)
    }

    @Test fun coincidentTargetsSpreadRingsOnTheExactBearing() {
        val p = plan(t("a", 500.0, 0f), t("b", 502.0, 0f))
        assertEquals(2, p.size)
        assertTrue(p.all { abs(it.bearing) < 1e-3f })
        assertTrue(abs(p[0].radius - p[1].radius) >= SEP - 1e-3f)
        assertTrue(p.all { it.radius in INNER..OUTER })
    }

    @Test fun nearestTargetKeepsItsExactRadius() {
        val p = plan(t("far", 300.0, 45f), t("near", 295.0, 45f))
        val near = p.first { it.target.id == "near" }
        val far = p.first { it.target.id == "far" }
        assertEquals(43.6f, near.radius, 1e-3f)
        assertTrue(abs(far.radius - near.radius) >= SEP - 1e-3f)
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
        val targets = (0 until 20).map { t("t$it", 700.0, 137f) }
        val p = RadarPlot.plan(targets, RANGE, INNER, OUTER, SEP, STEP)
        assertEquals(20, p.size)
        assertTrue(p.all { it.radius in INNER..OUTER })
        assertTrue(p.all { abs(it.bearing - 137f) <= RadarPlot.DEFAULT_MAX_ANGULAR_DEG + 1e-3f })
        for (i in p.indices) for (j in i + 1 until p.size) assertTrue(distanceMm(p[i], p[j]) > 1e-3)
    }

    @Test fun fourQuadrantsLandOnTheirTrueSides() {
        val p = plan(t("n", 500.0, 0f), t("e", 500.0, 90f), t("s", 500.0, 180f), t("w", 500.0, 270f))
        fun x(m: RadarPlot.Plotted) = m.radius * cos(Math.toRadians((m.bearing - 90f).toDouble()))
        fun y(m: RadarPlot.Plotted) = m.radius * sin(Math.toRadians((m.bearing - 90f).toDouble()))
        val n = p.first { it.target.id == "n" }; val e = p.first { it.target.id == "e" }
        val s = p.first { it.target.id == "s" }; val w = p.first { it.target.id == "w" }
        assertTrue(y(n) < 0 && abs(x(n)) < 1e-3)
        assertTrue(x(e) > 0 && abs(y(e)) < 1e-3)
        assertTrue(y(s) > 0 && abs(x(s)) < 1e-3)
        assertTrue(x(w) < 0 && abs(y(w)) < 1e-3)
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

    /** Pure spherical geodesic helper for JVM unit tests (Android runtime uses Location.distanceBetween). */
    private fun jvmGeodesic(lat1: Double, lon1: Double, lat2: Double, lon2: Double): GeoMath.Result {
        val rEarth = 6_371_000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        val brg = GeoMath.norm360(Math.toDegrees(atan2(y, x)).toFloat())
        return GeoMath.Result(rEarth * c, brg)
    }

    @Test fun rangeFilter_5km_showsOnlyLocationsWithin5km() {
        val userLat = 0.0
        val userLon = 0.0
        val destinations = listOf(
            Destination("d_800m", "Point A", 0.0072, 0.0, createdAt = 1L),   // ~800 m (inside 5 km)
            Destination("d_3200m", "Point B", 0.0, 0.0287, createdAt = 2L),  // ~3,191 m (inside 5 km)
            Destination("d_4900m", "Point C", -0.0440, 0.0, createdAt = 3L), // ~4,892 m (inside 5 km)
            Destination("d_5400m", "Point D", 0.0490, 0.0, createdAt = 4L),  // ~5,449 m (OUTSIDE 5 km)
            Destination("d_15km", "Point E", 0.1350, 0.0, createdAt = 5L)    // ~15.0 km (OUTSIDE 5 km)
        )

        val shown5km = RadarPage.filterTargetsInRange(userLat, userLon, destinations, 5_000.0, ::jvmGeodesic)
        val ids = shown5km.map { it.id }.toSet()
        assertEquals(setOf("d_800m", "d_3200m", "d_4900m"), ids)
        assertTrue(shown5km.all { it.distanceM <= 5_000.0 })

        // Narrowing range to 1 km leaves only d_800m
        val shown1km = RadarPage.filterTargetsInRange(userLat, userLon, destinations, 1_000.0, ::jvmGeodesic)
        assertEquals(listOf("d_800m"), shown1km.map { it.id })
    }

    @Test fun liveGpsMovement_updatesDistancesAndRadiiDirectly() {
        val dest = listOf(Destination("spot", "Saved Destination", 0.0200, 0.0, createdAt = 1L))
        val t1 = RadarPage.filterTargetsInRange(0.0000, 0.0, dest, 5_000.0, ::jvmGeodesic).single()
        val t2 = RadarPage.filterTargetsInRange(0.0100, 0.0, dest, 5_000.0, ::jvmGeodesic).single()

        assertTrue("Moving closer must reduce real distance", t2.distanceM < t1.distanceM)
        assertEquals(0f, t1.bearing, 0.5f)
        assertEquals(0f, t2.bearing, 0.5f)

        val p1 = RadarPlot.plan(listOf(t1), 5_000.0, INNER, OUTER, SEP, STEP).single()
        val p2 = RadarPlot.plan(listOf(t2), 5_000.0, INNER, OUTER, SEP, STEP).single()
        assertTrue("Closer GPS fix must pull marker closer to user centre", p2.radius < p1.radius)
    }

    @Test fun calloutPlacement_staysBesideDotAndInsideViewBounds() {
        val vw = 340f
        val vh = 340f
        val cx = 170f
        val cy = 170f
        val cw = 110f
        val ch = 42f
        val gap = 10f
        val margin = 6f

        // Dot in left half -> callout placed to the right of the dot
        val bLeft = RadarCalloutLayout.place(vw, vh, cx, cy, 90f, 120f, cw, ch, gap, margin)
        assertTrue("Callout should appear to the right of left-half dot", bLeft.left >= 90f + gap - 1e-3f)
        assertTrue(bLeft.left >= margin && bLeft.right <= vw - margin)
        assertTrue(bLeft.top >= margin && bLeft.bottom <= vh - margin)

        // Dot in right half -> callout placed to the left of the dot
        val bRight = RadarCalloutLayout.place(vw, vh, cx, cy, 260f, 210f, cw, ch, gap, margin)
        assertTrue("Callout should appear to the left of right-half dot", bRight.right <= 260f - gap + 1e-3f)
        assertTrue(bRight.left >= margin && bRight.right <= vw - margin)
        assertTrue(bRight.top >= margin && bRight.bottom <= vh - margin)

        // Dot near extreme edge -> clamped safely inside bounds
        val bEdge = RadarCalloutLayout.place(vw, vh, cx, cy, 332f, 12f, cw, ch, gap, margin)
        assertTrue(bEdge.left >= margin && bEdge.right <= vw - margin)
        assertTrue(bEdge.top >= margin && bEdge.bottom <= vh - margin)
    }

    @Test fun radarDesignSourceGuards_redGreenDotsAndSideCalloutInsteadOfBottomName() {
        var dir: File? = File("").absoluteFile
        var mainDir: File? = null
        while (dir != null) {
            if (File(dir, "src/main").isDirectory) { mainDir = File(dir, "src/main"); break }
            if (File(dir, "app/src/main").isDirectory) { mainDir = File(dir, "app/src/main"); break }
            dir = dir.parentFile
        }
        assumeTrue("src/main not reachable", mainDir != null)

        val radarViewSrc = File(mainDir!!, "java/com/nova/gpspro/ui/RadarView.kt").readText()
        assertTrue("RadarView must define normal red dot colour", radarViewSrc.contains("dotNormalRed"))
        assertTrue("RadarView must define selected green dot colour", radarViewSrc.contains("dotSelectedGreen"))
        assertTrue("RadarView must render compact callout beside selected dot", radarViewSrc.contains("drawSelectedCallout"))

        val radarPageSrc = File(mainDir, "java/com/nova/gpspro/ui/RadarPage.kt").readText()
        assertFalse("Bottom strip must not display selected.name below radar", radarPageSrc.contains("selected.name"))
        assertFalse("Bottom strip must not display nearest.name below radar", radarPageSrc.contains("nearest.name"))
    }

    companion object {
        private const val RANGE = 1000.0
        private const val INNER = 20f
        private const val OUTER = 100f
        private const val SEP = 14f
        private const val STEP = 15f
    }
}
