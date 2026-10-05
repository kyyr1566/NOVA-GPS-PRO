package com.nova.gpspro

import com.nova.gpspro.data.Destination
import com.nova.gpspro.data.LocxCodec
import com.nova.gpspro.portal.ImportPlanner
import com.nova.gpspro.portal.QrCodec
import com.nova.gpspro.portal.QrPayload
import org.junit.Assert.*
import org.junit.Test

class PortalLogicTest {
    private val a = Destination("a", "بيت; الجد", 32.0258341, 44.3462177, "/x.jpg", "باب\nأزرق", 1790683512000L)
    private val b = Destination("b", "Work \\ office", -33.8688, 151.2093, null, null, 1700000000000L)

    @Test fun multiLocxRoundTrip() {
        val all = LocxCodec.decodeAll(LocxCodec.encodeMany(listOf(a, b)))
        assertEquals(listOf(a, b), all)
        assertEquals(1, LocxCodec.decodeAll(LocxCodec.encode(a)).size)
    }

    @Test fun qrPayloadRoundTrip() {
        val r = QrPayload.decode(QrPayload.encode(listOf(a, b)), 5L, "QR")
        assertEquals(2, r.size)
        assertEquals(a.name, r[0].name); assertEquals(a.notes, r[0].notes); assertEquals(a.latitude, r[0].latitude, 1e-9)
        assertEquals(b.name, r[1].name); assertNull(r[1].notes); assertEquals(b.createdAt, r[1].createdAt)
        assertNull(r[0].photoPath)
    }

    @Test fun geoAndGarbage() {
        // Strict NOVA QR only — geo:, http, and random must be rejected
        assertTrue(QrPayload.decode("geo:33.3,44.4?q=33.3,44.4(My%20Place)", 1L, "QR").isEmpty())
        assertTrue(QrPayload.decode("geo:10,20", 1L, "QR").isEmpty())
        assertTrue(QrPayload.decode("https://example.com", 1L, "QR").isEmpty())
        assertTrue(QrPayload.decode("geo:99,20", 1L, "QR").isEmpty())
        assertTrue(QrPayload.decode("plain text", 1L, "QR").isEmpty())
        assertTrue(QrPayload.decode("NOVA GPS PRO LOCATION QR\n31.0;44.0;Test;;1", 1L, "QR").isNotEmpty())
        // backward compat for old header
        assertTrue(QrPayload.decode("NOVA-QR1\n31.0;44.0;Test;;1", 1L, "QR").isNotEmpty())
    }

    @Test fun realQrImageRoundTrip() {
        val text = QrPayload.encode(listOf(a, b))
        val m = QrCodec.encode(text, 400)
        val px = IntArray(m.width * m.height) { i -> if (m[i % m.width, i / m.width]) 0xFF1A44B0.toInt() else -1 }
        assertEquals(text, QrCodec.decodePixels(px, m.width, m.height))
    }

    @Test fun dedupe() {
        val same = a.copy(id = "zz", name = "other")           // same coordinates
        val sameId = b.copy(latitude = 1.0)                     // same id
        val fresh = b.copy(id = "c", latitude = 2.0)
        val p = ImportPlanner.plan(listOf(a, b), listOf(same, sameId, fresh, fresh.copy(id = "d")))
        assertEquals(listOf(fresh), p.add); assertEquals(3, p.duplicates)
    }
}
