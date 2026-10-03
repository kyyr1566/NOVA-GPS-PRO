package com.nova.gpspro.data

import java.nio.charset.StandardCharsets
import java.util.Base64

/** Small, versioned, offline codec for an individual trip file. */
object TripCodec {
    private const val HEADER = "NOVA_GPS_TRIP\t1"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(trip: Trip): String = buildString {
        append(HEADER).append('\n')
        field("id", encodeText(trip.id))
        field("name", encodeText(trip.name))
        field("started", trip.startedAtMs.toString())
        field("ended", trip.endedAtMs.toString())
        field("duration", trip.durationMs.toString())
        field("distance", trip.distanceM.toString())
        field("average_speed", trip.averageSpeedMps.toString())
        field("max_speed", trip.maxSpeedMps?.toString().orEmpty())
        field("destination_id", trip.destinationId?.let(::encodeText).orEmpty())
        field("destination_name", trip.destinationName?.let(::encodeText).orEmpty())
        field("destination_lat", trip.destinationLatitude?.toString().orEmpty())
        field("destination_lon", trip.destinationLongitude?.toString().orEmpty())
        field("endpoint_lat", trip.endpointLatitude?.toString().orEmpty())
        field("endpoint_lon", trip.endpointLongitude?.toString().orEmpty())
        trip.points.forEach { point ->
            append("point\t")
                .append(point.timestampMs).append('\t')
                .append(point.elapsedRealtimeNanos).append('\t')
                .append(point.latitude).append('\t')
                .append(point.longitude).append('\t')
                .append(point.accuracyM).append('\t')
                .append(point.speedMps?.toString().orEmpty()).append('\n')
        }
    }

    fun decode(text: String): Trip? {
        return try {
        val lines = text.lineSequence().iterator()
        if (!lines.hasNext() || lines.next() != HEADER) return null
        val fields = HashMap<String, String>()
        val points = ArrayList<TripPoint>()
        while (lines.hasNext()) {
            val line = lines.next()
            if (line.isEmpty()) continue
            val parts = line.split('\t')
            if (parts.firstOrNull() == "point") {
                if (parts.size != 7 || points.size >= MAX_POINTS) return null
                val point = TripPoint(
                    timestampMs = parts[1].toLong(),
                    elapsedRealtimeNanos = parts[2].toLong(),
                    latitude = parts[3].toDouble(),
                    longitude = parts[4].toDouble(),
                    accuracyM = parts[5].toFloat(),
                    speedMps = parts[6].takeIf { it.isNotEmpty() }?.toDouble()
                )
                if (!validPoint(point)) return null
                points.add(point)
            } else {
                if (parts.size != 2 || fields.put(parts[0], parts[1]) != null) return null
            }
        }
        val id = fields.requiredText("id") ?: return null
        val name = fields.requiredText("name") ?: return null
        val started = fields.long("started") ?: return null
        val ended = fields.long("ended") ?: return null
        val duration = fields.long("duration") ?: return null
        val distance = fields.double("distance") ?: return null
        val average = fields.double("average_speed") ?: return null
        val max = fields.optionalDouble("max_speed") ?: if (fields["max_speed"] == "") null else return null
        val destLat = fields.optionalDouble("destination_lat") ?: if (fields["destination_lat"] == "") null else return null
        val destLon = fields.optionalDouble("destination_lon") ?: if (fields["destination_lon"] == "") null else return null
        val endLat = fields.optionalDouble("endpoint_lat") ?: if (fields["endpoint_lat"] == "") null else return null
        val endLon = fields.optionalDouble("endpoint_lon") ?: if (fields["endpoint_lon"] == "") null else return null
        if (id.isBlank() || id.length > 128 || name.isBlank() || started < 0 || ended < started || duration < 0 ||
            !distance.isFinite() || distance < 0 || !average.isFinite() || average < 0 ||
            (max != null && (!max.isFinite() || max < 0)) ||
            (destLat != null && destLat !in -90.0..90.0) || (destLon != null && destLon !in -180.0..180.0) ||
            (endLat != null && endLat !in -90.0..90.0) || (endLon != null && endLon !in -180.0..180.0) ||
            (destLat == null) != (destLon == null) || (endLat == null) != (endLon == null)) return null

        Trip(
            id = id,
            name = name,
            startedAtMs = started,
            endedAtMs = ended,
            durationMs = duration,
            distanceM = distance,
            averageSpeedMps = average,
            maxSpeedMps = max,
            destinationId = fields["destination_id"]?.takeIf { it.isNotEmpty() }?.let(::decodeText),
            destinationName = fields["destination_name"]?.takeIf { it.isNotEmpty() }?.let(::decodeText),
            destinationLatitude = destLat,
            destinationLongitude = destLon,
            endpointLatitude = endLat,
            endpointLongitude = endLon,
            points = points
        )
        } catch (_: Exception) { null }
    }

    private fun StringBuilder.field(key: String, value: String) {
        append(key).append('\t').append(value).append('\n')
    }

    private fun encodeText(value: String): String = encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    private fun decodeText(value: String): String = String(decoder.decode(value), StandardCharsets.UTF_8)

    private fun Map<String, String>.requiredText(key: String): String? = this[key]?.let(::decodeText)
    private fun Map<String, String>.long(key: String): Long? = this[key]?.toLongOrNull()
    private fun Map<String, String>.double(key: String): Double? = this[key]?.toDoubleOrNull()?.takeIf { it.isFinite() }
    private fun Map<String, String>.optionalDouble(key: String): Double? = this[key]?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    private fun validPoint(point: TripPoint): Boolean =
        point.timestampMs >= 0L && point.elapsedRealtimeNanos >= 0L &&
            point.latitude.isFinite() && point.latitude in -90.0..90.0 &&
            point.longitude.isFinite() && point.longitude in -180.0..180.0 &&
            point.accuracyM.isFinite() && point.accuracyM >= 0f &&
            (point.speedMps == null || point.speedMps.isFinite() && point.speedMps >= 0.0)

    private const val MAX_POINTS = 500_000
}
