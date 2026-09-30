package com.nova.gpspro.ui

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.nova.gpspro.MainActivity
import com.nova.gpspro.R
import com.nova.gpspro.data.Destination
import com.nova.gpspro.location.GpsState
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Add / Edit / Save-current-location editor.
 * When coordinates come from GPS, the raw double values are stored (not the display text).
 */
class DestinationEditor(
    private val act: MainActivity,
    private val existing: Destination?,
    private val fromGps: GpsState? = null,
    private val onDone: (Destination, Boolean) -> Unit
) {
    private val repo = act.app.destinations
    private var photoPath: String? = existing?.photoPath
    private var importedPhoto: String? = null
    private var saved = false
    private val createdAt = existing?.createdAt ?: System.currentTimeMillis()

    private var rawLat = existing?.latitude ?: fromGps?.latitude
    private var rawLon = existing?.longitude ?: fromGps?.longitude
    private var rawAcc = fromGps?.accuracy ?: 0f
    private fun fmt(v: Double?) = v?.let { String.format(Locale.US, "%.7f", it) } ?: ""

    fun show() {
        val c = act
        val box = c.vbox()
        val nameIn = c.input(c.getString(R.string.name)).apply { setText(existing?.name ?: "") }
        val latIn = c.input(c.getString(R.string.latitude), numeric = true).apply { setText(fmt(rawLat)) }
        val lonIn = c.input(c.getString(R.string.longitude), numeric = true).apply { setText(fmt(rawLon)) }
        val notesIn = c.input(c.getString(R.string.notes_optional), multiline = true).apply { setText(existing?.notes ?: "") }
        val err = c.text("", 13f, C.RED).apply { visibility = View.GONE }

        fun label(t: String) = c.text(t, 12f, C.TEXT2, Fonts.medium).apply { letterSpacing = 0.04f }
        box.addView(label(c.getString(R.string.name)), lp().margins(b = c.dp(6)))
        box.addView(nameIn, lp())
        box.addView(label(c.getString(R.string.latitude)), lp().margins(t = c.dp(12), b = c.dp(6)))
        box.addView(latIn, lp())
        box.addView(label(c.getString(R.string.longitude)), lp().margins(t = c.dp(12), b = c.dp(6)))
        box.addView(lonIn, lp())

        if (fromGps != null || existing != null) {
            val dt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, c.resources.configuration.locales[0]).format(Date(createdAt))
            box.addView(label(c.getString(R.string.date_time)), lp().margins(t = c.dp(12), b = c.dp(6)))
            box.addView(c.text(dt, 15f, C.TEXT).apply {
                background = roundRect(C.GOLD_PALE, c.dp(14).toFloat())
                setPadding(c.dp(14), c.dp(12), c.dp(14), c.dp(12))
            }, lp())
        }

        // photo row
        box.addView(label(c.getString(R.string.photo_optional)), lp().margins(t = c.dp(12), b = c.dp(6)))
        val photoRow = c.hbox()
        val thumb = ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = roundRect(C.GOLD_PALE, c.dp(14).toFloat(), C.BORDER, c.dp(1))
        }
        val addBtn = c.outlineButton(c.getString(R.string.add_photo)) {}
        val removeBtn = c.outlineButton(c.getString(R.string.remove_photo), C.RED) {}
        photoRow.addView(thumb, LinearLayout.LayoutParams(c.dp(64), c.dp(64)))
        photoRow.addView(addBtn, lp(ViewGroup.LayoutParams.WRAP_CONTENT).margins(s = c.dp(12)))
        photoRow.addView(removeBtn, lp(ViewGroup.LayoutParams.WRAP_CONTENT).margins(s = c.dp(8)))
        box.addView(photoRow, lp())

        fun refreshPhoto() {
            val p = photoPath
            val bmp = p?.let { repo.decodeThumb(it, c.dp(64)) }
            if (bmp != null) { thumb.setImageBitmap(bmp); thumb.visibility = View.VISIBLE }
            else { thumb.setImageDrawable(null); thumb.visibility = View.GONE }
            addBtn.text = c.getString(if (bmp != null) R.string.change_photo else R.string.add_photo)
            removeBtn.visibility = if (bmp != null) View.VISIBLE else View.GONE
        }
        refreshPhoto()
        addBtn.setOnClickListener {
            act.pickPhoto { uri ->
                if (uri == null) return@pickPhoto
                val path = repo.importPhoto(uri)
                if (path == null) { act.message.error(c.getString(R.string.err_photo)); return@pickPhoto }
                importedPhoto?.let { if (it != path) repo.deletePhoto(it) }
                importedPhoto = path; photoPath = path
                refreshPhoto()
            }
        }
        removeBtn.setOnClickListener {
            importedPhoto?.let { repo.deletePhoto(it) }; importedPhoto = null
            photoPath = null; refreshPhoto()
        }

        box.addView(label(c.getString(R.string.notes_optional)), lp().margins(t = c.dp(12), b = c.dp(6)))
        box.addView(notesIn, lp())
        box.addView(err, lp().margins(t = c.dp(10)))

        val title = when {
            existing != null -> R.string.edit_destination
            fromGps != null -> R.string.save_current_location
            else -> R.string.add_destination
        }
        val dlg = NovaDialog(c).title(c.getString(title)).content(box)
        dlg.button(c.getString(R.string.cancel), C.TEXT2) { it.dismiss() }
        dlg.button(c.getString(R.string.save), filled = true) { d ->
            val name = nameIn.text.toString().trim()
            val latTxt = latIn.text.toString(); val lonTxt = lonIn.text.toString()
            // keep raw GPS precision when user did not edit the coordinate text
            val lat = if (rawLat != null && latTxt.trim() == fmt(rawLat)) rawLat else Destination.parseCoordinate(latTxt)
            val lon = if (rawLon != null && lonTxt.trim() == fmt(rawLon)) rawLon else Destination.parseCoordinate(lonTxt)
            val problem = when {
                name.isEmpty() -> R.string.err_name_required
                !Destination.validLat(lat) -> R.string.err_lat
                !Destination.validLon(lon) -> R.string.err_lon
                else -> 0
            }
            if (problem != 0) {
                err.text = c.getString(problem); err.visibility = View.VISIBLE
                err.animate().translationX(8f).setDuration(60).withEndAction { err.animate().translationX(0f).setDuration(60).start() }.start()
                return@button
            }
            val dest = Destination(
                id = existing?.id ?: repo.newId(), name = name,
                latitude = lat!!, longitude = lon!!,
                photoPath = photoPath, notes = notesIn.text.toString().trim().ifEmpty { null },
                createdAt = createdAt
            )
            if (repo.upsert(dest)) {
                saved = true
                d.dismiss()
                onDone(dest, existing == null)
            } else {
                err.text = c.getString(R.string.err_save); err.visibility = View.VISIBLE
            }
        }
        // "Save current location": while the dialog is open, refine to a more accurate real fix of the
        // same spot (never a moved position, never when the user typed coordinates himself).
        val refine = if (fromGps != null && existing == null) com.nova.gpspro.location.LocationEngine.Listener { g ->
            val la = rawLat; val lo = rawLon
            if (g.location == null || !g.isUsable || la == null || lo == null) return@Listener
            if (latIn.text.toString().trim() != fmt(la) || lonIn.text.toString().trim() != fmt(lo)) return@Listener
            if (g.accuracy >= rawAcc) return@Listener
            val moved = com.nova.gpspro.navigation.GeoMath.between(fromGps.latitude, fromGps.longitude, g.latitude, g.longitude).distanceM
            if (moved > maxOf(fromGps.accuracy.toDouble(), 5.0)) return@Listener
            rawLat = g.latitude; rawLon = g.longitude; rawAcc = g.accuracy
            latIn.setText(fmt(g.latitude)); lonIn.setText(fmt(g.longitude))
        } else null
        refine?.let { act.app.gps.addListener(it) }
        dlg.onDismiss {
            refine?.let { act.app.gps.removeListener(it) }
            // discard an imported photo that was never saved
            if (!saved) importedPhoto?.let { if (it != existing?.photoPath) repo.deletePhoto(it) }
        }
        dlg.show()
    }
}

/** Searchable destination list dialog used by Navigation. */
class DestinationPicker(private val act: MainActivity, private val onPick: (Destination) -> Unit) {
    fun show() {
        val c = act
        val all = act.app.destinations.all()
        val box = c.vbox()
        val search = c.input("🔎  " + c.getString(R.string.search_hint))
        val list = c.vbox()
        box.addView(search, lp().margins(b = c.dp(10)))
        box.addView(list, lp())
        val dlg = NovaDialog(c).title(c.getString(R.string.select_destination)).content(box)
        dlg.button(c.getString(R.string.cancel), C.TEXT2) { it.dismiss() }

        fun render(q: String) {
            list.removeAllViews()
            val items = all.filter { q.isBlank() || it.name.contains(q.trim(), ignoreCase = true) }
            if (items.isEmpty()) {
                list.addView(c.text(c.getString(if (all.isEmpty()) R.string.no_destinations else R.string.no_results), 14f, C.TEXT2).apply {
                    gravity = Gravity.CENTER; setPadding(0, c.dp(24), 0, c.dp(24))
                }, lp())
                return
            }
            for (d in items) {
                val row = c.hbox().apply {
                    val r = c.dp(14).toFloat()
                    background = ripple(roundRect(C.CARD_SOFT, r, C.BORDER, c.dp(1)), r)
                    setPadding(c.dp(12), c.dp(10), c.dp(12), c.dp(10))
                    setOnClickListener { dlg.dismiss(); onPick(d) }
                }
                row.addView(Thumb.make(c, d, 40), LinearLayout.LayoutParams(c.dp(40), c.dp(40)))
                row.addView(c.text(d.name, 16f, C.TEXT, Fonts.medium).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                    lp(0, weight = 1f).margins(s = c.dp(12)))
                list.addView(row, lp().margins(b = c.dp(8)))
            }
        }
        render("")
        search.addTextChangedListener(SimpleWatcher { render(it) })
        dlg.show()
    }
}

object Thumb {
    fun make(c: MainActivity, d: Destination, sizeDp: Int): View {
        val bmp = d.photoPath?.let { c.thumbCache(it, c.dp(sizeDp)) }
        if (bmp != null) return ImageView(c).apply {
            setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundRect(C.GOLD_PALE, c.dp(12).toFloat()); clipToOutline = true
        }
        return c.text(d.name.trim().take(1).uppercase(), (sizeDp * 0.42f), C.GOLD_DEEP, Fonts.medium).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C.GOLD_PALE); setStroke(c.dp(1), C.GOLD_LIGHT) }
        }
    }
}

class SimpleWatcher(private val f: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    override fun afterTextChanged(s: android.text.Editable?) { f(s?.toString() ?: "") }
}
