package com.nova.gpspro.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.nova.gpspro.MainActivity
import com.nova.gpspro.R
import com.nova.gpspro.location.GpsState
import com.nova.gpspro.location.GpsStatus
import com.nova.gpspro.location.LocationEngine

class HomePage(act: MainActivity) : Page(act) {
    private val c = act
    private val sat = SatelliteView(c)

    /** Very small «location pulse» on the LEFT of 🛰️ — real GPS state only. */
    private val pulse = GpsPulseView(c)

    /** SIM-style 📶 GPS signal indicator on the RIGHT of 🛰️ — real GPS/GNSS data only. */
    private val signal = GpsSignalView(c)

    private val status = c.text("", 18f, C.TEXT, Fonts.medium).apply { gravity = Gravity.CENTER }
    private val sats = c.text("", 13f, C.TEXT2).apply { gravity = Gravity.CENTER }
    private val hint = c.text("", 13f, C.TEXT2).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f); visibility = View.GONE }
    private val action = c.outlineButton("") {}.apply { visibility = View.GONE }
    private val latV = valueText(); private val lonV = valueText(); private val accV = valueText()
    private var last: GpsState? = null
    private val coordRows = ArrayList<View>()

    // 🛰️ keeps its exact size (200 dp, shrinkable by the adaptive fitter exactly as before) and
    // its centred position. The two indicators live in the free space at the card edges, aligned
    // with the emblem's own vertical centre, so they can never overlap it.
    private val satLp = FrameLayout.LayoutParams(c.dp(200), c.dp(200), Gravity.CENTER)
    private val pulseLp = FrameLayout.LayoutParams(c.dp(22), c.dp(22), Gravity.LEFT or Gravity.TOP)
    private val signalLp = FrameLayout.LayoutParams(c.dp(32), c.dp(26), Gravity.RIGHT or Gravity.TOP)

    private fun valueText(): TextView = c.text("—", 17f, C.TEXT, Fonts.medium).apply {
        textDirection = View.TEXT_DIRECTION_LTR; gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }

    /** Keeps the indicators on the emblem's centre line for ANY box size the fitter picks. */
    private fun alignIndicators(boxPx: Int) {
        val centerY = (boxPx * SatelliteView.EMOJI_CENTER_Y).toInt()
        pulseLp.topMargin = (centerY - pulseLp.height / 2).coerceAtLeast(0)
        signalLp.topMargin = (centerY - signalLp.height / 2).coerceAtLeast(0)
    }

    override val view: View = run {
        val col = c.vbox().apply { setPadding(c.dp(20), c.dp(18), c.dp(20), c.dp(20)); gravity = Gravity.CENTER_HORIZONTAL }

        col.addView(c.text(c.getString(R.string.app_name), 22f, C.GOLD_DEEP, Fonts.medium).apply {
            gravity = Gravity.CENTER; letterSpacing = 0.2f
        }, lp())
        col.addView(c.text(c.getString(R.string.app_tagline), 13f, C.TEXT2).apply { gravity = Gravity.CENTER; letterSpacing = 0.05f },
            lp().margins(t = c.dp(2)))

        // status card
        val statusCard = c.vbox().apply {
            background = c.card(26); softElevation(2f)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(c.dp(18), c.dp(14), c.dp(18), c.dp(18))
        }
        pulseLp.leftMargin = c.dp(1)
        signalLp.rightMargin = c.dp(1)
        alignIndicators(c.dp(200))
        val satFrame = FrameLayout(c).apply {
            addView(sat, satLp)
            addView(pulse, pulseLp)
            addView(signal, signalLp)
        }
        statusCard.addView(satFrame, lp())
        statusCard.addView(status, lp().margins(t = c.dp(2)))
        statusCard.addView(sats, lp().margins(t = c.dp(4)))
        statusCard.addView(hint, lp().margins(t = c.dp(8)))
        statusCard.addView(action, lp(ViewGroup.LayoutParams.WRAP_CONTENT).margins(t = c.dp(12)))
        col.addView(statusCard, lp().margins(t = c.dp(18)))

        // Save button – slightly above the middle
        val saveBtn = c.goldButton("＋  " + c.getString(R.string.save_location)) { onSave() }
        col.addView(saveBtn, lp().margins(t = c.dp(20)))

        // coordinates card
        val coords = c.vbox().apply { background = c.card(22); softElevation(2f); setPadding(c.dp(18), c.dp(8), c.dp(18), c.dp(8)) }
        coords.addView(c.text(c.getString(R.string.current_position), 12f, C.GOLD_DEEP, Fonts.medium).apply { letterSpacing = 0.08f },
            lp().margins(t = c.dp(8), b = c.dp(4)))
        coords.addView(row(R.string.latitude, latV)); coords.addView(divider())
        coords.addView(row(R.string.longitude, lonV)); coords.addView(divider())
        coords.addView(row(R.string.accuracy, accV))
        col.addView(coords, lp().margins(t = c.dp(20)))
        col.addView(View(c), lp(h = 0, weight = 1f))
        // adaptive: gaps, paddings and the satellite emblem (200 → 112 dp) shrink to the real available height
        FitScroll(c, col).compressGroups(col, statusCard, coords)
            .compressPadding(saveBtn, *coordRows.toTypedArray())
            .shrinkable(c.dp(112), c.dp(200)) { satLp.width = it; satLp.height = it; alignIndicators(it) }
    }

    private fun row(label: Int, v: TextView) = c.hbox().also { coordRows.add(it) }.apply {
        setPadding(0, c.dp(12), 0, c.dp(12))
        addView(c.text(c.getString(label), 15f, C.TEXT2), lp(0, weight = 1f))
        addView(v, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private fun divider() = View(c).apply { setBackgroundColor(C.BORDER); layoutParams = lp(h = c.dp(1)) }

    private val listener = LocationEngine.Listener { render(it) }

    override fun onShow() { act.app.gps.addListener(listener) }
    override fun onHide() { act.app.gps.removeListener(listener) }

    private fun render(s: GpsState) {
        last = s
        val (txt, color, look) = when (s.gpsStatus) {
            GpsStatus.NO_GPS_PERMISSION -> Triple(R.string.status_no_permission, C.RED, SatelliteView.Look.OFF)
            GpsStatus.GPS_DISABLED -> Triple(R.string.status_disabled, C.RED, SatelliteView.Look.OFF)
            GpsStatus.SEARCHING -> Triple(R.string.status_searching, C.AMBER, SatelliteView.Look.SEARCHING)
            GpsStatus.GPS_CONNECTED -> Triple(R.string.status_connected, C.GREEN, SatelliteView.Look.CONNECTED)
            GpsStatus.GPS_LOST -> Triple(R.string.status_lost, C.RED, SatelliteView.Look.OFF)
            GpsStatus.WEAK_ACCURACY -> Triple(R.string.status_weak, C.ORANGE_GOLD, SatelliteView.Look.WEAK)
        }
        status.text = c.getString(txt); status.setTextColor(color); sat.look = look

        // ---- real-data indicators (pulse left, signal right) — never touch the emblem
        pulse.tint = GpsSignal.pulseColor(s)
        pulse.live = GpsSignal.liveFix(s)
        signal.level = GpsSignal.level(s)

        val showSats = s.gpsStatus != GpsStatus.NO_GPS_PERMISSION && s.gpsStatus != GpsStatus.GPS_DISABLED && s.satellitesVisible > 0
        sats.visibility = if (showSats) View.VISIBLE else View.INVISIBLE
        if (showSats) sats.text = c.getString(R.string.satellites_fmt, s.satellitesUsed, s.satellitesVisible)

        when (s.gpsStatus) {
            GpsStatus.NO_GPS_PERMISSION -> {
                hint.text = c.getString(R.string.permission_explain); hint.visibility = View.VISIBLE
                action.text = c.getString(R.string.grant_permission); action.visibility = View.VISIBLE
                action.setOnClickListener { act.requestLocationPermission(userInitiated = true) }
            }
            GpsStatus.GPS_DISABLED -> {
                hint.visibility = View.GONE
                action.text = c.getString(R.string.open_location_settings); action.visibility = View.VISIBLE
                action.setOnClickListener { act.openLocationSettings() }
            }
            else -> {
                if (!s.preciseGranted) {
                    hint.text = c.getString(R.string.precise_needed); hint.visibility = View.VISIBLE
                    action.text = c.getString(R.string.grant_permission); action.visibility = View.VISIBLE
                    action.setOnClickListener { act.requestLocationPermission(userInitiated = true) }
                } else { hint.visibility = View.GONE; action.visibility = View.GONE }
            }
        }

        val live = GpsSignal.liveFix(s)
        if (live) {
            latV.text = act.units.coord(s.latitude)
            lonV.text = act.units.coord(s.longitude)
            accV.text = act.units.accuracy(s.accuracy)
            accV.setTextColor(if (s.gpsStatus == GpsStatus.GPS_CONNECTED) C.GREEN else C.ORANGE_GOLD)
        } else {
            latV.text = "—"; lonV.text = "—"
            accV.text = act.units.accuracy(0f); accV.setTextColor(C.TEXT3)
        }
    }

    private fun onSave() {
        val s = act.app.gps.state
        if (!s.isUsable || s.location == null) { act.message.error(c.getString(R.string.err_no_fix)); return }
        DestinationEditor(act, null, s) { _, _ -> act.message.success(c.getString(R.string.msg_saved)) }.show()
    }
}
