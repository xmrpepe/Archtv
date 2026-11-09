package dev.archdock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.archdock.graph
import dev.archdock.settings.GpuMode
import dev.archdock.settings.Settings
import dev.archdock.ui.theme.TokyoNight
import kotlinx.coroutines.launch

@Composable
fun GpuTestScreen(onBack: () -> Unit) {
    val g = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val gpu by g.gpu.status.collectAsState()
    val settings by g.settings.settings.collectAsState(Settings())
    var output by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }

    fun run(mode: GpuMode) {
        running = true; output = ""
        scope.launch {
            output = runCatching { g.gpu.gpuTest(mode, g.session.liveDisplay) }.getOrElse { "Failed: ${it.message}" }
            running = false
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        ArchTopBar("GPU Test", onBack)
        Column(Modifier.screenPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Panel(title = "Mali-G715 (Tensor G4)") {
                StatusLine("Android GL", gpu.androidRenderer ?: "?")
                StatusLine("Android VK", gpu.androidVulkan ?: "?")
                StatusLine("GPU server", if (gpu.serverBundled) "bundled" else "missing", if (gpu.serverBundled) TokyoNight.green else TokyoNight.red)
                StatusLine("Guest ICD", if (gpu.wrapperInstalled) "installed" else "missing", if (gpu.wrapperInstalled) TokyoNight.green else TokyoNight.red)
                gpu.fallbackReason?.let { StatusLine("Fallback", it, TokyoNight.orange) }
                StatusLine("Active", gpu.activeRenderer ?: "session not running")
                StatusLine("Mode", settings.gpuMode.label)
                Text("Runs vulkaninfo --summary and glxinfo -B inside proot – against the live desktop if it's running, otherwise inside a private Xvfb.",
                    color = TokyoNight.fgDark, fontSize = 12.sp)
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { run(settings.gpuMode) }, enabled = !running) { Text("Run GPU Test") }
                    GpuMode.entries.filter { it != settings.gpuMode }.forEach { m ->
                        androidx.compose.material3.OutlinedButton(onClick = { run(m) }, enabled = !running) {
                            Text("Try " + m.name.lowercase().replace('_', ' '))
                        }
                    }
                }
                if (running) CircularProgressIndicator(color = TokyoNight.accent)
            }
        }
        Text(
            output.ifEmpty { "No results yet." },
            fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TokyoNight.fg,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp).clip(RoundedCornerShape(10.dp))
                .background(TokyoNight.bgDarker).padding(8.dp)
                .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
        )
    }
}
