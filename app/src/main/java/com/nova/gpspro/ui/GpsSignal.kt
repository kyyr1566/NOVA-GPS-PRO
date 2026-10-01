package com.nova.gpspro.ui

import com.nova.gpspro.R
import com.nova.gpspro.location.GpsState
import com.nova.gpspro.location.GpsStatus
import kotlin.math.min

/**
 * GPS/GNSS signal strength for the 📶 indicator on the Home card.
 *
 * 100 % derived from REAL receiver data — the engine's own status, the number of satellites
 * actually used in the fix (GnssStatus.usedInFix) and the reported accuracy. No sensor input,
 * no timer, no invented value:
 *
 *   NO_GPS_PERMISSION / GPS_DISABLED / SEARCHING / GPS_LOST → NO bars (no signal)
 *   1 bar   🔴 WEAK      – very coarse accuracy or almost no satellites
 *   2 bars  🟡 MEDIUM    – usable, still coarse
 *   3 bars  🔵 GOOD
 *   4 bars  🟢 EXCELLENT – precise fix with plenty of satellites
 *
 * The engine's WEAK_ACCURACY verdict (network fallback fix / accuracy above the weak
 * threshold) can never report better than 2 bars, so the indicator never contradicts the
 * state text on the card.
 */
object GpsSignal {

    /** Signal level. [bars] = 0…4 lit bars of the SIM-style indicator. */
    enum class Level(val bars: Int, val labelRes: Int) {
        NONE(0, R.string.gps_signal_none),
        WEAK(1, R.string.gps_signal_weak),
        MEDIUM(2, R.string.gps_signal_medium),
        GOOD(3, R.string.gps_signal_good),
        EXCELLENT(4, R.string.gps_signal_excellent);

        companion object {
            fun of(value: Int): Level = Level.entries[value.coerceIn(0, Level.entries.size - 1)]
        }
    }

    fun level(state: GpsState): Level =
        level(state.gpsStatus, state.satellitesUsed, state.accuracy, state.location != null)

    fun level(status: GpsStatus, satellitesUsed: Int, accuracyM: Float, hasFix: Boolean): Level {
        if (!hasFix) return Level.NONE
        if (status != GpsStatus.GPS_CONNECTED && status != GpsStatus.WEAK_ACCURACY) return Level.NONE

        val byAccuracy = when {
            !accuracyM.isFinite() || accuracyM <= 0f -> 1          // unknown accuracy → weak
            accuracyM <= EXCELLENT_ACCURACY_M -> 4
            accuracyM <= GOOD_ACCURACY_M -> 3
            accuracyM <= MEDIUM_ACCURACY_M -> 2
            else -> 1
        }
        val bySatellites = when {
            satellitesUsed >= EXCELLENT_SATS -> 4
            satellitesUsed >= GOOD_SATS -> 3
            satellitesUsed >= MEDIUM_SATS -> 2
            else -> 1      // a real fix without reported satellites is a weak signal, never "none"
        }

        var bars = min(byAccuracy, bySatellites)
        if (status == GpsStatus.WEAK_ACCURACY) bars = min(bars, Level.MEDIUM.bars)   // engine said weak
        return Level.of(bars)
    }

    /**
     * true only while a real fix is being delivered by the receiver. The location pulse
     * animation is driven by this and stops on the very next state change, so the moment GPS
     * is lost (SEARCHING / GPS_LOST / disabled / no permission / no usable fix) the pulse stops.
     */
    fun liveFix(state: GpsState): Boolean = liveFix(state.gpsStatus, state.location != null)

    fun liveFix(status: GpsStatus, hasLocation: Boolean): Boolean =
        hasLocation && (status == GpsStatus.GPS_CONNECTED || status == GpsStatus.WEAK_ACCURACY)

    /** Pulse colour follows the real state: green locked, amber coarse, grey = stopped. */
    fun pulseColor(state: GpsState): Int = when (state.gpsStatus) {
        GpsStatus.GPS_CONNECTED -> C.GREEN
        GpsStatus.WEAK_ACCURACY -> C.AMBER
        else -> C.TEXT3
    }

    /** Colour of a lit bar: 🔴 weak · 🟡 medium · 🔵 good · 🟢 excellent. */
    fun barColor(level: Level): Int = when (level) {
        Level.WEAK -> C.RED
        Level.MEDIUM -> C.AMBER
        Level.GOOD -> C.SIGNAL_BLUE
        Level.EXCELLENT -> C.GREEN
        Level.NONE -> C.SIGNAL_TRACK
    }

    const val EXCELLENT_ACCURACY_M = 8f
    const val GOOD_ACCURACY_M = 15f
    const val MEDIUM_ACCURACY_M = 30f
    const val EXCELLENT_SATS = 10
    const val GOOD_SATS = 6
    const val MEDIUM_SATS = 4
}
