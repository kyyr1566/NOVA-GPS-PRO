package com.nova.gpspro.map

import android.location.Location
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
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
 * Professional Map Screen — MapLibre Native, offline PMTiles, vector, 3D, routing.
 * Keeps original arrow navigation untouched (separate system).
 * Fixed: destination clear on ×, preview card only name+start, turquoise route on start, enlarged pins/buttons, right-side column, honest offline, instant layer cycle.
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
    // Satellite selection state
    private var satelliteSelectionMode: Boolean = false
    private var satFirstPoint: LatLng? = null
    private var satSecondPoint: LatLng? = null

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

        // Handle offline PMTiles – respect Online/Smart/Offline mode
        val mode = try { OfflineMapManager.getMode(this) } catch (_: Exception) { OfflineMapManager.MapMode.SMART }
        val useOffline = mode != OfflineMapManager.MapMode.ONLINE && OfflineMapManager.listOfflineFiles(this).isNotEmpty()
        val offlineStyleJson = if (useOffline) try { OfflineMapManager.buildOfflineStyleJson(this) } catch (_: Exception) { null } else null
        if (offlineStyleJson != null) {
            try {
                val builder = Style.Builder().fromJson(offlineStyleJson)
                map.setStyle(builder, object : Style.OnStyleLoaded {
                    override fun onStyleLoaded(style: Style) {
                        try {
                            styleManager.applyStyle(style, map)
                            savedRepo.attach(map)
                        } catch (e: Exception) { android.util.Log.e("NOVA_MAP", "offline style apply failed", e) }
                    }
                })
                map.addOnCameraIdleListener { cameraController.saveCamera(map.cameraPosition) }
                cameraController.restoreCamera()?.let { pos -> map.moveCamera(CameraUpdateFactory.newCameraPosition(pos)) }
                // Click listener – satellite selection takes priority, then saved pins
                map.addOnMapClickListener { point ->
                    if (satelliteSelectionMode) {
                        runOnUiThread { onSatelliteMapClick(point) }
                        return@addOnMapClickListener true
                    }
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
                return
            } catch (e: Exception) {
                android.util.Log.e("NOVA_MAP", "offline style failed, falling back to online", e)
            }
        }

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
        // Tap – satellite selection takes priority
        map.addOnMapClickListener { point ->
            if (satelliteSelectionMode) {
                runOnUiThread { onSatelliteMapClick(point) }
                return@addOnMapClickListener true
            }
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
        val layersBtn = smallIconButton("⬢", "الطبقات") { cycleLayers() }
        val compassBtn = smallIconButton("◉", "الشمال") { map?.let { cameraController.bearingNorth(it) } }
        val myLocBtn = smallIconButton("◎", "موقعي") { centerToMyLocation() }
        val threeDBtn = smallIconButton("3D", "ثلاثي الأبعاد") { toggle3D() }
        val zoomInBtn = smallIconButton("+", "تكبير") { map?.animateCamera(CameraUpdateFactory.zoomIn()) }
        val zoomOutBtn = smallIconButton("−", "تصغير") { map?.animateCamera(CameraUpdateFactory.zoomOut()) }
        val settingsBtn = smallIconButton("⚙", "الإعدادات") { showMapSettings() }
        val downloadBtn = smallIconButton("⭳", "تنزيل الخرائط") { showOfflineManager() }

        // Order: layers, compass, myLoc, 3D, zoomIn, zoomOut, settings, download – all visible always
        listOf(layersBtn, compassBtn, myLocBtn, threeDBtn, zoomInBtn, zoomOutBtn, settingsBtn, downloadBtn).forEach { btn ->
            val wrapper = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { setMargins(0, dp(2), 0, dp(2)) }
                isClickable = false
            }
            wrapper.addView(btn, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER))
            wrapper.setOnClickListener { btn.performClick() }
            sideCol.addView(wrapper)
        }
        val sideParams = FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(0, dp(78), dp(8), 0)
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
            setTextColor(C.PRIMARY_A) // blue matching identity
            textSize = 15f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFFFFFFFF.toInt())
                setStroke(dp(1), 0xFFE5E7EB.toInt())
            }
            elevation = dp(3).toFloat()
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

    private fun cycleMode() {
        val current = OfflineMapManager.getMode(this)
        val next = when (current) {
            OfflineMapManager.MapMode.ONLINE -> OfflineMapManager.MapMode.SMART
            OfflineMapManager.MapMode.SMART -> OfflineMapManager.MapMode.OFFLINE
            OfflineMapManager.MapMode.OFFLINE -> OfflineMapManager.MapMode.ONLINE
        }
        OfflineMapManager.setMode(this, next)
        val label = when (next) {
            OfflineMapManager.MapMode.ONLINE -> "متصل"
            OfflineMapManager.MapMode.SMART -> "ذكي"
            OfflineMapManager.MapMode.OFFLINE -> "غير متصل"
        }
        android.widget.Toast.makeText(this, "الوضع: $label", android.widget.Toast.LENGTH_SHORT).show()
        map?.let { m ->
            m.getStyle { style -> styleManager.applyStyle(style, m) }
        }
    }

    private fun cycleLayers() {
        val order = listOf("dark", "streets", "satellite")
        val labels = mapOf("dark" to "الخريطة الليلية", "streets" to "خريطة الشوارع", "satellite" to "القمر الصناعي")
        val idx = order.indexOf(currentLayerKey).let { if (it == -1) 0 else it }
        val next = order[(idx + 1) % order.size]
        currentLayerKey = next
        styleManager.setLayer(next)
        val cam = map?.cameraPosition
        val url = styleManager.currentStyleUrl()
        val effective = if (url.startsWith("pmtiles://")) "asset://mapstyles/dark.json" else url
        map?.setStyle(Style.Builder().fromUri(effective)) { style ->
            styleManager.applyStyle(style, map!!)
            savedRepo.attach(map!!)
            // keep map position
            cam?.let { map?.moveCamera(CameraUpdateFactory.newCameraPosition(it)) }
            // redraw active route if navigating
            if (isNavigating) {
                navigationSession?.getRoute()?.let { navigationSession?.drawRoute(it) }
            }
        }
        android.widget.Toast.makeText(this, labels[next] ?: next, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun showLayersSheet() {
        // Instant cycle – no empty sheet, no cancel – per spec 8
        cycleLayers()
    }

    private fun showMapSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }
        val mode = OfflineMapManager.getMode(this)
        val modeLabel = when (mode) {
            OfflineMapManager.MapMode.ONLINE -> "متصل (Online)"
            OfflineMapManager.MapMode.SMART -> "ذكي (Smart)"
            OfflineMapManager.MapMode.OFFLINE -> "غير متصل (Offline)"
        }
        box.addView(TextView(this).apply { text = "وضع الخريطة: $modeLabel"; setTextColor(C.TEXT); textSize = 14f })
        box.addView(TextView(this).apply { text = "الطبقة: ${styleManager.currentStyleUrl()}"; setTextColor(C.TEXT2); textSize = 12f; setPadding(0, dp(6), 0, 0) })
        box.addView(TextView(this).apply {
            text = "إدارة الخرائط غير المتصلة"
            setTextColor(C.PRIMARY_A)
            textSize = 14f
            setPadding(0, dp(12), 0, 0)
            setOnClickListener { showOfflineManager() }
        })
        box.addView(TextView(this).apply {
            text = "تنزيل شبكة الطرق للملاحة غير المتصلة (BRouter)"
            setTextColor(C.PRIMARY_A)
            textSize = 14f
            setPadding(0, dp(8), 0, 0)
            setOnClickListener { showRoadNetworkManager() }
        })
        box.addView(TextView(this).apply {
            text = "صور الأقمار الصناعية (محدد منطقة)"
            setTextColor(C.PRIMARY_A)
            textSize = 14f
            setPadding(0, dp(8), 0, 0)
            setOnClickListener { showSatelliteManager() }
        })
        com.nova.gpspro.ui.NovaDialog(this).title("إعدادات الخرائط").content(box)
            .button(getString(R.string.portal_done), filled = true) { it.dismiss() }.show()
    }

    private fun showOfflineManager() {
        val scroll = android.widget.ScrollView(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        scroll.addView(box)
        val manifest = try { MapPackageManifest.defaultPackages() } catch (_: Exception) { null }
        val pkgs = manifest?.packages ?: OfflineMapManager.availableGovernorates().map { (id, ar) ->
            MapPackageManifest.PackageInfo(id, ar, id, "https://github.com/kyyr1566/NOVA-GPS-PRO/releases/download/maps-v1/$id.pmtiles", 0L, null, null, "1")
        }
        box.addView(TextView(this).apply {
            text = "الخرائط للعمل دون إنترنت (PMTiles Vector)\nالمصدر: OpenStreetMap (ODbL) عبر planetiler. كل محافظة ملف منفصل – يظهر الحجم والتقدم والتحقق."
            setTextColor(C.TEXT2); textSize = 12f; setPadding(0, 0, 0, dp(10))
        })
        pkgs.forEach { pkg ->
            val file = OfflineMapManager.pmtilesFile(this, pkg.id)
            val has = file.exists() && file.length() > 1024 // preliminary, will verify
            val verified = if (has) OfflineMapManager.verifySha256(file, pkg.sha256) && file.length() > 1024 else false
            val sizeMb = if (pkg.sizeBytes > 0) String.format("%.0f MB", pkg.sizeBytes / 1024.0 / 1024.0) else "—"
            // Determine state: UPDATE_AVAILABLE if verified but size differs >15% or sha mismatch (if sha provided)
            val updateAvailable = verified && pkg.sizeBytes > 0 && kotlin.math.abs(file.length() - pkg.sizeBytes) > pkg.sizeBytes * 0.15
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(10), dp(8), dp(10)) }
            val nameCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f); isClickable = false }
            // Name – clicking it must NOT delete (spec)
            nameCol.addView(TextView(this).apply { text = pkg.nameAr; setTextColor(C.TEXT); textSize = 14f; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL); isClickable = false })
            val infoView = TextView(this).apply {
                text = when {
                    verified && updateAvailable -> "تحديث متاح • ${String.format("%.1f MB", file.length()/1024.0/1024.0)}"
                    verified -> "✓ مكتمل • ${String.format("%.1f MB", file.length()/1024.0/1024.0)}"
                    has && !verified -> "فشل التحقق • ${String.format("%.1f MB", file.length()/1024.0/1024.0)}"
                    else -> "غير محمّل • الحجم: $sizeMb"
                }
                setTextColor(when {
                    verified && !updateAvailable -> 0xFF1B8B2A.toInt()
                    updateAvailable -> 0xFFB7791F.toInt()
                    has && !verified -> 0xFFD93A3A.toInt()
                    else -> C.TEXT2
                }); textSize = 11f
            }
            nameCol.addView(infoView)
            row.addView(nameCol)
            // Actions column: primary + delete independent
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val primary = TextView(this).apply {
                // Initial state
                when {
                    verified && updateAvailable -> { text = "تحديث"; setTextColor(C.PRIMARY_A) }
                    verified -> { text = "✓ مكتمل"; setTextColor(0xFF1B8B2A.toInt()) }
                    else -> { text = "تنزيل"; setTextColor(C.PRIMARY_A) }
                }
                textSize = 13f; gravity = Gravity.CENTER; minWidth = dp(78)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(C.CARD_SOFT); setStroke(dp(1), C.BORDER)
                }
                setPadding(dp(10), dp(7), dp(10), dp(7))
            }
            val deleteBtn = TextView(this).apply {
                text = "حذف"; setTextColor(0xFFD93A3A.toInt())
                textSize = 13f; gravity = Gravity.CENTER; minWidth = dp(52)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(0xFFFFE9E9.toInt()); setStroke(dp(1), 0xFFFFC9C9.toInt())
                }
                setPadding(dp(10), dp(7), dp(10), dp(7))
                visibility = if (verified || has) View.VISIBLE else View.GONE
            }
            // Clicking name must NOT trigger delete – so no listener on nameCol/row
            deleteBtn.setOnClickListener {
                OfflineMapManager.deletePackage(this@MapActivity, pkg.id)
                primary.text = "تنزيل"; primary.setTextColor(C.PRIMARY_A)
                primary.isEnabled = true
                deleteBtn.visibility = View.GONE
                infoView.text = "غير محمّل • الحجم: $sizeMb"; infoView.setTextColor(C.TEXT2)
                android.widget.Toast.makeText(this@MapActivity, "تم الحذف: ${pkg.nameAr}", android.widget.Toast.LENGTH_SHORT).show()
                // Remove observer? keep but reset
            }
            primary.setOnClickListener {
                // If already verified and not update, do nothing (don't re-download)
                if (OfflineMapManager.hasOffline(this@MapActivity, pkg.id) && OfflineMapManager.verifySha256(OfflineMapManager.pmtilesFile(this@MapActivity, pkg.id), pkg.sha256) && !updateAvailable) {
                    android.widget.Toast.makeText(this@MapActivity, "الملف مثبت ويعمل – افتح الخريطة في وضع الطيران للاختبار", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                // If verified but update available -> re-download
                // Else start download
                try {
                    primary.text = "0%"; primary.setTextColor(C.TEXT2); primary.isEnabled = false
                    infoView.text = "جارٍ التحميل • $sizeMb"; infoView.setTextColor(C.PRIMARY_A)
                    MapDownloadWorker.enqueue(this@MapActivity, pkg)
                    android.widget.Toast.makeText(this@MapActivity, "بدء تنزيل ${pkg.nameAr} ($sizeMb)", android.widget.Toast.LENGTH_SHORT).show()
                    val wm = androidx.work.WorkManager.getInstance(this@MapActivity)
                    wm.getWorkInfosByTagLiveData(pkg.id).observe(this@MapActivity) { infos ->
                        val info = infos?.firstOrNull() ?: return@observe
                        val prog = info.progress.getInt("progress", -1)
                        val state = info.state
                        if (prog in 0..100 && state == androidx.work.WorkInfo.State.RUNNING) {
                            primary.text = "$prog%"; infoView.text = "جارٍ التحميل • $prog%"
                        } else if (state == androidx.work.WorkInfo.State.SUCCEEDED) {
                            val f = OfflineMapManager.pmtilesFile(this@MapActivity, pkg.id)
                            // Verify truly can be opened: check PMTiles header (first bytes) + SHA
                            val canOpen = try {
                                val raf = java.io.RandomAccessFile(f, "r")
                                val header = ByteArray(2); raf.read(header); raf.close()
                                // PMTiles magic is 'PM' (0x50 0x4D) at start
                                header[0] == 0x50.toByte() && header[1] == 0x4D.toByte()
                            } catch (_: Exception) { false }
                            val ok = f.exists() && f.length() > 1024 && OfflineMapManager.verifySha256(f, pkg.sha256) && (canOpen || pkg.sha256 != null)
                            // If sha null, rely on canOpen + size > 1024
                            val finalOk = if (pkg.sha256.isNullOrBlank()) f.exists() && f.length() > 1024 && canOpen else ok
                            if (finalOk) {
                                primary.text = "✓ مكتمل"; primary.setTextColor(0xFF1B8B2A.toInt()); primary.isEnabled = true
                                infoView.text = "✓ مكتمل • ${String.format("%.1f MB", f.length()/1024.0/1024.0)}"; infoView.setTextColor(0xFF1B8B2A.toInt())
                                deleteBtn.visibility = View.VISIBLE
                                android.widget.Toast.makeText(this@MapActivity, "تم تثبيت ${pkg.nameAr} – افتح وضع الطيران لاختبار ${String.format("%.0f MB", f.length()/1024.0/1024.0)}", android.widget.Toast.LENGTH_LONG).show()
                                try { map?.let { m -> m.getStyle { style -> styleManager.applyStyle(style, m) } } } catch (_: Exception) {}
                            } else {
                                primary.text = "فشل"; primary.setTextColor(0xFFD93A3A.toInt()); primary.isEnabled = true
                                infoView.text = if (!canOpen) "فشل – الملف تالف" else "فشل التحقق"; infoView.setTextColor(0xFFD93A3A.toInt())
                                deleteBtn.visibility = View.VISIBLE
                                android.widget.Toast.makeText(this@MapActivity, "فشل التحقق من ${pkg.nameAr} – الملف لا يمكن فتحه كـ PMTiles", android.widget.Toast.LENGTH_LONG).show()
                            }
                        } else if (state == androidx.work.WorkInfo.State.FAILED) {
                            val err = info.outputData.getString("error") ?: "فشل"
                            primary.isEnabled = true
                            if (err.contains("404") || err.contains("Not Found")) {
                                primary.text = "غير متوفر"; primary.setTextColor(0xFFD93A3A.toInt())
                                infoView.text = "فشل – غير متوفر على الخادم"; infoView.setTextColor(0xFFD93A3A.toInt())
                                android.widget.Toast.makeText(this@MapActivity, "الملف ${pkg.nameAr} غير متوفر بعد على الخادم. يحتاج إنتاج PMTiles ورفعه إلى Releases.", android.widget.Toast.LENGTH_LONG).show()
                            } else if (err.contains("sha256")) {
                                primary.text = "فشل التحقق"; primary.setTextColor(0xFFD93A3A.toInt())
                                infoView.text = "فشل التحقق"; infoView.setTextColor(0xFFD93A3A.toInt())
                            } else {
                                primary.text = "فشل"; primary.setTextColor(0xFFD93A3A.toInt())
                                infoView.text = "فشل – $err"; infoView.setTextColor(0xFFD93A3A.toInt())
                                android.widget.Toast.makeText(this@MapActivity, "فشل تنزيل ${pkg.nameAr}: $err – إعادة المحاولة متاحة", android.widget.Toast.LENGTH_LONG).show()
                            }
                            deleteBtn.visibility = if (OfflineMapManager.hasOffline(this@MapActivity, pkg.id)) View.VISIBLE else View.GONE
                        } else if (state == androidx.work.WorkInfo.State.CANCELLED) {
                            primary.text = "أُلغي"; primary.setTextColor(C.TEXT2); primary.isEnabled = true
                            infoView.text = "أُلغي"; infoView.setTextColor(C.TEXT2)
                        } else if (state == androidx.work.WorkInfo.State.ENQUEUED || state == androidx.work.WorkInfo.State.BLOCKED) {
                            primary.text = "في الانتظار"; primary.setTextColor(C.TEXT2)
                            infoView.text = "في الانتظار"; infoView.setTextColor(C.TEXT2)
                        }
                    }
                } catch (e: Exception) {
                    primary.text = "خطأ"; primary.isEnabled = true
                    android.widget.Toast.makeText(this@MapActivity, "تعذر البدء: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            actions.addView(primary, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, dp(6), 0) })
            actions.addView(deleteBtn, LinearLayout.LayoutParams(-2, -2))
            row.addView(actions, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(8), 0, 0, 0) })
            box.addView(row)
            box.addView(View(this).apply { setBackgroundColor(C.BORDER); layoutParams = LinearLayout.LayoutParams(-1, dp(1)) })
        }
        box.addView(TextView(this).apply {
            text = "الاختبار: حمّل محافظة (مثلاً حلبجة 5MB للاختبار السريع)، ثم فعّل وضع الطيران وأعد فتح الخريطة – يجب أن ترى شوارع المحافظة دون إنترنت عبر pmtiles://file:// مع قابلية فتح الملف والتحقق."
            setTextColor(C.TEXT2); textSize = 11f; setPadding(dp(4), dp(10), dp(4), dp(4))
        })
        com.nova.gpspro.ui.NovaDialog(this).title("خرائط المحافظات (PMTiles) – الحالات: غير محمّل/جارٍ/مكتمل/فشل/تحديث").content(scroll)
            .button("إغلاق", C.TEXT2) { it.dismiss() }.show()
    }

    private fun showRoadNetworkManager() {
        val scroll = android.widget.ScrollView(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        scroll.addView(box)
        box.addView(TextView(this).apply {
            text = "شبكة الطرق للملاحة دون إنترنت (BRouter RD5)\nالمصدر: OpenStreetMap عبر BRouter segments (ODbL). كل قطعة 5×5 درجات – يظهر الحجم والتقدم والتحقق. لا يظهر مكتمل قبل نجاح التحقق وقابلية القراءة."
            setTextColor(C.TEXT2); textSize = 12f; setPadding(0, 0, 0, dp(10))
        })
        val segments = OfflineRoutingManager.availableSegments()
        segments.forEach { seg ->
            val file = OfflineRoutingManager.segmentFile(this, seg.id)
            val hasRaw = file.exists() && file.length() > 1024
            // Verify before showing completed: size + SHA (if provided) + RD5 readability (first 4 bytes not empty)
            val canRead = try { val raf = java.io.RandomAccessFile(file, "r"); val b = ByteArray(4); val n = raf.read(b); raf.close(); n==4 && b[0]!=0.toByte() } catch (_: Exception) { false }
            val verified = hasRaw && OfflineRoutingManager.verifySha256(file, seg.sha256) && canRead
            val sizeMb = String.format("%.0f MB", seg.sizeBytes / 1024.0 / 1024.0)
            val updateAvailable = verified && kotlin.math.abs(file.length() - seg.sizeBytes) > seg.sizeBytes * 0.15
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(10), dp(8), dp(10)) }
            val nameCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f); isClickable = false }
            nameCol.addView(TextView(this).apply { text = "${seg.id} – ${seg.nameAr}"; setTextColor(C.TEXT); textSize = 13f; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) })
            val infoView = TextView(this).apply {
                text = when {
                    verified && updateAvailable -> "تحديث متاح • ${String.format("%.1f MB", file.length()/1024.0/1024.0)}"
                    verified -> "✓ مكتمل • ${String.format("%.1f MB", file.length()/1024.0/1024.0)}"
                    hasRaw && !verified -> "فشل التحقق • ${String.format("%.1f MB", file.length()/1024.0/1024.0)}"
                    else -> "غير محمّل • الحجم: $sizeMb"
                }
                setTextColor(when {
                    verified && !updateAvailable -> 0xFF1B8B2A.toInt()
                    updateAvailable -> 0xFFB7791F.toInt()
                    hasRaw && !verified -> 0xFFD93A3A.toInt()
                    else -> C.TEXT2
                }); textSize = 11f
            }
            nameCol.addView(infoView)
            // Name click must NOT trigger download/delete
            row.addView(nameCol)
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val primary = TextView(this).apply {
                when {
                    verified && updateAvailable -> { text = "تحديث"; setTextColor(C.PRIMARY_A) }
                    verified -> { text = "✓ مكتمل"; setTextColor(0xFF1B8B2A.toInt()) }
                    else -> { text = "تنزيل"; setTextColor(C.PRIMARY_A) }
                }
                textSize = 13f; gravity = Gravity.CENTER; minWidth = dp(78)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(C.CARD_SOFT); setStroke(dp(1), C.BORDER)
                }
                setPadding(dp(10), dp(7), dp(10), dp(7))
            }
            val deleteBtn = TextView(this).apply {
                text = "حذف"; setTextColor(0xFFD93A3A.toInt())
                textSize = 13f; gravity = Gravity.CENTER; minWidth = dp(52)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(0xFFFFE9E9.toInt()); setStroke(dp(1), 0xFFFFC9C9.toInt())
                }
                setPadding(dp(10), dp(7), dp(10), dp(7))
                visibility = if (verified || hasRaw) View.VISIBLE else View.GONE
            }
            val retryBtn = TextView(this).apply {
                text = "إعادة"; setTextColor(C.PRIMARY_A)
                textSize = 13f; gravity = Gravity.CENTER; minWidth = dp(62)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(0xFFEAF2FF.toInt()); setStroke(dp(1), 0xFFC2D6FF.toInt())
                }
                setPadding(dp(10), dp(7), dp(10), dp(7))
                visibility = View.GONE
            }
            deleteBtn.setOnClickListener {
                OfflineRoutingManager.deleteSegment(this@MapActivity, seg.id)
                primary.text = "تنزيل"; primary.setTextColor(C.PRIMARY_A); primary.isEnabled = true
                deleteBtn.visibility = View.GONE; retryBtn.visibility = View.GONE
                infoView.text = "غير محمّل • الحجم: $sizeMb"; infoView.setTextColor(C.TEXT2)
                android.widget.Toast.makeText(this@MapActivity, "تم الحذف: ${seg.id}", android.widget.Toast.LENGTH_SHORT).show()
            }
            retryBtn.setOnClickListener { primary.performClick() }
            primary.setOnClickListener {
                if (verified && !updateAvailable) {
                    android.widget.Toast.makeText(this@MapActivity, "القطعة مثبتة وقابلة للقراءة – محرك BRouter يمكنه استخدامها بعد الدمج", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                try {
                    primary.text = "0%"; primary.setTextColor(C.TEXT2); primary.isEnabled = false
                    infoView.text = "جارٍ التحميل • $sizeMb"; infoView.setTextColor(C.PRIMARY_A)
                    retryBtn.visibility = View.GONE
                    OfflineRoutingManager.enqueueDownload(this@MapActivity, seg, useMirror = false)
                    android.widget.Toast.makeText(this@MapActivity, "بدء تنزيل ${seg.id} ($sizeMb) من brouter.de", android.widget.Toast.LENGTH_SHORT).show()
                    val wm = androidx.work.WorkManager.getInstance(this@MapActivity)
                    wm.getWorkInfosByTagLiveData(seg.id).observe(this@MapActivity) { infos ->
                        val info = infos?.firstOrNull() ?: return@observe
                        val prog = info.progress.getInt("progress", -1)
                        val state = info.state
                        if (prog in 0..100 && state == androidx.work.WorkInfo.State.RUNNING) {
                            primary.text = "$prog%"; infoView.text = "جارٍ التحميل • $prog%"
                        } else if (state == androidx.work.WorkInfo.State.SUCCEEDED) {
                            val f = OfflineRoutingManager.segmentFile(this@MapActivity, seg.id)
                            val okSize = f.exists() && f.length() > 1024
                            val canReadNow = try { val raf = java.io.RandomAccessFile(f, "r"); val b = ByteArray(4); val n=raf.read(b); raf.close(); n==4 } catch (_: Exception) { false }
                            val ok = okSize && OfflineRoutingManager.verifySha256(f, seg.sha256) && canReadNow
                            if (ok) {
                                primary.text = "✓ مكتمل"; primary.setTextColor(0xFF1B8B2A.toInt()); primary.isEnabled = true
                                infoView.text = "✓ مكتمل • ${String.format("%.1f MB", f.length()/1024.0/1024.0)}"; infoView.setTextColor(0xFF1B8B2A.toInt())
                                deleteBtn.visibility = View.VISIBLE; retryBtn.visibility = View.GONE
                                android.widget.Toast.makeText(this@MapActivity, "تم تثبيت ${seg.id} – قابل للقراءة", android.widget.Toast.LENGTH_LONG).show()
                            } else {
                                primary.text = "فشل"; primary.setTextColor(0xFFD93A3A.toInt()); primary.isEnabled = true
                                infoView.text = "فشل التحقق – غير قابل للقراءة"; infoView.setTextColor(0xFFD93A3A.toInt())
                                deleteBtn.visibility = View.VISIBLE; retryBtn.visibility = View.VISIBLE
                            }
                        } else if (state == androidx.work.WorkInfo.State.FAILED) {
                            val err = info.outputData.getString("error") ?: "فشل"
                            primary.isEnabled = true
                            if (err.contains("404") || err.contains("Not Found")) {
                                primary.text = "غير متوفر"; primary.setTextColor(0xFFD93A3A.toInt())
                                infoView.text = "فشل – غير متوفر"; infoView.setTextColor(0xFFD93A3A.toInt())
                                retryBtn.visibility = View.VISIBLE
                                // auto try mirror once
                                try { OfflineRoutingManager.enqueueDownload(this@MapActivity, seg, useMirror = true) } catch (_: Exception) {}
                            } else {
                                primary.text = "فشل"; primary.setTextColor(0xFFD93A3A.toInt())
                                infoView.text = "فشل – $err"; infoView.setTextColor(0xFFD93A3A.toInt())
                                retryBtn.visibility = View.VISIBLE
                            }
                        } else if (state == androidx.work.WorkInfo.State.CANCELLED) {
                            primary.text = "أُلغي"; primary.setTextColor(C.TEXT2); primary.isEnabled = true
                        }
                    }
                } catch (e: Exception) {
                    primary.text = "خطأ"; primary.isEnabled = true
                    retryBtn.visibility = View.VISIBLE
                }
            }
            actions.addView(primary, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, dp(6), 0) })
            actions.addView(deleteBtn, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, dp(6), 0) })
            actions.addView(retryBtn, LinearLayout.LayoutParams(-2, -2))
            row.addView(actions, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(8), 0, 0, 0) })
            box.addView(row)
            box.addView(View(this).apply { setBackgroundColor(C.BORDER); layoutParams = LinearLayout.LayoutParams(-1, dp(1)) })
        }
        box.addView(TextView(this).apply {
            text = "الحساب المحلي للمسار بعد التثبيت يتطلب محرك BRouter – التنزيل والتحقق يعملان، والتوجيه دون إنترنت سيُختبر في وضع الطيران بعد الدمج."
            setTextColor(C.TEXT2); textSize = 11f; setPadding(dp(4), dp(10), dp(4), dp(4))
        })
        box.addView(TextView(this).apply {
            text = "تنزيل كل قطع العراق (6 قطع)"
            setTextColor(android.graphics.Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.RECTANGLE; cornerRadius = dp(10).toFloat(); setColor(C.PRIMARY_A) }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener {
                segments.forEach { seg -> if (!OfflineRoutingManager.hasSegment(this@MapActivity, seg.id)) OfflineRoutingManager.enqueueDownload(this@MapActivity, seg) }
                android.widget.Toast.makeText(this@MapActivity, "بدء تنزيل كل القطع...", android.widget.Toast.LENGTH_SHORT).show()
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(12), 0, 0) }
        })
        com.nova.gpspro.ui.NovaDialog(this).title("شبكة الطرق (BRouter) – الحالات: غير محمّل/جارٍ/مكتمل/فشل/تحديث").content(scroll).button("إغلاق", C.TEXT2) { it.dismiss() }.show()
    }

    private fun showSatelliteManager() {
        val scroll = android.widget.ScrollView(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }
        scroll.addView(box)
        box.addView(TextView(this).apply {
            text = "صور الأقمار الصناعية (مرخّصة)\nالمصدر: EOX Sentinel-2 Cloudless 2020 – CC BY 4.0 (Contains Copernicus). يُسمح بالتنزيل والتخزين. مصدر بديل: Esri World Imagery.\nالاختيار: حدد مستطيل على الخريطة، اختر الدقة ومستويات الزوم، يظهر الحجم المتوقع، ثم حمّل وشاهد دون إنترنت."
            setTextColor(C.TEXT2); textSize = 12f
        })
        var satDialogRef: com.nova.gpspro.ui.NovaDialog? = null
        box.addView(TextView(this).apply {
            text = "تحديد منطقة على الخريطة"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f; gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.RECTANGLE; cornerRadius = dp(10).toFloat(); setColor(C.PRIMARY_A) }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener {
                try { satDialogRef?.dismiss() } catch (_: Exception) {}
                enterSatelliteSelectionMode()
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(12), 0, 0) }
        })
        // Existing regions
        val regions = SatelliteManager.listRegions(this)
        if (regions.isNotEmpty()) {
            box.addView(TextView(this).apply { text = "المناطق المحمّلة:"; setTextColor(C.TEXT); textSize = 13f; setPadding(0, dp(12), 0, dp(6)) })
            regions.forEach { dir ->
                val meta = try { File(dir, "meta.json").readText() } catch (_: Exception) { "{}" }
                val id = dir.name
                val size = try { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } } catch (_: Exception) { 0L }
                val sizeMb = String.format("%.1f MB", size/1024.0/1024.0)
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
                col.addView(TextView(this).apply { text = id; setTextColor(C.TEXT); textSize = 13f })
                col.addView(TextView(this).apply { text = "$sizeMb • ${meta.take(60)}"; setTextColor(C.TEXT2); textSize = 11f; maxLines = 1 })
                row.addView(col)
                val del = TextView(this).apply {
                    text = "حذف"; setTextColor(0xFFD93A3A.toInt()); textSize = 13f; gravity = Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.RECTANGLE; cornerRadius = dp(8).toFloat(); setColor(0xFFFFE9E9.toInt()); setStroke(dp(1), 0xFFFFC9C9.toInt()) }
                    setPadding(dp(10), dp(7), dp(10), dp(7))
                }
                del.setOnClickListener {
                    SatelliteManager.deleteRegion(this@MapActivity, id)
                    android.widget.Toast.makeText(this@MapActivity, "تم حذف $id", android.widget.Toast.LENGTH_SHORT).show()
                    row.visibility = View.GONE
                }
                val viewBtn = TextView(this).apply {
                    text = "عرض"; setTextColor(C.PRIMARY_A); textSize = 13f; gravity = Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.RECTANGLE; cornerRadius = dp(8).toFloat(); setColor(C.CARD_SOFT); setStroke(dp(1), C.BORDER) }
                    setPadding(dp(10), dp(7), dp(10), dp(7))
                }
                viewBtn.setOnClickListener {
                    // Load raster offline – add raster source file://
                    val region = try {
                        val j = org.json.JSONObject(meta)
                        SatelliteManager.Region(j.getString("id"), j.getDouble("north"), j.getDouble("south"), j.getDouble("east"), j.getDouble("west"), j.getInt("minZoom"), j.getInt("maxZoom"))
                    } catch (_: Exception) { null }
                    if (region != null) {
                        showSatelliteOffline(region)
                    } else {
                        android.widget.Toast.makeText(this@MapActivity, "لا يمكن عرض $id دون إنترنت – الملفات في ${dir.absolutePath}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                row.addView(viewBtn, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(6), 0, dp(6), 0) })
                row.addView(del, LinearLayout.LayoutParams(-2, -2))
                box.addView(row)
                box.addView(View(this).apply { setBackgroundColor(C.BORDER); layoutParams = LinearLayout.LayoutParams(-1, dp(1)) })
            }
        } else {
            box.addView(TextView(this).apply { text = "لا توجد مناطق محمّلة بعد."; setTextColor(C.TEXT2); textSize = 11f; setPadding(0, dp(8), 0, 0) })
        }
        // License note if no licensed source
        box.addView(TextView(this).apply {
            text = "ملاحظة: صور Google Satellite غير مسموحة للتخزين دون ترخيص – نستخدم فقط EOX/Esri المرخّصة. إذا لم تتوفر صور للمنطقة، سيظهر فشل واضح."
            setTextColor(0xFF8A8E99.toInt()); textSize = 11f; setPadding(0, dp(10), 0, 0)
        })
        val satDlg = com.nova.gpspro.ui.NovaDialog(this).title("صور الأقمار الصناعية").content(scroll).button("إغلاق", C.TEXT2) { it.dismiss() }
        satDialogRef = satDlg
        satDlg.show()
    }

    private fun enterSatelliteSelectionMode() {
        satelliteSelectionMode = true
        satFirstPoint = null
        satSecondPoint = null
        // Hide bottom bar temporarily to see map
        try { bottomBar.visibility = View.GONE } catch (_: Exception) {}
        android.widget.Toast.makeText(this, "وضع تحديد المنطقة: اضغط نقطتين على الخريطة لرسم المستطيل (زاويتين متقابلتين)", android.widget.Toast.LENGTH_LONG).show()
        // Add cancel button overlay
        showSatelliteSelectionOverlay()
    }

    private fun showSatelliteSelectionOverlay() {
        // Simple overlay with cancel
        try {
            val overlay = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.RECTANGLE; cornerRadius = dp(12).toFloat(); setColor(0xEEFFFFFF.toInt()); setStroke(dp(1), C.BORDER) }
                elevation = dp(4).toFloat()
            }
            overlay.tag = "sat_overlay"
            val cancel = TextView(this).apply {
                text = "✕ إلغاء التحديد"; setTextColor(0xFFD93A3A.toInt()); textSize = 13f; gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener { exitSatelliteSelectionMode() }
            }
            overlay.addView(cancel)
            val root = findViewById<FrameLayout>(android.R.id.content) ?: return
            val params = FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = dp(80) }
            root.addView(overlay, params)
        } catch (_: Exception) {}
    }

    private fun exitSatelliteSelectionMode() {
        satelliteSelectionMode = false
        satFirstPoint = null
        satSecondPoint = null
        try { findViewById<FrameLayout>(android.R.id.content)?.findViewWithTag<View>("sat_overlay")?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) } } catch (_: Exception) {}
        try { map?.getStyle { style ->
            try { style.removeLayer("sat-rect-fill") } catch (_:Exception){}
            try { style.removeLayer("sat-rect-line") } catch (_:Exception){}
            try { style.removeSource("sat-rect-source") } catch (_:Exception){}
        } } catch (_:Exception){}
        try { bottomBar.visibility = if (targetDestination != null) View.VISIBLE else View.GONE } catch (_:Exception){}
    }

    private fun onSatelliteMapClick(point: LatLng) {
        if (satFirstPoint == null) {
            satFirstPoint = point
            android.widget.Toast.makeText(this, "تم حفظ الزاوية الأولى – اضغط الزاوية المقابلة", android.widget.Toast.LENGTH_SHORT).show()
            drawSatelliteRect(point, point) // dot
        } else {
            satSecondPoint = point
            val first = satFirstPoint ?: return
            drawSatelliteRect(first, point)
            // Show confirm dialog with resolution/zoom and estimated size
            showSatelliteConfirm(first, point)
        }
    }

    private fun drawSatelliteRect(p1: LatLng, p2: LatLng) {
        val map = map ?: return
        val style = map.style ?: return
        try {
            val north = maxOf(p1.latitude, p2.latitude)
            val south = minOf(p1.latitude, p2.latitude)
            val east = maxOf(p1.longitude, p2.longitude)
            val west = minOf(p1.longitude, p2.longitude)
            val coords = "[[[$west,$north],[$east,$north],[$east,$south],[$west,$south],[$west,$north]]]"
            val geoJson = """{"type":"Feature","geometry":{"type":"Polygon","coordinates":$coords},"properties":{}}"""
            try { style.removeLayer("sat-rect-fill") } catch (_:Exception){}
            try { style.removeLayer("sat-rect-line") } catch (_:Exception){}
            try { style.removeSource("sat-rect-source") } catch (_:Exception){}
            val src = GeoJsonSource("sat-rect-source", geoJson)
            style.addSource(src)
            val fill = org.maplibre.android.style.layers.FillLayer("sat-rect-fill", "sat-rect-source")
            fill.setProperties(PropertyFactory.fillColor("#3F74EA"), PropertyFactory.fillOpacity(0.18f))
            style.addLayer(fill)
            val line = org.maplibre.android.style.layers.LineLayer("sat-rect-line", "sat-rect-source")
            line.setProperties(PropertyFactory.lineColor("#2E5BDA"), PropertyFactory.lineWidth(2.5f), PropertyFactory.lineDasharray(arrayOf(4f,2f)))
            style.addLayer(line)
        } catch (_:Exception){}
    }

    private fun showSatelliteConfirm(p1: LatLng, p2: LatLng) {
        val north = maxOf(p1.latitude, p2.latitude)
        val south = minOf(p1.latitude, p2.latitude)
        val east = maxOf(p1.longitude, p2.longitude)
        val west = minOf(p1.longitude, p2.longitude)
        // Default zooms 13-15 for balance
        var minZ = 13
        var maxZ = 15
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }
        box.addView(TextView(this).apply { text = "المنطقة: N $north  S $south  E $east  W $west"; setTextColor(C.TEXT2); textSize = 11f })
        val tiles13 = SatelliteManager.estimateTiles(north, south, east, west, 13, 13)
        val tiles15 = SatelliteManager.estimateTiles(north, south, east, west, 15, 15)
        val estInfo = TextView(this).apply {
            val total = SatelliteManager.estimateTiles(north, south, east, west, minZ, maxZ)
            val mb = SatelliteManager.estimateSizeMB(total)
            text = "التقدير ($minZ-$maxZ): $total بلاطة ≈ ${String.format("%.1f MB", mb)} (13:${tiles13}, 15:${tiles15})"
            setTextColor(C.TEXT); textSize = 13f; setPadding(0, dp(8), 0, dp(8))
        }
        box.addView(estInfo)
        // Zoom selector (simple +/-)
        val zoomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        zoomRow.addView(TextView(this).apply { text = "الزوم:"; setTextColor(C.TEXT); textSize = 13f })
        val minView = TextView(this).apply { text = "$minZ"; setTextColor(C.PRIMARY_A); textSize = 15f; setPadding(dp(12), dp(6), dp(12), dp(6)); background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.RECTANGLE; cornerRadius = dp(6).toFloat(); setColor(C.CARD_SOFT) } }
        val maxView = TextView(this).apply { text = "$maxZ"; setTextColor(C.PRIMARY_A); textSize = 15f; setPadding(dp(12), dp(6), dp(12), dp(6)); background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.RECTANGLE; cornerRadius = dp(6).toFloat(); setColor(C.CARD_SOFT) } }
        // Adjust buttons
        val decMin = TextView(this).apply { text = "−"; gravity = Gravity.CENTER; setTextColor(C.TEXT); textSize = 16f; setPadding(dp(8), dp(4), dp(8), dp(4)); background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFFF0F1F5.toInt()) } }
        decMin.setOnClickListener { if (minZ > 10) { minZ--; minView.text = "$minZ"; val t= SatelliteManager.estimateTiles(north, south, east, west, minZ, maxZ); estInfo.text = "التقدير ($minZ-$maxZ): $t بلاطة ≈ ${String.format("%.1f MB", SatelliteManager.estimateSizeMB(t))}" } }
        val incMin = TextView(this).apply { text = "+"; gravity = Gravity.CENTER; setTextColor(C.TEXT); textSize = 16f; setPadding(dp(8), dp(4), dp(8), dp(4)); background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFFF0F1F5.toInt()) } }
        incMin.setOnClickListener { if (minZ < maxZ) { minZ++; minView.text = "$minZ"; val t= SatelliteManager.estimateTiles(north, south, east, west, minZ, maxZ); estInfo.text = "التقدير ($minZ-$maxZ): $t بلاطة ≈ ${String.format("%.1f MB", SatelliteManager.estimateSizeMB(t))}" } }
        val decMax = TextView(this).apply { text = "−"; gravity = Gravity.CENTER; setTextColor(C.TEXT); textSize = 16f; setPadding(dp(8), dp(4), dp(8), dp(4)); background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFFF0F1F5.toInt()) } }
        decMax.setOnClickListener { if (maxZ > minZ) { maxZ--; maxView.text = "$maxZ"; val t= SatelliteManager.estimateTiles(north, south, east, west, minZ, maxZ); estInfo.text = "التقدير ($minZ-$maxZ): $t بلاطة ≈ ${String.format("%.1f MB", SatelliteManager.estimateSizeMB(t))}" } }
        val incMax = TextView(this).apply { text = "+"; gravity = Gravity.CENTER; setTextColor(C.TEXT); textSize = 16f; setPadding(dp(8), dp(4), dp(8), dp(4)); background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFFF0F1F5.toInt()) } }
        incMax.setOnClickListener { if (maxZ < 17) { maxZ++; maxView.text = "$maxZ"; val t= SatelliteManager.estimateTiles(north, south, east, west, minZ, maxZ); estInfo.text = "التقدير ($minZ-$maxZ): $t بلاطة ≈ ${String.format("%.1f MB", SatelliteManager.estimateSizeMB(t))}" } }
        zoomRow.addView(decMin); zoomRow.addView(minView); zoomRow.addView(incMin); zoomRow.addView(TextView(this).apply { text = "  إلى  "; setTextColor(C.TEXT2) }); zoomRow.addView(decMax); zoomRow.addView(maxView); zoomRow.addView(incMax)
        box.addView(zoomRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        box.addView(TextView(this).apply { text = "المصدر: EOX Sentinel-2 Cloudless 2020 (مرخّص). إذا لم تتوفر صور للمنطقة سيظهر فشل واضح."; setTextColor(C.TEXT2); textSize = 11f; setPadding(0, dp(8), 0, 0) })
        com.nova.gpspro.ui.NovaDialog(this).title("تأكيد تنزيل الأقمار").content(box)
            .button("تنزيل", filled = true) { d ->
                val id = "sat_${System.currentTimeMillis()}"
                val region = SatelliteManager.Region(id, north, south, east, west, minZ, maxZ)
                SatelliteManager.enqueueDownload(this, region)
                android.widget.Toast.makeText(this, "بدء تنزيل الأقمار $id – ${estInfo.text}", android.widget.Toast.LENGTH_LONG).show()
                val wm = androidx.work.WorkManager.getInstance(this)
                wm.getWorkInfosByTagLiveData(id).observe(this) { infos ->
                    val info = infos?.firstOrNull() ?: return@observe
                    val prog = info.progress.getInt("progress", -1)
                    if (prog in 0..100 && info.state == androidx.work.WorkInfo.State.RUNNING) {
                        // Could update a progress bar – toast for now
                    } else if (info.state == androidx.work.WorkInfo.State.SUCCEEDED) {
                        android.widget.Toast.makeText(this, "تم تنزيل الأقمار $id – افتح دون إنترنت عبر المعاينة", android.widget.Toast.LENGTH_LONG).show()
                    } else if (info.state == androidx.work.WorkInfo.State.FAILED) {
                        val err = info.outputData.getString("error") ?: "فشل"
                        android.widget.Toast.makeText(this, "فشل الأقمار: $err – قد لا يتوفر مصدر مرخّص لهذه المنطقة", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                exitSatelliteSelectionMode()
                d.dismiss()
            }
            .button("إلغاء") { it.dismiss(); exitSatelliteSelectionMode() }
            .show()
    }

    private fun showSatelliteOffline(region: SatelliteManager.Region) {
        val map = map ?: return
        val style = map.style ?: return
        try {
            val id = region.id
            val dir = SatelliteManager.regionDir(this, id)
            if (!dir.exists()) {
                android.widget.Toast.makeText(this, "المنطقة غير موجودة محلياً", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            val centerLat = (region.north + region.south)/2
            val centerLon = (region.east + region.west)/2
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(centerLat, centerLon), 13.0))
            // Add file:// raster source – works offline if tiles exist
            try {
                style.removeLayer("sat-layer-$id")
            } catch (_: Exception) {}
            try {
                style.removeSource("sat-offline-$id")
            } catch (_: Exception) {}
            try {
                val tileUri = "file://${dir.absolutePath}/{z}/{x}/{y}.jpg"
                val tileSet = org.maplibre.android.style.sources.TileSet("2.1.0", tileUri)
                val src = org.maplibre.android.style.sources.RasterSource("sat-offline-$id", tileSet, 256)
                style.addSource(src)
                val layer = org.maplibre.android.style.layers.RasterLayer("sat-layer-$id", "sat-offline-$id")
                // Place below labels if possible – top-most is okay for satellite view
                style.addLayer(layer)
                android.widget.Toast.makeText(this, "تم تفعيل أقمار $id دون إنترنت – مصدر $tileUri", android.widget.Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "البلاطات في ${dir.absolutePath} (" + dir.walkTopDown().count { it.extension == "jpg" } + " ملف) – عرض raster يحتاج وضع طيران: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "تعذر عرض الأقمار: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun startNavigation() {
        val dest = targetDestination ?: run {
            val lat = pendingPreviewLat ?: return
            val lon = pendingPreviewLon ?: return
            val name = pendingPreviewName ?: "الوجهة"
            Destination(id = "preview", name = name, latitude = lat, longitude = lon, photoPath = null, notes = null, createdAt = System.currentTimeMillis())
        }
        lifecycleScope.launch {
            val loc = locationController.lastLocation() ?: locationController.awaitFreshLocation()
            if (loc == null) {
                android.widget.Toast.makeText(this@MapActivity, "تعذر تحديد موقعك الحالي – تأكد من تفعيل GPS", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            try {
                try { (application as NovaApp).navigation.setDestination(dest) } catch (_: Exception) {}
                navigationSession?.previewDestination(dest)
                // ——— Road snapping: find nearest drivable road, keep original GPS —
                val snapped = try { routingRepo.snapToRoad(loc.latitude, loc.longitude) } catch (_: Exception) { null }
                val startLat: Double
                val startLon: Double
                var usedSnap = false
                if (snapped != null) {
                    val distToRoad = haversineMeters(loc.latitude, loc.longitude, snapped.first, snapped.second)
                    // Only use snapped if within 75m and road is reachable (OSRM driving profile guarantees)
                    if (distToRoad <= 75.0) {
                        startLat = snapped.first; startLon = snapped.second; usedSnap = true
                        // If far from road (>12m, e.g., inside house), show connector line
                        if (distToRoad > 12.0) {
                            try { navigationSession?.drawConnector(loc.latitude, loc.longitude, snapped.first, snapped.second) } catch (_: Exception) {}
                        }
                    } else {
                        startLat = loc.latitude; startLon = loc.longitude
                    }
                } else {
                    startLat = loc.latitude; startLon = loc.longitude
                    // If snap failed but we are far (>75m) show no connector, but inform
                }
                val route = routingRepo.route(startLat, startLon, dest.latitude, dest.longitude)
                if (!route.isRoad || route.coordinates.size < 2) {
                    try { navigationSession?.clearRoute() } catch (_: Exception) {}
                    try { map?.getStyle { s -> try { s.removeLayer("nova-connector-layer") } catch(_:Exception){}; try { s.removeSource("nova-connector-source")} catch(_:Exception){} } } catch (_:Exception){}
                    val hasOfflineRouting = OfflineRoutingManager.hasRoutingData(this@MapActivity)
                    val msg = if (!hasOfflineRouting && !isOnlineForRouting()) {
                        "لا يوجد اتصال ولا بيانات توجيه محلية – حمّل شبكة الطرق للملاحة دون إنترنت"
                    } else if (usedSnap) {
                        "تعذر حساب مسار من أقرب شارع — تحقق من الشبكة أو حمّل شبكة الطرق"
                    } else {
                        "تعذر حساب مسار عبر الشوارع — تحقق من الاتصال (الخط المستقيم ليس مساراً)"
                    }
                    android.widget.Toast.makeText(this@MapActivity, msg, android.widget.Toast.LENGTH_LONG).show()
                    return@launch
                }
                // If we used snap, route already starts at road; keep connector visible during navigation
                navigationSession?.drawRoute(route)
                lastRoute = route
                enterNavigationMode(route, loc, dest)
                android.widget.Toast.makeText(this@MapActivity, if (usedSnap) "تم بدء التوجه من أقرب شارع: ${dest.name}" else "تم بدء التوجه: ${dest.name}", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                try { navigationSession?.clearRoute() } catch (_: Exception) {}
                android.widget.Toast.makeText(this@MapActivity, "تعذر بدء الملاحة: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun enterNavigationMode(route: RoutingRepository.RouteResult, loc: Location, dest: Destination) {
        isNavigating = true
        routeRemainingMeters = route.distanceMeters
        routeRemainingSeconds = route.durationSeconds
        offRouteCount = 0
        // Hide floating buttons per spec 2
        try { sideCol.visibility = View.GONE } catch (_: Exception) {}
        // Update card to active
        cardSubtitleView.text = "${formatDistance(route.distanceMeters)} • ${formatDuration(route.durationSeconds)}"
        cardSubtitleView.visibility = View.VISIBLE
        distanceView.text = formatDistance(route.distanceMeters)
        etaView.text = formatDuration(route.durationSeconds)
        val speedMps = try { loc.speed.toDouble() } catch (_: Exception) { 0.0 }
        speedView.text = formatSpeed(speedMps)
        statsRow.visibility = View.VISIBLE
        dividerView.visibility = View.VISIBLE
        primaryActionBtn.text = "⏹  إنهاء"
        primaryActionBtn.setTextColor(android.graphics.Color.WHITE)
        primaryActionBtn.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(0xFFD93A3A.toInt())
        }
        primaryActionBtn.setOnClickListener { endNavigation() }
        bottomBar.visibility = View.VISIBLE
        // Real GPS: navigation camera – tilt 50-55, zoom 17.5, arrow at bottom-center so road ahead visible
        val bearing = bearingForLocation(loc)
        lastBearing = bearing
        try {
            // Offset target forward so vehicle appears ~30% from bottom (like global navigation)
            val offsetM = 95.0
            val rad = Math.toRadians(bearing.toDouble())
            val latRad = Math.toRadians(loc.latitude)
            val dLat = offsetM * cos(rad) / 111320.0
            val dLon = offsetM * sin(rad) / (111320.0 * cos(latRad))
            val targetLat = loc.latitude + dLat
            val targetLon = loc.longitude + dLon
            val cam = CameraPosition.Builder()
                .target(LatLng(targetLat, targetLon))
                .zoom(17.8)
                .tilt(52.0)
                .bearing(bearing.toDouble())
                .build()
            map?.animateCamera(CameraUpdateFactory.newCameraPosition(cam), 900)
        } catch (_: Exception) {}
        // Ensure blue arrow
        try { map?.getStyle { style -> ensureVehicleArrow(style); updateVehiclePosition(loc, bearing) } } catch (_: Exception) {}
        // Start follow
        startNavigationFollow()
    }

    private fun endNavigation() {
        isNavigating = false
        stopNavigationFollow()
        // Restore floating buttons
        try { sideCol.visibility = View.VISIBLE } catch (_: Exception) {}
        // Remove vehicle arrow
        try {
            map?.getStyle { style ->
                try { style.removeLayer("nova-vehicle-arrow-layer") } catch (_: Exception) {}
                try { style.removeSource("nova-vehicle-source") } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        // Reset camera tilt
        try {
            val cur = map?.cameraPosition
            if (cur != null) {
                val reset = CameraPosition.Builder(cur).tilt(0.0).bearing(0.0).build()
                map?.animateCamera(CameraUpdateFactory.newCameraPosition(reset), 600)
            }
        } catch (_: Exception) {}
        try { navigationSession?.clearRoute() } catch (_: Exception) {}
        lastRoute = null
        offRouteCount = 0
        clearPreview(animate = false)
        android.widget.Toast.makeText(this, "تم إنهاء الملاحة", android.widget.Toast.LENGTH_SHORT).show()
    }

    // ——— Navigation follow / real GPS handling ———

    private fun startNavigationFollow() {
        stopNavigationFollow()
        try {
            val app = application as NovaApp
            val listener = com.nova.gpspro.location.LocationEngine.Listener { state ->
                val loc = state.location ?: return@Listener
                if (!state.isUsable) return@Listener
                // Use real GPS only, no simulation
                runOnUiThread { onLocationUpdateDuringNavigation(loc, state.bearing, state.bearingLive) }
            }
            navigationListener = listener
            app.gps.addListener(listener)
        } catch (_: Exception) {}
    }

    private fun stopNavigationFollow() {
        try {
            val l = navigationListener ?: return
            (application as? NovaApp)?.gps?.removeListener(l)
            navigationListener = null
        } catch (_: Exception) {}
    }

    private fun onLocationUpdateDuringNavigation(loc: Location, bearing: Float, bearingLive: Boolean) {
        if (!isNavigating) return
        // When stationary, don't rotate randomly – keep last bearing if no live bearing and speed <0.8
        val speed = try { loc.speed.toDouble() } catch (_: Exception) { 0.0 }
        val useBearing = when {
            bearingLive -> bearing
            speed > 1.2 && loc.hasBearing() -> loc.bearing
            else -> lastBearing
        }
        if (bearingLive || speed > 1.0) lastBearing = useBearing
        // Map matching for arrow: only snap when GPS accuracy and road data sufficient
        var arrowLat = loc.latitude
        var arrowLon = loc.longitude
        try {
            val route = lastRoute
            val acc = if (loc.hasAccuracy()) loc.accuracy else 99f
            if (route != null && acc < 35f && (bearingLive || speed > 1.5) && route.coordinates.size >= 2) {
                // Find nearest road point within 30m
                var bestDist = Double.MAX_VALUE
                var bestLat = arrowLat
                var bestLon = arrowLon
                for (i in 0 until route.coordinates.size - 1) {
                    val (lat1, lon1) = route.coordinates[i]
                    val (lat2, lon2) = route.coordinates[i + 1]
                    val t = projectT(arrowLat, arrowLon, lat1, lon1, lat2, lon2)
                    val projLat = lat1 + t * (lat2 - lat1)
                    val projLon = lon1 + t * (lon2 - lon1)
                    val d = haversineMeters(arrowLat, arrowLon, projLat, projLon)
                    if (d < bestDist && d < 30.0) {
                        bestDist = d; bestLat = projLat; bestLon = projLon
                    }
                }
                if (bestDist < 30.0) {
                    arrowLat = bestLat; arrowLon = bestLon
                }
            }
        } catch (_: Exception) {}
        try {
            // Update arrow at (possibly snapped) position but keep original GPS for distance logic
            val arrowLoc = Location("").apply { latitude = arrowLat; longitude = arrowLon; bearing = useBearing }
            updateVehiclePosition(arrowLoc, useBearing)
        } catch (_: Exception) {}
        // Camera follow: offset so arrow at bottom-center, tilt 52, zoom 17.8, smooth
        try {
            val offsetM = 95.0
            val rad = Math.toRadians(useBearing.toDouble())
            val latRad = Math.toRadians(arrowLat)
            val dLat = offsetM * cos(rad) / 111320.0
            val dLon = offsetM * sin(rad) / (111320.0 * cos(latRad))
            val targetLat = arrowLat + dLat
            val targetLon = arrowLon + dLon
            val cam = CameraPosition.Builder()
                .target(LatLng(targetLat, targetLon))
                .zoom(17.8)
                .tilt(52.0)
                .bearing(useBearing.toDouble())
                .build()
            map?.animateCamera(CameraUpdateFactory.newCameraPosition(cam), 700)
        } catch (_: Exception) {}
        // Real trip info uses original GPS (not snapped) for distance progress
        updateTripInfoFromLocation(loc)
        checkDeviationAndReroute(loc)
    }

    private fun bearingForLocation(loc: Location): Float {
        return try {
            val app = application as NovaApp
            // Prefer BearingEngine via GpsState if available
            val state = app.gps.state
            if (state.isUsable && state.bearingLive) state.bearing else if (loc.hasBearing()) loc.bearing else lastBearing
        } catch (_: Exception) {
            try { if (loc.hasBearing()) loc.bearing else lastBearing } catch (_: Exception) { 0f }
        }
    }

    private fun isOnlineForRouting(): Boolean {
        return try {
            val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) { true }
    }

    private fun ensureVehicleArrow(style: Style) {
        try {
            if (style.getImage("nova-vehicle-arrow") != null) return
        } catch (_: Exception) {}
        try {
            val bmp = createBlueArrowBitmap()
            style.addImage("nova-vehicle-arrow", bmp)
            val src = GeoJsonSource("nova-vehicle-source", "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[0,0]},\"properties\":{}}")
            style.addSource(src)
            val layer = SymbolLayer("nova-vehicle-arrow-layer", "nova-vehicle-source")
            layer.setProperties(
                PropertyFactory.iconImage("nova-vehicle-arrow"),
                PropertyFactory.iconSize(1.45f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconAnchor("center"),
                PropertyFactory.iconRotate(0f),
                PropertyFactory.iconRotationAlignment("map")
            )
            style.addLayer(layer)
        } catch (_: Exception) {}
    }

    private fun createBlueArrowBitmap(): android.graphics.Bitmap {
        val size = 88
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val blue = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2E5BDA.toInt()
            style = android.graphics.Paint.Style.FILL
            setShadowLayer(5f, 0f, 3f, 0x55000000)
        }
        val white = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3f
        }
        val cx = size / 2f
        val cy = size / 2f
        val path = android.graphics.Path().apply {
            moveTo(cx, 10f) // tip north – larger
            lineTo(cx + 18f, 56f)
            lineTo(cx, 46f)
            lineTo(cx - 18f, 56f)
            close()
        }
        c.drawPath(path, blue)
        c.drawPath(path, white)
        // inner highlight
        val inner = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x66FFFFFF
            style = android.graphics.Paint.Style.FILL
        }
        c.drawCircle(cx, 30f, 4f, inner)
        return bmp
    }

    private fun updateVehiclePosition(loc: Location, bearing: Float) {
        val m = map ?: return
        val style = m.style ?: return
        try {
            ensureVehicleArrow(style)
            val src = style.getSourceAs<GeoJsonSource>("nova-vehicle-source") ?: return
            val json = "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[${loc.longitude},${loc.latitude}]},\"properties\":{}}"
            src.setGeoJson(json)
            val layer = style.getLayerAs<SymbolLayer>("nova-vehicle-arrow-layer") ?: return
            layer.setProperties(PropertyFactory.iconRotate(bearing))
        } catch (_: Exception) {}
    }

    private fun updateTripInfoFromLocation(loc: Location) {
        val route = lastRoute ?: return
        // Find progress along route
        val remaining = computeRemainingDistance(loc, route)
        routeRemainingMeters = remaining
        // Real speed from GPS (m/s) – no internet needed
        val speedMps = try { loc.speed.toDouble() } catch (_: Exception) { 0.0 }
        val kmh = speedMps * 3.6
        speedView.text = formatSpeed(speedMps)
        distanceView.text = formatDistance(remaining)
        // ETA from remaining / speed (if moving) else from route average
        val avgSpeed = if (route.durationSeconds > 0) route.distanceMeters / route.durationSeconds else 5.0
        val useSpeed = if (speedMps > 1.0) speedMps else avgSpeed
        val etaSec = if (useSpeed > 0.5) remaining / useSpeed else routeRemainingSeconds
        routeRemainingSeconds = etaSec
        etaView.text = formatDuration(etaSec)
        try { cardSubtitleView.text = "${formatDistance(remaining)} • ${formatDuration(etaSec)}" } catch (_: Exception) {}
    }

    private fun computeRemainingDistance(loc: Location, route: RoutingRepository.RouteResult): Double {
        val coords = route.coordinates // list of lat,lon
        if (coords.size < 2) return route.distanceMeters
        // Find nearest segment
        var bestIdx = 0
        var bestDist = Double.MAX_VALUE
        var bestT = 0.0
        var cumulative = 0.0
        val segLens = mutableListOf<Double>()
        for (i in 0 until coords.size - 1) {
            val (lat1, lon1) = coords[i]
            val (lat2, lon2) = coords[i + 1]
            val len = haversineMeters(lat1, lon1, lat2, lon2)
            segLens.add(len)
        }
        // find nearest point
        for (i in 0 until coords.size - 1) {
            val (lat1, lon1) = coords[i]
            val (lat2, lon2) = coords[i + 1]
            val t = projectT(loc.latitude, loc.longitude, lat1, lon1, lat2, lon2)
            val projLat = lat1 + t * (lat2 - lat1)
            val projLon = lon1 + t * (lon2 - lon1)
            val d = haversineMeters(loc.latitude, loc.longitude, projLat, projLon)
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
                bestT = t
            }
        }
        // Sum remaining from bestIdx
        var remaining = 0.0
        // remaining part of current segment
        val (lat1, lon1) = coords[bestIdx]
        val (lat2, lon2) = coords[bestIdx + 1]
        val segLen = segLens[bestIdx]
        remaining += (1 - bestT) * segLen
        for (i in bestIdx + 1 until coords.size - 1) remaining += segLens[i]
        return remaining.coerceAtLeast(0.0)
    }

    private fun projectT(lat: Double, lon: Double, lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        // Simple linear project in degrees (approx for small segments)
        val dx = lon2 - lon1
        val dy = lat2 - lat1
        if (dx == 0.0 && dy == 0.0) return 0.0
        val t = ((lon - lon1) * dx + (lat - lat1) * dy) / (dx * dx + dy * dy)
        return t.coerceIn(0.0, 1.0)
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    private fun checkDeviationAndReroute(loc: Location) {
        val route = lastRoute ?: return
        val coords = route.coordinates
        if (coords.isEmpty()) return
        var minDist = Double.MAX_VALUE
        for (i in 0 until coords.size - 1) {
            val (lat1, lon1) = coords[i]
            val (lat2, lon2) = coords[i + 1]
            val t = projectT(loc.latitude, loc.longitude, lat1, lon1, lat2, lon2)
            val projLat = lat1 + t * (lat2 - lat1)
            val projLon = lon1 + t * (lon2 - lon1)
            val d = haversineMeters(loc.latitude, loc.longitude, projLat, projLon)
            if (d < minDist) minDist = d
        }
        if (minDist > 50.0) {
            offRouteCount++
            if (offRouteCount >= 3) {
                offRouteCount = 0
                rerouteFrom(loc)
            }
        } else {
            offRouteCount = 0
        }
    }

    private fun rerouteFrom(loc: Location) {
        val dest = targetDestination ?: return
        lifecycleScope.launch {
            try {
                val newRoute = routingRepo.route(loc.latitude, loc.longitude, dest.latitude, dest.longitude)
                if (!newRoute.isRoad || newRoute.coordinates.size < 2) {
                    android.widget.Toast.makeText(this@MapActivity, "انحرفت عن المسار – تعذر إعادة الحساب (تحقق من الشبكة أو حمّل شبكة الطرق)", android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
                }
                lastRoute = newRoute
                navigationSession?.drawRoute(newRoute)
                android.widget.Toast.makeText(this@MapActivity, "تم إعادة حساب المسار", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(this@MapActivity, "فشل إعادة التوجيه", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun formatDistance(m: Double): String {
        return if (m < 1000) "${m.toInt()} م" else String.format("%.1f كم", m / 1000.0)
    }
    private fun formatDuration(s: Double): String {
        val minutes = (s / 60).toInt()
        return if (minutes < 60) "$minutes د" else "${minutes / 60} س ${minutes % 60} د"
    }
    private fun formatSpeed(mps: Double): String {
        val kmh = mps * 3.6
        return String.format("%.0f كم/س", kmh)
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
