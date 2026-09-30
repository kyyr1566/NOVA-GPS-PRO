package com.nova.gpspro

import com.nova.gpspro.ui.SpeedometerView
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedoModeTest {
    @Test fun sharedDisplayText() {
        assertEquals("0", SpeedometerView.displayText(0.0, 0.0))
        assertEquals("73", SpeedometerView.displayText(72.6, 72.4))   // near target → exact target
        assertEquals("40", SpeedometerView.displayText(80.0, 40.2))   // animating → animated value
        assertEquals("0", SpeedometerView.displayText(Double.NaN, Double.NaN))
    }
}
