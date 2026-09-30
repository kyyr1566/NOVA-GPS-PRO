package com.nova.gpspro.location

import android.location.Location

/** Distinct connection states. Each state has exactly one meaning. */
enum class GpsStatus {
    NO_GPS_PERMISSION,
    GPS_DISABLED,
    SEARCHING,
    GPS_CONNECTED,
    GPS_LOST,
    WEAK_ACCURACY
}

/**
 * Immutable snapshot of the GPS engine output.
 * Coordinates are raw doubles (never rounded before calculations).
 * speed is in m/s (Double, never rounded internally). bearing is movement course (0..360).
 */
data class GpsState(
    val gpsStatus: GpsStatus,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracy: Float = 0f,
    val speed: Double = 0.0,
    val bearing: Float = 0f,
    val hasBearing: Boolean = false,
    /** true when the bearing comes from live movement (not a held value). */
    val bearingLive: Boolean = false,
    /** 0..1 confidence in the movement bearing. */
    val bearingQuality: Float = 0f,
    val provider: String? = null,
    val timestamp: Long = 0L,
    val elapsedRealtimeNanos: Long = 0L,
    val isUsable: Boolean = false,
    val preciseGranted: Boolean = true,
    val satellitesUsed: Int = 0,
    val satellitesVisible: Int = 0,
    /** raw accepted fix (for exact geodesic math). null when there is no usable fix. */
    val location: Location? = null,
    /** true when this fix re-anchored after a gap/jump (distance accumulators must not add it). */
    val reanchored: Boolean = false
)
