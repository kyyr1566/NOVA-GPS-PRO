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
import com.nova.gpspro.ui.*
import java.util.Locale

class MainActivity : Activity() {

    lateinit var app: NovaApp; private set
    lateinit var units: Units; private set
    lateinit var message: CenterMessage; private set

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
        current = savedInstanceState?.getInt("page") ?: 0

        val root = FrameLayout(this).apply {
            setBackgroundColor(C.BG)
            layoutDirection = if (isRtl()) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        val column = vbox()
        val content = FrameLayout(this)
        pages = listOf(HomePage(this), DestinationsPage(this), NavigationPage(this), com.nova.gpspro.portal.PortalPage(this), RadarPage(this), SettingsPage(this))
        pages.forEach { content.addView(it.view, FrameLayout.LayoutParams(-1, -1)); it.view.visibility = View.GONE }
        column.addView(content, lp(h = 0, weight = 1f))
        column.addView(buildBottomBar(), lp())
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        message = CenterMessage(root)

        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsets.CONSUMED
        }
        setContentView(root)

        // Launch splash: on every real launch (fresh Activity), never on internal page
        // switches or on recreate() after a language change (savedInstanceState != null).
        if (savedInstanceState == null) {
            splashShowing = true
            val splash = SplashView(this, app.gps) {
                splashShowing = false
                if (isStartedFlag) maybeAskPermission()
            }
            root.addView(splash, FrameLayout.LayoutParams(-1, -1))
            splash.start()
        }
        selectTab(current)
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

    fun showPage(i: Int) { if (i != current || !pageShown) selectTab(i) }

    private fun selectTab(i: Int) {
        if (pageShown) { pages[current].onHide(); pageShown = false }
        pages[current].view.visibility = View.GONE
        current = i
        val v = pages[i].view
        v.alpha = 0f; v.visibility = View.VISIBLE
        v.animate().alpha(1f).setDuration(160).start()
        navItems.forEachIndexed { k, (iv, tv) ->
            val sel = k == i
            iv.imageTintList = ColorStateList.valueOf(if (sel) C.GOLD_DEEP else C.TEXT3)
            tv.setTextColor(if (sel) C.GOLD_DEEP else C.TEXT2)
            (iv.parent as View).background = ripple(
                if (sel) roundRect(C.GOLD_PALE, dp(18).toFloat()) else android.graphics.drawable.ColorDrawable(0), dp(18).toFloat())
        }
        if (isStartedFlag) { pages[i].onShow(); pageShown = true }
    }

    // ------------------------------------------------------------- lifecycle
    private var isStartedFlag = false
    private var askedThisLaunch = false
    private var splashShowing = false
    private var startedOnce = false

    private fun maybeAskPermission() {
        if (!splashShowing && !app.gps.hasPermission() && !askedThisLaunch) { askedThisLaunch = true; requestLocationPermission(false) }
    }

    override fun onStart() {
        super.onStart()
        isStartedFlag = true
        if (startedOnce) app.destinations.reload()   // pick up changes made in the Files app
        startedOnce = true
        app.gps.start()
        if (!pageShown) { pages[current].onShow(); pageShown = true }
        maybeAskPermission()   // deferred until the splash finishes
    }

    override fun onResume() {
        super.onResume()
        app.gps.refresh()   // user may return from system settings
    }

    override fun onStop() {
        if (pageShown) { pages[current].onHide(); pageShown = false }
        isStartedFlag = false
        app.gps.stop()
        app.navigation.flush()
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
    fun pickPhoto(cb: (Uri?) -> Unit) {
        photoCallback = cb
        val intent = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).apply { type = "image/*" }
        else Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }
        try { startActivityForResult(intent, REQ_PHOTO) } catch (_: Exception) {
            try { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }, REQ_PHOTO) }
            catch (_: Exception) { photoCallback = null; message.error(getString(R.string.err_photo)) }
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

    companion object {
        private const val REQ_LOC = 11
        private const val REQ_PHOTO = 12
        private const val REQ_FOLDER = 13
        private const val REQ_GENERIC = 14
        private const val REQ_PERM = 15
    }
}
