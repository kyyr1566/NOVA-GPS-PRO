package com.nova.gpspro.map

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nova.gpspro.NovaApp
import com.nova.gpspro.R
import com.nova.gpspro.data.Destination
import com.nova.gpspro.ui.C
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/**
 * Professional Map Screen — MapLibre Native, offline PMTiles, vector, 3D, routing.
 * Keeps original arrow navigation untouched (separate system).
 */
class MapActivity : AppCompatActivity() {

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
    private lateinit var distanceView: TextView
    private lateinit var etaView: TextView
    private lateinit var speedView: TextView
    private lateinit var bottomBar: LinearLayout

    private var targetDestination: Destination? = null
    private var pendingPreviewLat: Double? = null
    private var pendingPreviewLon: Double? = null
    private var pendingPreviewName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)

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
        mapView = MapView(this)
        root.addView(mapView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        styleManager = MapStyleManager(this)
        cameraController = MapCameraController()
        cameraController.init(this)
        locationController = LocationTrackingController(this)
        val novaApp = application as NovaApp
        savedRepo = SavedLocationsMapRepository(novaApp.destinations)
        routingRepo = RoutingRepository(this)
        navigationSession = NavigationSessionManager(this, routingRepo, lifecycleScope)
        navigationSession?.onRouteUpdated = { route ->
            runOnUiThread {
                if (route != null) {
                    distanceView.text = formatDistance(route.distanceMeters)
                    etaView.text = formatDuration(route.durationSeconds)
                    // speed comes from location; show —
                    bottomBar.visibility = View.VISIBLE
                }
            }
        }

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { mapLibreMap ->
            map = mapLibreMap
            cameraController.attach(mapLibreMap)
            setupMap(mapLibreMap)
            // attach navigation session after map ready
            navigationSession?.attach(mapLibreMap)
            pendingPreviewLat?.let { lat ->
                pendingPreviewLon?.let { lon ->
                    val name = pendingPreviewName
                    if (targetDestination != null) navigationSession?.previewDestination(targetDestination!!)
                    else navigationSession?.previewLatLng(lat, lon, name)
                    // move camera to destination
                    val pos = CameraPosition.Builder().target(LatLng(lat, lon)).zoom(15.0).build()
                    mapLibreMap.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 600)
                    // request route from current location
                    lifecycleScope.launch {
                        val loc = locationController.lastLocation() ?: locationController.awaitFreshLocation()
                        if (loc != null) navigationSession?.requestRoute(loc.latitude, loc.longitude)
                    }
                }
            }
        }

        setupUi(root)
    }

    private fun setupMap(map: MapLibreMap) {
        map.uiSettings.isRotateGesturesEnabled = true
        map.uiSettings.isTiltGesturesEnabled = true
        map.uiSettings.isZoomGesturesEnabled = true
        map.uiSettings.isScrollGesturesEnabled = true
        map.uiSettings.isDoubleTapGesturesEnabled = true

        val styleUrl = styleManager.currentStyleUrl()
        // If offline pmtiles url, we still load base style and let pmtiles source override
        val effectiveStyle = if (styleUrl.startsWith("pmtiles://")) "asset://mapstyles/dark.json" else styleUrl
        map.setStyle(Style.Builder().fromUri(effectiveStyle)) { style ->
            styleManager.applyStyle(style, map)
            savedRepo.attach(map)
            targetDestination?.let { dest ->
                savedRepo.highlight(dest.id)
                // Ensure marker visible even if highlight overrides
            }
        }

        map.addOnCameraIdleListener {
            cameraController.saveCamera(map.cameraPosition)
        }

        cameraController.restoreCamera()?.let { pos ->
            map.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
        }
    }

    private fun setupUi(root: FrameLayout) {
        // Top bar: back + destination/search + layers
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(0xCC0B1220.toInt())
            }
            elevation = dp(4).toFloat()
        }
        val backBtn = smallIconButton("‹", "رجوع") { finish() }
        destNameView = TextView(this).apply {
            text = targetDestination?.name ?: "الخرائط"
            setTextColor(C.TEXT)
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        val layersBtn = smallIconButton("◫", "الطبقات") { showLayersSheet() }
        val settingsBtn = smallIconButton("⚙", "الإعدادات") { showMapSettings() }

        topBar.addView(backBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
        topBar.addView(destNameView, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(8), 0, dp(8), 0) })
        topBar.addView(layersBtn, LinearLayout.LayoutParams(dp(36), dp(36)).apply { setMargins(0, 0, dp(4), 0) })
        topBar.addView(settingsBtn, LinearLayout.LayoutParams(dp(36), dp(36)))

        val topParams = FrameLayout.LayoutParams(-1, -2).apply {
            gravity = Gravity.TOP
            setMargins(dp(8), dp(12), dp(8), 0)
        }
        root.addView(topBar, topParams)

        // Side controls: compass, my location, 3D, zoom, mode
        val sideCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val compassBtn = smallIconButton("◉", "الشمال") { map?.let { cameraController.bearingNorth(it) } }
        val myLocBtn = smallIconButton("◎", "موقعي") { centerToMyLocation() }
        val threeDBtn = smallIconButton("3D", "ثلاثي الأبعاد") { toggle3D() }
        val zoomInBtn = smallIconButton("+", "تكبير") { map?.animateCamera(CameraUpdateFactory.zoomIn()) }
        val zoomOutBtn = smallIconButton("−", "تصغير") { map?.animateCamera(CameraUpdateFactory.zoomOut()) }
        val modeBtn = smallIconButton("◐", "وضع الاتصال") { cycleMode() }

        listOf(compassBtn, myLocBtn, threeDBtn, zoomInBtn, zoomOutBtn, modeBtn).forEach { btn ->
            sideCol.addView(btn, LinearLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(0, dp(4), 0, dp(4)) })
        }
        val sideParams = FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setMargins(0, 0, dp(8), 0)
        }
        root.addView(sideCol, sideParams)

        // Bottom bar: distance | ETA | speed + start nav
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(0xEEFAF7F0.toInt())
            }
            elevation = dp(6).toFloat()
            visibility = if (targetDestination != null) View.VISIBLE else View.GONE
        }
        distanceView = TextView(this).apply { text = "—"; setTextColor(C.TEXT); textSize = 13f; gravity = Gravity.CENTER; textDirection = View.TEXT_DIRECTION_LTR }
        etaView = TextView(this).apply { text = "—"; setTextColor(C.TEXT); textSize = 13f; gravity = Gravity.CENTER }
        speedView = TextView(this).apply { text = "—"; setTextColor(C.TEXT); textSize = 13f; gravity = Gravity.CENTER; textDirection = View.TEXT_DIRECTION_LTR }
        val startBtn = TextView(this).apply {
            text = "بدء الملاحة"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(C.PRIMARY_A)
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { startNavigation() }
        }
        bottomBar.addView(distanceView, LinearLayout.LayoutParams(0, -2, 1f))
        bottomBar.addView(etaView, LinearLayout.LayoutParams(0, -2, 1f))
        bottomBar.addView(speedView, LinearLayout.LayoutParams(0, -2, 1f))
        bottomBar.addView(startBtn, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(8), 0, 0, 0) })

        val bottomParams = FrameLayout.LayoutParams(-1, -2).apply {
            gravity = Gravity.BOTTOM
            setMargins(dp(8), dp(8), dp(8), dp(12))
        }
        root.addView(bottomBar, bottomParams)

        // Attribution
        val attr = TextView(this).apply {
            text = "© OpenStreetMap"
            setTextColor(0xFF2856BC.toInt())
            textSize = 10f
            setPadding(dp(4), dp(2), dp(4), dp(2))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(6).toFloat()
                setColor(0xCCFFFFFF.toInt())
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
            setMargins(dp(8), dp(8), dp(8), dp(52))
        }
        root.addView(attr, attrParams)

        targetDestination?.let { showPreviewCard(it) }
    }

    private fun showPreviewCard(dest: Destination) {
        bottomBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val loc = locationController.lastLocation() ?: locationController.awaitFreshLocation()
            if (loc != null) {
                val route = routingRepo.route(loc.latitude, loc.longitude, dest.latitude, dest.longitude)
                distanceView.text = formatDistance(route.distanceMeters)
                etaView.text = formatDuration(route.durationSeconds)
                val speed = try { loc.speed.toDouble() } catch (_: Exception) { 0.0 }
                speedView.text = formatSpeed(speed)
            } else {
                distanceView.text = String.format("%.1f كم", haversine(dest.latitude, dest.longitude))
            }
        }
    }

    private fun haversine(toLat: Double, toLon: Double): Double {
        val loc = locationController.lastLocation() ?: return 0.0
        val R = 6371000.0
        val dLat = Math.toRadians(toLat - loc.latitude)
        val dLon = Math.toRadians(toLon - loc.longitude)
        val a = kotlin.math.sin(dLat / 2).pow(2) + kotlin.math.cos(Math.toRadians(loc.latitude)) * kotlin.math.cos(Math.toRadians(toLat)) * kotlin.math.sin(dLon / 2).pow(2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return R * c
    }

    private fun smallIconButton(text: String, desc: String, onClick: () -> Unit): View {
        return TextView(this).apply {
            this.text = text
            this.contentDescription = desc
            gravity = Gravity.CENTER
            setTextColor(C.TEXT)
            textSize = 14f
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xCCFFFFFF.toInt())
                setStroke(dp(1), C.BORDER)
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

    private fun showLayersSheet() {
        val sheet = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        val layers = listOf(
            "الخريطة الليلية" to "dark",
            "خريطة الشوارع" to "streets",
            "التضاريس" to "terrain",
            "القمر الصناعي" to "satellite",
            "المباني 3D" to "3d"
        )
        layers.forEach { (label, key) ->
            val row = TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(C.TEXT)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(C.GOLD_PALE),
                    android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.RECTANGLE; cornerRadius = dp(8).toFloat(); setColor(C.CARD_SOFT) },
                    null
                )
                setOnClickListener {
                    styleManager.setLayer(key)
                    val url = styleManager.currentStyleUrl()
                    val effective = if (url.startsWith("pmtiles://")) "asset://mapstyles/dark.json" else url
                    map?.setStyle(Style.Builder().fromUri(effective)) { style ->
                        styleManager.applyStyle(style, map!!)
                        savedRepo.attach(map!!)
                        targetDestination?.let { d -> savedRepo.highlight(d.id) }
                        navigationSession?.let { sess ->
                            sess.getRoute()?.let { r -> sess.drawRoute(r) }
                        }
                    }
                    (sheet.parent as? android.view.ViewGroup)?.let { p -> p.removeView(sheet) }
                }
            }
            sheet.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, dp(4)) })
        }
        com.nova.gpspro.ui.NovaDialog(this).title("الطبقات").content(sheet)
            .button(getString(R.string.cancel), C.TEXT2) { it.dismiss() }.show()
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
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val packs = OfflineMapManager.availableGovernorates()
        packs.forEach { (id, ar) ->
            val has = OfflineMapManager.hasOffline(this, id)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
            row.addView(TextView(this).apply { text = ar; setTextColor(C.TEXT); textSize = 14f; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
            row.addView(TextView(this).apply {
                text = if (has) "✓ مثبت" else "تنزيل"
                setTextColor(if (has) 0xFF1B8B2A.toInt() else C.PRIMARY_A)
                textSize = 13f
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(C.CARD_SOFT)
                    setStroke(dp(1), C.BORDER)
                }
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener {
                    if (has) {
                        OfflineMapManager.deletePackage(this@MapActivity, id)
                        text = "تنزيل"; setTextColor(C.PRIMARY_A)
                        android.widget.Toast.makeText(this@MapActivity, "تم الحذف: $ar", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        // Trigger WorkManager download (stub: show message, real download via worker)
                        text = "قيد التنزيل…"
                        android.widget.Toast.makeText(this@MapActivity, "بدء تنزيل $ar (يتطلب خادم PMTiles)", android.widget.Toast.LENGTH_LONG).show()
                        // In real app: enqueue MapDownloadWorker with id
                    }
                }
            })
            box.addView(row)
            box.addView(View(this).apply { setBackgroundColor(C.BORDER); layoutParams = LinearLayout.LayoutParams(-1, dp(1)) })
        }
        com.nova.gpspro.ui.NovaDialog(this).title("خرائط المحافظات (PMTiles)").content(box)
            .button("إغلاق", C.TEXT2) { it.dismiss() }.show()
    }

    private fun showRoadNetworkManager() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }
        box.addView(TextView(this).apply { text = "شبكة الطرق للملاحة غير المتصلة\nيستخدم BRouter / Valhalla مع بيانات OSM للعراق.\nيمكن تنزيل ملف routing من خوادم مفتوحة المصدر."; setTextColor(C.TEXT2); textSize = 13f })
        box.addView(TextView(this).apply {
            text = "تنزيل شبكة طرق العراق"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.RECTANGLE; cornerRadius = dp(10).toFloat(); setColor(C.PRIMARY_A) }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { android.widget.Toast.makeText(this@MapActivity, "قريباً: تنزيل شبكة الطرق (BRouter)", android.widget.Toast.LENGTH_SHORT).show() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(12), 0, 0) }
        })
        com.nova.gpspro.ui.NovaDialog(this).title("شبكة الطرق").content(box).button("إغلاق", C.TEXT2) { it.dismiss() }.show()
    }

    private fun showSatelliteManager() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }
        box.addView(TextView(this).apply { text = "صور الأقمار الصناعية المرخصة\nالمصادر: OpenAerialMap, Copernicus Sentinel (CC BY).\nحدد منطقة يدوياً على الخريطة للتنزيل."; setTextColor(C.TEXT2); textSize = 13f })
        box.addView(TextView(this).apply {
            text = "تحديد منطقة على الخريطة"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.RECTANGLE; cornerRadius = dp(10).toFloat(); setColor(C.PRIMARY_A) }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { android.widget.Toast.makeText(this@MapActivity, "اسحب لتحديد المنطقة — قريباً", android.widget.Toast.LENGTH_SHORT).show() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(12), 0, 0) }
        })
        com.nova.gpspro.ui.NovaDialog(this).title("الأقمار الصناعية").content(box).button("إغلاق", C.TEXT2) { it.dismiss() }.show()
    }

    private fun startNavigation() {
        val dest = targetDestination ?: run {
            val lat = pendingPreviewLat ?: return
            val lon = pendingPreviewLon ?: return
            val name = pendingPreviewName ?: "الوجهة"
            Destination(id = "preview", name = name, latitude = lat, longitude = lon, photoPath = null, notes = null, createdAt = System.currentTimeMillis())
        }
        // Save as intent to NavigationEngine (arrow) — keep original arrow system untouched
        try {
            val novaApp = application as NovaApp
            // Start arrow navigation if not already
            novaApp.navigation.start(dest)
        } catch (_: Exception) {}
        // Also keep map route visible
        lifecycleScope.launch {
            val loc = locationController.lastLocation() ?: locationController.awaitFreshLocation()
            if (loc != null) navigationSession?.requestRoute(loc.latitude, loc.longitude)
        }
        android.widget.Toast.makeText(this, "تم بدء الملاحة: ${dest.name}", android.widget.Toast.LENGTH_SHORT).show()
        // Keep bottom bar visible with live updates
        bottomBar.visibility = View.VISIBLE
        // Optionally finish to show arrow screen, but stay on map as requested
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { super.onPause(); mapView.onPause() }
    override fun onStop() { super.onStop(); mapView.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() { super.onDestroy(); mapView.onDestroy(); navigationSession?.detach() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }
}


