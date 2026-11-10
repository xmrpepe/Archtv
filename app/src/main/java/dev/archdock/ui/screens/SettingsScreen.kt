package dev.archdock.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.archdock.graph
import dev.archdock.installer.DotfilesInstaller
import dev.archdock.proot.ProotRunner
import dev.archdock.session.SessionService
import dev.archdock.settings.DisplayBackend
import dev.archdock.settings.ExternalLaunchMode
import dev.archdock.settings.GpuMode
import dev.archdock.settings.Settings
import dev.archdock.theme.ThemeInfo
import dev.archdock.ui.Route
import dev.archdock.ui.theme.TokyoNight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(onBack: () -> Unit, onNavigate: (Route) -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val scope = rememberCoroutineScope()
    val s by g.settings.settings.collectAsState(Settings())
    val session by g.session.state.collectAsState()
    val backupProgress by g.backup.progress.collectAsState()
    var themes by remember { mutableStateOf(g.themes.themes()) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var themeBusy by remember { mutableStateOf(false) }

    fun update(t: (Settings) -> Settings) = scope.launch { g.settings.update(t) }
    /** Runs a guest command in the live session (DISPLAY + D-Bus) if there is one. */
    fun live(cmd: String) = scope.launch(Dispatchers.IO) {
        val d = g.session.liveDisplay ?: return@launch
        g.proot.capture(listOf("/bin/bash", "-c", "[ -f /tmp/archdock/dbus.env ] && . /tmp/archdock/dbus.env; . /tmp/archdock/session.env; $cmd"),
            ProotRunner.Options(asUser = true, env = mapOf("DISPLAY" to ":$d")))
    }
    fun compositorChanged(c: Boolean = s.compositor, b: Boolean = s.blur, sh: Boolean = s.shadows) {
        fun f(v: Boolean) = if (v) "1" else "0"
        live("/opt/archdock/bin/archdock-compositor restart --enabled=${f(c)} --blur=${f(b)} --shadows=${f(sh)}")
    }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zstd")) { uri ->
        if (uri != null) scope.launch { g.backup.backup(uri) }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { g.session.stop(); g.backup.restore(uri); g.installer.refresh() }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        ArchTopBar("Settings", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).screenPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel(title = "Theme") {
                Text("Omarchy themes. Switching re-themes GTK, Marco, panel, icons, Alacritty, Rofi, dunst, picom, btop, Neovim and the prompt live.",
                    color = TokyoNight.fgDark, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    themes.forEach { t ->
                        ThemeSwatch(t, selected = t.id == s.theme) {
                            themeBusy = true
                            scope.launch {
                                g.settings.update { it.copy(theme = t.id) }
                                if (g.installer.isInstalled) withContext(Dispatchers.IO) {
                                    g.themes.apply(t.id, g.session.liveDisplay, g.session.sessionEnv)
                                }
                                themeBusy = false
                            }
                        }
                    }
                }
                if (themeBusy) Text("Applying…", color = TokyoNight.accent, fontSize = 12.sp)
            }

            Panel(title = "Display") {
                Choice("Resolution", listOf("auto" to "Match TV", "1920x1080" to "1080p", "2560x1440" to "1440p", "3840x2160" to "4K"), s.resolution) { v -> update { it.copy(resolution = v) } }
                Choice("Refresh rate", listOf(0 to "Highest", 60 to "60 Hz", 120 to "120 Hz", 30 to "30 Hz"), s.refreshRate) { v -> update { it.copy(refreshRate = v) } }
                Choice("DPI", listOf(0 to "Auto", 96 to "96", 120 to "120", 144 to "144", 192 to "192"), s.dpi) { v -> update { it.copy(dpi = v) } }
                Choice("UI scale", listOf(1 to "1×", 2 to "2× (HiDPI)"), s.uiScale) { v -> update { it.copy(uiScale = v) } }
                Choice("Backend", DisplayBackend.entries.map { it to it.label }, s.backend) { v -> update { it.copy(backend = v) } }
                Choice("Show on TV via", ExternalLaunchMode.entries.map { it to it.label }, s.launchMode) { v -> update { it.copy(launchMode = v) } }
                Text("Resolution/DPI/backend changes apply on the next session start.", color = TokyoNight.fgDark, fontSize = 12.sp)
            }

            Panel(title = "GPU") {
                Choice("Mode", GpuMode.entries.map { it to it.label }, s.gpuMode) { v -> update { it.copy(gpuMode = v) } }
                Text("If the Vulkan wrapper fails its probe at session start, ArchDock falls back to llvmpipe automatically.",
                    color = TokyoNight.fgDark, fontSize = 12.sp)
                OutlinedButton(onClick = { onNavigate(Route.GPU_TEST) }) { Text("GPU Test") }
            }

            Panel(title = "Session") {
                Toggle("Auto-start when a display connects", s.autoStartOnConnect) { v -> update { it.copy(autoStartOnConnect = v) } }
                Toggle("Keep running when the display disconnects", s.keepRunningOnDisconnect) { v -> update { it.copy(keepRunningOnDisconnect = v) } }
                Toggle("Audio over HDMI (PulseAudio → AAudio)", s.audio) { v -> update { it.copy(audio = v) } }
                Toggle("Clipboard sync", s.clipboardSync) { v -> update { it.copy(clipboardSync = v) } }
                Toggle("Share /sdcard/Download as ~/Android-Downloads", s.sharedFolder) { v ->
                    update { it.copy(sharedFolder = v) }
                    if (v && !Environment.isExternalStorageManager()) runCatching {
                        ctx.startActivity(Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                    }
                }
            }

            Panel(title = "Input") {
                Text("Pair a Bluetooth keyboard and mouse; the Meta/Windows key acts as Super.", color = TokyoNight.fgDark, fontSize = 12.sp)
                OutlinedButton(onClick = { onNavigate(Route.BLUETOOTH) }) { Text("Bluetooth keyboard & mouse") }
            }

            Panel(title = "Compositor & performance") {
                Toggle("Compositor (picom)", s.compositor) { v -> update { it.copy(compositor = v) }; compositorChanged(c = v) }
                Toggle("Blur", s.blur, enabled = s.compositor) { v -> update { it.copy(blur = v) }; compositorChanged(b = v) }
                Toggle("Shadows", s.shadows, enabled = s.compositor) { v -> update { it.copy(shadows = v) }; compositorChanged(sh = v) }
                Text("Turn blur and shadows off for the lowest latency.", color = TokyoNight.fgDark, fontSize = 12.sp)
                Toggle("Pin X server + compositor to big cores", s.pinBigCores) { v -> update { it.copy(pinBigCores = v) } }
                Toggle("Performance overlay (CPU/GPU/temp/FPS)", s.perfOverlay) { v ->
                    update { it.copy(perfOverlay = v) }
                    if (v && !AndroidSettings.canDrawOverlays(ctx)) runCatching {
                        ctx.startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                    }
                }
            }

            Panel(title = "Install & maintenance") {
                var mirror by remember(s.mirror) { mutableStateOf(s.mirror) }
                OutlinedTextField(mirror, { mirror = it }, label = { Text("Arch Linux ARM mirror") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                if (mirror != s.mirror) TextButton(onClick = { update { it.copy(mirror = mirror.trim()) } }) { Text("Save mirror") }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            DotfilesInstaller(ctx).install(File(g.paths.rootfs, "opt/archdock"))
                            themes = g.themes.themes()
                        }
                    }, enabled = session.phase.name != "STARTING") { Text("Re-sync dotfiles") }
                    OutlinedButton(onClick = {
                        confirm = "Repair re-runs every configuration step (packages, user, GPU, theming) on the existing rootfs. Your files are kept." to {
                            scope.launch { g.session.stop(); g.installer.prepareRepair(); SessionService.send(ctx, SessionService.ACTION_INSTALL); onNavigate(Route.INSTALLER) }
                        }
                    }) { Text("Repair") }
                    OutlinedButton(onClick = {
                        confirm = "Reinstall DELETES the Arch rootfs, including everything in /home/user, and installs from scratch. Make a backup first." to {
                            scope.launch { g.session.stop(); g.installer.prepareReinstall(); SessionService.send(ctx, SessionService.ACTION_INSTALL); onNavigate(Route.INSTALLER) }
                        }
                    }) { Text("Reinstall", color = TokyoNight.red) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                        backupLauncher.launch("archdock-rootfs-$stamp.tar.zst")
                    }, enabled = g.installer.isInstalled) { Text("Backup rootfs") }
                    OutlinedButton(onClick = {
                        confirm = "Restoring replaces the current rootfs with the backup. The session will be stopped." to {
                            restoreLauncher.launch(arrayOf("*/*"))
                        }
                    }) { Text("Restore") }
                }
                backupProgress?.let { Text(it, color = TokyoNight.accent, fontSize = 12.sp) }
                OutlinedButton(onClick = { onNavigate(Route.LOGS) }) { Text("Crash logs & export") }
            }
            Text("ArchDock is GPL-3.0. Theme palettes and wallpapers © Omarchy (MIT, basecamp/omarchy). See README for all bundled licenses.",
                color = TokyoNight.fgDark, fontSize = 11.sp)
            Spacer(Modifier.height(24.dp))
        }
    }

    confirm?.let { (text, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
            text = { Text(text) },
        )
    }
}

@Composable
private fun ThemeSwatch(t: ThemeInfo, selected: Boolean, onClick: () -> Unit) {
    fun c(key: String) = runCatching { Color(android.graphics.Color.parseColor(t.color(key))) }.getOrDefault(Color.Gray)
    Column(
        Modifier.width(96.dp).clip(RoundedCornerShape(10.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) c("accent") else TokyoNight.muted, RoundedCornerShape(10.dp))
            .background(c("background")).clickable(onClick = onClick).padding(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            listOf("red", "green", "yellow", "blue", "magenta").forEach { Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(c(it))) }
        }
        Spacer(Modifier.height(6.dp))
        Text(t.name, color = c("foreground"), fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = if (enabled) TokyoNight.fgBright else TokyoNight.fgDark, fontSize = 14.sp)
        Switch(checked = value, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun <T> Choice(label: String, options: List<Pair<T, String>>, value: T, onChange: (T) -> Unit) {
    Text(label, color = TokyoNight.fg, fontSize = 13.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (v, text) ->
            FilterChip(selected = v == value, onClick = { onChange(v) }, label = { Text(text, fontSize = 12.sp) })
        }
    }
}
