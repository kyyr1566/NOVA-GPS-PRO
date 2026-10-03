package com.nova.gpspro.ui

import android.content.Intent
import android.location.LocationManager
import android.os.SystemClock
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
import com.nova.gpspro.data.Destination
import com.nova.gpspro.data.DestinationRepository
import com.nova.gpspro.location.GpsState
import com.nova.gpspro.location.LocationEngine
import com.nova.gpspro.navigation.GeoMath

class DestinationsPage(act: MainActivity) : Page(act) {
    companion object { private var restoreOffered = false }
    private val c = act
    private var all: List<Destination> = emptyList()
    private var shown: List<Destination> = emptyList()
    private var query = ""
    private var gps: GpsState? = null
    private var lastDistRefresh = 0L
    private val refPos = CardPositionFilter()

    private val list = ListView(c).apply {
        divider = null; dividerHeight = 0; selector = android.graphics.drawable.ColorDrawable(0)
        clipToPadding = false; setPadding(c.dp(20), c.dp(4), c.dp(20), c.dp(20))
        isVerticalScrollBarEnabled = false
    }
    private val empty = c.vbox().apply {
        gravity = Gravity.CENTER; setPadding(c.dp(32), 0, c.dp(32), c.dp(40))
    }
    private val emptyTitle = c.text("", 17f, C.TEXT, Fonts.medium).apply { gravity = Gravity.CENTER }
    private val emptyHint = c.text("", 14f, C.TEXT2).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int) = shown[p]
        override fun getItemId(p: Int) = shown[p].id.hashCode().toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View = bindCard(shown[p])
    }

    override val view: View = c.vbox().apply {
        val header = c.hbox().apply { setPadding(c.dp(20), c.dp(18), c.dp(20), c.dp(6)) }
        header.addView(c.text(c.getString(R.string.tab_destinations), 24f, C.TEXT, Fonts.medium), lp(0, weight = 1f))
        val plus = c.text("+", 26f, android.graphics.Color.WHITE, Fonts.light).apply {
            gravity = Gravity.CENTER
            val g = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(C.PRIMARY_A, C.PRIMARY_B)).apply { shape = android.graphics.drawable.GradientDrawable.OVAL }
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x55FFFFFF), g, null)
            softElevation(4f)
            contentDescription = c.getString(R.string.add_destination)
            setOnClickListener { DestinationEditor(act, null) { _, _ -> act.message.success(c.getString(R.string.msg_saved)) }.show() }
        }
        header.addView(plus, LinearLayout.LayoutParams(c.dp(48), c.dp(48)))
        addView(header, lp())

        val search = c.input("🔎  " + c.getString(R.string.search_hint)).apply {
            background = roundRect(C.CARD, c.dp(16).toFloat(), C.BORDER, c.dp(1))
            addTextChangedListener(SimpleWatcher { query = it; applyFilter() })
        }
        addView(search, lp().margins(c.dp(20), c.dp(8), c.dp(20), c.dp(10)))

        val frame = FrameLayout(c)
        frame.addView(list, FrameLayout.LayoutParams(-1, -1))
        empty.addView(emptyTitle, lp()); empty.addView(emptyHint, lp().margins(t = c.dp(6)))
        frame.addView(empty, FrameLayout.LayoutParams(-1, -1))
        addView(frame, lp(h = 0, weight = 1f))
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            act.app.navigation.setDestination(shown[pos]); act.showPage(2)
        }
        list.setOnItemLongClickListener { _, v, pos, _ -> v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); actions(shown[pos]); true }
    }

    private fun bindCard(d: Destination): View {
        val row = c.hbox().apply {
            background = c.card(18); softElevation(1.5f)
            setPadding(c.dp(12), c.dp(10), c.dp(14), c.dp(10))
        }
        row.addView(Thumb.make(c, d, 46), LinearLayout.LayoutParams(c.dp(46), c.dp(46)))
        row.addView(c.text(d.name, 16f, C.TEXT, Fonts.medium).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
            lp(0, weight = 1f).margins(s = c.dp(14)))
        val g = gps
        if (g != null && g.isUsable && g.location != null && !g.location.isFromMockProvider && refPos.hasFix) {
            val dist = GeoMath.between(refPos.lat, refPos.lon, d.latitude, d.longitude).distanceM
            row.addView(c.text(distText(dist), 13f, C.GOLD_DEEP, Fonts.medium).apply { textDirection = View.TEXT_DIRECTION_LTR },
                lp(ViewGroup.LayoutParams.WRAP_CONTENT).margins(s = c.dp(8)))
        }
        val wrap = FrameLayout(c).apply { setPadding(0, c.dp(5), 0, c.dp(5)) }
        wrap.addView(row, FrameLayout.LayoutParams(-1, -2))
        return wrap
    }

    /** Exact meters → precise text in the user's KM/Mile unit (no integer rounding). */
    private fun distText(m: Double): String {
        val p = DestDistance.format(m, act.app.settings.distanceUnit) ?: return "—"
        val u = c.getString(when (p.unit) { DestDistance.U.M -> R.string.u_m; DestDistance.U.KM -> R.string.u_km; DestDistance.U.FT -> R.string.u_ft; DestDistance.U.MI -> R.string.u_mi })
        return "${p.number} $u"
    }

    private fun applyFilter() {
        shown = if (query.isBlank()) all else all.filter { it.name.contains(query.trim(), true) || (it.notes?.contains(query.trim(), true) == true) }
        adapter.notifyDataSetChanged()
        val isEmpty = shown.isEmpty()
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        emptyTitle.text = c.getString(if (all.isEmpty()) R.string.no_destinations else R.string.no_results)
        emptyHint.text = if (all.isEmpty()) c.getString(R.string.no_destinations_hint) else ""
    }

    private fun actions(d: Destination) {
        val box = c.vbox()
        val dlg = NovaDialog(c).title(d.name)
        fun item(icon: String, label: Int, color: Int, f: () -> Unit) {
            val t = c.text("$icon   ${c.getString(label)}", 16f, color, Fonts.medium).apply {
                val r = c.dp(14).toFloat()
                background = ripple(roundRect(C.CARD_SOFT, r, C.BORDER, c.dp(1)), r)
                setPadding(c.dp(16), c.dp(14), c.dp(16), c.dp(14))
                setOnClickListener { dlg.dismiss(); f() }
            }
            box.addView(t, lp().margins(b = c.dp(8)))
        }
        item("✎", R.string.edit, C.TEXT) { DestinationEditor(act, d) { _, _ -> act.message.success(c.getString(R.string.msg_updated)) }.show() }
        item("⤴", R.string.share, C.GOLD_DEEP) { share(d) }
        item("🗑", R.string.delete, C.RED) { confirmDelete(d) }
        dlg.content(box).button(c.getString(R.string.cancel), C.TEXT2) { it.dismiss() }.show()
    }

    private fun confirmDelete(d: Destination) {
        NovaDialog(c).title(c.getString(R.string.delete_confirm_title))
            .message(c.getString(R.string.delete_confirm_msg, d.name))
            .button(c.getString(R.string.cancel), C.TEXT2) { it.dismiss() }
            .button(c.getString(R.string.delete), C.RED, filled = true) {
                it.dismiss()
                if (act.app.destinations.delete(d.id)) act.message.error(c.getString(R.string.msg_deleted))
                else act.message.error(c.getString(R.string.err_save))
            }.show()
    }

    private fun share(d: Destination) {
        val lat = act.units.coord(d.latitude); val lon = act.units.coord(d.longitude)
        val notes = d.notes?.let { c.getString(R.string.notes_label, it) } ?: ""
        val body = c.getString(R.string.share_fmt, d.name, lat, lon, notes).replace("\n\n", "\n")
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, d.name); putExtra(Intent.EXTRA_TEXT, body) }
        try { c.startActivity(Intent.createChooser(send, c.getString(R.string.share_title))) } catch (_: Exception) {}
    }

    private val repoListener = DestinationRepository.Listener { all = it; applyFilter() }
    private val gpsListener = LocationEngine.Listener { s ->
        val had = gps?.isUsable == true
        gps = s
        val now = SystemClock.elapsedRealtime()
        val location = s.location
        if (location != null && s.isUsable && !location.isFromMockProvider) {
            val lat = if (s.provider == LocationManager.GPS_PROVIDER && s.motion.hasPosition) s.motion.latitude else s.latitude
            val lon = if (s.provider == LocationManager.GPS_PROVIDER && s.motion.hasPosition) s.motion.longitude else s.longitude
            refPos.update(lat, lon, s.accuracy, usable = true, nowMs = now)
        } else if (location == null || !s.isUsable || location.isFromMockProvider) refPos.reset()
        if (had != s.isUsable || now - lastDistRefresh > 1000) { lastDistRefresh = now; adapter.notifyDataSetChanged() }
    }

    override fun onShow() {
        all = act.app.destinations.all(); applyFilter()
        act.app.destinations.addListener(repoListener)
        act.app.gps.addListener(gpsListener)
        maybeOfferRestore()
    }

    /** After a reinstall the old .locx files exist but need the user's folder grant to be read. */
    private fun maybeOfferRestore() {
        if (restoreOffered || !act.app.destinations.needsFolderAccess()) return
        restoreOffered = true
        NovaDialog(c).title(c.getString(R.string.folder_restore_title))
            .message(c.getString(R.string.folder_restore_msg))
            .button(c.getString(R.string.cancel), C.TEXT2) { it.dismiss() }
            .button(c.getString(R.string.folder_restore_allow), filled = true) { it.dismiss(); act.requestFolderAccess() }
            .show()
    }
    override fun onHide() {
        act.app.destinations.removeListener(repoListener)
        act.app.gps.removeListener(gpsListener)
    }
}
