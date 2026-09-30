package com.nova.gpspro.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import org.json.JSONArray
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Destinations are stored as individual `.locx` files in the single public folder
 * Documents/GPSarrow/ (visible in the Files app). That folder is the source of truth:
 * the list is (re)read from it on start, and every add / edit / delete writes to it.
 */
class DestinationRepository(private val ctx: Context) {

    fun interface Listener { fun onChanged(list: List<Destination>) }

    private class Slot(val handle: String, val fileName: String)

    private val prefs = ctx.getSharedPreferences("nova_locx", Context.MODE_PRIVATE)   // only the folder grant Uri
    private val photoDir = File(ctx.filesDir, "photos").apply { mkdirs() }
    private val listeners = CopyOnWriteArraySet<Listener>()
    private var store: LocxStore = openStore()
    private var items: MutableList<Destination> = mutableListOf()
    private val slots = HashMap<String, Slot>()

    init { migrateLegacyJson(); reload() }

    fun all(): List<Destination> = items.sortedByDescending { it.createdAt }
    fun get(id: String): Destination? = items.firstOrNull { it.id == id }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    fun newId(): String = UUID.randomUUID().toString()

    /** True when full-folder access (SAF) is granted – sees files from earlier installs too. */
    val hasFolderAccess get() = store is SafLocxStore

    /**
     * After a reinstall Android no longer treats the old files as ours, so the MediaStore
     * view is empty although Documents/GPSarrow still contains .locx files.
     */
    fun needsFolderAccess(): Boolean = !hasFolderAccess && items.isEmpty() && runCatching {
        LocxFolder.publicDir().isDirectory
    }.getOrDefault(false)

    /** Called with the Uri returned by ACTION_OPEN_DOCUMENT_TREE. */
    fun grantFolder(tree: Uri): Boolean {
        try {
            ctx.contentResolver.takePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) { return false }
        val s = SafLocxStore(ctx, tree)
        if (!s.isValid) return false
        prefs.edit().putString("tree", tree.toString()).apply()
        store = s
        reload(); return true
    }

    /** Re-read the folder (e.g. user edited/removed files in the Files app). */
    fun reload() {
        val list = mutableListOf<Destination>(); slots.clear()
        for (e in store.list().sortedBy { it.fileName }) {
            val d = LocxCodec.decode(e.text, "f_" + e.fileName.hashCode().toUInt().toString(16), e.modified) ?: continue
            if (slots.containsKey(d.id)) continue                 // duplicated copy of the same location
            val fixed = if (d.photoPath != null && !File(d.photoPath).exists()) d.copy(photoPath = null) else d
            list.add(fixed); slots[d.id] = Slot(e.handle, e.fileName)
        }
        items = list
        notifyChanged()
    }

    fun upsert(d: Destination): Boolean {
        val old = get(d.id)
        val slot = slots[d.id]
        val text = LocxCodec.encode(d)
        val taken = slots.filterKeys { it != d.id }.values.map { it.fileName }.toSet()
        val newSlot: Slot = if (slot == null) {
            val name = LocxCodec.uniqueFileName(d.name, taken)
            Slot(store.create(name, text) ?: return false, name)
        } else {
            val rename = if (old?.name != d.name) LocxCodec.uniqueFileName(d.name, taken) else null
            val h = store.update(slot.handle, rename, text) ?: return false
            Slot(h, rename ?: slot.fileName)
        }
        slots[d.id] = newSlot
        val copy = items.toMutableList()
        val idx = copy.indexOfFirst { it.id == d.id }
        if (idx >= 0) copy[idx] = d else copy.add(d)
        items = copy
        if (old?.photoPath != null && old.photoPath != d.photoPath) deletePhoto(old.photoPath)
        notifyChanged(); return true
    }

    fun delete(id: String): Boolean {
        val d = get(id) ?: return false
        val slot = slots[id]
        if (slot != null && !store.delete(slot.handle)) return false
        slots.remove(id)
        items = items.filter { it.id != id }.toMutableList()
        d.photoPath?.let { deletePhoto(it) }
        notifyChanged(); return true
    }

    /** User-initiated "clear all data": removes the app's .locx files. */
    fun clearAll() {
        slots.values.forEach { store.delete(it.handle) }
        slots.clear()
        items = mutableListOf()
        photoDir.listFiles()?.forEach { it.delete() }
        notifyChanged()
    }

    private fun openStore(): LocxStore {
        prefs.getString("tree", null)?.let { t ->
            val uri = Uri.parse(t)
            val stillGranted = ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
            if (stillGranted) { val s = SafLocxStore(ctx, uri); if (s.isValid) return s }
        }
        return MediaStoreLocxStore(ctx)
    }

    /** One-time move of the old private destinations.json into .locx files. */
    private fun migrateLegacyJson() {
        val f = File(ctx.filesDir, "destinations.json")
        if (!f.exists()) return
        try {
            val arr = JSONArray(f.readText())
            val names = store.list().map { it.fileName }.toMutableSet()
            var ok = true
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val d = Destination(
                    id = o.getString("id"), name = o.getString("name"),
                    latitude = o.getDouble("lat"), longitude = o.getDouble("lon"),
                    photoPath = o.optString("photo").takeIf { it.isNotEmpty() },
                    notes = o.optString("notes").takeIf { it.isNotEmpty() },
                    createdAt = o.getLong("createdAt"))
                val name = LocxCodec.uniqueFileName(d.name, names)
                if (store.create(name, LocxCodec.encode(d)) != null) names.add(name) else ok = false
            }
            if (ok) f.delete()
        } catch (_: Exception) { f.delete() }
    }

    /** Copy + downscale a picked image into private storage. Returns absolute path. */
    fun importPhoto(uri: Uri): String? = try {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            val w = info.size.width; val h = info.size.height
            val scale = minOf(1f, 1280f / maxOf(w, h))
            dec.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val out = File(photoDir, "p_${UUID.randomUUID()}.jpg")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bmp.recycle()
        out.absolutePath
    } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }

    fun deletePhoto(path: String) {
        val f = File(path)
        if (f.parentFile?.absolutePath == photoDir.absolutePath) f.delete()
    }

    fun decodeThumb(path: String, sizePx: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= sizePx && o.outHeight / (sample * 2) >= sizePx) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Exception) { null }

    private fun notifyChanged() { val l = all(); listeners.forEach { it.onChanged(l) } }
}
