package com.nova.gpspro.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

/** One stored .locx file. [handle] is a content Uri string. */
class LocxEntry(val handle: String, val fileName: String, val text: String, val modified: Long)

/** Storage backend for the single public folder Documents/GPSarrow/. */
interface LocxStore {
    fun list(): List<LocxEntry>
    /** Creates a new file; returns its handle or null. */
    fun create(fileName: String, text: String): String?
    /** Overwrites content (and renames if [newFileName] != null). Returns the (possibly new) handle or null. */
    fun update(handle: String, newFileName: String?, text: String): String?
    fun delete(handle: String): Boolean
}

private const val MIME = "application/octet-stream"

/**
 * Scoped-storage MediaStore backend (Android 10+, no permission needed).
 * Files are created in the shared Documents/GPSarrow/ folder and are visible in the Files app.
 * The app can see the files it created itself through this API.
 */
class MediaStoreLocxStore(ctx: Context) : LocxStore {
    private val cr = ctx.contentResolver
    private val collection: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val relPath = Environment.DIRECTORY_DOCUMENTS + "/" + LocxCodec.FOLDER + "/"

    override fun list(): List<LocxEntry> {
        val out = ArrayList<LocxEntry>()
        val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_MODIFIED)
        val sel = "(${MediaStore.MediaColumns.RELATIVE_PATH}=? OR ${MediaStore.MediaColumns.RELATIVE_PATH}=?) AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf(relPath, relPath.trimEnd('/'), "%" + LocxCodec.EXT)
        try {
            cr.query(collection, proj, sel, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, c.getLong(0))
                    val text = runCatching { cr.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull() ?: continue
                    out.add(LocxEntry(uri.toString(), c.getString(1) ?: "", text, c.getLong(2) * 1000))
                }
            }
        } catch (_: Exception) {}
        return out
    }

    override fun create(fileName: String, text: String): String? {
        var uri: Uri? = null
        return try {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, MIME)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)   // folder auto-created
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            uri = cr.insert(collection, v) ?: return null
            cr.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)); it.flush() }
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri.toString()
        } catch (_: Exception) {
            uri?.let { runCatching { cr.delete(it, null, null) } }
            null
        }
    }

    override fun update(handle: String, newFileName: String?, text: String): String? = try {
        val uri = Uri.parse(handle)
        cr.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)); it.flush() }
        if (newFileName != null) runCatching {
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newFileName) }, null, null)
        }
        handle
    } catch (_: Exception) { null }

    override fun delete(handle: String): Boolean = try {
        cr.delete(Uri.parse(handle), null, null) > 0
    } catch (_: Exception) { false }
}

/**
 * Storage Access Framework backend, used when the user granted access to the GPSarrow folder
 * (ACTION_OPEN_DOCUMENT_TREE, persisted). Sees ALL files in the folder, including ones created
 * by an earlier installation of the app — this is what restores locations after a reinstall.
 */
class SafLocxStore(ctx: Context, private val tree: Uri) : LocxStore {
    private val cr = ctx.contentResolver
    private val dirDocId: String? = resolveFolder()

    val isValid get() = dirDocId != null

    private fun resolveFolder(): String? = try {
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val rootName = queryName(DocumentsContract.buildDocumentUriUsingTree(tree, treeId))
        if (rootName == LocxCodec.FOLDER) treeId
        else children(treeId).firstOrNull { it.second == LocxCodec.FOLDER && it.third == DocumentsContract.Document.MIME_TYPE_DIR }?.first
            ?: DocumentsContract.createDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, treeId),
                DocumentsContract.Document.MIME_TYPE_DIR, LocxCodec.FOLDER)?.let { DocumentsContract.getDocumentId(it) }
    } catch (_: Exception) { null }

    private fun queryName(u: Uri): String? = cr.query(u, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }

    /** (docId, name, mime, modified) */
    private fun children(parent: String): List<Triple<String, String, String>> = childrenFull(parent).map { Triple(it[0] as String, it[1] as String, it[2] as String) }

    private fun childrenFull(parent: String): List<Array<Any>> {
        val out = ArrayList<Array<Any>>()
        val proj = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent), proj, null, null, null)?.use { c ->
            while (c.moveToNext()) out.add(arrayOf(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3)))
        }
        return out
    }

    override fun list(): List<LocxEntry> {
        val dir = dirDocId ?: return emptyList()
        return try {
            childrenFull(dir).filter { (it[1] as String).endsWith(LocxCodec.EXT, ignoreCase = true) }.mapNotNull {
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, it[0] as String)
                val text = runCatching { cr.openInputStream(uri)?.use { s -> s.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
                text?.let { t -> LocxEntry(uri.toString(), it[1] as String, t, it[3] as Long) }
            }
        } catch (_: Exception) { emptyList() }
    }

    override fun create(fileName: String, text: String): String? {
        val dir = dirDocId ?: return null
        var uri: Uri? = null
        return try {
            uri = DocumentsContract.createDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, dir), MIME, fileName) ?: return null
            cr.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)); it.flush() }
            uri.toString()
        } catch (_: Exception) { uri?.let { runCatching { DocumentsContract.deleteDocument(cr, it) } }; null }
    }

    override fun update(handle: String, newFileName: String?, text: String): String? = try {
        var uri = Uri.parse(handle)
        cr.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)); it.flush() }
        if (newFileName != null) runCatching { DocumentsContract.renameDocument(cr, uri, newFileName)?.let { uri = it } }
        uri.toString()
    } catch (_: Exception) { null }

    override fun delete(handle: String): Boolean = try {
        DocumentsContract.deleteDocument(cr, Uri.parse(handle))
    } catch (_: Exception) { false }
}

object LocxFolder {
    /** Public path, for display / existence check only (never used for I/O). */
    fun publicDir(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), LocxCodec.FOLDER)

    /** Initial location for the folder picker: Documents/GPSarrow on primary storage. */
    fun pickerInitialUri(): Uri = DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents", "primary:" + Environment.DIRECTORY_DOCUMENTS + "/" + LocxCodec.FOLDER)
}
