package dev.archdock.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.archdock.graph
import dev.archdock.session.Phase
import dev.archdock.session.PhantomProcessGuard
import dev.archdock.session.SessionService
import dev.archdock.ui.Route
import dev.archdock.ui.theme.TokyoNight
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(onNavigate: (Route) -> Unit, onOpenTrackpad: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val ext by g.displays.external.collectAsState()
    val session by g.session.state.collectAsState()
    val install by g.installer.state.collectAsState()
    val gpu by g.gpu.status.collectAsState()
    val audio by g.audio.status.collectAsState()
    val perf by g.perf.sample.collectAsState()
    var phantom by remember { mutableStateOf(PhantomProcessGuard.status(ctx)) }
    LaunchedEffect(Unit) { while (true) { phantom = PhantomProcessGuard.status(ctx); delay(5000) } }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).screenPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row {
            Text("arch", fontFamily = FontFamily.Monospace, fontSize = 30.sp, color = TokyoNight.accent)
            Text("dock", fontFamily = FontFamily.Monospace, fontSize = 30.sp, color = TokyoNight.fgBright)
        }

        // --- Hero: what the app is doing right now ---------------------------------------
        Panel(accent = if (session.phase == Phase.RUNNING) TokyoNight.green else TokyoNight.accent) {
            val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
                0.35f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "a")
            val headline = when {
                !install.installed -> "Arch Linux isn't installed yet"
                session.phase == Phase.STARTING -> session.message
                session.phase == Phase.RUNNING -> "Desktop running on ${session.displayName}"
                session.phase == Phase.DETACHED -> "Running in the background"
                session.phase == Phase.ERROR -> "Something went wrong"
                ext != null -> "Display connected: ${ext?.name}"
                else -> "Waiting for display…"
            }
            Text(headline, style = MaterialTheme.typography.headlineSmall, color = TokyoNight.fgBright,
                modifier = if (session.phase == Phase.IDLE && ext == null || session.phase == Phase.STARTING) Modifier.alpha(pulse) else Modifier)
            Text(
                when {
                    session.phase == Phase.ERROR -> session.error ?: ""
                    session.phase == Phase.DETACHED -> "Reconnect the USB-C → HDMI adapter to resume the same session."
                    session.phase == Phase.IDLE && ext == null -> "Connect a TV or monitor over USB-C (DisplayPort alt mode → HDMI). The desktop starts automatically."
                    else -> session.mode?.let { "${it.width}×${it.height} @ ${it.refresh.toInt()} Hz · ${session.dpi} dpi · ${session.backend?.name?.replace('_', ' ')?.lowercase() ?: ""}" } ?: ""
                },
                color = TokyoNight.fg,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!install.installed) {
                    Button(onClick = { onNavigate(Route.INSTALLER) }) { Text("Install Arch Linux") }
                } else if (session.active) {
                    Button(onClick = onOpenTrackpad) { IconText(Icons.Default.TouchApp, "Trackpad") }
                    OutlinedButton(onClick = { SessionService.send(ctx, SessionService.ACTION_RESTART) }) { IconText(Icons.Default.Refresh, "Restart") }
                    Button(onClick = { SessionService.send(ctx, SessionService.ACTION_STOP) },
                        colors = ButtonDefaults.buttonColors(containerColor = TokyoNight.red)) { IconText(Icons.Default.Stop, "Stop") }
                } else {
                    Button(onClick = { SessionService.send(ctx, SessionService.ACTION_START) }) {
                        IconText(Icons.Default.PlayArrow, if (ext != null) "Start on ${ext?.name}" else "Start without a display")
                    }
                }
            }
        }

        if (phantom == PhantomProcessGuard.Status.ENABLED || session.phantomKilled) {
            Panel(title = if (session.phantomKilled) "Android killed the desktop" else "Child process limit is on", accent = TokyoNight.red) {
                Text(PhantomProcessGuard.instructions(), color = TokyoNight.fg, fontSize = 13.sp)
                TextButton(onClick = {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                }) { Text("Open Developer options") }
            }
        }

        Panel(title = "Status") {
            StatusLine("Install", if (install.installed) "Installed" else if (install.running) "${(install.overall * 100).toInt()}% – ${install.message}" else "Not installed",
                if (install.installed) TokyoNight.green else TokyoNight.yellow)
            StatusLine("Display", ext?.let { "${it.name} · ${it.current}" } ?: "none", if (ext != null) TokyoNight.green else TokyoNight.fgDark)
            StatusLine("Android GPU", listOfNotNull(gpu.androidRenderer, gpu.androidVulkan).joinToString(" · ").ifEmpty { "checking…" },
                if (gpu.androidRenderer != null) TokyoNight.green else TokyoNight.yellow)
            StatusLine("Vulkan wrapper", when {
                    gpu.serverBundled && gpu.wrapperInstalled -> "ready (Android server + guest ICD)"
                    gpu.serverBundled -> "Android server bundled · guest ICD not installed"
                    else -> "not bundled in this build – llvmpipe only"
                }, if (gpu.serverBundled && gpu.wrapperInstalled) TokyoNight.green else TokyoNight.yellow)
            StatusLine("Renderer", gpu.activeRenderer ?: "— (session not running)",
                when { gpu.fallbackReason != null -> TokyoNight.orange; gpu.activeRenderer != null -> TokyoNight.green; else -> TokyoNight.fgDark })
            gpu.fallbackReason?.let { Text("GPU fallback: $it", color = TokyoNight.orange, fontSize = 12.sp) }
            StatusLine("Audio", if (audio.connected) "streaming (${audio.bufferFrames} frame buffer)" else "—",
                if (audio.connected) TokyoNight.green else TokyoNight.fgDark)
            if (session.active) {
                StatusLine("CPU / temp", "%.0f%% · %s · %s".format(perf.cpuPercent,
                    perf.batteryTempC?.let { "%.1f°C".format(it) } ?: "n/a", perf.thermalLabel),
                    if (perf.throttling) TokyoNight.red else TokyoNight.fgBright)
                if (perf.throttling) Text("The phone is thermal-throttling. Turn off blur/shadows or lower the resolution.", color = TokyoNight.red, fontSize = 12.sp)
            }
            if (session.restarts > 0) Text("Components restarted ${session.restarts}×", color = TokyoNight.yellow, fontSize = 12.sp)
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { onNavigate(Route.GPU_TEST) }) { IconText(Icons.Default.Memory, "GPU Test") }
            FilledTonalButton(onClick = { onNavigate(Route.TERMINAL) }, enabled = install.installed) { IconText(Icons.Default.Terminal, "Terminal") }
            FilledTonalButton(onClick = { onNavigate(Route.SETTINGS) }) { IconText(Icons.Default.Settings, "Settings") }
            FilledTonalButton(onClick = { onNavigate(Route.LOGS) }) { IconText(Icons.Default.Article, "Logs") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun IconText(icon: ImageVector, text: String) {
    Icon(icon, null, Modifier.padding(end = 6.dp))
    Text(text)
}

@Suppress("unused")
private val fill = Modifier.fillMaxWidth()
