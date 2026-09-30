package com.nova.gpspro

import com.nova.gpspro.settings.DistanceUnit
import com.nova.gpspro.ui.DestDistance
import org.junit.Assert.assertEquals
import org.junit.Test

class DestDistanceTest {
    private fun km(m: Double) = DestDistance.format(m, DistanceUnit.KM)!!.let { it.number + " " + it.unit }

    @Test fun wholeMetresOnly() {
        assertEquals("0 M", km(0.05)); assertEquals("1 M", km(1.0)); assertEquals("2 M", km(2.4))
        assertEquals("12 M", km(12.35)); assertEquals("237 M", km(236.53)); assertEquals("999 M", km(999.0))
        assertEquals("1 KM", km(999.6))
    }
    @Test fun kilometres() {
        assertEquals("1 KM", km(1000.0)); assertEquals("1.25 KM", km(1250.0)); assertEquals("138.5 KM", km(138_500.0))
    }
}
