package com.nova.gpspro.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.DashPathEffect
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.nova.gpspro.MainActivity
import com.nova.gpspro.R
import com.nova.gpspro.data.Trip
import com.nova.gpspro.trips.TripManager
import com.nova.gpspro.trips.TripRouteResolver
import java.util.Date

/** Portal-only trips page: on-device real-GPS recording and saved route history. */
class TripsPage(act: MainActivity) : Page(act) {
    private val c = act
    private val manager get() = act.app.trips
    private var visible = false
    private var lastRevision = -1L
    private var lastPhase: TripManager.Phase? = null
    private var lastMode: TripManager.Mode? = null

    private lateinit var modeManual: TextView
    private lateinit var modeAuto: TextView
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var hintText: TextView
    private lateinit var distanceValue: TextView
    private lateinit var durationValue: TextView
    private lateinit var averageValue: TextView
    private lateinit var maximumValue: TextView
    private lateinit var currentValue: TextView
    private lateinit var pointsValue: TextView
    private lateinit var actions: LinearLayout
    private lateinit var list: ListView
    private lateinit var emptyPanel: LinearLayout

    private val adapter = object : BaseAdapter() {
        private var items: List<Trip> = emptyList()
        fun replace(next: List<Trip>) { items = next; notifyDataSetChanged() }
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id.hashCode().toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View = tripCard(items[position])
    }

    override val view: View = c.vbox().apply {
        setPadding(c.dp(16), c.dp(10), c.dp(16), c.dp(8))

        val header = c.hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        val back = c.text(if (c.isRtl()) "›" else "‹", 30f, C.TEXT, Fonts.regular).apply {
            gravity = Gravity.CENTER
            contentDescription = c.getString(R.string.back_to_portal)
            background = ripple(roundRect(C.CARD, c.dp(16).toFloat()), c.dp(16).toFloat())
            setOnClickListener { act.showPage(MainActivity.PAGE_PORTAL) }
        }
        header.addView(back, LinearLayout.LayoutParams(c.dp(44), c.dp(44)))
        header.addView(c.text(c.getString(R.string.trips_title), 23f, C.TEXT, Fonts.medium), lp(0, weight = 1f).margins(s = c.dp(8)))
        addView(header, lp())

        val modeBox = c.hbox().apply {
            background = roundRect(C.CARD_SOFT, c.dp(16).toFloat(), C.BORDER, c.dp(1))
            setPadding(c.dp(3), c.dp(3), c.dp(3), c.dp(3))
        }
        modeManual = modeOption(c.getString(R.string.trip_mode_manual)) { changeMode(TripManager.Mode.MANUAL) }
        modeAuto = modeOption(c.getString(R.string.trip_mode_automatic)) { changeMode(TripManager.Mode.AUTOMATIC) }
        modeBox.addView(modeManual, LinearLayout.LayoutParams(0, c.dp(40), 1f))
        modeBox.addView(modeAuto, LinearLayout.LayoutParams(0, c.dp(40), 1f))
        addView(modeBox, lp().margins(t = c.dp(6)))

        val live = c.vbox().apply {
            background = c.card(18)
            setPadding(c.dp(12), c.dp(10), c.dp(12), c.dp(10))
        }
        val statusRow = c.hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        statusDot = View(c).apply { background = roundRect(C.TEXT3, c.dp(5).toFloat()) }
        statusText = c.text("", 13.5f, C.TEXT, Fonts.medium).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(c.dp(10), c.dp(10)).margins(e = c.dp(8)))
        statusRow.addView(statusText, lp(0, weight = 1f))
        live.addView(statusRow, lp())

        val row1 = c.hbox().apply { gravity = Gravity.TOP }
        val row2 = c.hbox().apply { gravity = Gravity.TOP; setPadding(0, c.dp(8), 0, 0) }
        distanceValue = addMetric(row1, R.string.trip_distance)
        durationValue = addMetric(row1, R.string.trip_duration)
        averageValue = addMetric(row1, R.string.trip_average_speed)
        maximumValue = addMetric(row2, R.string.trip_max_speed)
        currentValue = addMetric(row2, R.string.trip_current_speed)
        pointsValue = addMetric(row2, R.string.trip_gps_points)
        live.addView(row1, lp().margins(t = c.dp(8)))
        live.addView(row2, lp())
        hintText = c.text("", 11.5f, C.TEXT2).apply { setLineSpacing(0f, 1.1f) }
        live.addView(hintText, lp().margins(t = c.dp(6)))
        actions = c.hbox().apply { gravity = Gravity.CENTER; setPadding(0, c.dp(8), 0, 0) }
        live.addView(actions, lp())
        addView(live, lp().margins(t = c.dp(8)))

        val historyHeader = c.hbox().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(c.dp(2), c.dp(12), c.dp(2), c.dp(5)) }
        historyHeader.addView(c.text(c.getString(R.string.trip_history), 17f, C.TEXT, Fonts.medium), lp(0, weight = 1f))
        addView(historyHeader, lp())

        val frame = FrameLayout(c)
        list = ListView(c).apply {
            divider = android.graphics.drawable.ColorDrawable(C.BG)
            dividerHeight = c.dp(10)
            selector = android.graphics.drawable.ColorDrawable(0)
            clipToPadding = false
            setPadding(0, c.dp(2), 0, c.dp(12))
            isVerticalScrollBarEnabled = false
            adapter = this@TripsPage.adapter
        }
        frame.addView(list, FrameLayout.LayoutParams(-1, -1))
        emptyPanel = c.vbox().apply {
            gravity = Gravity.CENTER
            setPadding(c.dp(26), c.dp(12), c.dp(26), c.dp(18))
            addView(c.text("⌁", 36f, C.GOLD_DEEP, Fonts.light).apply { gravity = Gravity.CENTER })
            addView(c.text(c.getString(R.string.trip_empty_title), 15f, C.TEXT, Fonts.medium).apply { gravity = Gravity.CENTER }, lp().margins(t = c.dp(6)))
            addView(c.text(c.getString(R.string.trip_empty_hint), 12.5f, C.TEXT2).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.15f) }, lp().margins(t = c.dp(4)))
        }
        frame.addView(emptyPanel, FrameLayout.LayoutParams(-1, -1))
        addView(frame, lp(h = 0, weight = 1f))
    }

    private fun addMetric(row: LinearLayout, labelId: Int): TextView {
        val box = c.vbox().apply { gravity = Gravity.CENTER; setPadding(c.dp(2), 0, c.dp(2), 0) }
        val value = c.text("—", 15f, C.TEXT, Fonts.medium).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            textDirection = View.TEXT_DIRECTION_LTR
        }
        box.addView(value, lp())
        box.addView(c.text(c.getString(labelId), 10.5f, C.TEXT2).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, lp().margins(t = c.dp(1)))
        row.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return value
    }

    private fun modeOption(label: String, click: () -> Unit) = c.text(label, 13f, C.TEXT2, Fonts.medium).apply {
        gravity = Gravity.CENTER
        isFocusable = true
        setOnClickListener { click() }
    }

    private val listener = TripManager.Listener { state -> if (visible) render(state) }
    private val timer = object : Runnable {
        override fun run() {
            if (!visible) return
            render(manager.snapshot())
        }
    }
    private val handler = Handler(Looper.getMainLooper())

    override fun onShow() {
        visible = true
        manager.addListener(listener)
        updateTrips()
        render(manager.snapshot())
        handler.removeCallbacks(timer)
        if (manager.snapshot().phase == TripManager.Phase.RECORDING) handler.postDelayed(timer, 1_000L)
    }

    override fun onHide() {
        visible = false
        manager.removeListener(listener)
        handler.removeCallbacks(timer)
    }

    private fun render(state: TripManager.UiState) {
        if (state.savedRevision != lastRevision) {
            lastRevision = state.savedRevision
            updateTrips()
        }
        paintMode(modeManual, state.mode == TripManager.Mode.MANUAL, state.phase != TripManager.Phase.IDLE)
        paintMode(modeAuto, state.mode == TripManager.Mode.AUTOMATIC, state.phase != TripManager.Phase.IDLE)
        val idle = state.phase == TripManager.Phase.IDLE
        distanceValue.text = if (idle) "—" else act.units.distance(state.distanceM)
        durationValue.text = if (idle) "—" else formatDuration(state.durationMs)
        averageValue.text = if (idle) "—" else act.units.speed(state.averageSpeedMps)
        maximumValue.text = state.maxSpeedMps?.let(act.units::speed) ?: "—"
        currentValue.text = state.currentSpeedMps?.let(act.units::speed) ?: "—"
        pointsValue.text = if (idle) "—" else state.pointCount.toString()
        pointsValue.textDirection = View.TEXT_DIRECTION_LTR

        when (state.phase) {
            TripManager.Phase.IDLE -> if (state.mode == TripManager.Mode.AUTOMATIC) {
                statusText.text = c.getString(if (state.gpsReady) R.string.trip_auto_armed else R.string.trip_auto_waiting_gps)
                statusDot.background = roundRect(if (state.gpsReady) C.GREEN else C.TEXT3, c.dp(5).toFloat())
                hintText.text = c.getString(R.string.trip_auto_explanation)
            } else {
                statusText.text = c.getString(if (state.gpsReady) R.string.trip_manual_ready else R.string.trip_auto_waiting_gps)
                statusDot.background = roundRect(if (state.gpsReady) C.GREEN else C.TEXT3, c.dp(5).toFloat())
                hintText.text = c.getString(R.string.trip_manual_explanation)
            }
            TripManager.Phase.RECORDING -> {
                statusText.text = c.getString(if (state.mode == TripManager.Mode.AUTOMATIC) R.string.trip_recording_automatic else R.string.trip_recording_manual)
                statusDot.background = roundRect(C.RED, c.dp(5).toFloat())
                hintText.text = if (state.moving) c.getString(R.string.trip_recording_gps_live) else c.getString(R.string.trip_recording_waiting_motion)
            }
            TripManager.Phase.READY_TO_SAVE -> {
                statusText.text = c.getString(R.string.trip_ready_to_save)
                statusDot.background = roundRect(C.ORANGE_GOLD, c.dp(5).toFloat())
                hintText.text = c.getString(R.string.trip_save_explanation)
            }
        }
        if (state.phase != lastPhase || state.mode != lastMode) {
            lastPhase = state.phase
            lastMode = state.mode
            buildActions(state)
        }
        if (state.phase == TripManager.Phase.RECORDING && visible) {
            if (!handler.hasCallbacks(timer)) handler.postDelayed(timer, 1_000L)
        } else handler.removeCallbacks(timer)
    }

    private fun paintMode(option: TextView, selected: Boolean, locked: Boolean) {
        option.setTextColor(if (selected) C.ON_PRIMARY else if (locked) C.TEXT3 else C.TEXT2)
        option.background = if (selected) primaryFill(c.dp(13).toFloat()) else null
        option.isEnabled = !locked
        option.alpha = if (locked && !selected) 0.65f else 1f
    }

    private fun changeMode(mode: TripManager.Mode) {
        if (manager.snapshot().phase != TripManager.Phase.IDLE) return
        if (mode == TripManager.Mode.AUTOMATIC && !act.app.gps.hasFine()) {
            act.requestLocationPermission(userInitiated = true)
        }
        manager.setMode(mode)
    }

    private fun buildActions(state: TripManager.UiState) {
        actions.removeAllViews()
        when (state.phase) {
            TripManager.Phase.IDLE -> if (state.mode == TripManager.Mode.MANUAL) {
                actions.addView(button(c.getString(R.string.trip_start), C.ON_PRIMARY, true) {
                    if (!act.app.gps.hasFine()) act.requestLocationPermission(userInitiated = true)
                    else if (!act.app.gps.isGpsEnabled()) act.openLocationSettings()
                    else manager.startManual()
                }, lp())
            } else {
                actions.addView(c.text(c.getString(R.string.trip_auto_mode_badge), 12f, C.GOLD_DEEP, Fonts.medium).apply {
                    gravity = Gravity.CENTER
                    background = roundRect(C.GOLD_PALE, c.dp(12).toFloat())
                    setPadding(c.dp(14), c.dp(9), c.dp(14), c.dp(9))
                }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            TripManager.Phase.RECORDING -> if (state.mode == TripManager.Mode.MANUAL) {
                actions.addView(button(c.getString(R.string.trip_stop), C.ON_PRIMARY, true) { manager.stopManual() }, lp())
            } else {
                actions.addView(c.text(c.getString(R.string.trip_auto_recording_badge), 12f, C.RED, Fonts.medium).apply {
                    gravity = Gravity.CENTER
                    background = roundRect(C.RED_LIGHT, c.dp(12).toFloat())
                    setPadding(c.dp(14), c.dp(9), c.dp(14), c.dp(9))
                }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            TripManager.Phase.READY_TO_SAVE -> {
                actions.addView(button(c.getString(R.string.trip_save), C.ON_PRIMARY, true) { showSaveDialog() }, lp(0, weight = 1f))
                actions.addView(button(c.getString(R.string.trip_discard), C.RED, false) {
                    manager.discardPending()
                    act.message.success(c.getString(R.string.trip_discarded))
                }, lp(0, weight = 1f).margins(s = c.dp(8)))
            }
        }
    }

    private fun button(label: String, color: Int, filled: Boolean, click: () -> Unit): TextView = c.text(label, 14f, if (filled) color else C.RED, Fonts.medium).apply {
        gravity = Gravity.CENTER
        val radius = c.dp(14).toFloat()
        background = if (filled) ripple(primaryFill(radius), radius, 0x55FFFFFF)
        else ripple(roundRect(C.RED_LIGHT, radius, C.RED, c.dp(1)), radius)
        setPadding(c.dp(16), c.dp(10), c.dp(16), c.dp(10))
        isFocusable = true
        setOnClickListener { click() }
    }

    private fun showSaveDialog() {
        val field = c.input(c.getString(R.string.trip_name_hint))
        field.setText(manager.suggestedName())
        field.setSelection(field.text.length)
        NovaDialog(c).title(c.getString(R.string.trip_save_title)).content(field)
            .button(c.getString(R.string.cancel), C.TEXT2) { it.dismiss() }
            .button(c.getString(R.string.save), C.GOLD_DEEP, filled = true) { dialog ->
                if (manager.savePending(field.text.toString())) {
                    dialog.dismiss()
                    act.message.success(c.getString(R.string.trip_saved_success))
                } else {
                    act.message.error(c.getString(if (manager.snapshot().pointCount < 2) R.string.trip_not_enough_points else R.string.err_save))
                }
            }.show()
    }

    private fun updateTrips() {
        val trips = manager.trips()
        adapter.replace(trips)
        emptyPanel.visibility = if (trips.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (trips.isEmpty()) View.INVISIBLE else View.VISIBLE
    }

    private fun tripCard(trip: Trip): View {
        val card = c.vbox().apply {
            background = c.card(18)
            setPadding(c.dp(12), c.dp(11), c.dp(12), c.dp(12))
            softElevation(1f)
        }
        val titleRow = c.hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(c.text(trip.name, 15.5f, C.TEXT, Fonts.medium).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, lp(0, weight = 1f))
        titleRow.addView(compactAction(c.getString(R.string.edit), C.GOLD_DEEP) { renameTrip(trip) }, lp(ViewGroup.LayoutParams.WRAP_CONTENT).margins(s = c.dp(6)))
        card.addView(titleRow, lp())
        val start = formatDateTime(trip.startedAtMs)
        val end = formatDateTime(trip.endedAtMs)
        val dateRange = c.getString(R.string.trip_time_range_fmt, start, end)
        card.addView(c.text(dateRange, 11.5f, C.TEXT2).apply { textDirection = View.TEXT_DIRECTION_LOCALE }, lp().margins(t = c.dp(2)))
        trip.destinationName?.let { name ->
            card.addView(c.text(c.getString(R.string.trip_to_name, name), 11.5f, C.GOLD_DEEP, Fonts.medium).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, lp().margins(t = c.dp(2)))
        }
        card.addView(TripRouteView(c, trip), lp(h = c.dp(118)).margins(t = c.dp(9)))
        val legend = c.hbox().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(c.dp(4), c.dp(5), c.dp(4), 0) }
        legend.addView(View(c).apply { background = roundRect(C.ORANGE_GOLD, c.dp(4).toFloat()) }, LinearLayout.LayoutParams(c.dp(8), c.dp(8)).margins(e = c.dp(5)))
        legend.addView(c.text(c.getString(R.string.trip_route_start), 10.5f, C.TEXT2))
        legend.addView(View(c), lp(0, h = 0, weight = 1f))
        legend.addView(c.text(c.getString(if (trip.destinationName != null) R.string.trip_route_destination else R.string.trip_route_end), 10.5f, C.TEXT2))
        legend.addView(View(c).apply { background = roundRect(C.GREEN, c.dp(4).toFloat()) }, LinearLayout.LayoutParams(c.dp(8), c.dp(8)).margins(s = c.dp(5)))
        card.addView(legend, lp())
        val stats = c.hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dp(2), c.dp(8), c.dp(2), c.dp(6))
        }
        stats.addView(c.text(act.units.distance(trip.distanceM), 12f, C.TEXT, Fonts.medium).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, lp(0, weight = 1f))
        stats.addView(c.text(formatDuration(trip.durationMs), 12f, C.TEXT2).apply { gravity = Gravity.CENTER }, lp(0, weight = 1f))
        stats.addView(c.text(act.units.speed(trip.averageSpeedMps), 12f, C.TEXT2).apply {
            gravity = if (c.isRtl()) Gravity.START else Gravity.END
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, lp(0, weight = 1f))
        card.addView(stats, lp())
        val moreStats = c.hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dp(2), 0, c.dp(2), c.dp(6))
        }
        val maxSpeed = trip.maxSpeedMps?.let(act.units::speed) ?: "—"
        moreStats.addView(c.text("${c.getString(R.string.trip_max_speed)}  $maxSpeed", 11.5f, C.TEXT2).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, lp(0, weight = 1f))
        moreStats.addView(c.text("${trip.points.size}  ${c.getString(R.string.trip_gps_points)}", 11.5f, C.TEXT2).apply {
            textDirection = View.TEXT_DIRECTION_LOCALE
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, lp(0, weight = 1f))
        card.addView(moreStats, lp())

        val actionsRow = c.hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        actionsRow.addView(compactAction(c.getString(R.string.trip_reroute), C.GOLD_DEEP) { reroute(trip) }, lp(0, weight = 1f))
        actionsRow.addView(compactAction(c.getString(R.string.delete), C.RED) { confirmDelete(trip) }, lp(0, weight = 1f).margins(s = c.dp(7)))
        card.addView(actionsRow, lp())
        return FrameLayout(c).apply {
            setPadding(0, c.dp(2), 0, c.dp(2))
            addView(card, FrameLayout.LayoutParams(-1, -2))
        }
    }

    private fun compactAction(label: String, color: Int, click: () -> Unit) = c.text(label, 12f, color, Fonts.medium).apply {
        gravity = Gravity.CENTER
        val radius = c.dp(11).toFloat()
        background = ripple(roundRect(C.CARD_SOFT, radius, (color and 0x00FFFFFF) or 0x50000000, c.dp(1)), radius)
        setPadding(c.dp(9), c.dp(7), c.dp(9), c.dp(7))
        isFocusable = true
        setOnClickListener { click() }
    }

    private fun renameTrip(trip: Trip) {
        val field = c.input(c.getString(R.string.trip_name_hint))
        field.setText(trip.name)
        field.setSelection(field.text.length)
        NovaDialog(c).title(c.getString(R.string.trip_rename_title)).content(field)
            .button(c.getString(R.string.cancel), C.TEXT2) { it.dismiss() }
            .button(c.getString(R.string.save), C.GOLD_DEEP, filled = true) { dialog ->
                if (manager.rename(trip.id, field.text.toString())) {
                    dialog.dismiss()
                    act.message.success(c.getString(R.string.trip_renamed_success))
                } else act.message.error(c.getString(R.string.err_save))
            }.show()
    }

    private fun confirmDelete(trip: Trip) {
        NovaDialog(c).title(c.getString(R.string.trip_delete_title))
            .message(c.getString(R.string.trip_delete_message, trip.name))
            .button(c.getString(R.string.cancel), C.TEXT2) { it.dismiss() }
            .button(c.getString(R.string.delete), C.RED, filled = true) { dialog ->
                dialog.dismiss()
                if (manager.delete(trip.id)) act.message.success(c.getString(R.string.trip_deleted_success))
                else act.message.error(c.getString(R.string.err_save))
            }.show()
    }

    private fun reroute(trip: Trip) {
        val destination = TripRouteResolver.resolve(trip, act.app.destinations.all())
        if (destination == null) {
            act.message.error(c.getString(R.string.trip_endpoint_unavailable))
            return
        }
        act.app.navigation.setDestination(destination)
        act.showPage(MainActivity.PAGE_NAVIGATION)
    }

    private fun formatDateTime(timeMs: Long): String =
        DateFormat.getDateFormat(c).format(Date(timeMs)) + " · " + DateFormat.getTimeFormat(c).format(Date(timeMs))

    private fun formatDuration(milliseconds: Long): String =
        android.text.format.DateUtils.formatElapsedTime((milliseconds.coerceAtLeast(0L) / 1000L))
}

/** Small, offline route sketch drawn from every point saved in the trip's GPS track. */
private class TripRouteView(context: MainActivity, private val trip: Trip) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val route = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val startDot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val endDot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val corner = context.dp(13).toFloat()
        fill.color = C.CARD_SOFT
        canvas.drawRoundRect(0f, 0f, w, h, corner, corner, fill)
        grid.color = C.BORDER
        grid.strokeWidth = context.dp(1).toFloat()
        val pad = context.dp(18).toFloat()
        for (i in 1..3) {
            val x = pad + (w - 2 * pad) * i / 4f
            val y = pad + (h - 2 * pad) * i / 4f
            canvas.drawLine(x, pad, x, h - pad, grid)
            canvas.drawLine(pad, y, w - pad, y, grid)
        }

        val points = trip.points
        if (points.isEmpty()) return
        val meanLat = points.map { it.latitude }.average()
        val cosLat = kotlin.math.cos(Math.toRadians(meanLat)).coerceAtLeast(0.01)
        val xs = points.map { it.longitude * cosLat }
        val ys = points.map { it.latitude }
        val minX = xs.minOrNull() ?: return
        val maxX = xs.maxOrNull() ?: return
        val minY = ys.minOrNull() ?: return
        val maxY = ys.maxOrNull() ?: return
        val box = RectF(context.dp(27).toFloat(), context.dp(24).toFloat(), w - context.dp(27), h - context.dp(24))
        val spanX = (maxX - minX).coerceAtLeast(0.0000001)
        val spanY = (maxY - minY).coerceAtLeast(0.0000001)
        val scale = minOf(box.width() / spanX.toFloat(), box.height() / spanY.toFloat())
        val drawW = spanX.toFloat() * scale
        val drawH = spanY.toFloat() * scale
        val left = box.centerX() - drawW / 2f
        val top = box.centerY() - drawH / 2f
        val coords = points.indices.map { index ->
            val x = left + ((xs[index] - minX).toFloat() * scale)
            val y = top + (drawH - ((ys[index] - minY).toFloat() * scale))
            x to y
        }

        if (coords.size > 1) {
            path.reset()
            path.moveTo(coords.first().first, coords.first().second)
            coords.drop(1).forEach { (x, y) -> path.lineTo(x, y) }
            route.color = C.RADAR_CHIP_TEXT
            route.strokeWidth = context.dp(2.5f).toFloat()
            route.pathEffect = DashPathEffect(floatArrayOf(context.dp(5f).toFloat(), context.dp(4f).toFloat()), 0f)
            canvas.drawPath(path, route)
            route.pathEffect = null
        }
        val radius = context.dp(4.5f).toFloat()
        startDot.color = C.ORANGE_GOLD
        endDot.color = C.GREEN
        coords.firstOrNull()?.let { canvas.drawCircle(it.first, it.second, radius, startDot) }
        coords.lastOrNull()?.let { canvas.drawCircle(it.first, it.second, radius, endDot) }
    }
}
