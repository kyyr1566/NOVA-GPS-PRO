package com.nova.gpspro.data

import java.io.File
import java.io.FileOutputStream

/**
 * Device-private offline store. Each trip has its own atomically replaced file, so deleting or
 * updating one route cannot overwrite another trip's points.
 */
class TripRepository(private val directory: File) {
    @Synchronized
    fun all(): List<Trip> {
        if (!directory.exists()) return emptyList()
        recoverBackups()
        return directory.listFiles()?.asSequence()
            ?.filter { it.isFile && it.name.endsWith(EXT) }
            ?.mapNotNull { file -> runCatching { TripCodec.decode(file.readText()) }.getOrNull() }
            ?.sortedByDescending { it.endedAtMs }
            ?.toList()
            .orEmpty()
    }

    @Synchronized
    fun get(id: String): Trip? = all().firstOrNull { it.id == id }

    @Synchronized
    fun save(trip: Trip): Boolean {
        if (!safeId(trip.id) || !directory.mkdirs() && !directory.isDirectory) return false
        val target = fileFor(trip.id)
        val backup = backupFor(trip.id)
        val temp = File(directory, "trip_${trip.id}.${System.nanoTime()}.tmp")
        return try {
            if (backup.exists()) {
                if (target.exists()) backup.delete() else backup.renameTo(target)
            }
            FileOutputStream(temp).use { out ->
                out.write(TripCodec.encode(trip).toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (target.exists() && !target.renameTo(backup)) {
                temp.delete()
                return false
            }
            if (!temp.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                temp.delete()
                return false
            }
            backup.delete()
            true
        } catch (_: Exception) {
            temp.delete()
            if (!target.exists() && backup.exists()) backup.renameTo(target)
            false
        }
    }

    @Synchronized
    fun clearAll(): Boolean {
        if (!directory.exists()) return true
        var ok = true
        directory.listFiles()?.filter { it.isFile }?.forEach { if (!it.delete()) ok = false }
        return ok
    }

    @Synchronized
    fun delete(id: String): Boolean {
        if (!safeId(id)) return false
        val target = fileFor(id)
        val backup = backupFor(id)
        val existed = target.exists() || backup.exists()
        val targetDeleted = !target.exists() || target.delete()
        val backupDeleted = !backup.exists() || backup.delete()
        directory.listFiles()?.filter { it.name.startsWith("trip_${id}.") && it.name.endsWith(".tmp") }?.forEach { it.delete() }
        return existed && targetDeleted && backupDeleted
    }

    private fun recoverBackups() {
        directory.listFiles()?.filter { it.isFile && it.name.startsWith("trip_") && it.name.endsWith(".bak") }?.forEach { backup ->
            val id = backup.name.removePrefix("trip_").removeSuffix(".bak")
            val target = if (safeId(id)) fileFor(id) else null
            if (target == null || target.exists()) backup.delete() else backup.renameTo(target)
        }
        directory.listFiles()?.filter { it.isFile && it.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    private fun fileFor(id: String) = File(directory, "trip_$id$EXT")
    private fun backupFor(id: String) = File(directory, "trip_$id.bak")
    private fun safeId(id: String) = id.isNotBlank() && id.length <= 128 && id.matches(Regex("[A-Za-z0-9_-]+"))

    companion object { private const val EXT = ".trip" }
}
