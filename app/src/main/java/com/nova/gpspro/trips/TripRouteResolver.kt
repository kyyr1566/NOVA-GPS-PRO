package com.nova.gpspro.trips

import com.nova.gpspro.data.Destination
import com.nova.gpspro.data.Trip
import com.nova.gpspro.navigation.NavigationEngine

/** Resolve rerouting from a saved place when it still exists, otherwise use stored real GPS coordinates. */
object TripRouteResolver {
    fun resolve(trip: Trip, destinations: List<Destination>): Destination? {
        trip.destinationId?.let { id -> destinations.firstOrNull { it.id == id }?.let { return it } }
        val savedDestinationCoordinate = if (trip.destinationLatitude != null && trip.destinationLongitude != null) {
            trip.destinationLatitude to trip.destinationLongitude
        } else null
        val endpoint = savedDestinationCoordinate ?: if (trip.endpointLatitude != null && trip.endpointLongitude != null) {
            trip.endpointLatitude to trip.endpointLongitude
        } else null
        val coordinate = endpoint ?: return null
        return Destination(
            id = NavigationEngine.TRIP_ENDPOINT_PREFIX + trip.id,
            name = trip.destinationName ?: trip.name,
            latitude = coordinate.first,
            longitude = coordinate.second,
            createdAt = trip.endedAtMs
        )
    }
}
