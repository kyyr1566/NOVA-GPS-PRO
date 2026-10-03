package com.nova.gpspro.data

/** One accepted, real GPS position in a recorded route. Coordinates are never rounded. */
data class TripPoint(
    val timestampMs: Long,
    val elapsedRealtimeNanos: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
    val speedMps: Double? = null
)

/** A completed trip and its independently stored GPS track. */
data class Trip(
    val id: String,
    val name: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val durationMs: Long,
    val distanceM: Double,
    val averageSpeedMps: Double,
    val maxSpeedMps: Double?,
    val destinationId: String? = null,
    val destinationName: String? = null,
    val destinationLatitude: Double? = null,
    val destinationLongitude: Double? = null,
    /** Last actual GPS point, retained even when the destination is not in Destinations. */
    val endpointLatitude: Double? = null,
    val endpointLongitude: Double? = null,
    val points: List<TripPoint>
)
