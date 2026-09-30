package com.nova.gpspro.ui

import android.location.LocationManager
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import com.nova.gpspro.MainActivity
import com.nova.gpspro.R
import com.nova.gpspro.data.DestinationRepository
import com.nova.gpspro.location.GpsState
import com.nova.gpspro.location.GpsStatus
import com.nova.gpspro.location.LocationEngine
import com.nova.gpspro.navigation.GeoMath

/**
 * Saved-location radar. Positions are derived anew from the current GPS coordinate whenever a
 * GPS fix arrives. It never depends on device-orientation or motion input.
 */
class RadarPage(act: MainActivity) : Page(act) {
    private val c = act
    private val radar = RadarView(c)
    private var autoScan = true
    private var selectedRange = RANGES.first()
    private var lastState: GpsState? = null
    private var lastAccuracyNanos = Long.MIN_VALUE
    private var savedDestinations = act.app.destinations.all()

    private lateinit var autoOption: View
    private lateinit var offOption: View
    private lateinit var rangeButton: android.widget.TextView

    override val view: View = c.vbox().apply {
        setPadding(c.dp(16), c.dp(12), c.dp(16), c.dp(12))

        val header = c.hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(c.text(c.getString(R.string.tab_radar), 24f, C.TEXT, Fonts.medium), lp(0, weight = 1f))
        header.addView(c.text(c.getString(R.string.radar_live), 11f, 0xFF007E9A.toInt(), Fonts.medium).apply {
            gravity = Gravity.CENTER
            background = roundRect(0xFFE2F8FB.toInt(), c.dp(12).toFloat())
            setPadding(c.dp(10), c.dp(6), c.dp(10), c.dp(6))
            letterSpacing = .04f
        }, lp(android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(header, lp())

        val controls = c.hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, c.dp(10), 0, c.dp(6))
        }
        val scanChoices = c.hbox().apply {
            background = roundRect(0xFFEAF7FA.toInt(), c.dp(16).toFloat(), 0xFFBDE9EF.toInt(), c.dp(1))
            setPadding(c.dp(3), c.dp(3), c.dp(3), c.dp(3))
        }
        autoOption = compactOption(c.getString(R.string.radar_auto)) { setAutoScan(true) }
        offOption = compactOption(c.getString(R.string.radar_off)) { setAutoScan(false) }
        scanChoices.addView(autoOption, LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        scanChoices.addView(offOption, LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(scanChoices, lp(c.dp(126)))

        rangeButton = c.text("", 13f, 0xFF075B79.toInt(), Fonts.medium).apply {
            gravity = Gravity.CENTER
            background = ripple(roundRect(0xFFFFFFFF.toInt(), c.dp(15).toFloat(), 0xFFBDE9EF.toInt(), c.dp(1)), c.dp(15).toFloat(), 0x2200B8D4)
            setPadding(c.dp(12), c.dp(10), c.dp(12), c.dp(10))
            isClickable = true
            isFocusable = true
            contentDescription = c.getString(R.string.radar_range)
            setOnClickListener { showRangeMenu() }
        }
        controls.addView(rangeButton, lp(0, weight = 1f).margins(s = c.dp(10)))
        addView(controls, lp())

        radar.apply {
            setRange(selectedRange)
            setScanning(autoScan)
            onTargetClick = { showTarget(it) }
        }
        addView(radar, lp(h = 0, weight = 1f).margins(t = c.dp(2)))

        setAutoScan(true)
        updateRangeLabel()
    }

    private fun compactOption(label: String, onClick: () -> Unit): View =
        c.text(label, 12f, 0xFF386D7D.toInt(), Fonts.medium).apply {
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
        if (!::autoOption.isInitialized || !::offOption.isInitialized) return
        paintScanOption(autoOption, enabled)
        paintScanOption(offOption, !enabled)
    }

    private fun paintScanOption(option: View, selected: Boolean) {
        val text = option as android.widget.TextView
        if (selected) {
            text.setTextColor(android.graphics.Color.WHITE)
            text.background = gradientRect(0xFF0D9DC4.toInt(), 0xFF087895.toInt(), c.dp(13).toFloat())
            text.elevation = c.dp(1).toFloat()
        } else {
            text.setTextColor(0xFF386D7D.toInt())
            text.background = null
            text.elevation = 0f
        }
    }

    private fun showRangeMenu() {
        PopupMenu(c, rangeButton).apply {
            RANGES.forEachIndexed { index, range ->
                menu.add(0, index, index, rangeText(range)).isCheckable = true
                menu.findItem(index).isChecked = range == selectedRange
            }
            setOnMenuItemClickListener { item ->
                selectedRange = RANGES[item.itemId]
                radar.setRange(selectedRange)
                refreshTargets()
                updateRangeLabel()
                true
            }
            show()
        }
    }

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
        else -> c.getString(R.string.radar_range_300km)
    }

    private fun isRealGpsFix(s: GpsState): Boolean =
        s.location != null && s.provider == LocationManager.GPS_PROVIDER &&
            (s.gpsStatus == GpsStatus.GPS_CONNECTED || s.gpsStatus == GpsStatus.WEAK_ACCURACY)

    private fun render(state: GpsState) {
        lastState = state
        val gpsFix = isRealGpsFix(state)
        val addSample = gpsFix && state.elapsedRealtimeNanos != 0L && state.elapsedRealtimeNanos != lastAccuracyNanos
        if (addSample) lastAccuracyNanos = state.elapsedRealtimeNanos
        radar.updateTelemetry(
            gpsFix = gpsFix,
            statusLabel = c.getString(radarFixResource(state.gpsStatus)),
            usedSatellites = state.satellitesUsed,
            accuracy = state.accuracy.takeIf { gpsFix },
            formattedAccuracy = if (gpsFix) act.units.accuracy(state.accuracy) else null,
            labels = RadarLabels(
                gpsFix = c.getString(R.string.radar_gps_fix),
                satellites = c.getString(R.string.radar_satellites),
                accuracy = c.getString(R.string.radar_accuracy),
                waiting = c.getString(R.string.radar_waiting_fix)
            ),
            addAccuracySample = addSample
        )
        refreshTargets()
    }

    private fun radarFixResource(status: GpsStatus): Int = when (status) {
        GpsStatus.GPS_CONNECTED -> R.string.radar_fix_locked
        GpsStatus.WEAK_ACCURACY -> R.string.radar_fix_weak
        else -> R.string.radar_fix_unavailable
    }

    private fun refreshTargets() {
        val s = lastState
        if (s == null || !isRealGpsFix(s)) {
            radar.setTargets(emptyList())
            return
        }
        val withinRange = savedDestinations.mapNotNull { destination ->
            val geo = GeoMath.between(s.latitude, s.longitude, destination.latitude, destination.longitude)
            if (geo.distanceM <= selectedRange) RadarTarget(destination.id, destination.name, geo.distanceM, geo.initialBearing) else null
        }
        radar.setTargets(withinRange)
    }

    private fun showTarget(target: RadarTarget) {
        NovaDialog(c)
            .title(target.name)
            .message(c.getString(R.string.radar_distance_fmt, act.units.distance(target.distanceM)))
            .button(c.getString(R.string.confirm), C.GOLD_DEEP, filled = true) { it.dismiss() }
            .show()
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
    }

    override fun onHide() {
        act.app.destinations.removeListener(destinationsListener)
        act.app.gps.removeListener(gpsListener)
    }

    companion object {
        private val RANGES = doubleArrayOf(100.0, 1_000.0, 10_000.0, 25_000.0, 50_000.0, 100_000.0, 200_000.0, 300_000.0)
    }
}
