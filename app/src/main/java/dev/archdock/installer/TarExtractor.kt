package dev.archdock.installer

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPInputStream

/**
 * Extracts a (gzip/zstd/plain) tarball into a directory without root and
 * without an external `tar`, preserving what can be preserved in Android app
 * storage:
 *  - regular files, directories and permission bits (setuid/setgid dropped –
 *    proot's fake root makes them unnecessary),
 *  - symbolic links verbatim (absolute targets stay absolute; proot resolves
 *    them against the guest root),
 *  - hard links, which Android forbids for apps: we try link() and fall back
 *    to copying the target,
 *  - modification times.
 * Device nodes and FIFOs are skipped (the guest gets /dev bound from Android).
 * Entries that would escape [dest] are rejected.
 */
class TarExtractor(
    private val chmod: (File, Int) -> Unit = ::defaultChmod,
) {
    data class Stats(var files: Long = 0, var dirs: Long = 0, var symlinks: Long = 0,
                     var hardlinks: Long = 0, var skipped: Long = 0)

    enum class Compression { GZIP, ZSTD, NONE }

    fun extract(
        source: File,
        dest: File,
        compression: Compression = guess(source),
        onProgress: (bytesRead: Long, total: Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Stats {
        val total = source.length()
        val counting = CountingInputStream(source.inputStream().buffered(1 shl 20))
        val decompressed: InputStream = when (compression) {
            Compression.GZIP -> GZIPInputStream(counting, 1 shl 16)
            Compression.ZSTD -> com.github.luben.zstd.ZstdInputStream(counting)
            Compression.NONE -> counting
        }
        return TarArchiveInputStream(decompressed).use { tar ->
            extract(tar, dest, isCancelled) { onProgress(counting.count, total) }
        }
    }

    fun extract(tar: TarArchiveInputStream, dest: File, isCancelled: () -> Boolean = { false },
                tick: () -> Unit = {}): Stats {
        val stats = Stats()
        dest.mkdirs()
        val root = dest.canonicalFile
        val dirModes = ArrayList<Pair<File, Int>>()
        val buffer = ByteArray(1 shl 16)
        var n = 0
        while (true) {
            val entry: TarArchiveEntry = tar.nextEntry ?: break
            if (isCancelled()) throw InterruptedException("extraction cancelled")
            val target = resolve(root, entry.name) ?: run { stats.skipped++; null } ?: continue
            when {
                // Must come first: commons-compress reports device nodes as isFile.
                entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO -> stats.skipped++
                entry.isDirectory -> {
                    target.mkdirs()
                    dirModes += target to entry.mode
                    stats.dirs++
                }
                entry.isSymbolicLink -> {
                    target.parentFile?.mkdirs()
                    deleteExisting(target)
                    Files.createSymbolicLink(target.toPath(), java.nio.file.Paths.get(entry.linkName))
                    stats.symlinks++
                }
                entry.isLink -> {
                    val linkTarget = resolve(root, entry.linkName)
                    target.parentFile?.mkdirs()
                    deleteExisting(target)
                    if (linkTarget != null && linkTarget.exists()) {
                        try {
                            Files.createLink(target.toPath(), linkTarget.toPath())
                        } catch (e: Exception) {
                            Files.copy(linkTarget.toPath(), target.toPath())
                            chmod(target, entry.mode)
                        }
                        stats.hardlinks++
                    } else stats.skipped++
                }
                entry.isFile -> {
                    target.parentFile?.mkdirs()
                    deleteExisting(target)
                    target.outputStream().buffered(1 shl 16).use { out ->
                        while (true) {
                            val r = tar.read(buffer)
                            if (r < 0) break
                            out.write(buffer, 0, r)
                        }
                    }
                    chmod(target, entry.mode)
                    runCatching {
                        Files.setLastModifiedTime(target.toPath(), FileTime.fromMillis(entry.modTime.time))
                    }
                    stats.files++
                }
                else -> stats.skipped++ // char/block devices, FIFOs
            }
            if (++n % 256 == 0) tick()
        }
        // Apply directory modes last (deepest first) so read-only dirs don't
        // block writing their children.
        dirModes.sortedByDescending { it.first.path.length }.forEach { (d, m) -> chmod(d, m or 0b111_000_000) }
        tick()
        return stats
    }

    private fun deleteExisting(f: File) {
        val p = f.toPath()
        if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) f.deleteRecursively() else Files.delete(p)
        }
    }

    companion object {
        /** Returns the file for [name] under [root], or null if it escapes root. */
        fun resolve(root: File, name: String): File? {
            val clean = name.trimStart('/').removePrefix("./")
            if (clean.isEmpty() || clean == ".") return null
            val parts = ArrayList<String>()
            for (p in clean.split('/')) {
                when (p) {
                    "", "." -> {}
                    ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
                    else -> parts += p
                }
            }
            if (parts.isEmpty()) return null
            return File(root, parts.joinToString("/"))
        }

        fun guess(f: File): Compression = when {
            f.name.endsWith(".gz") || f.name.endsWith(".tgz") -> Compression.GZIP
            f.name.endsWith(".zst") || f.name.endsWith(".zstd") -> Compression.ZSTD
            else -> Compression.NONE
        }

        fun defaultChmod(f: File, mode: Int) {
            val perms = HashSet<PosixFilePermission>()
            val all = PosixFilePermission.entries // OWNER_READ … OTHERS_EXECUTE, MSB first
            for (i in 0 until 9) if (mode and (1 shl (8 - i)) != 0) perms += all[i]
            try {
                Files.setPosixFilePermissions(f.toPath(), perms)
            } catch (e: UnsupportedOperationException) {
                f.setReadable(true); f.setWritable(true)
                f.setExecutable(mode and 0b001_001_001 != 0, false)
            } catch (e: IOException) {
                // Dangling or permission-protected entries: not fatal.
            }
        }
    }
}

class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    @Volatile var count = 0L; private set
    override fun read(): Int = super.read().also { if (it >= 0) count++ }
    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) count += it }
    override fun skip(n: Long): Long = super.skip(n).also { count += it }
}
