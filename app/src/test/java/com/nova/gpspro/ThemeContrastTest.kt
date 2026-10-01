package com.nova.gpspro

import com.nova.gpspro.ui.C
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Guards the two things the dark mode must never break:
 *  1. every important text/number colour keeps enough contrast with the surface it is drawn on;
 *  2. the light palette stays exactly the original design (nothing was changed for light users).
 *
 * Contrast = WCAG 2.1 relative luminance ratio. 4.5 is the normal-text threshold, 3.0 is used
 * for graphical elements (signal bars) and 2.0 for large filled shapes (the navigation arrow).
 */
class ThemeContrastTest {

    @After fun resetPalette() { C.use(false) }

    // ------------------------------------------------------------------ helpers

    private fun luminance(color: Int): Double {
        fun channel(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel((color shr 16) and 0xFF) +
            0.7152 * channel((color shr 8) and 0xFF) +
            0.0722 * channel(color and 0xFF)
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Text colour → the surfaces it is really drawn on in the app. */
    private fun textPairs(): List<Triple<String, Int, Int>> {
        val onCards = listOf("BG" to C.BG, "CARD" to C.CARD, "CARD_SOFT" to C.CARD_SOFT, "GOLD_PALE" to C.GOLD_PALE)
        val pairs = ArrayList<Triple<String, Int, Int>>()
        for ((n, surface) in onCards) {
            pairs += Triple("TEXT on $n", C.TEXT, surface)
            pairs += Triple("TEXT2 on $n", C.TEXT2, surface)
            pairs += Triple("TEXT3 on $n", C.TEXT3, surface)
            pairs += Triple("GOLD_DEEP on $n", C.GOLD_DEEP, surface)
            pairs += Triple("GOLD on $n", C.GOLD, surface)
        }
        pairs += Triple("ACCENT on CARD", C.ACCENT, C.CARD)
        pairs += Triple("GREEN on CARD", C.GREEN, C.CARD)
        pairs += Triple("RED on CARD", C.RED, C.CARD)
        pairs += Triple("AMBER on CARD", C.AMBER, C.CARD)
        pairs += Triple("SIGNAL_BLUE on CARD", C.SIGNAL_BLUE, C.CARD)
        pairs += Triple("SCALE_BLUE on CARD", C.SCALE_BLUE, C.CARD)
        pairs += Triple("RADAR_TEXT on RADAR_BTN_BG", C.RADAR_TEXT, C.RADAR_BTN_BG)
        pairs += Triple("RADAR_TEXT2 on RADAR_SOFT_BG", C.RADAR_TEXT2, C.RADAR_SOFT_BG)
        pairs += Triple("RADAR_CHIP_TEXT on RADAR_CHIP_BG", C.RADAR_CHIP_TEXT, C.RADAR_CHIP_BG)
        pairs += Triple("ON_PRIMARY on PRIMARY_B", C.ON_PRIMARY, C.PRIMARY_B)
        return pairs
    }

    // ------------------------------------------------------------------ dark mode

    @Test fun darkModeTextIsAlwaysReadable() {
        C.use(true)
        for ((name, fg, bg) in textPairs()) {
            val ratio = contrast(fg, bg)
            assertTrue("$name → contrast %.2f is too low in dark mode".format(ratio), ratio >= 4.5)
        }
    }

    @Test fun darkModeSignalBarsAreVisibleOnCards() {
        C.use(true)
        for (color in listOf(C.RED to "weak", C.AMBER to "medium", C.SIGNAL_BLUE to "good", C.GREEN to "excellent")) {
            val ratio = contrast(color.first, C.CARD)
            assertTrue("${color.second} bar → contrast %.2f".format(ratio), ratio >= 3.0)
        }
        assertTrue("empty bars must stay visible", contrast(C.SIGNAL_TRACK, C.CARD) >= 1.2)
    }

    @Test fun darkModeArrowAndBordersStayVisible() {
        C.use(true)
        assertTrue(contrast(C.ARROW_A, C.CARD) >= 2.0)
        assertTrue(contrast(C.ARROW_B, C.CARD) >= 2.0)
        assertTrue(contrast(C.ARROW_SHADE_A, C.CARD) >= 2.0)
        assertTrue(contrast(C.ARROW_SHADE_B, C.CARD) >= 2.0)
        assertTrue("card border must be visible on the page", contrast(C.BORDER, C.BG) >= 1.15)
        assertTrue("card border must be visible on a card", contrast(C.BORDER, C.CARD) >= 1.15)
    }

    @Test fun darkModeSurfacesAreActuallyDark() {
        C.use(true)
        assertTrue("page background", luminance(C.BG) < 0.10)
        assertTrue("card", luminance(C.CARD) < 0.15)
        assertTrue("dark mode text must be bright", luminance(C.TEXT) > 0.55)
    }

    @Test fun primaryControlsStayReadableInBothThemes() {
        for (dark in listOf(false, true)) {
            C.use(dark)
            assertTrue(contrast(C.ON_PRIMARY, C.PRIMARY_A) >= 3.0)
            assertTrue(contrast(C.ON_PRIMARY, C.PRIMARY_B) >= 4.5)
        }
    }

    @Test fun qrStaysDarkOnWhiteInBothThemes() {
        for (dark in listOf(false, true)) {
            C.use(dark)
            assertTrue(contrast(C.QR_INK, C.QR_BG) >= 4.5)
        }
    }

    // ------------------------------------------------------------------ light mode regression

    @Test fun lightPaletteIsUnchanged() {
        C.use(false)
        // the exact values of the original light design — nothing may drift
        assertEquals(0xFFFBF8F1.toInt(), C.BG)
        assertEquals(0xFFFFFFFF.toInt(), C.CARD)
        assertEquals(0xFFFDFBF6.toInt(), C.CARD_SOFT)
        assertEquals(0xFFEFE7D6.toInt(), C.BORDER)
        assertEquals(0xFF2457D6.toInt(), C.GOLD)
        assertEquals(0xFF1A44B0.toInt(), C.GOLD_DEEP)
        assertEquals(0xFFD5E0FA.toInt(), C.GOLD_LIGHT)
        assertEquals(0xFFEEF3FD.toInt(), C.GOLD_PALE)
        assertEquals(0xFFE39B2E.toInt(), C.ORANGE_GOLD)
        assertEquals(0xFF1A44B0.toInt(), C.ORANGE_DEEP)
        assertEquals(0xFF3A6DE6.toInt(), C.ACCENT)
        assertEquals(0xFF2FA66A.toInt(), C.GREEN)
        assertEquals(0xFFDDF3E6.toInt(), C.GREEN_LIGHT)
        assertEquals(0xFFD9544D.toInt(), C.RED)
        assertEquals(0xFFFBE3E1.toInt(), C.RED_LIGHT)
        assertEquals(0xFFE0A030.toInt(), C.AMBER)
        assertEquals(0xFF3D3628.toInt(), C.TEXT)
        assertEquals(0xFF9B958A.toInt(), C.TEXT2)
        assertEquals(0xFFC2BCB0.toInt(), C.TEXT3)
        assertEquals(0xFFEEEAE1.toInt(), C.TRACK)
        // fixed artwork and the primary button keep their original light values
        assertEquals(0xFF0D9DC4.toInt(), C.RADAR_SEL_A)
        assertEquals(0xFF087895.toInt(), C.RADAR_SEL_B)
        assertEquals(0xFF1A44B0.toInt(), C.QR_INK)
        assertEquals(0xFFFFFFFF.toInt(), C.QR_BG)
        assertEquals(0xFF1565C0.toInt(), C.SCALE_BLUE)
    }

    @Test fun paletteSwitchesBothWays() {
        C.use(true)
        assertTrue(C.dark)
        val darkText = C.TEXT
        C.use(false)
        assertTrue(!C.dark)
        assertTrue("text colour must come from the active palette", darkText != C.TEXT)
    }
}
