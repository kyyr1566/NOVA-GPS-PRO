package com.nova.gpspro.portal

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/** Minimal read-only provider so the Share Sheet can receive exported .locx files (no AndroidX). */
class ExportProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.nova.gpspro.export"
        fun dir(ctx: Context) = File(ctx.cacheDir, "export").apply { mkdirs() }
        fun uriFor(f: File): Uri = Uri.parse("content://$AUTHORITY/" + Uri.encode(f.name))
    }

    private fun fileFor(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        val f = File(dir(context!!), name)
        return f.takeIf { it.parentFile?.canonicalPath == dir(context!!).canonicalPath && it.exists() }
    }

    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/octet-stream"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
        fileFor(uri)?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }

    override fun query(uri: Uri, projection: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? {
        val f = fileFor(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map { when (it) { OpenableColumns.DISPLAY_NAME -> f.name; OpenableColumns.SIZE -> f.length(); else -> null } })
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
