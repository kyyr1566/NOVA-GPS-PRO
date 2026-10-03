package com.nova.gpspro.license

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityTest {
    @Test fun hashIsSaltedSha256OfProductAndAndroidId() {
        val h = AndroidDeviceIdentity.computeHash("NOVA_GPS_PRO", "0123456789abcdef")
        assertEquals(sha256Hex("NOVA_GPS_PRO:0123456789abcdef:nova-gps-pro/device-binding/v1"), h)
        assertTrue(Regex("[0-9a-f]{64}").matches(h))
    }

    @Test fun stableAndDeviceSpecific() {
        assertEquals(AndroidDeviceIdentity.computeHash("NOVA_GPS_PRO", "abc"), AndroidDeviceIdentity.computeHash("NOVA_GPS_PRO", "abc"))
        assertNotEquals(AndroidDeviceIdentity.computeHash("NOVA_GPS_PRO", "abc"), AndroidDeviceIdentity.computeHash("NOVA_GPS_PRO", "abd"))
        assertNotEquals(AndroidDeviceIdentity.computeHash("NOVA_GPS_PRO", "abc"), AndroidDeviceIdentity.computeHash("OTHER_APP", "abc"))
    }

    @Test fun rawAndroidIdNeverAppears() {
        val raw = "0123456789abcdef"
        val h = AndroidDeviceIdentity.computeHash("NOVA_GPS_PRO", raw)
        assertFalse(h.contains(raw))
        assertFalse(AndroidDeviceIdentity.shortIdOf(h).contains(raw, ignoreCase = true))
    }

    @Test fun shortIdFormat() {
        val s = AndroidDeviceIdentity.shortIdOf(AndroidDeviceIdentity.computeHash("NOVA_GPS_PRO", "x"))
        assertTrue(s, Regex("NVD-[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}").matches(s))
    }
}
