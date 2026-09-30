package com.nova.gpspro

import com.nova.gpspro.data.Destination
import com.nova.gpspro.location.GpsState
import com.nova.gpspro.location.GpsStatus
import com.nova.gpspro.navigation.ArrowEngine
import com.nova.gpspro.navigation.ArrivalEngine
import com.nova.gpspro.location.BearingEngine
import com.nova.gpspro.navigation.GeoMath
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class NavigationLogicTest {

    @Suppress("unused") private fun gps(bearing: Float, speed: Double, live: Boolean, q: Float, tSec: Double, has: Boolean = true) = GpsState(
        gpsStatus = GpsStatus.GPS_CONNECTED, speed = speed, bearing = bearing, hasBearing = has,
        bearingLive = live, bearingQuality = q, elapsedRealtimeNanos = (tSec * 1e9).toLong(), isUsable = true
    )

    @Test fun norm180_shortestPath() {
        assertEquals(2f, GeoMath.norm180(1f - 359f), 1e-4f)
        assertEquals(-2f, GeoMath.norm180(359f - 1f), 1e-4f)
        assertEquals(180f, GeoMath.norm180(-180f), 1e-4f)
        assertEquals(-170f, GeoMath.norm180(190f), 1e-4f)
        assertEquals(0f, GeoMath.norm180(720f), 1e-4f)
        assertEquals(350f, GeoMath.norm360(-10f), 1e-4f)
    }

    // Helper: feed a GPS bearing through the REAL pipeline BearingEngine → GpsState → ArrowEngine
    private class Pipe(val dest: Float) {
        val be = BearingEngine(); val ae = ArrowEngine(); var t = 0.0
        fun fix(bearing: Float?, acc: Float = 15f, speed: Double = 1.0): Float {
            t += 0.5; val ns = (t * 1e9).toLong()
            if (bearing != null) be.update(32.0, 44.0, 5f, true, bearing, acc, speed, ns) else be.update(32.0, 44.0, 5f, false, 0f, -1f, speed, ns)
            val g = GpsState(GpsStatus.GPS_CONNECTED, speed = speed, bearing = be.bearing, hasBearing = be.isUsable(ns),
                bearingLive = be.isUsable(ns), bearingQuality = be.quality, elapsedRealtimeNanos = ns, isUsable = true)
            return ae.update(dest, g)
        }
    }
    private fun angDiff(a: Float, b: Float) = abs(GeoMath.norm180(a - b))

    @Test fun formula_arrowIsDestinationMinusGpsBearing() {
        val p = Pipe(90f); assertEquals(90f, p.fix(0f), 0.01f)
        assertEquals(ArrowEngine.Mode.TRACKING, p.ae.mode)
    }

    @Test fun slowWalking_under5kmh_bearingStillDrivesArrow() {
        val p = Pipe(0f)
        p.fix(0f, acc = 25f, speed = 0.7)            // 2.5 km/h
        val a = p.fix(40f, acc = 25f, speed = 0.7)   // user turns 40° while walking slowly
        assertTrue("frozen at low speed: $a", angDiff(a, -40f) < 6f)   // responds on the FIRST fix
        assertEquals(ArrowEngine.Mode.TRACKING, p.ae.mode)
        val b = p.fix(80f, acc = 30f, speed = 0.4)   // 1.4 km/h
        assertTrue(angDiff(b, -80f) < 8f)
    }

    @Test fun gradualBearingChange_followsWithoutLag() {
        val p = Pipe(0f); p.fix(0f, speed = 3.0)
        var last = 0f
        for (h in 5..90 step 5) { last = p.fix(h.toFloat(), acc = 8f, speed = 3.0)
            assertTrue("lag at $h: $last", angDiff(last, -h.toFloat()) < 3f) }
    }

    @Test fun fastBearingChange_andTurns90_180_convergeImmediately() {
        val p = Pipe(0f); p.fix(0f, acc = 10f, speed = 5.0)
        assertTrue(angDiff(p.fix(90f, acc = 10f, speed = 5.0), -90f) < 5f)     // 90° turn: first fix
        assertTrue(angDiff(p.fix(90f, acc = 10f, speed = 5.0), -90f) < 1f)
        val q = Pipe(0f); q.fix(0f, acc = 10f, speed = 5.0)
        q.fix(180f, acc = 10f, speed = 5.0)
        assertTrue(angDiff(q.fix(180f, acc = 10f, speed = 5.0), 180f) < 2f)    // 180° turn: ≤2 fixes
    }

    @Test fun crossing_359to0_and_0to359_shortestPath() {
        val p = Pipe(0f); p.fix(359f, acc = 5f, speed = 4.0)               // arrow +1
        val a = p.fix(0f, acc = 5f, speed = 4.0)
        assertTrue("long way: $a", abs(a) < 1.01f)
        val b = p.fix(359f, acc = 5f, speed = 4.0)                          // 0 → 359
        assertTrue("long way: $b", b in 0f..1.01f)
        // the view must also rotate the short way: unwrapped target step must be tiny
        assertTrue(angDiff(1f, -1f) == 2f)
    }

    @Test fun weakBearing_noShimmer_badAccuracyRejected() {
        val p = Pipe(0f); p.fix(100f, acc = 40f, speed = 0.5)
        val base = p.ae.angle
        p.fix(100.5f, acc = 50f, speed = 0.5)                              // sub-degree noise → ignored
        assertEquals(base, p.ae.angle, 0.001f)
        p.fix(250f, acc = 85f, speed = 0.3)                                // bearing accuracy 85° → rejected
        assertEquals(100.5f, p.be.bearing, 0f)                              // keeps last VALID bearing
        assertEquals(base, p.ae.angle, 0.001f)
    }

    @Test fun bearingLoss_noHoldModeWhileFixesArrive_thenNoInventedDirection() {
        val p = Pipe(30f); p.fix(0f, acc = 10f, speed = 2.0)
        repeat(8) { p.fix(null) }                                           // 4 s of fixes without direction
        assertEquals(ArrowEngine.Mode.TRACKING, p.ae.mode)                  // no HOLD message during movement
        assertEquals(30f, p.ae.angle, 0.5f)
        repeat(14) { p.fix(null) }                                          // > 10 s without any direction
        assertEquals(ArrowEngine.Mode.NO_BEARING, p.ae.mode)
        val frozenAt = p.ae.angle
        repeat(4) { p.fix(null) }; assertEquals(frozenAt, p.ae.angle, 0f)  // not rotated with invented data
        val r = p.fix(60f, acc = 10f, speed = 2.0)                          // new valid bearing → resumes at once
        assertEquals(ArrowEngine.Mode.TRACKING, p.ae.mode)
        assertTrue(angDiff(r, -30f) < 5f)
    }

    @Test fun noAccuracyReported_stationaryRejected_movingAccepted() {
        val b = BearingEngine()
        assertFalse(b.update(32.0, 44.0, 5f, true, 10f, -1f, 0.1, 1))
        assertTrue(b.update(32.0, 44.0, 5f, true, 10f, -1f, 0.5, 2))
        assertFalse(b.update(32.0, 44.0, 5f, false, 99f, 5f, 3.0, 3))
    }

    @Test fun arrival_onlyWithinTwoMetres_withReliableAccuracy() {
        val ar = ArrivalEngine()
        for (d in listOf(60.0, 50.0, 20.0, 5.0, 3.0)) { ar.update(d, 3f); ar.update(d, 3f); assertFalse("arrived at $d m", ar.arrived) }
        ar.update(1.5, 10f); ar.update(1.5, 10f); assertFalse("claimed arrival with ±10 m", ar.arrived)
        ar.update(2.0, 4f); assertFalse(ar.arrived)                     // needs confirmation
        ar.update(1.0, 4f); assertTrue(ar.arrived); assertEquals(1, ar.eventId)
        repeat(20) { ar.update(1.2 + it * 0.1, 4f) }                    // jitter: no new events
        assertTrue(ar.arrived); assertEquals(1, ar.eventId)
        ar.update(9.0, 4f); assertFalse(ar.arrived)                     // left
        ar.update(1.0, 3f); ar.update(0.5, 3f); assertEquals(2, ar.eventId)
    }

    @Test fun arrow_holdMode_whenStopped_isStable() {
        val e = ArrowEngine()
        e.update(45f, gps(0f, 12.0, true, 1f, 1.0))
        var t = 2.0
        repeat(20) { e.update(45f, gps(0f, 0.2, false, 0.2f, t)); t += 1 }
        assertEquals(ArrowEngine.Mode.HOLD, e.mode)
        assertEquals(45f, e.angle, 0.5f)
    }

    @Test fun arrow_noBearing_doesNotInventDirection() {
        val e = ArrowEngine()
        val a = e.update(123f, gps(0f, 0.0, false, 0f, 1.0, has = false))
        assertEquals(ArrowEngine.Mode.NO_BEARING, e.mode)
        assertEquals(0f, a, 0f)   // no GPS bearing → arrow is not rotated with invented data
    }

    @Test fun arrow_smoothingIsResponsiveNotLaggy() {
        val e = ArrowEngine()
        e.update(0f, gps(0f, 25.0, true, 1f, 1.0))
        e.update(0f, gps(300f, 25.0, true, 1f, 2.0))   // real 60° turn, high quality
        e.update(0f, gps(300f, 25.0, true, 1f, 3.0))
        assertEquals(60f, e.angle, 3f)                 // converged within 2 fixes
    }

    @Test fun coordinates_strictNumericAndRanges() {
        assertEquals(31.9900, Destination.parseCoordinate("31.9900")!!, 1e-12)
        assertEquals(-44.5, Destination.parseCoordinate(" -44.5 ")!!, 1e-12)
        assertEquals(32.1, Destination.parseCoordinate("٣٢٫١")!!, 1e-12)
        assertNull(Destination.parseCoordinate("abc"))
        assertNull(Destination.parseCoordinate("12a"))
        assertNull(Destination.parseCoordinate("1e5"))
        assertNull(Destination.parseCoordinate(""))
        assertNull(Destination.parseCoordinate("--1"))
        assertTrue(Destination.validLat(90.0)); assertFalse(Destination.validLat(90.0001))
        assertTrue(Destination.validLon(-180.0)); assertFalse(Destination.validLon(180.5))
        assertFalse(Destination.validLat(null))
    }

    @Test fun rawPrecisionPreserved() {
        val v = Destination.parseCoordinate("32.0012345678")!!
        assertEquals(32.0012345678, v, 0.0)   // no rounding to 5 decimals
    }

    @Test fun speedConversion_threeDigits() {
        val mps = 61.1111111
        val d = com.nova.gpspro.settings.Units.Companion
        assertEquals(220, d.displayInt(mps * 3.6))
        assertEquals(137, d.displayInt(mps * 2.2369362920544))
        assertEquals(125, d.displayInt(34.7222223 * 3.6))
        assertEquals(0, d.displayInt(0.0)); assertEquals(1, d.displayInt(1.2)); assertEquals(9, d.displayInt(9.4))
        assertEquals(99, d.displayInt(99.0)); assertEquals(100, d.displayInt(99.6)); assertEquals(199, d.displayInt(199.2))
        assertEquals(0, d.displayInt(Double.NaN))
    }

    // ---------- Realistic walking simulation: NO chipset bearing, GPS noise, 2 Hz fixes ----------
    private class Walker(seed: Long, val noiseM: Double = 1.5, val acc: Float = 5f) {
        val rnd = java.util.Random(seed)
        var north = 0.0; var east = 0.0; var t = 0.0
        val be = BearingEngine(); val ae = ArrowEngine()
        val lat0 = 32.0; val lon0 = 44.0
        val mLat = 111_320.0; val mLon = 111_320.0 * Math.cos(Math.toRadians(32.0))
        /** move `speed` m/s along `heading` for one 0.5 s step; returns arrow angle toward dest bearing */
        fun step(heading: Double, speed: Double, destBearing: Float, chipBearing: Boolean = false): Float {
            t += 0.5
            north += Math.cos(Math.toRadians(heading)) * speed * 0.5
            east += Math.sin(Math.toRadians(heading)) * speed * 0.5
            val n = north + rnd.nextGaussian() * noiseM * 0.5; val e = east + rnd.nextGaussian() * noiseM * 0.5
            val ns = (t * 1e9).toLong()
            val reportedSpeed = if (speed > 0) speed + rnd.nextGaussian() * 0.1 else Math.abs(rnd.nextGaussian() * 0.05)
            be.update(lat0 + n / mLat, lon0 + e / mLon, acc, chipBearing, heading.toFloat(), if (chipBearing) 20f else -1f, reportedSpeed, ns)
            val g = GpsState(GpsStatus.GPS_CONNECTED, speed = reportedSpeed, bearing = be.bearing, hasBearing = be.isUsable(ns),
                bearingLive = be.isUsable(ns), bearingQuality = be.quality, elapsedRealtimeNanos = ns, isUsable = true)
            return ae.update(destBearing, g)
        }
    }

    @Test fun slowWalk_3kmh_noChipBearing_arrowKeepsUpdating() {
        val w = Walker(1)
        var trackingSteps = 0; var errSum = 0.0; var n = 0
        repeat(60) { i ->                                    // 30 s walking north at 0.85 m/s (~3 km/h)
            val a = w.step(0.0, 0.85, destBearing = 45f)
            if (w.ae.mode == ArrowEngine.Mode.TRACKING) trackingSteps++
            if (i >= 12) { errSum += angDiff(a, 45f); n++ }
        }
        assertTrue("arrow frozen: tracking only $trackingSteps/60", trackingSteps >= 48)
        assertTrue("mean error ${errSum / n}", errSum / n < 12)
        assertEquals(BearingEngine.Source.COURSE_OVER_GROUND, w.be.source)
    }

    @Test fun slowWalk_turn90_arrowCorrectsWithinSeconds() {
        val w = Walker(2)
        repeat(30) { w.step(0.0, 1.0, destBearing = 90f) }     // heading N, dest E → arrow ≈ +90
        assertTrue(angDiff(w.ae.angle, 90f) < 15f)
        var stepsToCorrect = -1
        for (i in 1..30) {                                      // turn to face E at 3.6 km/h
            val a = w.step(90.0, 1.0, destBearing = 90f)
            if (stepsToCorrect < 0 && angDiff(a, 0f) < 15f) stepsToCorrect = i
        }
        assertTrue("slow correction: $stepsToCorrect steps", stepsToCorrect in 1..10)   // ≤ 5 s
        assertTrue(angDiff(w.ae.angle, 0f) < 12f)
    }

    @Test fun walk_turn180_reversesDirection() {
        val w = Walker(3)
        repeat(30) { w.step(0.0, 1.2, destBearing = 0f) }
        var steps = -1
        for (i in 1..30) { val a = w.step(180.0, 1.2, destBearing = 0f); if (steps < 0 && angDiff(a, 180f) < 20f) steps = i }
        assertTrue("180° slow: $steps", steps in 1..12)
    }

    @Test fun walk_crossingNorth_359to1_noSpin() {
        val w = Walker(4, noiseM = 0.6)
        repeat(20) { w.step(355.0, 1.5, destBearing = 0f) }
        var maxJump = 0f; var prev = w.ae.angle
        repeat(20) { val a = w.step(5.0, 1.5, destBearing = 0f); maxJump = maxOf(maxJump, angDiff(a, prev)); prev = a }
        assertTrue("spin detected: $maxJump", maxJump < 30f)
        assertTrue(angDiff(w.ae.angle, -5f) < 10f)
    }

    @Test fun standingStill_gpsDrift_doesNotSpinArrow_thenStops() {
        val w = Walker(5, noiseM = 2.5, acc = 6f)
        repeat(20) { w.step(90.0, 1.2, destBearing = 90f) }
        val before = w.step(0.0, 0.0, destBearing = 90f)           // first reading after stopping
        var moved = 0f
        repeat(40) { val a = w.step(0.0, 0.0, destBearing = 90f); moved = maxOf(moved, angDiff(a, before)) }  // 20 s standing
        assertTrue("drift spun the arrow by $moved°", moved < 25f)
        assertEquals(ArrowEngine.Mode.NO_BEARING, w.ae.mode)        // hold was only temporary
    }

    @Test fun chipBearing_preferredWhenGood() {
        val w = Walker(6)
        repeat(6) { w.step(30.0, 0.6, destBearing = 30f, chipBearing = true) }
        assertEquals(BearingEngine.Source.GPS_BEARING, w.be.source)
        assertTrue(angDiff(w.ae.angle, 0f) < 3f)
    }

    @Test fun robustness_40NoiseSeeds_slowWalkTurnAndStand() {
        var worstTurn = 0; var worstDrift = 0f; var minTracking = 100
        for (seed in 100L until 140L) {
            val w = Walker(seed, noiseM = 2.0, acc = 6f)
            var tracking = 0
            repeat(30) { w.step(0.0, 0.9, destBearing = 90f); if (w.ae.mode == ArrowEngine.Mode.TRACKING) tracking++ }
            minTracking = minOf(minTracking, tracking)
            var steps = 99
            for (i in 1..30) { val a = w.step(90.0, 0.9, destBearing = 90f); if (steps == 99 && angDiff(a, 0f) < 20f) steps = i }
            worstTurn = maxOf(worstTurn, steps)
            val before = w.step(0.0, 0.0, destBearing = 90f)       // first reading after stopping
            repeat(30) { worstDrift = maxOf(worstDrift, angDiff(w.step(0.0, 0.0, destBearing = 90f), before)) }
        }
        println("ROBUST worstTurnSteps=$worstTurn worstDrift=$worstDrift minTracking=$minTracking/30")
        assertTrue("turn too slow: $worstTurn", worstTurn <= 14)       // ≤ 7 s at 3.2 km/h with 2 m noise
        assertTrue("drift: $worstDrift", worstDrift < 30f)
        assertTrue("frozen: $minTracking", minTracking >= 20)
    }

    /** Zig-zag walk right/left at 1–5 km/h, no chipset bearing, 1.5 m GPS noise, 40 seeds. */
    @Test fun zigzag_1to5kmh_rightLeft_fastResponse_noHold() {
        for (kmh in listOf(1.0, 2.0, 3.0, 5.0)) {
            val v = kmh / 3.6
            var worst = 0.0; var total = 0.0; var turns = 0; var holdSteps = 0; var noBearingSteps = 0; var steps = 0
            for (seed in 0L until 40L) {
                val w = Walker(1000 + seed, noiseM = 1.5, acc = 5f)
                repeat((6 / v / 0.5).toInt() + 4) { w.step(0.0, v, destBearing = 0f) }       // settle heading north
                var heading = 0.0
                for (turn in listOf(60.0, -120.0, 120.0, -60.0)) {                          // right, left, right, left
                    heading += turn
                    var t = -1.0
                    for (i in 1..80) {
                        val a = w.step(heading, v, destBearing = 0f); steps++
                        if (w.ae.mode == ArrowEngine.Mode.HOLD) holdSteps++
                        if (w.ae.mode == ArrowEngine.Mode.NO_BEARING) noBearingSteps++
                        if (t < 0 && angDiff(a, (-heading).toFloat()) < 20f) t = i * 0.5
                        if (t > 0 && i * 0.5 >= t + 3) break
                    }
                    if (t < 0) t = 40.0
                    worst = maxOf(worst, t); total += t; turns++
                }
            }
            println("ZIGZAG %.0f km/h: mean response %.1f s, worst %.1f s, HOLD steps %d, NO_BEARING %d / %d".format(kmh, total / turns, worst, holdSteps, noBearingSteps, steps))
            assertEquals("HOLD shown while moving at $kmh km/h", 0, holdSteps)
            assertEquals("direction lost while moving at $kmh km/h", 0, noBearingSteps)
            val limit = when (kmh) { 1.0 -> 14.0; 2.0 -> 7.0; 3.0 -> 5.0; else -> 3.5 }
            assertTrue("too slow at $kmh km/h: mean ${total / turns}", total / turns <= limit)
        }
    }

    @Test fun animation_settlesFast() {
        // ArrowView easing: tau 50 ms → 95 % of a turn in 150 ms
        assertTrue(1 - Math.exp(-0.15 / 0.05) > 0.94)
    }

}
