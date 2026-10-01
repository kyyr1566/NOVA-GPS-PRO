package com.nova.gpspro

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Static guard for the dark mode: no UI source file may hard-wire a colour that only works in
 * one appearance. Text/data colours must come from the theme-aware palette (ui/Ui.kt), so a
 * second theme can never leave a dark number on a dark surface.
 *
 * Fixed literals are allowed only for artwork that always draws on its own fixed background
 * (radar disc, QR code, camera viewfinder) — those files are listed below.
 */
class ThemeSourceTest {

    private val allowedFixedArtwork = setOf(
        "RadarView.kt",   // dark radar disc: its own inner HUD palette
        "QrScanner.kt"    // camera screen: dark viewfinder with white hint text
    )

    private val uiRoot: File? by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "src/main/java/com/nova/gpspro")
            if (candidate.isDirectory) return@lazy candidate
            val nested = File(dir, "app/src/main/java/com/nova/gpspro")
            if (nested.isDirectory) return@lazy nested
            dir = dir.parentFile
        }
        null
    }

    @Test fun noBlackOrHardcodedDarkTextInUiCode() {
        val root = uiRoot
        assumeTrue("source tree not reachable from the test working directory", root != null)
        val files = root!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("no Kotlin sources found under $root", files.isNotEmpty())

        val banned = listOf("Color.BLACK", "0xFF000000", "0xff000000", "setTextColor(0x")
        val offenders = ArrayList<String>()
        for (f in files) {
            if (f.name in allowedFixedArtwork) continue
            val text = f.readText()
            for (b in banned) if (text.contains(b)) offenders += "${f.name}: $b"
        }
        assertTrue("hard-coded colours found: $offenders", offenders.isEmpty())
    }

    @Test fun thePaletteIsTheSingleSourceOfColours() {
        val root = uiRoot
        assumeTrue("source tree not reachable from the test working directory", root != null)
        val palette = File(root!!, "ui/Ui.kt").readText()
        for (name in listOf("TEXT", "TEXT2", "TEXT3", "BG", "CARD", "GOLD_DEEP", "GREEN", "RED", "AMBER", "SIGNAL_BLUE")) {
            assertTrue("palette must define $name for both appearances", palette.contains("val $name get() = pick("))
        }
        assertTrue("palette must expose the dark switch", palette.contains("fun use(darkMode: Boolean)"))
    }
}
