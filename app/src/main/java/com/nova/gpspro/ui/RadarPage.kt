package com.nova.gpspro.ui

import android.location.GnssStatus
import android.location.LocationManager
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.nova.gpspro.MainActivity
import com.nova.gpspro.R
import com.nova.gpspro.data.Destination
import com.nova.gpspro.data.DestinationRepository
import com.nova.gpspro.location.GpsState
import com.nova.gpspro.location.GpsStatus
import com.nova.gpspro.location.LocationEngine
import com.nova.gpspro.navigation.GeoMath

/**
 * Saved-location radar page. Marker positions are derived anew from the current GPS
 * coordinate on EVERY valid GPS fix — GPS only, never device orientation or motion input.
 *
 * Layout (no ScrollView, nothing overlaps the bottom navigation bar):
 *   header        – title + LIVE chip
 *   controls      – Auto/Off scan toggle + range selector (100 m … 400 km + custom)
 *   top metrics   – small rolling C/N0, used-satellite, and GPS-accuracy graphs from real
 *                   Android GNSS/GPS callbacks only
 *   radar disc    – as large as the space allows; centre = the phone's GPS position
 *   bottom HUD    – «N destinations | nearest | its distance», or the picked destination's
 *                   name + live real distance + target bearing while a marker is selected
 */
class RadarPage(act: MainActivity) : Page(act) {
    private val c = act
    private val radar = RadarView(c)
    private var autoScan = true
    private var selectedRange = RANGES.first()
    private var customRange = false
    private var lastState: GpsState? = null
    private var savedDestinations = act.app.destinations.all()
    private var lastTargets: List<RadarTarget> = emptyList()
    private var selectedId: String? = null

    private lateinit var autoOption: View
    private lateinit var offOption: View
    private lateinit var rangeButton: TextView
    private lateinit var cn0Graph: RadarMetricCard
    private lateinit var satellitesGraph: RadarMetricCard
    private lateinit var accuracyGraph: RadarMetricCard
    private lateinit var bottomDot: View
    private lateinit var bottomText: TextView

    private val locationManager = c.getSystemService(LocationManager::class.java)
    private var gnssStatusRegistered = false
    private var lastAccuracyFixNanos = Long.MIN_VALUE

    override val view: View = c.vbox().apply {
        setPadding(c.dp(16), c.dp(12), c.dp(16), c.dp(12))

        val header = c.hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(c.text(c.getString(R.string.tab_radar), 24f, C.TEXT, Fonts.medium), lp(0, weight = 1f))
        header.addView(c.text(c.getString(R.string.radar_live), 11f, C.RADAR_CHIP_TEXT, Fonts.medium).apply {
            gravity = Gravity.CENTER
            background = roundRect(C.RADAR_CHIP_BG, c.dp(12).toFloat())
            setPadding(c.dp(10), c.dp(6), c.dp(10), c.dp(6))
            letterSpacing = .04f
        }, lp(android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(header, lp())

        val controls = c.hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, c.dp(10), 0, c.dp(6))
        }
        val scanChoices = c.hbox().apply {
            background = roundRect(C.RADAR_SOFT_BG, c.dp(16).toFloat(), C.RADAR_BORDER, c.dp(1))
            setPadding(c.dp(3), c.dp(3), c.dp(3), c.dp(3))
        }
        autoOption = compactOption(c.getString(R.string.radar_auto)) { setAutoScan(true) }
        offOption = compactOption(c.getString(R.string.radar_off)) { setAutoScan(false) }
        scanChoices.addView(autoOption, LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        scanChoices.addView(offOption, LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(scanChoices, lp(c.dp(126)))

        rangeButton = c.text("", 13f, C.RADAR_TEXT, Fonts.medium).apply {
            gravity = Gravity.CENTER
            background = ripple(roundRect(C.RADAR_BTN_BG, c.dp(15).toFloat(), C.RADAR_BORDER, c.dp(1)), c.dp(15).toFloat(), 0x2200B8D4)
            setPadding(c.dp(12), c.dp(10), c.dp(12), c.dp(10))
            isClickable = true
            isFocusable = true
            contentDescription = c.getString(R.string.radar_range)
            setOnClickListener { showRangeMenu() }
        }
        controls.addView(rangeButton, lp(0, weight = 1f).margins(s = c.dp(10)))
        addView(controls, lp())

        // Three graph cards use the same full-width columns and row height as the former
        // telemetry chips, preserving the radar's exact available area and centre.
        val graphTileSize = previousTelemetryRowHeight()
        val graphHud = c.hbox().apply { setPadding(0, 0, 0, 0) }
        cn0Graph = RadarMetricCard(
            c,
            c.getString(R.string.radar_graph_cn0_title),
            RadarGraphScale.CN0,
            { value -> RadarMetricCard.cn0Summary(value) }
        )
        satellitesGraph = RadarMetricCard(
            c,
            c.getString(R.string.radar_graph_satellites_title),
            RadarGraphScale.SATELLITES_USED,
            { value -> value.toInt().toString() },
            C.RADAR_CHIP_TEXT
        )
        accuracyGraph = RadarMetricCard(
            c,
            c.getString(R.string.radar_graph_accuracy_title),
            RadarGraphScale.GPS_ACCURACY,
            { value -> act.units.accuracy(value.toFloat()) },
            C.RADAR_CHIP_TEXT
        )
        graphHud.addView(cn0Graph, lp(0, graphTileSize, weight = 1f))
        graphHud.addView(satellitesGraph, lp(0, graphTileSize, weight = 1f).margins(s = c.dp(8), e = c.dp(8)))
        graphHud.addView(accuracyGraph, lp(0, graphTileSize, weight = 1f))
        addView(graphHud, lp(h = graphTileSize).margins(b = c.dp(4)))

        radar.apply {
            setRange(selectedRange)
            setScanning(autoScan)
            onTargetClick = { selectTarget(it) }
            onBackgroundClick = { clearSelection() }
        }
        addView(radar, lp(h = 0, weight = 1f).margins(t = c.dp(4)))

        // Dynamic summary strip below the disc — never covers any marker.
        val bottomHud = c.hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(C.CARD, c.dp(14).toFloat(), C.BORDER, c.dp(1))
            setPadding(c.dp(14), c.dp(12), c.dp(14), c.dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { if (selectedId != null) clearSelection() }
        }
        bottomDot = View(c).apply { background = roundRect(C.TEXT3, c.dp(4).toFloat()) }
        bottomText = c.text("", 13.5f, C.TEXT, Fonts.medium).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        bottomHud.addView(bottomDot, LinearLayout.LayoutParams(c.dp(8), c.dp(8)).margins(e = c.dp(9)))
        bottomHud.addView(bottomText, lp(0, weight = 1f))
        addView(bottomHud, lp().margins(t = c.dp(4)))

        setAutoScan(true)
        updateRangeLabel()
    }

    /**
     * Measures the former two-line HUD chip exactly, so replacing its row with square charts
     * does not alter the radar's available area or its centre point.
     */
    private fun previousTelemetryRowHeight(): Int {
        fun measuredTextHeight(sizeSp: Float): Int {
            val sampleText = if (sizeSp == 10f) {
                if (c.isRtl()) "تثبيت GPS" else "GPS FIX"
            } else "—"
            val text = c.text(sampleText, sizeSp, C.TEXT2, Fonts.medium)
            val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            text.measure(unspecified, unspecified)
            return text.measuredHeight
        }
        return c.dp(7 + 8 + 1) + measuredTextHeight(10f) + measuredTextHeight(15f)
    }

    private fun compactOption(label: String, onClick: () -> Unit): View =
        c.text(label, 12f, C.RADAR_TEXT2, Fonts.medium).apply {
            gravity = Gravity.CENTER
            minHeight = c.dp(35)
            setPadding(c.dp(7), c.dp(7), c.dp(7), c.dp(7))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun setAutoScan(enabled: Boolean) {
        autoScan = enabled
        radar.setScanning(enabled)
        render(lastState ?: c.app.gps.state)   // scanning only toggles the visual sweep; real data stays live
        if (!::autoOption.isInitialized || !::offOption.isInitialized) return
        paintScanOption(autoOption, enabled)
        paintScanOption(offOption, !enabled)
    }

    private fun paintScanOption(option: View, selected: Boolean) {
        val text = option as TextView
        if (selected) {
            text.setTextColor(android.graphics.Color.WHITE)
            text.background = gradientRect(C.RADAR_SEL_A, C.RADAR_SEL_B, c.dp(13).toFloat())
            text.elevation = c.dp(1).toFloat()
        } else {
            text.setTextColor(C.RADAR_TEXT2)
            text.background = null
            text.elevation = 0f
        }
    }

    // ------------------------------------------------------------- range selection

    private fun showRangeMenu() {
        PopupMenu(c, rangeButton).apply {
            RANGES.forEachIndexed { index, range ->
                menu.add(0, index, index, rangeText(range)).isCheckable = true
                menu.findItem(index).isChecked = !customRange && range == selectedRange
            }
            menu.add(0, MENU_CUSTOM, RANGES.size, c.getString(R.string.radar_range_custom)).isCheckable = true
            menu.findItem(MENU_CUSTOM).isChecked = customRange
            setOnMenuItemClickListener { item ->
                if (item.itemId == MENU_CUSTOM) showCustomRangeDialog()
                else applyRange(RANGES[item.itemId], custom = false)
                true
            }
            show()
        }
    }

    /** Applies a new range using the last REAL GPS snapshot (also works while scanning is Off). */
    private fun applyRange(meters: Double, custom: Boolean) {
        selectedRange = meters
        customRange = custom
        radar.setRange(meters)
        refreshTargets()
        updateRangeLabel()
    }

    private fun showCustomRangeDialog() {
        val dialog = NovaDialog(c)
        dialog.title(c.getString(R.string.radar_custom_title))
        val value = c.input(c.getString(R.string.radar_custom_value), numeric = true)
        if (customRange) value.setText(previewCustomValue(selectedRange, selectedRange >= 1000.0))
        var unitKm = !customRange || selectedRange >= 1000.0
        val mOption = compactOption(c.getString(R.string.u_m)) {}
        val kmOption = compactOption(c.getString(R.string.u_km)) {}
        fun paintUnits(kmSelected: Boolean) {
            paintUnitOption(kmOption, kmSelected)
            paintUnitOption(mOption, !kmSelected)
        }
        mOption.setOnClickListener { unitKm = false; paintUnits(false) }
        kmOption.setOnClickListener { unitKm = true; paintUnits(true) }
        val unitRow = c.hbox().apply {
            background = roundRect(C.RADAR_SOFT_BG, c.dp(16).toFloat(), C.RADAR_BORDER, c.dp(1))
            setPadding(c.dp(3), c.dp(3), c.dp(3), c.dp(3))
        }
        unitRow.addView(mOption, LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        unitRow.addView(kmOption, LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        paintUnits(unitKm)
        val body = c.vbox().apply {
            addView(value, lp())
            addView(unitRow, lp().margins(t = c.dp(10)))
        }
        dialog.content(body)
        dialog.button(c.getString(R.string.cancel), C.TEXT2) { it.dismiss() }
        dialog.button(c.getString(R.string.confirm), C.GOLD_DEEP, filled = true) {
            val raw = Destination.parseCoordinate(value.text.toString().trim())
            val meters = raw?.let { v -> if (unitKm) v * 1000.0 else v }
            if (meters == null || meters < CUSTOM_MIN_M || meters > CUSTOM_MAX_M) {
                c.message.error(c.getString(R.string.err_custom_range))
            } else {
                applyRange(meters, custom = true)
                it.dismiss()
            }
        }
        dialog.show()
    }

    private fun paintUnitOption(option: View, selected: Boolean) = paintScanOption(option, selected)

    private fun previewCustomValue(meters: Double, inKm: Boolean): String =
        if (inKm) String.format(java.util.Locale.US, "%.2f", meters / 1000.0)
        else Math.round(meters).toString()

    private fun updateRangeLabel() {
        rangeButton.text = "${c.getString(R.string.radar_range)}  ${rangeText(selectedRange)}  ▾"
    }

    private fun rangeText(range: Double): String = when (range.toInt()) {
        100 -> c.getString(R.string.radar_range_100m)
        1_000 -> c.getString(R.string.radar_range_1km)
        10_000 -> c.getString(R.string.radar_range_10km)
        25_000 -> c.getString(R.string.radar_range_25km)
        50_000 -> c.getString(R.string.radar_range_50km)
        100_000 -> c.getString(R.string.radar_range_100km)
        200_000 -> c.getString(R.string.radar_range_200km)
        300_000 -> c.getString(R.string.radar_range_300km)
        400_000 -> c.getString(R.string.radar_range_400km)
        else -> act.units.distance(range)
    }

    // ------------------------------------------------------------- real GPS data

    private fun isRealGpsFix(s: GpsState): Boolean {
        val location = s.location ?: return false
        return s.provider == LocationManager.GPS_PROVIDER &&
            location.provider == LocationManager.GPS_PROVIDER &&
            !location.isFromMockProvider &&
            (s.gpsStatus == GpsStatus.GPS_CONNECTED || s.gpsStatus == GpsStatus.WEAK_ACCURACY)
    }

    private fun render(state: GpsState) {
        lastState = state
        val realFix = isRealGpsFix(state)
        radar.setFixState(realFix, c.getString(R.string.radar_waiting_fix))
        recordGpsAccuracy(state, realFix)
        refreshTargets()
    }

    /** GPS accuracy is sampled once per distinct, accepted real GPS fix. */
    private fun recordGpsAccuracy(state: GpsState, realFix: Boolean) {
        val location = state.location
        if (!realFix || location == null || !location.hasAccuracy() ||
            !location.accuracy.isFinite() || location.accuracy <= 0f || location.elapsedRealtimeNanos <= 0L) {
            accuracyGraph.clearCurrent()
            return
        }
        val fixNanos = location.elapsedRealtimeNanos
        val fixMillis = fixNanos / 1_000_000L
        if (fixNanos != lastAccuracyFixNanos) {
            if (accuracyGraph.addSample(fixMillis, location.accuracy.toDouble())) {
                lastAccuracyFixNanos = fixNanos
            }
        } else {
            accuracyGraph.showCurrent(act.units.accuracy(location.accuracy), fixMillis)
        }
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val sampleTime = SystemClock.elapsedRealtime()
            var usedInFix = 0
            val cn0Readings = ArrayList<Double>(status.satelliteCount)
            for (index in 0 until status.satelliteCount) {
                if (status.usedInFix(index)) usedInFix++
                val cn0 = status.getCn0DbHz(index)
                // Android returns 0 when C/N0 is unavailable; do not turn that into a datapoint.
                if (cn0.isFinite() && cn0 > 0f) {
                    val seriesId = "${status.getConstellationType(index)}:${status.getSvid(index)}"
                    if (cn0Graph.addSeriesSample(seriesId, sampleTime, cn0.toDouble())) {
                        cn0Readings.add(cn0.toDouble())
                    }
                }
            }

            satellitesGraph.addSample(sampleTime, usedInFix.toDouble())
            if (cn0Readings.isEmpty()) {
                cn0Graph.clearCurrent()
            } else {
                // The plotted traces are individual satellites; this number is their actual
                // current mean, not a generated or smoothed signal value.
                cn0Graph.showCurrent(RadarMetricCard.cn0Summary(cn0Readings.average()), sampleTime)
            }
        }

        override fun onStopped() {
            cn0Graph.clearCurrent()
            satellitesGraph.clearCurrent()
        }
    }

    private fun registerGnssStatus() {
        if (!act.app.gps.hasFine() || !act.app.gps.isGpsEnabled()) {
            cn0Graph.clearCurrent()
            satellitesGraph.clearCurrent()
            return
        }
        gnssStatusRegistered = try {
            locationManager.registerGnssStatusCallback(c.mainExecutor, gnssCallback)
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
        if (!gnssStatusRegistered) {
            cn0Graph.clearCurrent()
            satellitesGraph.clearCurrent()
        }
    }

    private fun unregisterGnssStatus() {
        if (!gnssStatusRegistered) return
        try { locationManager.unregisterGnssStatusCallback(gnssCallback) } catch (_: Exception) {}
        gnssStatusRegistered = false
        cn0Graph.clearCurrent()
        satellitesGraph.clearCurrent()
    }

    /** Recomputes every distance/bearing from the current fix — markers follow real movement. */
    private fun refreshTargets() {
        val s = lastState
        val withinRange = if (s == null || !isRealGpsFix(s)) emptyList() else savedDestinations.mapNotNull { destination ->
            val currentLat = if (s.motion.hasPosition) s.motion.latitude else s.latitude
            val currentLon = if (s.motion.hasPosition) s.motion.longitude else s.longitude
            val geo = GeoMath.between(currentLat, currentLon, destination.latitude, destination.longitude)
            // Only destinations really inside the selected range are ever shown.
            if (geo.distanceM <= selectedRange) RadarTarget(destination.id, destination.name, geo.distanceM, geo.initialBearing) else null
        }
        lastTargets = withinRange
        if (selectedId != null && withinRange.none { it.id == selectedId }) selectedId = null
        radar.setSelected(selectedId)
        radar.setTargets(withinRange)
        updateBottomHud()
    }

    private fun updateBottomHud() {
        val s = lastState
        if (s == null || !isRealGpsFix(s)) {
            paintBottomDot(C.TEXT3)
            bottomText.text = c.getString(R.string.radar_waiting_fix)
            return
        }
        val selected = selectedId?.let { id -> lastTargets.firstOrNull { it.id == id } }
        if (selected != null) {
            paintBottomDot(C.RED)
            bottomText.text = c.getString(
                R.string.radar_selected_fmt,
                selected.name,
                act.units.distance(selected.distanceM),
                c.getString(R.string.radar_bearing_fmt, bearingDegrees(selected.bearing))
            )
            return
        }
        if (lastTargets.isEmpty()) {
            paintBottomDot(C.TEXT3)
            bottomText.text = c.getString(R.string.radar_none_in_range)
            return
        }
        val nearest = lastTargets.minByOrNull { it.distanceM } ?: return
        paintBottomDot(C.RED)
        val count = if (lastTargets.size == 1) c.getString(R.string.radar_count_one)
        else c.getString(R.string.radar_count_fmt, lastTargets.size)
        bottomText.text = "$count  |  ${nearest.name}  |  ${act.units.distance(nearest.distanceM)}"
    }

    private fun bearingDegrees(bearing: Float): Int {
        val v = Math.round(GeoMath.norm360(bearing))
        return if (v == 360) 0 else v
    }

    private fun paintBottomDot(color: Int) {
        bottomDot.background = roundRect(color, c.dp(4).toFloat())
    }

    private fun selectTarget(target: RadarTarget) {
        selectedId = target.id
        radar.setSelected(selectedId)
        updateBottomHud()
    }

    private fun clearSelection() {
        if (selectedId == null) return
        selectedId = null
        radar.setSelected(null)
        updateBottomHud()
    }

    private val gpsListener = LocationEngine.Listener { render(it) }
    private val destinationsListener = DestinationRepository.Listener {
        savedDestinations = it
        refreshTargets()
    }

    override fun onShow() {
        savedDestinations = act.app.destinations.all()
        act.app.destinations.addListener(destinationsListener)
        act.app.gps.addListener(gpsListener)
        registerGnssStatus()
    }

    override fun onHide() {
        act.app.destinations.removeListener(destinationsListener)
        act.app.gps.removeListener(gpsListener)
        unregisterGnssStatus()
        accuracyGraph.clearCurrent()
    }

    companion object {
        private val RANGES = doubleArrayOf(100.0, 1_000.0, 10_000.0, 25_000.0, 50_000.0, 100_000.0, 200_000.0, 300_000.0, 400_000.0)
        private const val MENU_CUSTOM = 100
        private const val CUSTOM_MIN_M = 10.0
        private const val CUSTOM_MAX_M = 2_000_000.0
    }
}
