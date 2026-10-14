package com.nova.gpspro.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.nova.gpspro.MainActivity
import com.nova.gpspro.R
import com.nova.gpspro.navigation.ArrowEngine
import com.nova.gpspro.navigation.NavState
import com.nova.gpspro.navigation.NavigationEngine
import java.text.DateFormat
import java.util.Date

class NavigationPage(act: MainActivity) : Page(act) {
    private val c = act
    private val arrow = ArrowView(c)
    private val speedo = SpeedometerView(c)
    private val destName = c.text("", 17f, C.TEXT, Fonts.medium).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val hint = c.text("", 12f, C.TEXT2).apply { gravity = Gravity.CENTER }
    private val bigDistance = c.text("", 20f, C.GOLD_DEEP, Fonts.medium).apply { textDirection = View.TEXT_DIRECTION_LTR; maxLines = 1 }
    private val arrivalDetail = c.text("", 12f, C.GREEN).apply { maxLines = 1; visibility = View.GONE; textDirection = View.TEXT_DIRECTION_LOCALE }
    private val infoBox = c.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
    private val vRemaining = statValue(); private val vCovered = statValue()
    private val vMax = statValue(); private val vArrival = statValue()

    private fun statValue(): TextView = c.text("—", 13f, C.TEXT, Fonts.medium).apply {
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; textDirection = View.TEXT_DIRECTION_LTR
        textAlignment = View.TEXT_ALIGNMENT_CENTER
    }

    private val navCol = NavLayout(c)
    override val view: View = navCol
    init { navCol.apply {
        setPadding(c.dp(14), c.dp(8), c.dp(14), c.dp(6))

        // 1) compact destination selector (still ≥ 48dp touch target)
        val sel = c.hbox().apply {
            val r = c.dp(16).toFloat()
            background = ripple(roundRect(C.CARD, r, C.BORDER, c.dp(1)), r); softElevation(1.5f)
            setPadding(c.dp(10), c.dp(6), c.dp(12), c.dp(6))
            minimumHeight = c.dp(48)
            setOnClickListener { DestinationPicker(act) { d -> act.app.navigation.setDestination(d) }.show() }
        }
        val pin = c.text("◆", 12f, C.ACCENT).apply {
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(C.GOLD_PALE) }
        }
        sel.addView(pin, LinearLayout.LayoutParams(c.dp(30), c.dp(30)))
        val tcol = c.vbox()
        tcol.addView(c.text(c.getString(R.string.destination), 10f, C.TEXT2, Fonts.medium).apply { letterSpacing = 0.08f })
        destName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
        tcol.addView(destName)
        sel.addView(tcol, lp(0, weight = 1f).margins(s = c.dp(10)))
        sel.addView(c.text(if (c.isRtl()) "‹" else "›", 22f, C.GOLD, Fonts.light), lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(sel)

        // 2) destination info: distance + status / arrival message (outside the arrow area)
        infoBox.addView(bigDistance, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        hint.maxLines = 1; hint.ellipsize = android.text.TextUtils.TruncateAt.END
        infoBox.addView(hint, lp())
        infoBox.addView(arrivalDetail, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(infoBox)

        // 3) large arrow zone – gets the biggest share of space
        addView(arrow)

        // 4) safety gap, 5) speedometer
        addView(speedo)

        // 6) trip info – one compact row
        val r1 = c.hbox()
        r1.addView(stat(R.string.distance_remaining, vRemaining), lp(0, weight = 1f).margins(e = c.dp(3)))
        r1.addView(stat(R.string.distance_covered, vCovered), lp(0, weight = 1f).margins(s = c.dp(3), e = c.dp(3)))
        r1.addView(stat(R.string.max_speed, vMax), lp(0, weight = 1f).margins(s = c.dp(3), e = c.dp(3)))
        r1.addView(stat(R.string.arrival, vArrival), lp(0, weight = 1f).margins(s = c.dp(3)))
        addView(r1)

        // 7) controls
        val ctr = c.hbox()
        ctr.addView(c.outlineButton(c.getString(R.string.reset_trip)) { act.app.navigation.resetTrip() }.apply { setPadding(0, c.dp(8), 0, c.dp(8)); setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f) },
            lp(0, weight = 1f).margins(e = c.dp(5)))
        ctr.addView(c.outlineButton(c.getString(R.string.end_navigation), C.RED) { act.app.navigation.setDestination(null) }.apply { setPadding(0, c.dp(8), 0, c.dp(8)); setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f) },
            lp(0, weight = 1f).margins(s = c.dp(5)))
        addView(ctr)
        // sizing: speedometer fixed at its design size, arrow adapts first, then the gaps
        arrowView = arrow; speedoView = speedo
        gapsPx = intArrayOf(0, c.dp(6), c.dp(4), c.dp(14), c.dp(6), c.dp(6))
        speedoPx = c.dp(180); speedoFloorPx = c.dp(120)
        arrowMaxPx = c.dp(260); arrowMinPx = c.dp(130); arrowFloorPx = c.dp(80)
    } }

    private fun stat(label: Int, v: TextView) = c.vbox().apply {
        background = c.card(16)
        background = c.card(14)
        setPadding(c.dp(6), c.dp(6), c.dp(6), c.dp(6))
        gravity = Gravity.CENTER_HORIZONTAL
        addView(c.text(c.getString(label), 10f, C.TEXT2, Fonts.medium).apply { maxLines = 2; gravity = Gravity.CENTER; minLines = 2 })
        addView(v, lp().margins(t = c.dp(2)))
    }

    private val listener = NavigationEngine.Listener { render(it) }
    private var firstRender = true
    private var lastArrivalEvent = -1

    override fun onShow() {
        speedo.unitLabel = act.units.speedUnitLabel()
        speedo.digital = act.app.settings.digitalSpeedometer
        firstRender = true
        act.app.navigation.addListener(listener)
    }
    override fun onHide() { act.app.navigation.removeListener(listener) }

    private fun render(s: NavState) {
        val u = act.units
        val d = s.destination
        destName.text = d?.name ?: c.getString(R.string.select_destination)
        destName.setTextColor(if (d != null) C.TEXT else C.GOLD_DEEP)

        speedo.active = s.hasFix
        speedo.setSpeed(if (s.hasFix) u.speedValue(s.speedMps) else 0.0)

        val navigating = d != null && s.hasFix && s.distanceRemainingM >= 0
        arrow.active = navigating && s.arrowMode != ArrowEngine.Mode.NO_BEARING
        if (navigating) arrow.setAngle(s.arrowAngle, immediate = firstRender)
        firstRender = false

        hint.text = when {
            d == null -> c.getString(R.string.hint_no_dest)
            !s.hasFix -> c.getString(R.string.hint_no_gps)
            s.arrowMode == ArrowEngine.Mode.NO_BEARING -> c.getString(R.string.hint_move)
            else -> ""
        }
        bigDistance.text = if (navigating) u.distance(s.distanceRemainingM) else ""
        bigDistance.visibility = if (navigating) View.VISIBLE else View.GONE
        if (navigating && s.arrived) {
            hint.text = "✓  " + c.getString(R.string.arrival_ready)
            hint.setTextColor(C.GREEN); hint.typeface = Fonts.medium
            arrivalDetail.text = c.getString(R.string.arrival_detail_fmt, u.distance(s.distanceRemainingM), u.accuracy(s.accuracyM))
            arrivalDetail.visibility = View.VISIBLE
        } else {
            hint.setTextColor(C.TEXT2); hint.typeface = Fonts.regular
            arrivalDetail.visibility = View.GONE
        }
        // one-time arrival announcement per real arrival (not on every GPS update)
        if (s.arrivalEventId != lastArrivalEvent) {
            val isNew = lastArrivalEvent >= 0 && s.arrived
            lastArrivalEvent = s.arrivalEventId
            if (isNew) {
                act.message.success(c.getString(R.string.arrival_ready))
                arrow.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
            }
        }

        vRemaining.text = if (navigating) u.distance(s.distanceRemainingM) else "—"
        vCovered.text = if (d != null) u.distance(s.distanceCoveredM) else "—"
        vMax.text = if (d != null) u.speed(s.maxSpeedMps) else "—"
        vArrival.text = when {
            !navigating -> "—"
            s.arrived -> c.getString(R.string.arrived)
            s.etaEpochMs > 0 -> DateFormat.getTimeInstance(DateFormat.SHORT, c.resources.configuration.locales[0]).format(Date(s.etaEpochMs))
            else -> "—"
        }
        vArrival.setTextColor(if (navigating && s.arrived) C.GREEN else C.TEXT)
    }
}
