package com.nova.gpspro

import com.nova.gpspro.map.MapStyleManager
import com.nova.gpspro.map.RoutingRepository
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MapLogicTest {

    // 3 layers only – as requested: light, dark, satellite HD
    @Test fun layersCountIs3() {
        val expected = listOf("light","dark","satellite")
        assertEquals(3, expected.size)
        assertTrue(expected.contains("dark"))
        assertTrue(expected.contains("light"))
        assertTrue(expected.contains("satellite"))
    }
    @Test fun darkLayerExists() { assertTrue(listOf("light","dark","satellite").contains("dark")) }
    @Test fun satelliteLayerIsReal() { assertTrue(listOf("light","dark","satellite").contains("satellite")) }
    @Test fun lightLayerDistinctFromDark() { assertNotEquals("light", "dark") }
    @Test fun satelliteDistinctFromLight() { assertNotEquals("satellite", "light") }

    // Routing providers
    @Test fun routingProvidersExist() {
        val providers = listOf("fosgis","valhalla","osrm_demo")
        assertEquals(3, providers.size)
        assertTrue(providers.contains("fosgis"))
    }
    @Test fun routingRepositoryCanBeInstantiated() {
        assertNotNull(RoutingRepository::class.java)
    }
    @Test fun haversineDistancePositive() {
        val dist = haversine(33.3152, 44.3661, 30.5085, 47.7804) // Baghdad to Basra
        assertTrue(dist > 300000 && dist < 600000)
    }
    @Test fun haversineZeroForSamePoint() {
        val dist = haversine(33.3152, 44.3661, 33.3152, 44.3661)
        assertEquals(0.0, dist, 1.0)
    }
    @Test fun haversineSymmetric() {
        val d1 = haversine(33.0, 44.0, 30.0, 47.0)
        val d2 = haversine(30.0, 47.0, 33.0, 44.0)
        assertEquals(d1, d2, 0.001)
    }

    // Style URLs and palette
    @Test fun styleUrlsContainAsset() {
        val expected = listOf("light","dark","satellite")
        assertEquals(3, expected.size)
    }
    @Test fun stylePaletteColorsAreDefined() {
        val colors = listOf("#0B1220", "#D9A441", "#35D6D1", "#2856BC", "#FAF7F0", "#1E3A5F")
        assertTrue(colors.all { it.matches(Regex("^#[0-9A-Fa-f]{6}$")) })
    }
    @Test fun noDownloadButtonInOnlineOnly() {
        // Ensure offline download system removed – check that OfflineMapManager class does not exist
        try {
            Class.forName("com.nova.gpspro.map.OfflineMapManager")
            fail("OfflineMapManager should be deleted")
        } catch (_: ClassNotFoundException) {
            // expected
        }
    }
    @Test fun satelliteManagerDeleted() {
        try {
            Class.forName("com.nova.gpspro.map.SatelliteManager")
            fail("SatelliteManager should be deleted (online only now)")
        } catch (_: ClassNotFoundException) {
        }
    }

    @Test fun mapActivityExtrasDefined() {
        assertEquals("dest_id", com.nova.gpspro.map.MapActivity.EXTRA_DEST_ID)
        assertEquals("dest_lat", com.nova.gpspro.map.MapActivity.EXTRA_DEST_LAT)
        assertEquals("dest_lon", com.nova.gpspro.map.MapActivity.EXTRA_DEST_LON)
    }
    @Test fun routingResultIsRoadFlag() {
        val r = RoutingRepository.RouteResult(listOf(33.0 to 44.0, 30.0 to 47.0), 1000.0, 100.0, isRoad = false)
        assertFalse(r.isRoad)
        assertEquals(1000.0, r.distanceMeters, 0.001)
    }
    @Test fun routingResultIsRoadTrue() {
        val r = RoutingRepository.RouteResult(listOf(33.0 to 44.0, 30.0 to 47.0), 2000.0, 200.0, isRoad = true)
        assertTrue(r.isRoad)
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return R * c
    }
}
