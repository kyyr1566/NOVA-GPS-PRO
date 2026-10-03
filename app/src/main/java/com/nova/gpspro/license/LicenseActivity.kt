package com.nova.gpspro.license

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import com.nova.gpspro.MainActivity
import com.nova.gpspro.NovaApp
import com.nova.gpspro.R
import com.nova.gpspro.portal.QrCodec
import com.nova.gpspro.portal.QrScanner
import com.nova.gpspro.settings.SettingsRepository
import java.util.Locale

/**
 * First entry point (LAUNCHER) of the app.
 *
 *   licensed   : local check → MainActivity (its existing splash then plays unchanged)
 *   unlicensed : activation screen → on success → MainActivity
 *
 * This activity never touches GPS, never builds app pages and never shows the splash.
 */
class LicenseActivity : Activity() {

    private lateinit var app: NovaApp
    private lateinit var root: FrameLayout
    private var screen: LicenseScreen? = null
    private var busy = false
    private var proceeded = false

    override fun attachBaseContext(base: Context) {
        // same language handling as MainActivity (Arabic → RTL, English → LTR)
        val loc = Locale.forLanguageTag(SettingsRepository.peekLanguage(base))
        Locale.setDefault(loc)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(loc); cfg.setLayoutDirection(loc)
        super.attachBaseContext(base.createConfigurationContext(cfg))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as NovaApp
        root = FrameLayout(this).apply {
            setBackgroundColor(LicensePalette.BG)
            layoutDirection = if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
                View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsets.CONSUMED
        }
        setContentView(root)

        // Local license check off the UI thread (signature maths). Only a blank navy window is visible meanwhile.
        Thread({
            val state = app.license.check()
            runOnUiThread { if (!isFinishing && !isDestroyed) onInitialState(state) }
        }, "license-check").start()
    }

    private fun onInitialState(state: LicenseState) {
        when (state) {
            is LicenseState.Activated -> openApp(animate = false)
            is LicenseState.NotActivated -> showScreen(null)
            is LicenseState.Rejected -> showScreen(state.error)
        }
    }

    private fun showScreen(error: LicenseError?) {
        val s = LicenseScreen(
            this, app.license.shortDeviceId(),
            onActivate = { activate(it) },
            onScan = { scanWithCamera() },
            onGallery = { pickFromGallery() },
            onCopyDeviceId = { copyDeviceId() }
        )
        screen = s
        root.addView(s, FrameLayout.LayoutParams(-1, -1))
        if (error != null) s.setStatus(LicenseScreen.Tone.ERROR, getString(error.messageRes()))
    }

    // ------------------------------------------------------------- activation
    private fun activate(code: String) {
        val s = screen ?: return
        if (busy || proceeded) return
        if (LicenseCodec.normalize(code).isEmpty()) { s.setStatus(LicenseScreen.Tone.ERROR, getString(LicenseError.EMPTY_CODE.messageRes())); return }
        busy = true
        s.setBusy(true)
        s.setStatus(LicenseScreen.Tone.NEUTRAL, getString(R.string.license_status_verifying))
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(root.windowToken, 0)
        Thread({
            val state = app.license.activate(code)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy = false
                s.setBusy(false)
                when (state) {
                    is LicenseState.Activated -> {
                        s.setStatus(LicenseScreen.Tone.SUCCESS, getString(R.string.license_status_success))
                        s.setBusy(true)
                        root.postDelayed({ if (!isFinishing && !isDestroyed) openApp(animate = true) }, 700)
                    }
                    is LicenseState.Rejected -> s.setStatus(LicenseScreen.Tone.ERROR, getString(state.error.messageRes()))
                    is LicenseState.NotActivated -> s.setStatus(LicenseScreen.Tone.ERROR, getString(LicenseError.SERVER_ERROR.messageRes()))
                }
            }
        }, "license-activate").start()
    }

    /** Hand over to the existing app. MainActivity shows its own splash (fresh launch, no saved state). */
    @Suppress("DEPRECATION")
    private fun openApp(animate: Boolean) {
        if (proceeded) return
        proceeded = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        if (!animate) overridePendingTransition(0, 0)
    }

    private fun onQrText(text: String) {
        screen?.setCode(text)
        activate(text)
    }

    private fun copyDeviceId() {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Device ID", app.license.shortDeviceId()))
        screen?.setStatus(LicenseScreen.Tone.NEUTRAL, getString(R.string.license_device_copied))
    }

    private fun LicenseError.messageRes(): Int = when (this) {
        LicenseError.EMPTY_CODE -> R.string.license_err_empty
        LicenseError.MALFORMED, LicenseError.INVALID_SIGNATURE, LicenseError.WRONG_PRODUCT,
        LicenseError.LICENSE_NOT_FOUND -> R.string.license_err_invalid
        LicenseError.UNSUPPORTED_VERSION -> R.string.license_err_version
        LicenseError.LICENSE_ALREADY_BOUND -> R.string.license_err_bound
        LicenseError.LICENSE_REVOKED -> R.string.license_err_revoked
        LicenseError.NETWORK_REQUIRED -> R.string.license_err_internet
        LicenseError.NOT_CONFIGURED -> R.string.license_err_not_configured
        LicenseError.STORAGE_FAILED -> R.string.license_err_storage
        LicenseError.SERVER_ERROR, LicenseError.INVALID_ACTIVATION -> R.string.license_err_unavailable
    }

    // ------------------------------------------------------------- QR: camera (existing ZXing scanner)
    private fun scanWithCamera() {
        if (busy || proceeded) return
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openScanner()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
    }

    private fun openScanner() {
        QrScanner(this, onError = { screen?.setStatus(LicenseScreen.Tone.ERROR, it) }) { onQrText(it) }.show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) openScanner()
        else screen?.setStatus(LicenseScreen.Tone.ERROR, getString(R.string.portal_camera_denied))
    }

    // ------------------------------------------------------------- QR: image from gallery
    private fun pickFromGallery() {
        if (busy || proceeded) return
        val intent = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).apply { type = "image/*" }
        else Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }
        try { startActivityForResult(intent, REQ_PHOTO) } catch (_: Exception) {
            try { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }, REQ_PHOTO) }
            catch (_: Exception) { screen?.setStatus(LicenseScreen.Tone.ERROR, getString(R.string.err_photo)) }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PHOTO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val s = screen ?: return
        s.setBusy(true)
        s.setStatus(LicenseScreen.Tone.NEUTRAL, getString(R.string.license_status_verifying))
        Thread({
            val text = decodeQr(uri)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                s.setBusy(false)
                if (text == null) s.setStatus(LicenseScreen.Tone.ERROR, getString(R.string.portal_no_qr)) else onQrText(text)
            }
        }, "license-qr").start()
    }

    private fun decodeQr(uri: Uri): String? = try {
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { dec, info, _ ->
            val k = minOf(1f, 1600f / maxOf(info.size.width, info.size.height))
            dec.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1))
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        QrCodec.decodePixels(px, bmp.width, bmp.height).also { bmp.recycle() }
    } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }

    private companion object {
        const val REQ_CAMERA = 21
        const val REQ_PHOTO = 22
    }
}
