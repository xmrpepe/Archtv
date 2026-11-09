package dev.archdock.backup

import android.content.Context
import android.net.Uri
import dev.archdock.core.Logs
import dev.archdock.core.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Backup/restore of the rootfs to a user-chosen `.tar.zst` (Storage Access Framework). */
class BackupManager(private val context: Context, private val paths: Paths) {
    private val _progress = MutableStateFlow<String?>(null)
    val progress: StateFlow<String?> = _progress.asStateFlow()

    suspend fun backup(target: Uri) = withContext(Dispatchers.IO) {
        try {
            _progress.value = "Starting backup…"
            val out = context.contentResolver.openOutputStream(target, "w") ?: throw IOException("cannot open $target")
            val n = out.use { RootfsArchiver.backup(paths.rootfs, it) { c -> _progress.value = "Backed up $c entries…" } }
            Logs.i(Logs.Channel.APP, "Backup complete: $n entries → $target")
            _progress.value = "Backup complete ($n entries)"
        } catch (e: Exception) {
            Logs.e(Logs.Channel.APP, "Backup failed", e)
            _progress.value = "Backup failed: ${e.message}"
        }
    }

    /** Restores into a staging dir first; the live rootfs is replaced only on success. */
    suspend fun restore(source: Uri) = withContext(Dispatchers.IO) {
        val staging = File(paths.files, "arch.restore")
        try {
            _progress.value = "Restoring…"
            staging.deleteRecursively()
            val input = context.contentResolver.openInputStream(source) ?: throw IOException("cannot open $source")
            val stats = input.use { RootfsArchiver.restore(it, staging) }
            if (!File(staging, "usr/bin").exists()) throw IOException("archive does not contain an Arch rootfs")
            val old = File(paths.files, "arch.old")
            old.deleteRecursively()
            if (paths.rootfs.exists() && !paths.rootfs.renameTo(old)) throw IOException("could not move old rootfs")
            if (!staging.renameTo(paths.rootfs)) { old.renameTo(paths.rootfs); throw IOException("could not activate restored rootfs") }
            old.deleteRecursively()
            File(paths.rootfs, "tmp").mkdirs()
            paths.installedFlag.writeText("restored ${System.currentTimeMillis()}\n")
            _progress.value = "Restore complete (${stats.files} files)"
            Logs.i(Logs.Channel.APP, "Restore complete: $stats")
        } catch (e: Exception) {
            staging.deleteRecursively()
            Logs.e(Logs.Channel.APP, "Restore failed", e)
            _progress.value = "Restore failed: ${e.message}"
        }
    }
}
