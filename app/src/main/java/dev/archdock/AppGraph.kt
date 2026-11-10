package dev.archdock

import android.content.Context
import dev.archdock.audio.AudioBridge
import dev.archdock.backup.BackupManager
import dev.archdock.bluetooth.BluetoothInputManager
import dev.archdock.core.Logs
import dev.archdock.core.Paths
import dev.archdock.display.DisplayWatcher
import dev.archdock.gpu.GpuManager
import dev.archdock.input.InputBridge
import dev.archdock.installer.InstallerManager
import dev.archdock.perf.PerfMonitor
import dev.archdock.proot.ProotRunner
import dev.archdock.session.SessionController
import dev.archdock.settings.SettingsRepo
import dev.archdock.terminal.TerminalSession
import dev.archdock.theme.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Hand-rolled dependency graph; one instance per process. */
class AppGraph(val context: Context, val paths: Paths) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepo(context)
    val proot = ProotRunner(paths)
    val installer = InstallerManager(context, paths, proot, settings)
    val displays = DisplayWatcher(context)
    val gpu = GpuManager(context, paths, proot)
    val themes = ThemeManager(context, paths, proot)
    val audio = AudioBridge()
    val perf = PerfMonitor(context)
    val backup = BackupManager(context, paths)
    val terminal = TerminalSession(proot)
    val bluetooth = BluetoothInputManager(context)
    val input = InputBridge(paths) { text -> typeViaXdotool(text) }
    val session = SessionController(context, paths, proot, settings, gpu, audio, input, perf, scope)

    /** Non-ASCII text from the soft keyboard: let xdotool resolve it with the X keymap. */
    private fun typeViaXdotool(text: String) {
        scope.launch {
            val display = session.liveDisplay ?: return@launch
            runCatching {
                proot.capture(
                    listOf("/usr/bin/xdotool", "type", "--delay", "8", "--", text),
                    ProotRunner.Options(asUser = true, env = mapOf("DISPLAY" to ":$display")),
                )
            }.onFailure { Logs.w(Logs.Channel.X11, "xdotool type failed: ${it.message}") }
        }
    }
}
