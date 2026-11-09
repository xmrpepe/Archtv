package dev.archdock

import dev.archdock.installer.CheckpointStore
import dev.archdock.installer.Checksums
import dev.archdock.installer.PacmanConf
import dev.archdock.terminal.TerminalSession
import dev.archdock.theme.ThemeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConfigParsingTest {
    @get:Rule val tmp = TemporaryFolder()

    private val stockPacmanConf = """
        #
        # /etc/pacman.conf
        #
        [options]
        HoldPkg     = pacman glibc
        Architecture = auto
        #Color
        #NoProgressBar
        CheckSpace
        #VerbosePkgLists
        ParallelDownloads = 5
        DownloadUser = alpm
        #DisableSandbox
        SigLevel    = Required DatabaseOptional
        LocalFileSigLevel = Optional

        [core]
        Include = /etc/pacman.d/mirrorlist
    """.trimIndent()

    @Test fun pacmanConfPatchIsCorrectAndIdempotent() {
        val once = PacmanConf.patch(stockPacmanConf)
        val lines = once.lines().map { it.trim() }
        assertTrue("DisableSandbox" in lines)
        assertTrue("Color" in lines)
        assertTrue("ILoveCandy" in lines)
        assertFalse("CheckSpace" in lines)
        assertFalse(lines.any { it.startsWith("DownloadUser") })
        assertEquals(1, lines.count { it.startsWith("ParallelDownloads") })
        assertFalse("#Color" in lines)
        // New options land in [options], before [core].
        assertTrue(lines.indexOf("DisableSandbox") < lines.indexOf("[core]"))
        assertEquals(once, PacmanConf.patch(once))
    }

    @Test fun checksumFileParsing() {
        val md5 = "0123456789abcdef0123456789abcdef  ArchLinuxARM-aarch64-latest.tar.gz\n"
        assertEquals("0123456789abcdef0123456789abcdef", Checksums.parse(md5, "ArchLinuxARM-aarch64-latest.tar.gz"))
        val multi = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa  other.tar.gz\nBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB  wanted.tar.gz\n"
        assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", Checksums.parse(multi, "wanted.tar.gz"))
        assertNull(Checksums.parse("<html>404</html>", "x"))
        assertEquals("MD5", Checksums.algorithmFor("a".repeat(32)))
        assertEquals("SHA-256", Checksums.algorithmFor("a".repeat(64)))
    }

    @Test fun digestMatchesKnownValue() {
        val f = tmp.newFile("abc"); f.writeText("abc")
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Checksums.digest(f, "MD5"))
    }

    @Test fun themeTomlKeepsHexColours() {
        val p = ThemeManager.parseToml("""
            mode = "dark"
            # comment
            accent = "#7aa2f7"
            background = "#1a1b26"  # trailing comment
            bare = 12 # number
        """.trimIndent())
        assertEquals("#7aa2f7", p["accent"])
        assertEquals("#1a1b26", p["background"])
        assertEquals("dark", p["mode"])
        assertEquals("12", p["bare"])
    }

    @Test fun shippedThemesHaveCompletePalettes() {
        val themes = File("../dotfiles/themes").listFiles()!!.filter { File(it, "colors.toml").exists() }
        assertTrue("expected the Omarchy theme set", themes.size >= 7)
        val required = listOf("background", "foreground", "accent", "selection", "red", "green", "yellow", "blue", "magenta", "cyan")
        for (t in themes) {
            val p = ThemeManager.parseToml(File(t, "colors.toml").readText())
            for (k in required) assertTrue("${t.name} lacks $k", p[k]?.matches(Regex("#[0-9a-fA-F]{6}")) == true)
        }
    }

    @Test fun checkpointsPersist() {
        val dir = tmp.newFolder("state")
        val a = CheckpointStore(dir)
        a.markDone("download"); a.markDone("extract")
        val b = CheckpointStore(dir)
        assertEquals(setOf("download", "extract"), b.completed())
        b.clear("extract")
        assertFalse(CheckpointStore(dir).isDone("extract"))
    }

    @Test fun ansiStripping() {
        assertEquals("user@archdock:~$ ls", TerminalSession.stripAnsi("\u001B[1;32muser@archdock\u001B[0m:~$ \u001B]0;title\u0007ls"))
        assertEquals("plain", TerminalSession.stripAnsi("plain"))
    }
}
