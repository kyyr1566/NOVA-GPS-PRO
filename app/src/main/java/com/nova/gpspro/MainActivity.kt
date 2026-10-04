package com.nova.gpspro

import com.nova.gpspro.data.LocxFolder
import android.Manifest
import android.content.pm.PackageManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nova.gpspro.settings.SettingsRepository
import com.nova.gpspro.settings.Units
import com.nova.gpspro.license.ActivationCancellation
import com.nova.gpspro.license.ActivationView
import com.nova.gpspro.license.LicenseActivationResult
import com.nova.gpspro.portal.QrCodec
import com.nova.gpspro.portal.QrScanner
import com.nova.gpspro.ui.*
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {

    lateinit var app: NovaApp; private set
    lateinit var units: Units; private set
    lateinit var message: CenterMessage; private set

    private lateinit var activityRoot: FrameLayout
    private var activationView: ActivationView? = null
    private var mainUiReady = false
    private var activationInFlight = false
    private var activationCancellation: ActivationCancellation? = null
    private var activationExecutor: ExecutorService? = null
    private lateinit var pages: List<Page>
    private val navItems = mutableListOf<Pair<ImageView, TextView>>()
    private var current = 0
    private var pageShown = false
    private var photoCallback: ((Uri?) -> Unit)? = null
    private val thumbs = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    override fun attachBaseContext(base: Context) {
        val lang = SettingsRepository.peekLanguage(base)
        val loc = Locale.forLanguageTag(lang)
        Locale.setDefault(loc)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(loc); cfg.setLayoutDirection(loc)
        super.attachBaseContext(base.createConfigurationContext(cfg))
    }

    fun isRtl() = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as NovaApp
        units = Units(this, app.settings)
        current = (savedInstanceState?.getInt("page") ?: 0).coerceIn(0, 5)

        activityRoot = FrameLayout(this).apply {
            setBackgroundColor(C.BG)
            layoutDirection = if (isRtl()) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        activityRoot.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsets.CONSUMED
        }
        setContentView(activityRoot)

        // No application page, GPS service, or launch splash is created before activation.
        if (app.licenseManager.cachedLicense() == null) {
            current = 0
            showActivationScreen()
        } else {
            setMainSystemBars()
            createMainUi(showSplash = savedInstanceState == null)
        }
    }

    private fun showActivationScreen() {
        mainUiReady = false
        setActivationSystemBars()
        val screen = ActivationView(
            this,
            onActivate = ::beginActivation,
            onScanCamera = ::scanLicenseQrWithCamera,
            onPickQr = ::pickLicenseQrFromGallery
        )
        activationView = screen
        activityRoot.addView(screen, FrameLayout.LayoutParams(-1, -1))
    }

    private fun createMainUi(showSplash: Boolean) {
        activityRoot.removeAllViews()
        activationView = null
        setMainSystemBars()

        val column = vbox()
        val content = FrameLayout(this)
        pages = listOf(
            HomePage(this), DestinationsPage(this), NavigationPage(this),
            com.nova.gpspro.portal.PortalPage(this), RadarPage(this), SettingsPage(this)
        )
        pages.forEach { content.addView(it.view, FrameLayout.LayoutParams(-1, -1)); it.view.visibility = View.GONE }
        column.addView(content, lp(h = 0, weight = 1f))
        column.addView(buildBottomBar(), lp())
        activityRoot.addView(column, FrameLayout.LayoutParams(-1, -1))
        message = CenterMessage(activityRoot)
        mainUiReady = true

        // Keep the existing launch splash for licensed launches and after a successful activation.
        splashShowing = showSplash
        if (showSplash) {
            val splash = SplashView(this, app.gps) {
                splashShowing = false
                if (isStartedFlag) maybeAskPermission()
            }
            activityRoot.addView(splash, FrameLayout.LayoutParams(-1, -1))
            splash.start()
        }

        // If activation finished while this Activity was already started, start GPS before
        // exposing the selected page. Otherwise onStart() performs this in the usual order.
        if (isStartedFlag) startMainGpsRuntime()
        selectTab(current)
        if (isStartedFlag) maybeAskPermission()
    }

    private fun beginActivation(code: String) {
        if (activationInFlight) return
        val screen = activationView ?: return
        activationInFlight = true
        screen.setBusy(true)
        val cancellation = ActivationCancellation().also { activationCancellation = it }

        val executor = activationExecutor ?: Executors.newSingleThreadExecutor().also { activationExecutor = it }
        try {
            executor.execute {
                val result = app.licenseManager.activate(code, cancellation)
                runOnUiThread {
                    if (isFinishing || isDestroyed || cancellation.isCancelled() ||
                        activationCancellation !== cancellation || activationView !== screen) return@runOnUiThread
                    activationInFlight = false
                    activationCancellation = null
                    screen.setBusy(false)
                    when (result) {
                        is LicenseActivationResult.Success -> {
                            screen.showSuccess(getString(R.string.activation_success))
                            screen.postDelayed({
                                if (!isFinishing && activationView === screen && app.licenseManager.isActivated()) {
                                    current = 0
                                    activationExecutor?.shutdown()
                                    activationExecutor = null
                                    createMainUi(showSplash = true)
                                }
                            }, ACTIVATION_SUCCESS_DELAY_MS)
                        }
                        LicenseActivationResult.InvalidCode -> screen.showError(getString(R.string.activation_error_invalid))
                        LicenseActivationResult.UsedOnAnotherDevice -> screen.showError(getString(R.string.activation_error_used))
                        LicenseActivationResult.NetworkError -> screen.showError(getString(R.string.activation_error_network))
                        LicenseActivationResult.TemporaryServerError -> screen.showError(getString(R.string.activation_error_server))
                        LicenseActivationResult.Cancelled -> Unit
                    }
                }
            }
        } catch (_: Exception) {
            cancellation.cancel()
            activationCancellation = null
            activationInFlight = false
            screen.setBusy(false)
            screen.showError(getString(R.string.activation_error_server))
        }
    }

    private fun scanLicenseQrWithCamera() {
        fun launchScanner() {
            QrScanner(this) { text -> activationView?.setLicenseCode(text) }.show()
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchScanner()
        } else {
            requestPerm(Manifest.permission.CAMERA) { granted ->
                if (granted) launchScanner()
                else activationView?.showError(getString(R.string.portal_camera_denied))
            }
        }
    }

    private fun pickLicenseQrFromGallery() {
        pickPhoto { uri ->
            if (uri == null) return@pickPhoto
            val scanned = decodeQrImage(uri)
            if (scanned.isNullOrBlank()) activationView?.showError(getString(R.string.portal_no_qr))
            else activationView?.setLicenseCode(scanned)
        }
    }

    private fun decodeQrImage(uri: Uri): String? {
        var bitmap: Bitmap? = null
        return try {
            val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                val longestSide = maxOf(info.size.width, info.size.height).coerceAtLeast(1)
                val scale = minOf(1f, 1600f / longestSide)
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1)
                )
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            bitmap = decoded
            val pixels = IntArray(decoded.width * decoded.height)
            decoded.getPixels(pixels, 0, decoded.width, 0, 0, decoded.width, decoded.height)
            QrCodec.decodePixels(pixels, decoded.width, decoded.height)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } finally {
            bitmap?.recycle()
        }
    }

    /** QR scanner callback used by both the activation screen and NOVA PORTAL. */
    fun showQrError(messageRes: Int) {
        val text = getString(messageRes)
        val screen = activationView
        if (screen != null) screen.showError(text)
        else if (::message.isInitialized) message.error(text)
    }

    private fun setActivationSystemBars() {
        window.statusBarColor = ACTIVATION_BACKGROUND
        window.navigationBarColor = ACTIVATION_BACKGROUND
        val lightFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and lightFlags.inv()
    }

    private fun setMainSystemBars() {
        window.statusBarColor = C.BG
        window.navigationBarColor = C.BG
        var flags = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= 26) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.systemUiVisibility = flags
    }

    private fun buildBottomBar(): View {
        val bar = hbox().apply {
            background = roundRect(C.CARD, dp(24).toFloat(), C.BORDER, dp(1))
            softElevation(6f)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val defs = listOf(
            R.drawable.ic_nav_home to R.string.tab_home,
            R.drawable.ic_nav_dest to R.string.tab_destinations,
            R.drawable.ic_nav_navigate to R.string.tab_navigation,
            R.drawable.ic_nav_portal to R.string.tab_portal,
            R.drawable.ic_nav_radar to R.string.tab_radar,
            R.drawable.ic_nav_settings to R.string.tab_settings
        )
        val navLabelSize = if (defs.size >= 6) 10f else 11f
        defs.forEachIndexed { i, (icon, label) ->
            val item = vbox().apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(7), 0, dp(6))
                background = ripple(android.graphics.drawable.ColorDrawable(0), dp(18).toFloat())
                setOnClickListener { showPage(i) }
            }
            val iv = ImageView(this).apply { setImageResource(icon) }
            val tv = text(getString(label), navLabelSize, C.TEXT2, Fonts.medium).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            item.addView(iv, LinearLayout.LayoutParams(dp(24), dp(24)))
            item.addView(tv, lp().margins(t = dp(2)))
            bar.addView(item, lp(0, weight = 1f))
            navItems.add(iv to tv)
        }
        return FrameLayout(this).apply {
            setPadding(dp(12), dp(4), dp(12), dp(10))
            addView(bar, FrameLayout.LayoutParams(-1, -2))
        }
    }

    fun showPage(i: Int) {
        if (mainUiReady && i in pages.indices && (i != current || !pageShown)) selectTab(i)
    }

    private fun selectTab(i: Int) {
        if (!mainUiReady || pages.isEmpty()) return
        val target = i.coerceIn(pages.indices)
        if (pageShown) { pages[current].onHide(); pageShown = false }
        pages[current].view.visibility = View.GONE
        current = target
        val v = pages[target].view
        v.alpha = 0f; v.visibility = View.VISIBLE
        v.animate().alpha(1f).setDuration(160).start()
        navItems.forEachIndexed { k, (iv, tv) ->
            val sel = k == target
            iv.imageTintList = ColorStateList.valueOf(if (sel) C.GOLD_DEEP else C.TEXT3)
            tv.setTextColor(if (sel) C.GOLD_DEEP else C.TEXT2)
            (iv.parent as View).background = ripple(
                if (sel) roundRect(C.GOLD_PALE, dp(18).toFloat()) else android.graphics.drawable.ColorDrawable(0), dp(18).toFloat())
        }
        if (isStartedFlag) { pages[target].onShow(); pageShown = true }
    }

    // ------------------------------------------------------------- lifecycle
    private var isStartedFlag = false
    private var askedThisLaunch = false
    private var splashShowing = false
    private var startedOnce = false

    private fun startMainGpsRuntime() {
        if (!mainUiReady) return
        if (startedOnce) app.destinations.reload()   // pick up changes made in the Files app
        startedOnce = true
        app.gps.start()
    }

    private fun maybeAskPermission() {
        if (mainUiReady && !splashShowing && !app.gps.hasPermission() && !askedThisLaunch) {
            askedThisLaunch = true
            requestLocationPermission(false)
        }
    }

    override fun onStart() {
        super.onStart()
        isStartedFlag = true
        if (!mainUiReady && app.licenseManager.isActivated()) {
            current = 0
            createMainUi(showSplash = true)
            return
        }
        if (!mainUiReady) return
        startMainGpsRuntime()
        if (!pageShown) { pages[current].onShow(); pageShown = true }
        maybeAskPermission()   // deferred until the splash finishes
    }

    override fun onResume() {
        super.onResume()
        if (mainUiReady) app.gps.refresh()   // user may return from system settings
    }

    override fun onStop() {
        if (activationInFlight) {
            activationCancellation?.cancel()
            activationCancellation = null
            activationInFlight = false
            activationView?.setBusy(false)
        }
        if (mainUiReady) {
            if (pageShown) { pages[current].onHide(); pageShown = false }
            app.gps.stop()
            app.navigation.flush()
        }
        isStartedFlag = false
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", current)
    }

    // ------------------------------------------------------------- language
    fun applyLanguage() {
        recreate()
    }

    // ------------------------------------------------------------- permissions
    fun requestLocationPermission(userInitiated: Boolean) {
        val prefs = getSharedPreferences("nova_perm", MODE_PRIVATE)
        val askedBefore = prefs.getBoolean("asked", false)
        val rationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        if (userInitiated && askedBefore && !rationale && !app.gps.hasFine()) {
            // permanently denied (or approximate only) → system settings
            NovaDialog(this).title(getString(R.string.status_no_permission))
                .message(getString(R.string.permission_denied_forever))
                .button(getString(R.string.cancel), C.TEXT2) { it.dismiss() }
                .button(getString(R.string.open_app_settings), filled = true) {
                    it.dismiss()
                    try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))) } catch (_: Exception) {}
                }.show()
            return
        }
        prefs.edit().putBoolean("asked", true).apply()
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOC) app.gps.refresh()
        if (requestCode == REQ_PERM) { val cb = permCb; permCb = null; cb?.invoke(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) }
    }

    fun openLocationSettings() {
        try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------- photos
    private fun showPhotoError() {
        val error = getString(R.string.err_photo)
        val screen = activationView
        if (screen != null) screen.showError(error)
        else if (::message.isInitialized) message.error(error)
    }

    fun pickPhoto(cb: (Uri?) -> Unit) {
        photoCallback = cb
        val intent = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).apply { type = "image/*" }
        else Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }
        try { startActivityForResult(intent, REQ_PHOTO) } catch (_: Exception) {
            try { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }, REQ_PHOTO) }
            catch (_: Exception) { photoCallback = null; showPhotoError() }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_GENERIC) { val cb = resultCb; resultCb = null; cb?.invoke(resultCode == RESULT_OK, data) }
        if (requestCode == REQ_FOLDER) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) {
                if (app.destinations.grantFolder(uri)) { clearThumbCache(); message.success(getString(R.string.folder_linked)) }
                else message.error(getString(R.string.err_save))
            }
        }
        if (requestCode == REQ_PHOTO) {
            val cb = photoCallback; photoCallback = null
            cb?.invoke(if (resultCode == RESULT_OK) data?.data else null)
        }
    }

    // ------------------------------------------------------------- GPSarrow folder access (SAF)
    fun requestFolderAccess() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, LocxFolder.pickerInitialUri())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        try { startActivityForResult(i, REQ_FOLDER) } catch (_: Exception) { message.error(getString(R.string.err_save)) }
    }

    // ------------------------------------------------------------- generic result / permission hooks (Portal)
    private var resultCb: ((Boolean, Intent?) -> Unit)? = null
    private var permCb: ((Boolean) -> Unit)? = null
    fun startForResult(i: Intent, cb: (Boolean, Intent?) -> Unit) {
        resultCb = cb
        try { startActivityForResult(i, REQ_GENERIC) } catch (_: Exception) { resultCb = null; message.error(getString(R.string.err_save)) }
    }
    fun requestPerm(perm: String, cb: (Boolean) -> Unit) { permCb = cb; requestPermissions(arrayOf(perm), REQ_PERM) }

    fun thumbCache(path: String, size: Int): Bitmap? {
        val key = "$path@$size"
        thumbs.get(key)?.let { return it }
        val b = app.destinations.decodeThumb(path, size) ?: return null
        thumbs.put(key, b); return b
    }
    fun clearThumbCache() = thumbs.evictAll()

    override fun onDestroy() {
        activationCancellation?.cancel()
        activationCancellation = null
        activationExecutor?.shutdownNow()
        activationExecutor = null
        super.onDestroy()
    }

    companion object {
        private const val ACTIVATION_BACKGROUND = 0xFF0B1628.toInt()
        private const val ACTIVATION_SUCCESS_DELAY_MS = 650L
        private const val REQ_LOC = 11
        private const val REQ_PHOTO = 12
        private const val REQ_FOLDER = 13
        private const val REQ_GENERIC = 14
        private const val REQ_PERM = 15
    }
}
