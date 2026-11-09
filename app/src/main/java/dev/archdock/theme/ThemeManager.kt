package dev.archdock.theme

import android.content.Context
import dev.archdock.core.Logs
import dev.archdock.core.Paths
import dev.archdock.proot.ProotRunner
import java.io.File

data class ThemeInfo(val id: String, val name: String, val palette: Map<String, String>) {
    fun color(key: String, fallback: String = "#888888") = palette[key] ?: fallback
}

/**
 * Omarchy theme handling. Theme sources (first match wins):
 *   ~/.config/archdock/themes/<id>/   (user-edited or added themes)
 *   /opt/archdock/themes/<id>/        (shipped from the repo's dotfiles/)
 *   APK assets themes/<id>/           (before the rootfs exists)
 * Applying runs `archdock-theme-set <id>` inside the guest, which renders the
 * templates and reloads GTK, Marco, the panel, picom, dunst, Alacritty … live.
 */
class ThemeManager(private val context: Context, private val paths: Paths, private val proot: ProotRunner) {

    private fun themeDirs(): List<File> = listOf(
        File(paths.userHome, ".config/archdock/themes"),
        File(paths.rootfs, "opt/archdock/themes"),
    )

    fun themes(): List<ThemeInfo> {
        val found = LinkedHashMap<String, ThemeInfo>()
        for (dir in themeDirs()) {
            dir.listFiles()?.filter { File(it, "colors.toml").isFile }?.sortedBy { it.name }?.forEach { d ->
                if (d.name !in found) found[d.name] = ThemeInfo(d.name, displayName(d.name), parseToml(File(d, "colors.toml").readText()))
            }
        }
        context.assets.list("themes")?.sorted()?.forEach { id ->
            if (id !in found) runCatching {
                val text = context.assets.open("themes/$id/colors.toml").bufferedReader().readText()
                found[id] = ThemeInfo(id, displayName(id), parseToml(text))
            }
        }
        // Tokyo Night first – it's the default.
        return found.values.sortedWith(compareBy<ThemeInfo> { it.id != "tokyo-night" }.thenBy { it.name })
    }

    /**
     * Applies [id]. With [display] set (session running) the change is live;
     * otherwise the theme is rendered and picked up at next login.
     */
    suspend fun apply(id: String, display: Int?, sessionEnv: Map<String, String> = emptyMap()): Boolean {
        val env = buildMap {
            putAll(sessionEnv)
            if (display != null) put("DISPLAY", ":$display")
        }
        val code = proot.run(
            listOf("/bin/bash", "-c", "[ -f /tmp/archdock/dbus.env ] && . /tmp/archdock/dbus.env; exec /opt/archdock/bin/archdock-theme-set \"$1\"", "theme", id),
            ProotRunner.Options(asUser = true, env = env),
            Logs.Channel.THEME,
        )
        Logs.i(Logs.Channel.THEME, "theme $id applied (exit $code)")
        return code == 0
    }

    companion object {
        fun displayName(id: String) = id.split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

        /** Parses the flat `key = "value"` subset of TOML that colors.toml uses. */
        fun parseToml(text: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (raw in text.lineSequence()) {
                val t = raw.trim()
                if (t.isEmpty() || t.startsWith("#") || t.startsWith("[")) continue
                val eq = t.indexOf('=')
                if (eq <= 0) continue
                val key = t.substring(0, eq).trim()
                val rest = t.substring(eq + 1).trim()
                val value = if (rest.startsWith("\"")) {
                    val end = rest.indexOf('"', 1)
                    if (end < 0) rest.substring(1) else rest.substring(1, end)
                } else rest.substringBefore('#').trim()
                out[key] = value
            }
            return out
        }
    }
}
