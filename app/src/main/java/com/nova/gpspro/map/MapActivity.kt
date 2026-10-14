package com.nova.gpspro.map

import android.location.Location
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.nova.gpspro.NovaApp
import com.nova.gpspro.R
import com.nova.gpspro.data.Destination
import com.nova.gpspro.ui.C
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.*

/**
 * Professional Map Screen — MapLibre Native, online vector/raster, 8 layers, 3D, real routing.
 * Keeps original arrow navigation untouched (separate system).
 * Online only: no download, 8 real layers, blue tools on right, instant switch, real navigation camera.
 */
class MapActivity : ComponentActivity() {

    companion object {
        const val EXTRA_DEST_ID = "dest_id"
        const val EXTRA_DEST_LAT = "dest_lat"
        const val EXTRA_DEST_LON = "dest_lon"
        const val EXTRA_DEST_NAME = "dest_name"
    }

    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private lateinit var styleManager: MapStyleManager
    private lateinit var cameraController: MapCameraController
    private lateinit var locationController: LocationTrackingController
    private lateinit var savedRepo: SavedLocationsMapRepository
    private lateinit var routingRepo: RoutingRepository
    private var navigationSession: NavigationSessionManager? = null

    private lateinit var destNameView: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var statsRow: LinearLayout
    private lateinit var dividerView: View
    private lateinit var primaryActionBtn: TextView
    private lateinit var cardTitleView: TextView
    private lateinit var cardSubtitleView: TextView
    private lateinit var labelView: TextView
    private lateinit var sideCol: LinearLayout

    private var targetDestination: Destination? = null
    private var pendingPreviewLat: Double? = null
    private var pendingPreviewLon: Double? = null
    private var pendingPreviewName: String? = null
    private var isNavigating: Boolean = false
    private var currentLayerKey: String = "dark"
    // Navigation follow state
    private var navigationListener: com.nova.gpspro.location.LocationEngine.Listener? = null
    private var lastRoute: RoutingRepository.RouteResult? = null
    private var routeRemainingMeters: Double = 0.0
    private var routeRemainingSeconds: Double = 0.0
    private var lastBearing: Float = 0f
    private var offRouteCount: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            MapLibre.getInstance(this)
        } catch (e: Exception) {
            android.util.Log.e("NOVA_MAP", "MapLibre init failed", e)
        } catch (e: Error) {
            android.util.Log.e("NOVA_MAP", "MapLibre native init failed", e)
            showMapError("تعذر تهيئة محرك الخرائط")
            return
        }

        // Restore destination from intent
        val destId = intent.getStringExtra(EXTRA_DEST_ID)
        val destLat = intent.getDoubleExtra(EXTRA_DEST_LAT, Double.NaN)
        val destLon = intent.getDoubleExtra(EXTRA_DEST_LON, Double.NaN)
        val destName = intent.getStringExtra(EXTRA_DEST_NAME)
        if (!destLat.isNaN() && !destLon.isNaN() && destName != null) {
            targetDestination = Destination(
                id = destId ?: "map_preview",
                name = destName,
                latitude = destLat,
                longitude = destLon,
                photoPath = null,
                notes = null,
                createdAt = System.currentTimeMillis()
            )
            pendingPreviewLat = destLat
            pendingPreviewLon = destLon
            pendingPreviewName = destName
        } else if (destId != null) {
            targetDestination = (application as? NovaApp)?.destinations?.get(destId)
            targetDestination?.let {
                pendingPreviewLat = it.latitude
                pendingPreviewLon = it.longitude
                pendingPreviewName = it.name
            }
        }

        val root = FrameLayout(this)
        try {
            mapView = MapView(this)
        } catch (e: Exception) {
            android.util.Log.e("NOVA_MAP", "MapView creation failed", e)
            showMapError("تعذر إنشاء الخريطة")
            return
        } catch (e: Error) {
            android.util.Log.e("NOVA_MAP", "MapView native error", e)
            showMapError("تعذر تحميل الخرائط (خطأ أصلي)")
            return
        }
        root.addView(mapView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        styleManager = MapStyleManager(this)
        currentLayerKey = try { styleManager.currentLayer } catch (_: Exception) { "dark" }
        cameraController = MapCameraController()
        cameraController.init(this)
        locationController = LocationTrackingController(this)
        val novaApp = application as NovaApp
        savedRepo = SavedLocationsMapRepository(novaApp.destinations)
        routingRepo = RoutingRepository(this)
        navigationSession = NavigationSessionManager(this, routingRepo, lifecycleScope)
        navigationSession?.onRouteUpdated = { route ->
            runOnUiThread {
                // Only auto-update stats if already navigating – preview must not show stats
                if (isNavigating && route != null) {
                    distanceViewGlobal?.let { it.text = formatDistance(route.distanceMeters) }
                    etaViewGlobal?.let { it.text = formatDuration(route.durationSeconds) }
                    try {
                        cardSubtitleView.text = "${formatDistance(route.distanceMeters)} • ${formatDuration(route.durationSeconds)}"
                    } catch (_: Exception) {}
                }
            }
        }
        navigationSession?.onError = { msg ->
            runOnUiThread { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show() }
        }

        try {
            mapView.onCreate(savedInstanceState)
        } catch (e: Exception) {
            android.util.Log.e("NOVA_MAP", "MapView onCreate failed", e)
            showMapError("تعذر تهيئة الخريطة")
            return
        }
        try {
            mapView.getMapAsync { mapLibreMap ->
                try {
                    map = mapLibreMap
                    cameraController.attach(mapLibreMap)
                    setupMap(mapLibreMap)
                    navigationSession?.attach(mapLibreMap)
                    // Show preview without auto-routing – stats must not appear before Start
                    targetDestination?.let { dest ->
                        runOnUiThread {
                            showPreviewCard(dest)
                            pendingPreviewLat?.let { lat ->
                                pendingPreviewLon?.let { lon ->
                                    val pos = CameraPosition.Builder().target(LatLng(lat, lon)).zoom(15.0).build()
                                    mapLibreMap.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 600)
                                }
                            }
                        }
                    } ?: run {
                        // No destination – ensure preview cleared and map normal
                        pendingPreviewLat?.let { lat ->
                            pendingPreviewLon?.let { lon ->
                                // If only lat/lon without Destination object (edge), still just center camera, no card
                                val pos = CameraPosition.Builder().target(LatLng(lat, lon)).zoom(14.0).build()
                                mapLibreMap.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 600)
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("NOVA_MAP", "Map ready failed", e)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("NOVA_MAP", "getMapAsync failed", e)
            showMapError("تعذر تحميل الخريطة")
            return
        }

        setupUi(root)
        // If opened without destination, ensure UI is normal (no card)
        if (targetDestination == null) {
            // post to ensure bottomBar initialized
            root.post { clearPreview(animate = false) }
        }
    }

    // Views for stats that are accessed from callbacks – keep global refs to avoid null
    private var distanceViewGlobal: TextView? = null
    private var etaViewGlobal: TextView? = null
    private var speedViewGlobal: TextView? = null

    private lateinit var distanceView: TextView
    private lateinit var etaView: TextView
    private lateinit var speedView: TextView

    private fun setupMap(map: MapLibreMap) {
        try { map.uiSettings.isRotateGesturesEnabled = true } catch (_: Exception) {}
        try { map.uiSettings.isTiltGesturesEnabled = true } catch (_: Exception) {}
        try { map.uiSettings.isZoomGesturesEnabled = true } catch (_: Exception) {}
        try { map.uiSettings.isScrollGesturesEnabled = true } catch (_: Exception) {}
        try { map.uiSettings.isDoubleTapGesturesEnabled = true } catch (_: Exception) {}

        // Online-only: no offline PMTiles – always use OpenFreeMap vector / Esri raster via styleManager

        val styleUrl = try { styleManager.currentStyleUrl() } catch (_: Exception) { "asset://mapstyles/dark.json" }
        val effectiveStyle = if (styleUrl.startsWith("pmtiles://")) "asset://mapstyles/dark.json" else styleUrl
        try {
            val builder = Style.Builder().fromUri(effectiveStyle)
            map.setStyle(builder, object : Style.OnStyleLoaded {
                override fun onStyleLoaded(style: Style) {
                    try {
                        styleManager.applyStyle(style, map)
                        savedRepo.attach(map)
                    } catch (e: Exception) { android.util.Log.e("NOVA_MAP", "style apply failed", e) }
                }
            })
        } catch (e: Exception) {
            android.util.Log.e("NOVA_MAP", "setStyle failed: $effectiveStyle", e)
            try {
                val fallback = Style.Builder().fromJson("{\"version\":8,\"sources\":{},\"layers\":[{\"id\":\"background\",\"type\":\"background\",\"paint\":{\"background-color\":\"#0B1220\"}}]}")
                map.setStyle(fallback) { style ->
                    try { savedRepo.attach(map) } catch (_: Exception) {}
                }
            } catch (_: Exception) {
                android.widget.Toast.makeText(this, "تعذر تحميل طبقة الخريطة — تحقق من الاتصال", android.widget.Toast.LENGTH_LONG).show()
            }
        }

        map.addOnCameraIdleListener {
            cameraController.saveCamera(map.cameraPosition)
        }
        cameraController.restoreCamera()?.let { pos ->
            map.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
        }
        // Tap saved pin
        map.addOnMapClickListener { point ->
            try {
                val screen = map.projection.toScreenLocation(point)
                val feats = map.queryRenderedFeatures(screen, "nova-dest-layer")
                if (feats.isNotEmpty()) {
                    val id = feats[0].getStringProperty("id")
                    val dest = if (id != null) savedRepo.getDestinationById(id) else null
                    if (dest != null) {
                        runOnUiThread { showMarkerPreview(dest) }
                        return@addOnMapClickListener true
                    }
                }
            } catch (_: Exception) {}
            false
        }
    }

    private fun setupUi(root: FrameLayout) {
        // ── Top white pill – matches reference image ──
        val topPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(0xFFFFFFFF.toInt())
            }
            elevation = dp(6).toFloat()
        }
        val backBtn = TextView(this).apply {
            text = "‹"; contentDescription = "رجوع"; gravity = Gravity.CENTER
            setTextColor(C.TEXT); textSize = 22f
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFFF0F1F5.toInt())
            }
            setOnClickListener { finish() }
        }
        // Center: label "الوجهة" + name
        val centerCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        labelView = TextView(this).apply { text = "الوجهة"; setTextColor(0xFF8A8E99.toInt()); textSize = 11f; gravity = Gravity.CENTER }
        destNameView = TextView(this).apply {
            text = targetDestination?.name ?: "—"
            setTextColor(if (targetDestination != null) 0xFF2E5BDA.toInt() else 0xFF8A8E99.toInt()); textSize = 16f; gravity = Gravity.CENTER
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        centerCol.addView(labelView); centerCol.addView(destNameView)
        val closeBtn = TextView(this).apply {
            text = "×"; contentDescription = "إغلاق / إلغاء الوجهة"; gravity = Gravity.CENTER
            setTextColor(0xFF6B7280.toInt()); textSize = 20f
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFFF0F1F5.toInt())
            }
            setOnClickListener { clearPreview() }
        }
        topPill.addView(backBtn, LinearLayout.LayoutParams(dp(32), dp(32)))
        topPill.addView(centerCol, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(8), 0, dp(8), 0) })
        topPill.addView(closeBtn, LinearLayout.LayoutParams(dp(32), dp(32)))
        val topParams = FrameLayout.LayoutParams(-1, -2).apply {
            gravity = Gravity.TOP
            setMargins(dp(12), dp(14), dp(12), 0)
        }
        root.addView(topPill, topParams)

        // ── Side controls – all on right, below top pill, slightly larger (42dp visual / 52dp touch) ──
        sideCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        // 6 tools on right – vertical, medium, NOVA blue, not overlapping top pill
        val layersBtn = smallIconButton("⬢", "الطبقات") { cycleLayers() }
        val compassBtn = smallIconButton("◉", "البوصلة") { map?.let { cameraController.bearingNorth(it) } }
        val myLocBtn = smallIconButton("◎", "موقعي") { centerToMyLocation() }
        val threeDBtn = smallIconButton("3D", "2D/3D") { toggle3D() }
        val zoomInBtn = smallIconButton("+", "تكبير") { map?.animateCamera(CameraUpdateFactory.zoomIn()) }
        val zoomOutBtn = smallIconButton("−", "تصغير") { map?.animateCamera(CameraUpdateFactory.zoomOut()) }
        // Order: zoomIn, zoomOut, myLoc, compass, layers, 3D – organized vertical on right
        listOf(zoomInBtn, zoomOutBtn, myLocBtn, compassBtn, layersBtn, threeDBtn).forEach { btn ->
            val wrapper = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { setMargins(0, dp(2), 0, dp(2)) }
                isClickable = false
            }
            wrapper.addView(btn, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER))
            wrapper.setOnClickListener { btn.performClick() }
            sideCol.addView(wrapper)
        }
        val sideParams = FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            // Right side, vertical, not overlapping top pill (topMargin 90dp) and not overlapping bottom bar
            topMargin = dp(90)
            marginEnd = dp(10)
            bottomMargin = dp(90)
        }
        root.addView(sideCol, sideParams)

        // ── Bottom floating card – small, not full width, rounded 20dp ──
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(0xFFFFFFFF.toInt())
            }
            elevation = dp(10).toFloat()
            visibility = View.GONE
        }
        // Handle
        val handle = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(2).toFloat(); setColor(0xFFE5E7EB.toInt())
            }
        }
        bottomBar.addView(handle, LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER; setMargins(0, 0, 0, dp(10)) })
        // Header row: icon + title/subtitle + arrow
        val headerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val iconBox = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFFEEF2FF.toInt())
            }
        }
        val bridgeIcon = TextView(this).apply { text = "▭"; gravity = Gravity.CENTER; setTextColor(0xFF3F74EA.toInt()); textSize = 18f }
        iconBox.addView(bridgeIcon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        headerRow.addView(iconBox, LinearLayout.LayoutParams(dp(44), dp(44)))
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        cardTitleView = TextView(this).apply {
            text = targetDestination?.name ?: ""
            setTextColor(C.TEXT); textSize = 16f; maxLines = 1
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        cardSubtitleView = TextView(this).apply {
            text = ""
            setTextColor(C.TEXT2); textSize = 13f
            visibility = View.GONE
        }
        titleCol.addView(cardTitleView); titleCol.addView(cardSubtitleView)
        headerRow.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(10), 0, dp(10), 0) })
        val arrowBtn = TextView(this).apply {
            text = "›"; gravity = Gravity.CENTER; setTextColor(0xFF3F74EA.toInt()); textSize = 20f
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFFF0F4FF.toInt())
            }
            setOnClickListener { if (isNavigating) endNavigation() else startNavigation() }
        }
        headerRow.addView(arrowBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
        bottomBar.addView(headerRow, LinearLayout.LayoutParams(-1, -2))

        // Divider – hidden in preview, visible in active
        dividerView = View(this).apply { setBackgroundColor(0xFFF0F1F5.toInt()); visibility = View.GONE }
        bottomBar.addView(dividerView, LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, dp(12), 0, dp(12)) })

        // Stats row: 3 cols – hidden until navigation starts
        statsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; visibility = View.GONE }
        distanceView = TextView(this).apply { text = "—"; setTextColor(C.TEXT); textSize = 16f; gravity = Gravity.CENTER; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) }
        val dLab = TextView(this).apply { text = "المسافة المتبقية"; setTextColor(C.TEXT2); textSize = 11f; gravity = Gravity.CENTER }
        val dCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        dCol.addView(distanceView); dCol.addView(dLab)
        etaView = TextView(this).apply { text = "—"; setTextColor(C.TEXT); textSize = 16f; gravity = Gravity.CENTER; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) }
        val eLab = TextView(this).apply { text = "الوقت المتوقع"; setTextColor(C.TEXT2); textSize = 11f; gravity = Gravity.CENTER }
        val eCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        eCol.addView(etaView); eCol.addView(eLab)
        speedView = TextView(this).apply { text = "—"; setTextColor(C.TEXT); textSize = 16f; gravity = Gravity.CENTER; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) }
        val sLab = TextView(this).apply { text = "السرعة الحالية"; setTextColor(C.TEXT2); textSize = 11f; gravity = Gravity.CENTER }
        val sCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        sCol.addView(speedView); sCol.addView(sLab)
        statsRow.addView(sCol, LinearLayout.LayoutParams(0, -2, 1f))
        statsRow.addView(View(this).apply { setBackgroundColor(0xFFE5E7EB.toInt()) }, LinearLayout.LayoutParams(dp(1), dp(36)).apply { setMargins(dp(4), 0, dp(4), 0) })
        statsRow.addView(eCol, LinearLayout.LayoutParams(0, -2, 1f))
        statsRow.addView(View(this).apply { setBackgroundColor(0xFFE5E7EB.toInt()) }, LinearLayout.LayoutParams(dp(1), dp(36)).apply { setMargins(dp(4), 0, dp(4), 0) })
        statsRow.addView(dCol, LinearLayout.LayoutParams(0, -2, 1f))
        bottomBar.addView(statsRow, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(14)) })

        distanceViewGlobal = distanceView
        etaViewGlobal = etaView
        speedViewGlobal = speedView

        // Primary action button – toggles Start / End
        primaryActionBtn = TextView(this).apply {
            text = "◤  ابدأ التوجه"
            setTextColor(android.graphics.Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF3F74EA.toInt(), 0xFF2E56C8.toInt())
            ).apply { shape = android.graphics.drawable.GradientDrawable.RECTANGLE; cornerRadius = dp(14).toFloat() }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { if (isNavigating) endNavigation() else startNavigation() }
        }
        bottomBar.addView(primaryActionBtn, LinearLayout.LayoutParams(-1, -2))

        val bottomParams = FrameLayout.LayoutParams(-1, -2).apply {
            gravity = Gravity.BOTTOM
            setMargins(dp(16), dp(8), dp(16), dp(16))
        }
        root.addView(bottomBar, bottomParams)

        // Attribution
        val attr = TextView(this).apply {
            text = "© OpenStreetMap"
            setTextColor(0xFF2856BC.toInt()); textSize = 9f
            setPadding(dp(4), dp(2), dp(4), dp(2))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(6).toFloat(); setColor(0xCCFFFFFF.toInt())
            }
            setOnClickListener {
                try {
                    val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.openstreetmap.org/copyright"))
                    startActivity(i)
                } catch (_: Exception) {}
            }
        }
        val attrParams = FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            setMargins(dp(12), dp(8), dp(12), dp(84))
        }
        root.addView(attr, attrParams)

        // Initial UI state: if destination already set via intent, preview will be shown by onCreate callback; else keep hidden
    }

    private fun showPreviewCard(dest: Destination) {
        targetDestination = dest
        pendingPreviewLat = dest.latitude
        pendingPreviewLon = dest.longitude
        pendingPreviewName = dest.name
        isNavigating = false
        // Update top pill
        try {
            destNameView.text = dest.name
            destNameView.setTextColor(0xFF2E5BDA.toInt())
        } catch (_: Exception) {}
        // Update card
        try {
            cardTitleView.text = dest.name
            cardSubtitleView.text = ""
            cardSubtitleView.visibility = View.GONE
        } catch (_: Exception) {}
        // Preview mode: hide stats, divider, set Start button
        bottomBar.visibility = View.VISIBLE
        statsRow.visibility = View.GONE
        dividerView.visibility = View.GONE
        primaryActionBtn.text = "◤  ابدأ التوجه"
        primaryActionBtn.setTextColor(android.graphics.Color.WHITE)
        primaryActionBtn.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xFF3F74EA.toInt(), 0xFF2E56C8.toInt())
        ).apply { shape = android.graphics.drawable.GradientDrawable.RECTANGLE; cornerRadius = dp(14).toFloat() }
        primaryActionBtn.setOnClickListener { startNavigation() }
        // Reset trip info to placeholders
        distanceView.text = "—"
        etaView.text = "—"
        speedView.text = "—"
        // Ensure no old route is shown – clear previous route if any
        try { navigationSession?.clearRoute() } catch (_: Exception) {}
        try { savedRepo.highlight(dest.id) } catch (_: Exception) {}
    }

    private fun showMarkerPreview(dest: Destination) {
        // Called when user taps a saved pin – shows only name + start, no auto-route, no stats
        targetDestination = dest
        pendingPreviewLat = dest.latitude
        pendingPreviewLon = dest.longitude
        pendingPreviewName = dest.name
        isNavigating = false
        try {
            destNameView.text = dest.name
            destNameView.setTextColor(0xFF2E5BDA.toInt())
        } catch (_: Exception) {}
        try {
            cardTitleView.text = dest.name
            cardSubtitleView.text = ""
            cardSubtitleView.visibility = View.GONE
        } catch (_: Exception) {}
        bottomBar.visibility = View.VISIBLE
        statsRow.visibility = View.GONE
        dividerView.visibility = View.GONE
        primaryActionBtn.text = "◤  ابدأ التوجه"
        primaryActionBtn.setTextColor(android.graphics.Color.WHITE)
        primaryActionBtn.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xFF3F74EA.toInt(), 0xFF2E56C8.toInt())
        ).apply { shape = android.graphics.drawable.GradientDrawable.RECTANGLE; cornerRadius = dp(14).toFloat() }
        primaryActionBtn.setOnClickListener { startNavigation() }
        distanceView.text = "—"; etaView.text = "—"; speedView.text = "—"
        try { navigationSession?.clearRoute() } catch (_: Exception) {}
        try { savedRepo.highlight(dest.id) } catch (_: Exception) {}
        // Animate camera to marker
        map?.let { m ->
            val pos = CameraPosition.Builder().target(LatLng(dest.latitude, dest.longitude)).zoom(15.0).build()
            m.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 600)
        }
        // Preview session but do NOT request route yet
        try { navigationSession?.previewDestination(dest) } catch (_: Exception) {}
    }

    private fun clearPreview(animate: Boolean = true) {
        targetDestination = null
        pendingPreviewLat = null
        pendingPreviewLon = null
        pendingPreviewName = null
        isNavigating = false
        try {
            destNameView.text = "—"
            destNameView.setTextColor(0xFF8A8E99.toInt())
        } catch (_: Exception) {}
        bottomBar.visibility = View.GONE
        statsRow.visibility = View.GONE
        dividerView.visibility = View.GONE
        // Clear route and highlight
        try { navigationSession?.clearRoute() } catch (_: Exception) {}
        try { savedRepo.clearHighlight() } catch (_: Exception) {}
        // Clear navigationEngine destination if any – but keep arrow logic untouched except clearing map route
        try {
            // Do not touch NovaApp navigation arrow unless explicitly ending – clearPreview just hides UI
        } catch (_: Exception) {}
        if (animate) {
            android.widget.Toast.makeText(this, "تم إلغاء الوجهة", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun haversine(toLat: Double, toLon: Double): Double {
        val loc = locationController.lastLocation() ?: return 0.0
        val R = 6371000.0
        val dLat = Math.toRadians(toLat - loc.latitude)
        val dLon = Math.toRadians(toLon - loc.longitude)
        val sinDLat = kotlin.math.sin(dLat / 2)
        val sinDLon = kotlin.math.sin(dLon / 2)
        val a = sinDLat * sinDLat + kotlin.math.cos(Math.toRadians(loc.latitude)) * kotlin.math.cos(Math.toRadians(toLat)) * sinDLon * sinDLon
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return R * c
    }

    private fun smallIconButton(text: String, desc: String, onClick: () -> Unit): View {
        return TextView(this).apply {
            this.text = text
            this.contentDescription = desc
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(C.PRIMARY_A) // NOVA blue
                setStroke(dp(1), 0xFF1E3A5F.toInt())
            }
            elevation = dp(2).toFloat()
            setOnClickListener { onClick() }
        }
    }

    private fun centerToMyLocation() {
        lifecycleScope.launch {
            val loc = locationController.lastLocation() ?: locationController.awaitFreshLocation()
            if (loc != null) {
                val pos = CameraPosition.Builder()
                    .target(LatLng(loc.latitude, loc.longitude))
                    .zoom(16.0)
                    .build()
                map?.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 800)
            }
        }
    }

    private fun toggle3D() {
        map?.let { m ->
            val is3D = styleManager.is3DEnabled
            styleManager.is3DEnabled = !is3D
            m.getStyle { style -> styleManager.applyStyle(style, m) }
            val current = m.cameraPosition
            val tilt = if (!is3D) 45.0 else 0.0
            m.animateCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(current).tilt(tilt).build()
            ), 400)
        }
    }

    private fun cycleLayers() {
        val order = styleManager.orderedLayers
        val idx = order.indexOf(currentLayerKey).let { if (it == -1) 0 else it }
        val next = styleManager.nextLayer(currentLayerKey)
        currentLayerKey = next
        styleManager.setLayer(next)
        val cam = map?.cameraPosition
        val url = styleManager.currentStyleUrl()
        map?.setStyle(Style.Builder().fromUri(url)) { style ->
            styleManager.applyStyle(style, map!!)
            savedRepo.attach(map!!)
            cam?.let { map?.moveCamera(CameraUpdateFactory.newCameraPosition(it)) }
            if (isNavigating) {
                navigationSession?.getRoute()?.let { navigationSession?.drawRoute(it) }
            }
        }
        android.widget.Toast.makeText(this, styleManager.layerLabel(next), android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun showLayersSheet() {
        // Instant cycle – no empty sheet, no cancel – per spec 8
        cycleLayers()
    }

    private fun showMapSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }
        box.addView(TextView(this).apply { text = "الطبقات (8 طبقات حقيقية)"; setTextColor(C.TEXT); textSize = 14f; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) })
        styleManager.orderedLayers.forEach { key ->
            val isCurrent = key == currentLayerKey
            box.addView(TextView(this).apply {
                text = (if (isCurrent) "● " else "○ ") + styleManager.layerLabel(key)
                setTextColor(if (isCurrent) C.PRIMARY_A else C.TEXT)
                textSize = 13f
                setPadding(dp(8), dp(10), dp(8), dp(10))
                background = if (isCurrent) android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.RECTANGLE; cornerRadius = dp(8).toFloat(); setColor(C.CARD_SOFT); setStroke(dp(1), C.PRIMARY_A) } else null
                setOnClickListener {
                    currentLayerKey = key
                    styleManager.setLayer(key)
                    val cam = map?.cameraPosition
                    val url = styleManager.currentStyleUrl()
                    map?.setStyle(Style.Builder().fromUri(url)) { style ->
                        styleManager.applyStyle(style, map!!)
                        savedRepo.attach(map!!)
                        cam?.let { map?.moveCamera(CameraUpdateFactory.newCameraPosition(it)) }
                        if (isNavigating) navigationSession?.getRoute()?.let { navigationSession?.drawRoute(it) }
                    }
                    android.widget.Toast.makeText(this@MapActivity, styleManager.layerLabel(key), android.widget.Toast.LENGTH_SHORT).show()
                }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, 0) }
            })
        }
        box.addView(View(this).apply { setBackgroundColor(C.BORDER); layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, dp(12), 0, dp(12)) } })
        box.addView(TextView(this).apply { text = "وضع العرض: ${if (styleManager.is3DEnabled) "3D" else "2D"}"; setTextColor(C.TEXT); textSize = 13f })
        box.addView(TextView(this).apply {
            text = if (styleManager.is3DEnabled) "التبديل إلى 2D" else "التبديل إلى 3D"
            setTextColor(android.graphics.Color.WHITE); gravity = Gravity.CENTER; textSize = 13f
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.RECTANGLE; cornerRadius = dp(8).toFloat(); setColor(C.PRIMARY_A) }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { toggle3D(); try { (parent as? LinearLayout)?.let { } } catch (_:Exception){} }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, 0) }
        })
        box.addView(View(this).apply { setBackgroundColor(C.BORDER); layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, dp(12), 0, dp(12)) } })
        box.addView(TextView(this).apply { text = "محرك التوجيه (Online): ${routingRepo.provider.key}"; setTextColor(C.TEXT2); textSize = 12f })
        box.addView(TextView(this).apply {
            text = "تغيير مصدر التوجيه"
            setTextColor(C.PRIMARY_A); textSize = 13f; setPadding(0, dp(6), 0, 0)
            setOnClickListener {
                val providers = routingRepo.availableProviders()
                val cur = routingRepo.provider.key
                val next = when (cur) {
                    "fosgis" -> "valhalla"
                    "valhalla" -> "osrm_demo"
                    else -> "fosgis"
                }
                routingRepo.provider = when (next) {
                    "valhalla" -> RoutingRepository.Provider.VALHALLA
                    "osrm_demo" -> RoutingRepository.Provider.OSRM_DEMO
                    else -> RoutingRepository.Provider.FOSSGIS
                }
                android.widget.Toast.makeText(this@MapActivity, "المصدر: $next", android.widget.Toast.LENGTH_SHORT).show()
            }
        })
        box.addView(TextView(this).apply { text = "ملاحظة: الخرائط Online فقط – لا يوجد تنزيل. التوجيه يتطلب إنترنت (FOSSGIS مجاني للإنتاج، Valhalla بديل). لـ 10k يومياً يُنصح بخادم ذاتي."; setTextColor(0xFF8A8E99.toInt()); textSize = 11f; setPadding(0, dp(10), 0, 0) })
        com.nova.gpspro.ui.NovaDialog(this).title("الإعدادات والطبقات").content(box).button("إغلاق", C.TEXT2) { it.dismiss() }.show()
    }

    private fun showMapError(msg: String) {
        try {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
        } catch (_: Exception) {}
        try {
            val fallback = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(24), dp(24), dp(24))
                setBackgroundColor(0xFF0B1220.toInt())
            }
            fallback.addView(TextView(this).apply {
                text = msg
                setTextColor(0xFFFAF7F0.toInt())
                textSize = 16f
                gravity = Gravity.CENTER
            })
            fallback.addView(TextView(this).apply {
                text = "إغلاق"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    setColor(C.PRIMARY_A)
                }
                setPadding(dp(16), dp(10), dp(16), dp(10))
                setOnClickListener { finish() }
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, dp(24), 0, 0) }
            })
            setContentView(fallback)
        } catch (_: Exception) {
            try { finish() } catch (_: Exception) {}
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onStart() { super.onStart(); if (::mapView.isInitialized) try { mapView.onStart() } catch (_: Exception) {} }
    override fun onResume() { super.onResume(); if (::mapView.isInitialized) try { mapView.onResume() } catch (_: Exception) {} }
    override fun onPause() { if (::mapView.isInitialized) try { mapView.onPause() } catch (_: Exception) {}; super.onPause() }
    override fun onStop() { if (::mapView.isInitialized) try { mapView.onStop() } catch (_: Exception) {}; super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); if (::mapView.isInitialized) try { mapView.onLowMemory() } catch (_: Exception) {} }
    override fun onDestroy() { try { stopNavigationFollow() } catch (_: Exception) {}; if (::mapView.isInitialized) try { mapView.onDestroy() } catch (_: Exception) {}; try { navigationSession?.detach() } catch (_: Exception) {}; super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); if (::mapView.isInitialized) try { mapView.onSaveInstanceState(outState) } catch (_: Exception) {} }
}
