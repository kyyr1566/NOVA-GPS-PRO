package com.nova.gpspro.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.nova.gpspro.MainActivity
import com.nova.gpspro.R
import com.nova.gpspro.settings.DistanceUnit
import com.nova.gpspro.settings.SpeedUnit

/**
 * Settings. Compact spacing + the adaptive [FitScroll] keep the whole page visible without
 * scrolling on normal phones. The only added option is «المظهر» (☀️ light / 🌙 dark), which is
 * saved immediately and restored after a restart.
 */
class SettingsPage(act: MainActivity) : Page(act) {
    private val c = act
    private val st = act.app.settings
    private val sections = ArrayList<ViewGroup>()

    override val view: View = run {
        val col = c.vbox().apply { setPadding(c.dp(16), c.dp(12), c.dp(16), c.dp(14)) }
        col.addView(c.text(c.getString(R.string.tab_settings), 20f, C.TEXT, Fonts.medium), lp().margins(b = c.dp(8)))

        col.addView(section(R.string.language, segmented(listOf(c.getString(R.string.lang_ar), c.getString(R.string.lang_en)),
            if (st.language == "ar") 0 else 1) { i ->
            val lang = if (i == 0) "ar" else "en"
            if (lang != st.language) { st.language = lang; act.applyLanguage() }
        }))

        // ☀️/🌙 appearance — the only new setting
        col.addView(section(R.string.appearance, segmented(listOf(c.getString(R.string.theme_light), c.getString(R.string.theme_dark)),
            if (st.darkTheme) 1 else 0) { i ->
            val wantDark = i == 1
            if (wantDark != st.darkTheme) { st.darkTheme = wantDark; act.applyTheme() }
        }))

        col.addView(section(R.string.distance_unit, segmented(listOf(c.getString(R.string.unit_km_label), c.getString(R.string.unit_mile_label)),
            if (st.distanceUnit == DistanceUnit.KM) 0 else 1) { i ->
            st.distanceUnit = if (i == 0) DistanceUnit.KM else DistanceUnit.MILE
        }))
        col.addView(section(R.string.speed_unit, segmented(listOf(c.getString(R.string.unit_kmh_label), c.getString(R.string.unit_mph_label)),
            if (st.speedUnit == SpeedUnit.KMH) 0 else 1) { i ->
            st.speedUnit = if (i == 0) SpeedUnit.KMH else SpeedUnit.MPH
        }))

        col.addView(section(R.string.speedo_type, segmented(listOf(c.getString(R.string.speedo_circular), c.getString(R.string.speedo_digital)),
            if (st.digitalSpeedometer) 1 else 0) { i -> st.digitalSpeedometer = i == 1 }))

        // clear data
        val clear = c.vbox().apply { background = c.card(20); setPadding(c.dp(14), c.dp(10), c.dp(14), c.dp(12)) }
        clear.addView(c.text(c.getString(R.string.clear_data), 15f, C.TEXT, Fonts.medium))
        clear.addView(c.text(c.getString(R.string.clear_data_desc), 12.5f, C.TEXT2).apply { setLineSpacing(0f, 1.15f) }, lp().margins(t = c.dp(3)))
        clear.addView(c.outlineButton(c.getString(R.string.clear_data), C.RED) { confirmClear() }.apply {
            setPadding(c.dp(16), c.dp(9), c.dp(16), c.dp(9)); setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        }, lp(ViewGroup.LayoutParams.WRAP_CONTENT).margins(t = c.dp(8)))
        col.addView(clear, lp().margins(t = c.dp(4)))

        val ver = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
        col.addView(c.text(c.getString(R.string.app_name), 12f, C.GOLD, Fonts.medium).apply { gravity = Gravity.CENTER; letterSpacing = 0.2f },
            lp().margins(t = c.dp(12)))
        col.addView(c.text(c.getString(R.string.version_fmt, ver), 11f, C.TEXT3).apply { gravity = Gravity.CENTER }, lp().margins(t = c.dp(2)))
        FitScroll(c, col).compressGroups(col, clear, *sections.toTypedArray())
    }

    private fun section(title: Int, content: View) = c.vbox().also { sections.add(it) }.apply {
        background = c.card(20)
        setPadding(c.dp(14), c.dp(9), c.dp(14), c.dp(9))
        addView(c.text(c.getString(title), 12f, C.GOLD_DEEP, Fonts.medium).apply { letterSpacing = 0.06f })
        addView(content, lp().margins(t = c.dp(6)))
        layoutParams = lp().margins(b = c.dp(7))
    }

    private fun segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
        val box = c.hbox().apply {
            background = roundRect(C.GOLD_PALE, c.dp(14).toFloat())
            setPadding(c.dp(3), c.dp(3), c.dp(3), c.dp(3))
        }
        val views = options.mapIndexed { i, label ->
            c.text(label, 14f, C.TEXT2, Fonts.medium).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, c.dp(7), 0, c.dp(7))
                box.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        fun paint(sel: Int) = views.forEachIndexed { i, v ->
            if (i == sel) {
                v.background = primaryFill(c.dp(11).toFloat()); v.setTextColor(C.ON_PRIMARY); v.elevation = c.dp(2).toFloat()
            } else { v.background = null; v.setTextColor(C.TEXT2); v.elevation = 0f }
        }
        paint(selected)
        views.forEachIndexed { i, v -> v.setOnClickListener { paint(i); onSelect(i) } }
        return box
    }

    private fun confirmClear() {
        NovaDialog(c).title(c.getString(R.string.clear_confirm_title))
            .message(c.getString(R.string.clear_confirm_msg))
            .button(c.getString(R.string.cancel), C.TEXT2) { it.dismiss() }
            .button(c.getString(R.string.clear), C.RED, filled = true) {
                it.dismiss()
                act.app.clearAllData()
                act.clearThumbCache()
                act.message.success(c.getString(R.string.msg_cleared))
            }.show()
    }
}
