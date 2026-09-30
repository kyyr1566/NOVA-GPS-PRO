package com.nova.gpspro.ui

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Reference position for the Destinations distance column (pure, JVM-tested).
 * Uses only real GPS fixes; nothing is invented:
 *   • fixes with accuracy worse than [goodAccM] are ignored (unless no good fix arrived for [fallbackMs],
 *     then the engine's usable fixes are accepted so the column never stays blank forever);
 *   • a single fix implying an impossible jump (> [maxSpeedMps]) is rejected; after [reanchorAfter]
 *     consecutive rejections the new place is accepted (real relocation, e.g. after a tunnel);
 *   • accepted fixes are smoothed (time constant [tauS], faster when the new fix is more accurate),
 *     so the distance changes gradually with the real movement instead of jittering.
 * Distances themselves are still computed with Location.distanceBetween on the result.
 */
class CardPositionFilter(
    private val goodAccM: Float = 20f,
    private val fallbackMs: Long = 5000,
    private val maxSpeedMps: Double = 70.0,
    private val reanchorAfter: Int = 3,
    private val tauS: Double = 1.2
) {
    var lat = 0.0; private set
    var lon = 0.0; private set
    var hasFix = false; private set
    private var acc = 0f
    private var lastMs = 0L
    private var firstSeenMs = -1L
    private var rejects = 0

    fun reset() { hasFix = false; rejects = 0; firstSeenMs = -1L }

    /** Feed a fix; returns true if the reference position changed. */
    fun update(fLat: Double, fLon: Double, fAcc: Float, usable: Boolean, nowMs: Long): Boolean {
        if (firstSeenMs < 0) firstSeenMs = nowMs
        val good = fAcc <= goodAccM
        val fallback = usable && !hasFix && nowMs - firstSeenMs >= fallbackMs
        if (!good && !fallback && !(hasFix && usable && fAcc <= acc)) return false
        if (!hasFix) { set(fLat, fLon, fAcc, nowMs); return true }

        val dt = ((nowMs - lastMs).coerceAtLeast(1)) / 1000.0
        val d = approxM(lat, lon, fLat, fLon)
        val allowed = maxSpeedMps * dt + fAcc + acc
        if (d > allowed) {
            if (++rejects < reanchorAfter) return false
            set(fLat, fLon, fAcc, nowMs); return true                 // consistent new place
        }
        rejects = 0
        // accuracy-aware exponential smoothing
        val k0 = 1 - exp(-dt / tauS)
        val w = (acc / (acc + fAcc).coerceAtLeast(0.1f)).toDouble()   // better new fix → larger step
        val k = (k0 * (0.5 + w)).coerceIn(0.0, 1.0)
        lat += (fLat - lat) * k; lon += (fLon - lon) * k
        acc = (acc + (fAcc - acc) * k.toFloat()); lastMs = nowMs
        return true
    }

    private fun set(a: Double, o: Double, ac: Float, t: Long) { lat = a; lon = o; acc = ac; lastMs = t; hasFix = true; rejects = 0 }

    companion object {
        /** Equirectangular metres – only used for the plausibility gate. */
        fun approxM(a1: Double, o1: Double, a2: Double, o2: Double): Double {
            val k = 111_320.0
            val dy = (a2 - a1) * k; val dx = (o2 - o1) * k * cos(Math.toRadians((a1 + a2) / 2))
            return sqrt(dx * dx + dy * dy)
        }
    }
}
