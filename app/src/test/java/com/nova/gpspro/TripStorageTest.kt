package com.nova.gpspro

import com.nova.gpspro.data.Trip
import com.nova.gpspro.data.TripCodec
import com.nova.gpspro.data.TripPoint
import com.nova.gpspro.data.TripRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TripStorageTest {
    private fun sample(id: String, name: String, east: Double = 0.0) = Trip(
        id = id,
        name = name,
        startedAtMs = 1_700_000_000_000L,
        endedAtMs = 1_700_000_060_000L,
        durationMs = 60_000L,
        distanceM = 24.75 + east,
        averageSpeedMps = (24.75 + east) / 60.0,
        maxSpeedMps = 5.8,
        destinationId = "loc-home",
        destinationName = "البيت 🏠",
        destinationLatitude = 32.0,
        destinationLongitude = 44.3,
        endpointLatitude = 32.0001,
        endpointLongitude = 44.3001,
        points = listOf(
            TripPoint(1_700_000_000_000L, 9_000_000_000L, 32.0, 44.3, 4.5f, 1.0),
            TripPoint(1_700_000_030_000L, 39_000_000_000L, 32.0001, 44.3001, 3.2f, 5.8)
        )
    )

    @Test fun codecRoundTripsLocalizedNameAndEveryGpsPoint() {
        val original = sample("trip-α", "رحلة إلى البيت · 07:30")
        val decoded = TripCodec.decode(TripCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test fun repositoryRestoresIndependentTripsAndDeletesOnlySelectedTrack() {
        val directory = Files.createTempDirectory("nova-trips-test").toFile()
        try {
            val repo = TripRepository(directory)
            val first = sample("trip-one", "Morning")
            val second = sample("trip-two", "Evening", east = 10.0)
            assertTrue(repo.save(first))
            assertTrue(repo.save(second))

            val reopened = TripRepository(directory)
            assertEquals(2, reopened.all().size)
            assertEquals(first, reopened.get(first.id))
            assertEquals(second, reopened.get(second.id))
            assertTrue(reopened.delete(first.id))
            assertNull(reopened.get(first.id))
            assertNotNull(reopened.get(second.id))
            assertEquals(1, directory.listFiles()!!.count { it.extension == "trip" })
            assertFalse(reopened.delete(first.id))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun malformedTripFileIsRejected() {
        assertNull(TripCodec.decode("NOVA_GPS_TRIP\t1\nid\tbad\n"))
    }
}
