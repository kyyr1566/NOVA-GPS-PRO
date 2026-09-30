package com.nova.gpspro.navigation

/**
 * Real arrival: the current geodesic distance to the target must be within ~2 m AND
 * the GPS accuracy must be good enough to actually support that claim.
 * Needs consecutive confirming fixes; leaves arrival with hysteresis so it does not flicker.
 * Emits a single arrival event per arrival (no repeat on every GPS update).
 */
class ArrivalEngine {
    var arrived = false; private set
    /** increments once per real arrival */
    var eventId = 0; private set
    private var confirm = 0

    fun reset() { arrived = false; confirm = 0 }

    fun update(distanceM: Double, accuracyM: Float): Boolean {
        if (!distanceM.isFinite() || distanceM < 0) return arrived
        if (!arrived) {
            val reliable = accuracyM > 0f && accuracyM <= MAX_ACCURACY_M
            if (reliable && distanceM <= ENTER_M) confirm++ else confirm = 0
            if (confirm >= CONFIRM_FIXES) { arrived = true; confirm = 0; eventId++ }
        } else if (distanceM > EXIT_M) {
            arrived = false; confirm = 0
        }
        return arrived
    }

    companion object {
        const val ENTER_M = 2.0            // real arrival radius
        const val MAX_ACCURACY_M = 6f      // GPS must be precise enough to support a ~2 m claim
        const val EXIT_M = 8.0             // hysteresis
        const val CONFIRM_FIXES = 2
    }
}
