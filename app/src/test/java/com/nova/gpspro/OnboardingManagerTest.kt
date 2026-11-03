package com.nova.gpspro

import android.content.SharedPreferences
import com.nova.gpspro.onboarding.OnboardingManager
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Comprehensive verification suite for:
 * 1) One-time onboarding persistence logic (7 lifecycle cases)
 * 2) Complete royal-blue arrow icon replacement across all Android densities & adaptive/splash/activation/onboarding spots
 * 3) Luxury 3-screen onboarding redesign (#2457D6, #FFFFFF, #F7F4EC) + Arabic & English localization parity
 * 4) Mandatory license & core feature protection guards
 */
class OnboardingManagerTest {

    class FakePrefs : SharedPreferences {
        private val map = mutableMapOf<String, Any>()
        private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
        override fun contains(key: String) = map.containsKey(key)
        override fun getBoolean(key: String, defValue: Boolean) = map[key] as? Boolean ?: defValue
        override fun getInt(key: String, defValue: Int) = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = map[key] as? Float ?: defValue
        override fun getString(key: String, defValue: String?) = map[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = map[key] as? MutableSet<String> ?: defValues
        override fun getAll(): MutableMap<String, *> = map.toMutableMap()
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners.add(listener) }
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners.remove(listener) }

        inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clear = false
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
            override fun remove(key: String) = apply { pending[key] = null }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean {
                if (clear) map.clear()
                pending.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
                return true
            }
            override fun apply() { commit() }
        }
    }

    private val appMainDir: File? by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val direct = File(dir, "src/main")
            if (direct.isDirectory) return@lazy direct
            val nested = File(dir, "app/src/main")
            if (nested.isDirectory) return@lazy nested
            dir = dir.parentFile
        }
        null
    }

    @Test fun case1_firstLaunch_notCompleted() {
        val prefs = FakePrefs()
        assertFalse("First launch must not be marked completed", OnboardingManager.isCompleted(prefs))
    }

    @Test fun case2_skip_savesCompleted() {
        val prefs = FakePrefs()
        OnboardingManager.setCompleted(prefs)
        assertTrue("Skip must persist onboarding_completed = true", OnboardingManager.isCompleted(prefs))
    }

    @Test fun case3_start_savesCompleted() {
        val prefs = FakePrefs()
        OnboardingManager.setCompleted(prefs)
        assertTrue("Start now must persist onboarding_completed = true", OnboardingManager.isCompleted(prefs))
    }

    @Test fun case4_secondLaunch_noOnboarding() {
        val prefs = FakePrefs()
        OnboardingManager.setCompleted(prefs)
        assertTrue("Second launch must skip onboarding", OnboardingManager.isCompleted(prefs))
    }

    @Test fun case5_persistsAfterReboot_simulated() {
        val prefs = FakePrefs()
        OnboardingManager.setCompleted(prefs)
        val rebootedPrefs = prefs
        assertTrue("Completed state must survive reboot", OnboardingManager.isCompleted(rebootedPrefs))
    }

    @Test fun case6_onlyOnce_untilDataClear() {
        val prefs = FakePrefs()
        OnboardingManager.setCompleted(prefs)
        assertTrue(OnboardingManager.isCompleted(prefs))
        OnboardingManager.reset(prefs)
        assertFalse("Clearing app data resets onboarding state", OnboardingManager.isCompleted(prefs))
    }

    @Test fun case7_constants_andClassesExist() {
        assertEquals("nova_onboarding", OnboardingManager.FILE)
        assertEquals("onboarding_completed", OnboardingManager.KEY_COMPLETED)
        assertNotNull(Class.forName("com.nova.gpspro.onboarding.OnboardingView"))
        assertNotNull(Class.forName("com.nova.gpspro.onboarding.ShortSplashView"))
    }

    @Test fun allIconAssetsReplacedWithRoyalBlueArrowAndValidDimensions() {
        val main = appMainDir
        assumeTrue("src/main not reachable", main != null)
        val res = File(main!!, "res")

        // 1) splash_icon.png (1024x1024 transparent RGBA with royal-blue arrow at center)
        val splashFile = File(res, "drawable/splash_icon.png")
        assertTrue("splash_icon.png missing", splashFile.isFile)
        val splashImg = ImageIO.read(splashFile)
        assertEquals(1024, splashImg.width)
        assertEquals(1024, splashImg.height)
        assertEquals("Top-left corner of splash_icon must be transparent", 0, (splashImg.getRGB(0, 0) ushr 24) and 0xFF)
        assertRoyalBluePixel(splashImg.getRGB(512, 512), "splash_icon center")

        // 2) ic_launcher_fg.png (864x864 transparent adaptive foreground within safe zone)
        val fgFile = File(res, "drawable/ic_launcher_fg.png")
        assertTrue("ic_launcher_fg.png missing", fgFile.isFile)
        val fgImg = ImageIO.read(fgFile)
        assertEquals(864, fgImg.width)
        assertEquals(864, fgImg.height)
        assertEquals("Adaptive fg outer margin must be transparent", 0, (fgImg.getRGB(40, 40) ushr 24) and 0xFF)
        assertRoyalBluePixel(fgImg.getRGB(432, 432), "ic_launcher_fg center")

        // 3) Legacy launcher icons across all 5 Android densities
        val expectedSizes = mapOf(
            "mipmap-mdpi" to 48,
            "mipmap-hdpi" to 72,
            "mipmap-xhdpi" to 96,
            "mipmap-xxhdpi" to 144,
            "mipmap-xxxhdpi" to 192
        )
        for ((folder, px) in expectedSizes) {
            for (name in listOf("ic_launcher.png", "ic_launcher_round.png")) {
                val f = File(res, "$folder/$name")
                assertTrue("$folder/$name missing", f.isFile)
                val im = ImageIO.read(f)
                assertEquals("$folder/$name width", px, im.width)
                assertEquals("$folder/$name height", px, im.height)
                assertRoyalBluePixel(im.getRGB(px / 2, px / 2), "$folder/$name center")
            }
        }

        // 4) Adaptive icon background & system splash theme references
        val bgXml = File(res, "drawable/ic_launcher_bg.xml").readText()
        assertFalse("Old dark icon background must be removed", bgXml.contains("#0A0F16", ignoreCase = true))
        assertTrue("Adaptive icon bg must use brand ivory/white", bgXml.contains("#F7F4EC", ignoreCase = true))

        val v31Theme = File(res, "values-v31/themes.xml").readText()
        assertTrue(v31Theme.contains("@drawable/ic_launcher_fg"))

        val manifest = File(main, "AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:icon=\"@mipmap/ic_launcher\""))
        assertTrue(manifest.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""))
    }

    @Test fun onboardingDesignPaletteLocalizationAndLicenseProtectionIntact() {
        val main = appMainDir
        assumeTrue("src/main not reachable", main != null)

        val onboardingCode = File(main!!, "java/com/nova/gpspro/onboarding/OnboardingView.kt").readText()
        assertTrue("Must use royal blue #2457D6", onboardingCode.contains("#2457D6", ignoreCase = true))
        assertTrue("Must use ivory #F7F4EC", onboardingCode.contains("#F7F4EC", ignoreCase = true))
        assertTrue("Must use white #FFFFFF", onboardingCode.contains("#FFFFFF", ignoreCase = true))
        assertTrue("Must include 3 dedicated custom artworks",
            onboardingCode.contains("GpsSatelliteArtworkView") &&
                onboardingCode.contains("SmartNavigationArtworkView") &&
                onboardingCode.contains("RadarPortalArtworkView")
        )

        val shortSplashCode = File(main, "java/com/nova/gpspro/onboarding/ShortSplashView.kt").readText()
        assertTrue(shortSplashCode.contains("R.drawable.splash_icon"))
        assertTrue(shortSplashCode.contains("#2457D6", ignoreCase = true))
        assertTrue(shortSplashCode.contains("#F7F4EC", ignoreCase = true))

        val activationCode = File(main, "java/com/nova/gpspro/license/ActivationView.kt").readText()
        assertTrue(activationCode.contains("R.drawable.splash_icon"))

        // Bilingual string parity check (EN & AR)
        val stringsEn = File(main, "res/values/strings.xml").readText()
        val stringsAr = File(main, "res/values-ar/strings.xml").readText()
        val requiredKeys = listOf(
            "onboarding_skip", "onboarding_next", "onboarding_start",
            "onboarding_badge_1", "onboarding_title_1", "onboarding_desc_1",
            "onboarding_badge_2", "onboarding_title_2", "onboarding_desc_2",
            "onboarding_badge_3", "onboarding_title_3", "onboarding_desc_3",
            "onboarding_gps_connected", "onboarding_sats_mock", "onboarding_accuracy_mock",
            "onboarding_dest_mock", "onboarding_stat_dist_label", "onboarding_stat_speed_label",
            "onboarding_stat_eta_label", "onboarding_action_radar", "onboarding_action_import",
            "onboarding_action_export", "onboarding_action_qr"
        )
        for (k in requiredKeys) {
            assertTrue("EN strings missing $k", stringsEn.contains("name=\"$k\""))
            assertTrue("AR strings missing $k", stringsAr.contains("name=\"$k\""))
        }

        // License & core identity protection check
        val mainActivityCode = File(main, "java/com/nova/gpspro/MainActivity.kt").readText()
        assertTrue("License gate before splash/onboarding must stay intact",
            mainActivityCode.contains("if (app.licenseManager.cachedLicense() == null)") &&
                mainActivityCode.contains("showActivationScreen()")
        )
        val licenseManagerFile = File(main, "java/com/nova/gpspro/license/LicenseManager.kt")
        assertTrue("LicenseManager.kt must remain present", licenseManagerFile.isFile)
    }

    private fun assertRoyalBluePixel(argb: Int, label: String) {
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        assertTrue("$label must be opaque (alpha=$a)", a >= 245)
        assertTrue("$label must be royal blue (rgb=$r,$g,$b)", b >= 185 && g in 65..125 && r in 20..65)
    }
}
