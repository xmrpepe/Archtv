package dev.archdock

import dev.archdock.backup.RootfsArchiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions

class RootfsArchiverTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun backupRestoreRoundTrip() {
        val src = tmp.newFolder("src")
        File(src, "usr/bin").mkdirs()
        File(src, "usr/bin/tool").apply { writeText("binary"); Files.setPosixFilePermissions(toPath(), PosixFilePermissions.fromString("rwxr-x---")) }
        File(src, "home/user").mkdirs()
        File(src, "home/user/notes.txt").writeText("hello")
        Files.createSymbolicLink(File(src, "bin").toPath(), Paths.get("usr/bin"))
        File(src, "tmp/.X11-unix").mkdirs()
        File(src, "tmp/.X11-unix/X0").writeText("socket-ish")
        File(src, "var/cache/pacman/pkg").mkdirs()
        File(src, "var/cache/pacman/pkg/big.pkg.tar.zst").writeText("cached")

        val buf = ByteArrayOutputStream()
        RootfsArchiver.backup(src, buf)
        val dest = tmp.newFolder("dest")
        RootfsArchiver.restore(ByteArrayInputStream(buf.toByteArray()), dest)

        assertEquals("binary", File(dest, "usr/bin/tool").readText())
        assertEquals("rwxr-x---", PosixFilePermissions.toString(Files.getPosixFilePermissions(File(dest, "usr/bin/tool").toPath())))
        assertEquals("hello", File(dest, "home/user/notes.txt").readText())
        assertTrue(Files.isSymbolicLink(File(dest, "bin").toPath()))
        assertEquals("usr/bin", Files.readSymbolicLink(File(dest, "bin").toPath()).toString())
        // Volatile content excluded, but the directories themselves remain.
        assertTrue(File(dest, "tmp").isDirectory)
        assertFalse(File(dest, "tmp/.X11-unix/X0").exists())
        assertFalse(File(dest, "var/cache/pacman/pkg/big.pkg.tar.zst").exists())
    }

    @Test fun excludeRules() {
        assertTrue(RootfsArchiver.excluded("tmp/foo"))
        assertFalse(RootfsArchiver.excluded("tmp"))
        assertFalse(RootfsArchiver.excluded("tmpfiles"))
        assertTrue(RootfsArchiver.excluded("proc/self"))
        assertFalse(RootfsArchiver.excluded("usr/lib/proc"))
    }
}
