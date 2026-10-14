package com.nova.gpspro.map

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads a PMTiles / road network / satellite package in background.
 * InputData: id, url, sha256 (optional), outputFile
 * Uses HttpURLConnection, verifies SHA-256 if provided.
 */
class MapDownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.failure()
        val urlStr = inputData.getString("url") ?: return Result.failure()
        val sha256 = inputData.getString("sha256")
        val outPath = inputData.getString("outPath") ?: run {
            val f = OfflineMapManager.pmtilesFile(applicationContext, id)
            f.absolutePath
        }
        val outFile = File(outPath)
        val tempFile = File(outPath + ".tmp")
        return try {
            outFile.parentFile?.mkdirs()
            tempFile.parentFile?.mkdirs()
            // Resume support: if temp exists, continue
            val existing = if (tempFile.exists()) tempFile.length() else 0L
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "NOVA-GPS-PRO/1.0 (OSM)")
            if (existing > 0) {
                conn.setRequestProperty("Range", "bytes=$existing-")
            }
            conn.connect()
            val code = conn.responseCode
            if (code == 404) {
                return Result.failure(workDataOf("error" to "404 Not Found"))
            }
            if (code == 416) {
                // Range not satisfiable – restart
                tempFile.delete()
                return Result.retry()
            }
            if (code !in 200..299 && code != 206) {
                // Server error -> retry, client error -> failure
                return if (code in 500..599 && runAttemptCount < 3) Result.retry() else Result.failure(workDataOf("error" to "HTTP $code"))
            }
            val contentLen = conn.contentLengthLong
            val total = if (code == 206 && existing > 0 && contentLen > 0) contentLen + existing else if (contentLen > 0) contentLen else -1L
            var downloaded: Long = existing
            // If 200 and existing >0, server ignored Range -> restart from 0
            val append = code == 206 && existing > 0
            if (!append && existing > 0) {
                tempFile.delete()
                downloaded = 0
            }
            conn.inputStream.use { input ->
                FileOutputStream(tempFile, append).use { output ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) {
                            val progress = (downloaded * 100 / total).toInt().coerceIn(0, 100)
                            setProgress(workDataOf("progress" to progress, "id" to id))
                        }
                        if (isStopped) {
                            // Keep temp for resume, don't delete
                            return Result.failure()
                        }
                    }
                }
            }
            // Verify SHA if provided
            if (!sha256.isNullOrBlank()) {
                val ok = OfflineMapManager.verifySha256(tempFile, sha256)
                if (!ok) {
                    tempFile.delete()
                    return Result.failure(workDataOf("error" to "sha256_mismatch"))
                }
            }
            // Move temp to final
            if (outFile.exists()) outFile.delete()
            tempFile.renameTo(outFile)
            Result.success(workDataOf("id" to id, "path" to outFile.absolutePath))
        } catch (e: Exception) {
            // Keep temp for resume on transient errors; delete only on final failure after retries
            if (runAttemptCount >= 3) {
                // keep temp for manual resume? but mark failure
                // tempFile.delete() // keep for resume even on failure
            }
            if (runAttemptCount < 3) Result.retry() else Result.failure(workDataOf("error" to (e.message ?: "download_failed")))
        }
    }

    companion object {
        fun enqueue(context: Context, pkg: MapPackageManifest.PackageInfo) {
            val data = androidx.work.Data.Builder()
                .putString("id", pkg.id)
                .putString("url", pkg.url)
                .putString("sha256", pkg.sha256)
                .putString("outPath", OfflineMapManager.pmtilesFile(context, pkg.id).absolutePath)
                .build()
            val req = androidx.work.OneTimeWorkRequestBuilder<MapDownloadWorker>()
                .setInputData(data)
                .addTag("nova_map_download")
                .addTag(pkg.id)
                .build()
            androidx.work.WorkManager.getInstance(context).enqueue(req)
        }
    }
}
