package dev.archdock.backup

import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdOutputStream
import dev.archdock.installer.TarExtractor
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission

/**
 * Pure-JVM `.tar.zst` backup/restore of the rootfs (no external tar/zstd).
 * Symlinks are stored as links, permission bits are kept, and volatile paths
 * (/tmp, /proc, /sys, /dev, pacman's package cache, bind-mount targets) are
 * skipped. Pure JVM so it is unit tested on the build machine.
 */
object RootfsArchiver {
    val EXCLUDES = listOf("tmp/", "proc/", "sys/", "dev/", "run/", "var/cache/pacman/pkg/",
        "home/user/Android-Downloads/", "system/", "vendor/", "apex/")

    fun excluded(rel: String): Boolean {
        val r = if (rel.endsWith("/")) rel else "$rel/"
        return EXCLUDES.any { r.startsWith(it) && r != it } // keep the empty dir itself
    }

    fun backup(root: File, out: OutputStream, level: Int = 3, onProgress: (Long) -> Unit = {}): Long {
        var count = 0L
        val zstd = ZstdOutputStream(out.buffered(1 shl 20), level).apply { runCatching { setWorkers(4) } }
        TarArchiveOutputStream(zstd).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            val rootPath = root.toPath()
            Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val rel = rootPath.relativize(dir).toString()
                    if (rel.isNotEmpty()) {
                        if (excluded(rel)) return FileVisitResult.SKIP_SUBTREE
                        add(tar, dir, "$rel/", attrs)
                        count++
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val rel = rootPath.relativize(file).toString()
                    if (excluded(rel)) return FileVisitResult.CONTINUE
                    if (attrs.isRegularFile || attrs.isSymbolicLink) {
                        add(tar, file, rel, attrs)
                        if (++count % 500 == 0L) onProgress(count)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: java.io.IOException) = FileVisitResult.CONTINUE
            })
            tar.finish()
        }
        onProgress(count)
        return count
    }

    private fun add(tar: TarArchiveOutputStream, path: Path, name: String, attrs: BasicFileAttributes) {
        val entry = if (attrs.isSymbolicLink) {
            TarArchiveEntry(name, TarConstants.LF_SYMLINK).apply { linkName = Files.readSymbolicLink(path).toString() }
        } else TarArchiveEntry(path.toFile(), name)
        entry.mode = (entry.mode and 0b111_111_111.inv()) or mode(path)
        tar.putArchiveEntry(entry)
        if (attrs.isRegularFile) Files.newInputStream(path).use { it.copyTo(tar, 1 shl 16) }
        tar.closeArchiveEntry()
    }

    private fun mode(path: Path): Int = runCatching {
        val perms = Files.readAttributes(path, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).permissions()
        PosixFilePermission.entries.foldIndexed(0) { i, m, p -> if (p in perms) m or (1 shl (8 - i)) else m }
    }.getOrDefault(0b110_100_100)

    fun restore(input: InputStream, dest: File, isCancelled: () -> Boolean = { false }): TarExtractor.Stats =
        TarArchiveInputStream(ZstdInputStream(input.buffered(1 shl 20))).use { tar ->
            TarExtractor().extract(tar, dest, isCancelled)
        }
}
