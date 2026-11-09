package dev.archdock.installer

import android.content.Context
import dev.archdock.core.Logs
import java.io.File

/**
 * Copies the repo's `dotfiles/` tree (packaged as APK assets) into the rootfs
 * at /opt/archdock. Themes, templates and scripts are plain files there, so
 * they can be edited or extended in place (or via ~/.config/archdock, which
 * takes precedence) without rebuilding the app.
 */
class DotfilesInstaller(private val context: Context) {
    companion object {
        /** Top-level folders of dotfiles/ (the APK's asset root also holds framework dirs). */
        val TOP_LEVEL = listOf("bin", "install", "themes", "templates", "config", "share")
        private val EXECUTABLE_DIRS = setOf("bin", "install")
    }

    fun install(dest: File): Int {
        var count = 0
        for (top in TOP_LEVEL) count += copyTree(top, File(dest, top), top in EXECUTABLE_DIRS)
        File(dest, "VERSION").writeText(
            context.packageManager.getPackageInfo(context.packageName, 0).versionName + "\n"
        )
        Logs.i(Logs.Channel.INSTALL, "Installed $count dotfiles to /opt/archdock")
        return count
    }

    private fun copyTree(assetPath: String, dest: File, executable: Boolean): Int {
        val children = context.assets.list(assetPath) ?: return 0
        if (children.isEmpty()) {
            // A file (or an empty dir, which AAPT drops anyway).
            return runCatching {
                dest.parentFile?.mkdirs()
                context.assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
                dest.setReadable(true, false)
                if (executable || dest.name.endsWith(".sh")) dest.setExecutable(true, false)
                1
            }.getOrDefault(0)
        }
        dest.mkdirs()
        return children.sumOf { copyTree("$assetPath/$it", File(dest, it), executable) }
    }
}
