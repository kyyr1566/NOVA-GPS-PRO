package com.nova.gpspro

import com.nova.gpspro.data.Destination
import com.nova.gpspro.data.Trip
import com.nova.gpspro.data.TripPoint
import com.nova.gpspro.trips.TripRouteResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripRouteResolverTest {
    private fun trip(id: String? = null, destinationLat: Double? = null, endpointLat: Double? = 32.1) = Trip(
        id = "route-1", name = "Trip to Home", startedAtMs = 1L, endedAtMs = 2L,
        durationMs = 1L, distanceM = 5.0, averageSpeedMps = 5.0, maxSpeedMps = null,
        destinationId = id, destinationName = "Home", destinationLatitude = destinationLat,
        destinationLongitude = destinationLat?.let { 44.4 }, endpointLatitude = endpointLat,
        endpointLongitude = endpointLat?.let { 44.5 }, points = listOf(TripPoint(2L, 2L, 32.1, 44.5, 4f))
    )

    @Test fun existingSavedDestinationIsPreferred() {
        val saved = Destination("home-id", "Home - updated", 32.2, 44.6, createdAt = 3L)
        val target = TripRouteResolver.resolve(trip(id = "home-id", destinationLat = 32.0), listOf(saved))
        assertEquals(saved, target)
    }

    @Test fun deletedDestinationUsesStoredDestinationCoordinatesNotInventedEndpoint() {
        val target = TripRouteResolver.resolve(trip(id = "deleted", destinationLat = 32.0), emptyList())
        assertNotNull(target)
        assertEquals(32.0, target!!.latitude, 0.0)
        assertEquals(44.4, target.longitude, 0.0)
        assertTrue(target.id.startsWith("trip-endpoint:"))
    }

    @Test fun unnamedDestinationFallsBackToRecordedEndpoint() {
        val target = TripRouteResolver.resolve(trip(), emptyList())
        assertEquals(32.1, target!!.latitude, 0.0)
        assertEquals(44.5, target.longitude, 0.0)
    }

    @Test fun cannotRouteWithoutAnyRecordedCoordinate() {
        assertNull(TripRouteResolver.resolve(trip(endpointLat = null), emptyList()))
    }
}
