package com.nova.gpspro

import com.nova.gpspro.data.Destination
import com.nova.gpspro.data.LocxCodec
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class LocxCodecTest {
    private val d = Destination("id-1", "بيت الجد: \"قديم\"", 32.0258341, 44.3462177,
        "/data/photos/p.jpg", "ملاحظة\nسطر ثانٍ = \\ نهاية", 1790683512000L)

    @Test fun roundTrip() {
        val txt = LocxCodec.encode(d, TimeZone.getTimeZone("Asia/Baghdad"))
        assertTrue(txt.startsWith("NOVA-LOCX 1\n"))
        assertTrue(txt.contains("latitude=32.0258341")); assertTrue(txt.contains("saved_at=2026-"))
        assertEquals(d, LocxCodec.decode(txt, "x"))
    }
    @Test fun rejectsInvalid() {
        assertNull(LocxCodec.decode("hello", "x"))
        assertNull(LocxCodec.decode("NOVA-LOCX 1\nname=a\nlatitude=95\nlongitude=1\n", "x"))
    }
    @Test fun fallbackIdAndIsoTime() {
        val r = LocxCodec.decode("NOVA-LOCX 1\r\nname=A\r\nlatitude=1\r\nlongitude=2\r\nsaved_at=2026-01-01T00:00:00+0000\r\n", "fb")!!
        assertEquals("fb", r.id); assertEquals(1767225600000L, r.createdAt); assertNull(r.notes)
    }
    @Test fun safeNames() {
        assertEquals("بيت الجد_ _قديم", LocxCodec.safeBaseName("بيت الجد: \"قديم\""))
        assertEquals("a_b_c", LocxCodec.safeBaseName("a/b\\c"))
        assertEquals("location", LocxCodec.safeBaseName(" ..?? "))
        assertTrue(LocxCodec.safeBaseName("x".repeat(300)).length <= 80)
    }
    @Test fun uniqueNames() {
        assertEquals("Home.locx", LocxCodec.uniqueFileName("Home", emptySet()))
        assertEquals("Home (3).locx", LocxCodec.uniqueFileName("Home", setOf("home.locx", "Home (2).locx")))
    }
}
