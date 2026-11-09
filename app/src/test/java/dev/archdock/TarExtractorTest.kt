package dev.archdock

import dev.archdock.installer.TarExtractor
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.GZIPOutputStream

class TarExtractorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun buildTarball(): File {
        val f = tmp.newFile("rootfs.tar.gz")
        TarArchiveOutputStream(GZIPOutputStream(f.outputStream())).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            fun dir(name: String, mode: Int) {
                tar.putArchiveEntry(TarArchiveEntry(name).apply { this.mode = 0x4000 or mode })
                tar.closeArchiveEntry()
            }
            fun file(name: String, body: String, mode: Int) {
                val bytes = body.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong(); this.mode = 0x8000 or mode })
                tar.write(bytes); tar.closeArchiveEntry()
            }
            dir("./", "755".toInt(8))
            dir("./usr/", "755".toInt(8))
            dir("./usr/bin/", "755".toInt(8))
            dir("./root/", "750".toInt(8))
            dir("./readonly/", "555".toInt(8))
            file("./readonly/inside", "x", "644".toInt(8))
            file("./usr/bin/bash", "#!fake-bash", "755".toInt(8))
            file("./etc/shadow", "root:*:", "600".toInt(8))
            tar.putArchiveEntry(TarArchiveEntry("./bin", TarConstants.LF_SYMLINK).apply { linkName = "usr/bin" }); tar.closeArchiveEntry()
            tar.putArchiveEntry(TarArchiveEntry("./usr/bin/sh", TarConstants.LF_SYMLINK).apply { linkName = "/usr/bin/bash" }); tar.closeArchiveEntry()
            tar.putArchiveEntry(TarArchiveEntry("./usr/bin/rbash", TarConstants.LF_LINK).apply { linkName = "./usr/bin/bash" }); tar.closeArchiveEntry()
            tar.putArchiveEntry(TarArchiveEntry("./dev/null", TarConstants.LF_CHR)); tar.closeArchiveEntry()
            file("../../escape", "evil", "644".toInt(8))
            file("./usr/share/" + "deep/".repeat(30) + "long-name-file.txt", "long", "644".toInt(8))
        }
        return f
    }

    @Test fun extractsFilesLinksAndModes() {
        val dest = tmp.newFolder("rootfs")
        val stats = TarExtractor().extract(buildTarball(), dest)

        val bash = File(dest, "usr/bin/bash")
        assertEquals("#!fake-bash", bash.readText())
        assertEquals("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(bash.toPath())))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(File(dest, "etc/shadow").toPath())))

        // Symlinks are kept verbatim (absolute targets are resolved by proot later).
        assertTrue(Files.isSymbolicLink(File(dest, "bin").toPath()))
        assertEquals("usr/bin", Files.readSymbolicLink(File(dest, "bin").toPath()).toString())
        assertEquals("/usr/bin/bash", Files.readSymbolicLink(File(dest, "usr/bin/sh").toPath()).toString())

        // Hard link → same content (link() or copy).
        assertEquals("#!fake-bash", File(dest, "usr/bin/rbash").readText())
        assertEquals(1, stats.hardlinks)

        // Device nodes and path traversal are skipped.
        assertFalse(File(dest, "dev/null").exists())
        assertFalse(File(tmp.root, "escape").exists())
        assertFalse(File(dest.parentFile.parentFile, "escape").exists())
        assertTrue(stats.skipped >= 2)

        // Read-only dir was still populated, and long (PAX) names work.
        assertEquals("x", File(dest, "readonly/inside").readText())
        assertTrue(File(dest, "usr/share/" + "deep/".repeat(30) + "long-name-file.txt").exists())
    }

    @Test fun reExtractionOverwritesInPlace() {
        val dest = tmp.newFolder("again")
        val tarball = buildTarball()
        TarExtractor().extract(tarball, dest)
        val stats = TarExtractor().extract(tarball, dest)
        assertEquals(1, stats.symlinks.coerceAtMost(1))
        assertEquals("#!fake-bash", File(dest, "usr/bin/bash").readText())
    }

    @Test fun resolveRejectsEscapes() {
        val root = File("/r")
        assertNull(TarExtractor.resolve(root, "../x"))
        assertNull(TarExtractor.resolve(root, "./a/../../x"))
        assertNull(TarExtractor.resolve(root, "./"))
        assertEquals(File("/r/a/b"), TarExtractor.resolve(root, "/a/./b"))
        assertEquals(File("/r/b"), TarExtractor.resolve(root, "a/../b"))
    }
}
