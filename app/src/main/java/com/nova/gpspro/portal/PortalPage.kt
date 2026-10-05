package com.nova.gpspro.portal

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.nova.gpspro.MainActivity
import com.nova.gpspro.R
import com.nova.gpspro.data.Destination
import com.nova.gpspro.data.ExternalLocationParser
import com.nova.gpspro.data.LocxCodec
import com.nova.gpspro.ui.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** «بوابة الموقع — NOVA PORTAL»: offline transfer of locations (.locx files and QR codes). */
class PortalPage(act: MainActivity) : Page(act) {
    private val c = act
    private val repo get() = act.app.destinations

    private val tiles = ArrayList<View>()
    override val view: View = run {
        val col = c.vbox().apply { setPadding(c.dp(20), c.dp(18), c.dp(20), c.dp(24)) }
        col.addView(c.text(c.getString(R.string.portal_title), 24f, C.TEXT, Fonts.medium))
        col.addView(c.text("NOVA PORTAL", 12f, C.GOLD, Fonts.medium).apply { letterSpacing = 0.32f }, lp().margins(t = c.dp(2)))
        col.addView(View(c).apply { background = gradientRect(C.GOLD, C.GOLD_LIGHT, c.dp(2).toFloat()) },
            LinearLayout.LayoutParams(c.dp(44), c.dp(3)).margins(t = c.dp(10), b = c.dp(22)))

        val row1 = c.hbox(); val row2 = c.hbox()
        row1.addView(tile("📤", R.string.portal_export, R.string.portal_export_desc) { export() }, lp(0, weight = 1f).margins(e = c.dp(7)))
        row1.addView(tile("📥", R.string.portal_import, R.string.portal_import_desc) { import() }, lp(0, weight = 1f).margins(s = c.dp(7)))
        row2.addView(tile("◈", R.string.portal_qr_create, R.string.portal_qr_create_desc) { createQr() }, lp(0, weight = 1f).margins(e = c.dp(7)))
        row2.addView(tile("◇", R.string.portal_qr_scan, R.string.portal_qr_scan_desc) { scanQr() }, lp(0, weight = 1f).margins(s = c.dp(7)))
        col.addView(row1, lp()); col.addView(row2, lp().margins(t = c.dp(14)))
        FitScroll(c, col).compressGroups(col).compressPadding(*tiles.toTypedArray())
            .shrinkable(c.dp(118), c.dp(168)) { h -> tiles.forEach { if (it.minimumHeight != h) it.minimumHeight = h } }
    }

    private fun tile(icon: String, title: Int, desc: Int, onClick: () -> Unit): View = c.vbox().also { tiles.add(it) }.apply {
        val r = c.dp(24).toFloat()
        background = ripple(c.card(24), r); softElevation(3f)
        setPadding(c.dp(16), c.dp(20), c.dp(16), c.dp(20))
        minimumHeight = c.dp(168)
        setOnClickListener { onClick() }
        addView(c.text(icon, 24f, C.GOLD_DEEP).apply {
            gravity = Gravity.CENTER
            background = roundRect(C.GOLD_PALE, c.dp(18).toFloat(), C.GOLD_LIGHT, c.dp(1))
        }, LinearLayout.LayoutParams(c.dp(54), c.dp(54)))
        addView(c.text(c.getString(title), 16f, C.TEXT, Fonts.medium), lp().margins(t = c.dp(16)))
        addView(c.text(c.getString(desc), 12.5f, C.TEXT2).apply { setLineSpacing(0f, 1.15f) }, lp().margins(t = c.dp(4)))
    }

    // ================================================================ selection
    /** Pick one/several locations, or «نسخة كاملة» (all). */
    private fun chooseLocations(title: Int, action: Int, onChosen: (List<Destination>, Boolean) -> Unit) {
        val all = repo.all()
        if (all.isEmpty()) { c.message.error(c.getString(R.string.portal_no_locations)); return }
        val box = c.vbox()
        val checks = ArrayList<Pair<CheckBox, Destination>>()
        val full = CheckBox(c).apply {
            text = c.getString(R.string.portal_full_backup_fmt, all.size); setTextColor(C.GOLD_DEEP); typeface = Fonts.medium; textSize = 15f
            buttonTintList = android.content.res.ColorStateList.valueOf(C.GOLD)
            background = roundRect(C.GOLD_PALE, c.dp(14).toFloat(), C.GOLD_LIGHT, c.dp(1))
            setPadding(c.dp(8), c.dp(12), c.dp(8), c.dp(12))
        }
        box.addView(full, lp().margins(b = c.dp(12)))
        for (d in all) {
            val cb = CheckBox(c).apply {
                text = d.name; setTextColor(C.TEXT); textSize = 15f; maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                buttonTintList = android.content.res.ColorStateList.valueOf(C.GOLD)
                setPadding(c.dp(6), c.dp(8), c.dp(6), c.dp(8))
                isChecked = all.size == 1
            }
            checks.add(cb to d); box.addView(cb, lp())
        }
        full.setOnCheckedChangeListener { _, on -> checks.forEach { it.first.isChecked = on; it.first.isEnabled = !on } }
        NovaDialog(c).title(c.getString(title)).content(box)
            .button(c.getString(R.string.cancel), C.TEXT2) { it.dismiss() }
            .button(c.getString(action), filled = true) { dlg ->
                val sel = if (full.isChecked) all else checks.filter { it.first.isChecked }.map { it.second }
                if (sel.isEmpty()) { c.message.error(c.getString(R.string.portal_select_one)); return@button }
                dlg.dismiss(); onChosen(sel, full.isChecked)
            }.show()
    }

    // ================================================================ 1. export
    private fun export() = chooseLocations(R.string.portal_export, R.string.portal_share) { list, full ->
        try {
            val dir = ExportProvider.dir(c); dir.listFiles()?.forEach { it.delete() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            val name = when {
                full -> "GPSarrow-backup-$stamp${LocxCodec.EXT}"
                list.size == 1 -> LocxCodec.safeBaseName(list[0].name) + LocxCodec.EXT
                else -> "GPSarrow-${list.size}-$stamp${LocxCodec.EXT}"
            }
            val f = File(dir, name)
            f.writeText(LocxCodec.encodeMany(list.map { it.copy(photoPath = null) }))   // photo paths are device-local
            val uri = ExportProvider.uriFor(f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_SUBJECT, name)
                clipData = ClipData.newRawUri(name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            c.startActivity(Intent.createChooser(send, c.getString(R.string.portal_export)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) { c.message.error(c.getString(R.string.err_save)) }
    }

    // ================================================================ 2. import
    private fun import() {
        val box = c.vbox()
        val dlg = NovaDialog(c).title(c.getString(R.string.portal_import))
        fun option(icon: String, title: Int, desc: Int, multiple: Boolean) = c.hbox().apply {
            val r = c.dp(16).toFloat()
            background = ripple(roundRect(C.CARD_SOFT, r, C.BORDER, c.dp(1)), r)
            setPadding(c.dp(14), c.dp(14), c.dp(14), c.dp(14))
            addView(c.text(icon, 20f, C.GOLD_DEEP).apply { gravity = Gravity.CENTER; background = roundRect(C.GOLD_PALE, c.dp(12).toFloat()) },
                LinearLayout.LayoutParams(c.dp(42), c.dp(42)))
            val t = c.vbox()
            t.addView(c.text(c.getString(title), 15f, C.TEXT, Fonts.medium))
            t.addView(c.text(c.getString(desc), 12.5f, C.TEXT2))
            addView(t, lp(0, weight = 1f).margins(s = c.dp(12)))
            setOnClickListener { dlg.dismiss(); pickFiles(multiple) }
        }
        box.addView(option("📄", R.string.portal_import_files, R.string.portal_import_files_desc, true), lp().margins(b = c.dp(10)))
        box.addView(option("🗂", R.string.portal_import_full, R.string.portal_import_full_desc, false), lp())
        dlg.content(box).button(c.getString(R.string.cancel), C.TEXT2) { it.dismiss() }.show()
    }

    private fun pickFiles(multiple: Boolean) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        }
        c.startForResult(i) { ok, data ->
            if (!ok || data == null) return@startForResult
            val uris = ArrayList<Uri>()
            data.clipData?.let { cd -> for (k in 0 until cd.itemCount) uris.add(cd.getItemAt(k).uri) }
            if (uris.isEmpty()) data.data?.let { uris.add(it) }
            val found = ArrayList<Destination>(); var bad = 0
            for (u in uris) {
                val text = try {
                    c.contentResolver.openInputStream(u)?.use { s -> s.readBytes().takeIf { it.size <= 4_000_000 }?.toString(Charsets.UTF_8) }
                } catch (_: Exception) { null }
                if (text == null) { bad++; continue }
                val recsNova = try { LocxCodec.decodeAll(text, "imp_${found.size}_", System.currentTimeMillis()) } catch (_: Exception) { emptyList() }
                if (recsNova.isNotEmpty()) { found.addAll(recsNova.map { it.copy(photoPath = null) }); continue }
                val recsExt = try { ExternalLocationParser.parse(text, "ext_${found.size}_", System.currentTimeMillis()) } catch (_: Exception) { emptyList() }
                if (recsExt.isNotEmpty()) found.addAll(recsExt.map { it.copy(photoPath = null) }) else bad++
            }
            if (found.isEmpty()) { c.message.error(c.getString(R.string.portal_invalid_file)); return@startForResult }
            review(found, fromQr = false)
        }
    }

    // ================================================================ 3. create QR
    private fun createQr() = chooseLocations(R.string.portal_qr_create, R.string.portal_generate) { list, _ ->
        val bmp = try {
            val m = QrCodec.encode(QrPayload.encode(list))
            val px = IntArray(m.width * m.height) { i -> if (m[i % m.width, i / m.width]) C.QR_INK else C.QR_BG }
            val small = Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
            Bitmap.createScaledBitmap(small, m.width * 12, m.height * 12, false)
        } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
        if (bmp == null) { c.message.error(c.getString(R.string.portal_qr_too_big)); return@chooseLocations }
        val box = c.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        box.addView(ImageView(c).apply {
            setImageBitmap(bmp); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
            background = roundRect(C.QR_BG, c.dp(18).toFloat(), C.BORDER, c.dp(1))
            setPadding(c.dp(10), c.dp(10), c.dp(10), c.dp(10))
        }, LinearLayout.LayoutParams(c.dp(280), c.dp(280)))
        val label = if (list.size == 1) list[0].name else c.getString(R.string.portal_n_locations, list.size)
        box.addView(c.text(label, 15f, C.TEXT, Fonts.medium).apply { gravity = Gravity.CENTER }, lp().margins(t = c.dp(14)))
        box.addView(c.text(c.getString(R.string.portal_qr_hint), 12.5f, C.TEXT2).apply { gravity = Gravity.CENTER }, lp().margins(t = c.dp(4)))
        NovaDialog(c).title(c.getString(R.string.portal_qr_create)).content(box)
            .button(c.getString(R.string.portal_done), filled = true) { it.dismiss() }.show()
    }

    // ================================================================ 4. scan QR
    private fun scanQr() {
        NovaDialog(c).title(c.getString(R.string.portal_qr_scan))
            .button(c.getString(R.string.portal_from_gallery), C.GOLD_DEEP) { it.dismiss(); scanFromGallery() }
            .button(c.getString(R.string.portal_camera), filled = true) { it.dismiss(); scanWithCamera() }
            .show()
    }

    private fun scanWithCamera() {
        if (c.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { QrScanner(c) { handleQr(it) }.show(); return }
        c.requestPerm(Manifest.permission.CAMERA) { granted ->
            if (granted) QrScanner(c) { handleQr(it) }.show() else c.message.error(c.getString(R.string.portal_camera_denied))
        }
    }

    private fun scanFromGallery() = c.pickPhoto { uri ->
        if (uri == null) return@pickPhoto
        val text = try {
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.contentResolver, uri)) { dec, info, _ ->
                val s = minOf(1f, 1600f / maxOf(info.size.width, info.size.height))
                dec.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val px = IntArray(bmp.width * bmp.height); bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            QrCodec.decodePixels(px, bmp.width, bmp.height).also { bmp.recycle() }
        } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
        if (text == null) c.message.error(c.getString(R.string.portal_no_qr)) else handleQr(text)
    }

    private fun handleQr(text: String) {
        val list = QrPayload.decode(text, System.currentTimeMillis(), c.getString(R.string.portal_qr_location))
        if (list.isEmpty()) c.message.error(c.getString(R.string.portal_not_location_qr)) else review(list, fromQr = true)
    }

    // ================================================================ review + save (dedupe)
    private fun review(incoming: List<Destination>, fromQr: Boolean) {
        val plan = ImportPlanner.plan(repo.all(), incoming)
        val box = c.vbox()
        if (plan.add.isEmpty()) {
            NovaDialog(c).title(c.getString(R.string.portal_nothing_new))
                .message(c.getString(R.string.portal_all_duplicates_fmt, plan.duplicates))
                .button(c.getString(R.string.portal_done), filled = true) { it.dismiss() }.show()
            return
        }
        fun info(label: Int, value: String) {
            box.addView(c.text(c.getString(label), 12f, C.TEXT2, Fonts.medium), lp().margins(t = c.dp(10), b = c.dp(4)))
            box.addView(c.text(value, 15f, C.TEXT).apply {
                background = roundRect(C.GOLD_PALE, c.dp(14).toFloat()); setPadding(c.dp(14), c.dp(11), c.dp(14), c.dp(11))
            }, lp())
        }
        val single = plan.add.size == 1
        val nameIn = if (single) c.input(c.getString(R.string.name)).apply { setText(plan.add[0].name) } else null
        if (single) {
            val d = plan.add[0]
            box.addView(c.text(c.getString(R.string.name), 12f, C.TEXT2, Fonts.medium), lp().margins(b = c.dp(6)))
            box.addView(nameIn, lp())
            info(R.string.latitude, String.format(Locale.US, "%.7f", d.latitude))
            info(R.string.longitude, String.format(Locale.US, "%.7f", d.longitude))
            d.notes?.let { info(R.string.notes_optional, it) }
        } else {
            box.addView(c.text(c.getString(R.string.portal_n_locations, plan.add.size), 15f, C.GOLD_DEEP, Fonts.medium), lp().margins(b = c.dp(8)))
            for (d in plan.add) box.addView(c.text("•  " + d.name + String.format(Locale.US, "   %.5f, %.5f", d.latitude, d.longitude), 14f, C.TEXT).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, lp().margins(b = c.dp(6)))
        }
        if (plan.duplicates > 0) box.addView(c.text(c.getString(R.string.portal_skipped_fmt, plan.duplicates), 13f, C.TEXT2), lp().margins(t = c.dp(12)))
        val err = c.text("", 13f, C.RED).apply { visibility = View.GONE }
        box.addView(err, lp().margins(t = c.dp(8)))

        NovaDialog(c).title(c.getString(if (fromQr) R.string.portal_qr_result else R.string.portal_import)).content(box)
            .button(c.getString(R.string.cancel), C.TEXT2) { it.dismiss() }
            .button(c.getString(R.string.save), filled = true) { dlg ->
                val toSave = if (single) {
                    val n = nameIn!!.text.toString().trim()
                    if (n.isEmpty()) { err.text = c.getString(R.string.err_name_required); err.visibility = View.VISIBLE; return@button }
                    listOf(plan.add[0].copy(name = n))
                } else plan.add
                val taken = repo.all().map { it.id }.toHashSet()
                var saved = 0
                for (d in toSave) {
                    val id = if (d.id.startsWith("qr_") || d.id.startsWith("imp_") || d.id.startsWith("f_") || d.id in taken) repo.newId() else d.id
                    if (repo.upsert(d.copy(id = id, photoPath = null))) { saved++; taken.add(id) }
                }
                dlg.dismiss()
                if (saved == toSave.size) c.message.success(c.getString(R.string.portal_saved_fmt, saved))
                else c.message.error(c.getString(R.string.err_save))
            }.show()
    }
}
