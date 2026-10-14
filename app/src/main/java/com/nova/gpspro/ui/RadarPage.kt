package com.nova.gpspro.ui

import android.location.LocationManager
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
 *   top GPS HUD   – real GPS FIX state, satellites actually used in the fix, current accuracy
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
    private lateinit var fixValue: TextView
    private lateinit var satellitesValue: TextView
    private lateinit var accuracyValue: TextView
    private lateinit var bottomDot: View
    private lateinit var bottomText: TextView

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

        // GPS telemetry HUD — always OUTSIDE the radar circle, updated on every GPS event.
        val gpsHud = c.hbox().apply { setPadding(0, 0, 0, 0) }
        val fixChip = hudChip(R.string.radar_gps_fix)
        val satsChip = hudChip(R.string.radar_satellites)
        val accChip = hudChip(R.string.radar_accuracy)
        fixValue = fixChip.second; satellitesValue = satsChip.second; accuracyValue = accChip.second
        gpsHud.addView(fixChip.first, lp(0, weight = 1f))
        gpsHud.addView(satsChip.first, lp(0, weight = 1f).margins(s = c.dp(8), e = c.dp(8)))
        gpsHud.addView(accChip.first, lp(0, weight = 1f))
        addView(gpsHud, lp().margins(b = c.dp(4)))

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

    /** A small telemetry card: caption + live value, kept outside the radar disc. */
    private fun hudChip(labelRes: Int): Pair<View, TextView> {
        val value = c.text("—", 15f, C.TEXT, Fonts.medium).apply {
            gravity = Gravity.CENTER
            maxLines = 1
        }
        val chip = c.vbox().apply {
            gravity = Gravity.CENTER
            background = roundRect(C.CARD, c.dp(13).toFloat(), C.BORDER, c.dp(1))
            setPadding(c.dp(4), c.dp(7), c.dp(4), c.dp(8))
            addView(c.text(c.getString(labelRes), 10f, C.TEXT2, Fonts.medium).apply {
                gravity = Gravity.CENTER
                maxLines = 1
            }, lp())
            addView(value, lp().margins(t = c.dp(1)))
        }
        return chip to value
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
        if (enabled) render(lastState ?: c.app.gps.state)   // instant return to real data
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

    private fun isRealGpsFix(s: GpsState): Boolean =
        s.location != null && s.provider == LocationManager.GPS_PROVIDER &&
            (s.gpsStatus == GpsStatus.GPS_CONNECTED || s.gpsStatus == GpsStatus.WEAK_ACCURACY)

    private fun render(state: GpsState) {
        lastState = state
        if (!autoScan) return          // Off: sweep and every radar visual stays frozen (GPS engine itself keeps running)
        radar.setFixState(isRealGpsFix(state), c.getString(R.string.radar_waiting_fix))
        paintGpsHud(state)
        refreshTargets()
    }

    private fun paintGpsHud(s: GpsState) {
        val fix = isRealGpsFix(s)
        val (labelRes, color) = when (s.gpsStatus) {
            GpsStatus.GPS_CONNECTED -> R.string.radar_fix_locked to C.GREEN
            GpsStatus.WEAK_ACCURACY -> R.string.radar_fix_weak to C.AMBER
            else -> R.string.radar_fix_unavailable to C.RED
        }
        fixValue.text = c.getString(labelRes)
        fixValue.setTextColor(color)
        satellitesValue.text = s.satellitesUsed.coerceAtLeast(0).toString()
        satellitesValue.setTextColor(if (fix) C.ACCENT else C.TEXT2)
        accuracyValue.text = if (fix) act.units.accuracy(s.accuracy) else "—"
        accuracyValue.setTextColor(if (fix) C.ACCENT else C.TEXT2)
    }

    /** Recomputes every distance/bearing from the current fix — markers follow real movement. */
    private fun refreshTargets() {
        val s = lastState
        val withinRange = if (s == null || !isRealGpsFix(s)) emptyList() else savedDestinations.mapNotNull { destination ->
            val geo = GeoMath.between(s.latitude, s.longitude, destination.latitude, destination.longitude)
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
            paintBottomDot(bandColor(RadarPlot.band(selected.distanceM, selectedRange)))
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
        paintBottomDot(bandColor(RadarPlot.band(nearest.distanceM, selectedRange)))
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

    private fun bandColor(band: RadarPlot.Band): Int = when (band) {
        RadarPlot.Band.NEAR -> C.GREEN
        RadarPlot.Band.MEDIUM -> C.AMBER
        RadarPlot.Band.FAR -> C.RED
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
        if (autoScan) refreshTargets()
    }

    override fun onShow() {
        savedDestinations = act.app.destinations.all()
        act.app.destinations.addListener(destinationsListener)
        act.app.gps.addListener(gpsListener)
    }

    override fun onHide() {
        act.app.destinations.removeListener(destinationsListener)
        act.app.gps.removeListener(gpsListener)
    }

    companion object {
        private val RANGES = doubleArrayOf(100.0, 1_000.0, 10_000.0, 25_000.0, 50_000.0, 100_000.0, 200_000.0, 300_000.0, 400_000.0)
        private const val MENU_CUSTOM = 100
        private const val CUSTOM_MIN_M = 10.0
        private const val CUSTOM_MAX_M = 2_000_000.0
    }
}
