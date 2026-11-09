package dev.archdock.session

import android.content.Context
import dev.archdock.audio.AudioBridge
import dev.archdock.core.Logs
import dev.archdock.core.Paths
import dev.archdock.display.DisplayModeInfo
import dev.archdock.display.DisplayWatcher
import dev.archdock.display.ExternalDisplay
import dev.archdock.display.PerfOverlayWindow
import dev.archdock.gpu.GpuManager
import dev.archdock.input.InputBridge
import dev.archdock.perf.NativePerf
import dev.archdock.perf.PerfMonitor
import dev.archdock.proot.ManagedProcess
import dev.archdock.proot.ProotRunner
import dev.archdock.settings.DisplayBackend
import dev.archdock.settings.Settings
import dev.archdock.settings.SettingsRepo
import dev.archdock.x11.DisplayServer
import dev.archdock.x11.EmbeddedX11Server
import dev.archdock.x11.VncServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

enum class Phase { IDLE, STARTING, RUNNING, DETACHED, STOPPING, ERROR }

data class SessionState(
    val phase: Phase = Phase.IDLE,
    val backend: DisplayServer.Kind? = null,
    val displayName: String? = null,
    val mode: DisplayModeInfo? = null,
    val dpi: Int = 96,
    val message: String = "",
    val error: String? = null,
    val restarts: Int = 0,
    val phantomKilled: Boolean = false,
) {
    val active get() = phase == Phase.STARTING || phase == Phase.RUNNING || phase == Phase.DETACHED
}

/**
 * Orchestrates one desktop session: X server → GPU probe → desktop (dbus,
 * PulseAudio, MATE via start-desktop) → audio bridge → presentation on the TV.
 * A supervisor restarts crashed components with exponential backoff and
 * flags SIGKILLs that look like Android's phantom process killer.
 */
class SessionController(
    private val context: Context,
    private val paths: Paths,
    private val proot: ProotRunner,
    private val settings: SettingsRepo,
    private val gpu: GpuManager,
    private val audio: AudioBridge,
    private val input: InputBridge,
    private val perf: PerfMonitor,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Android → Linux clipboard (VNC backend; Termux:X11 syncs its own clipboard). */
    val androidClipboard = MutableSharedFlow<String?>(extraBufferCapacity = 4)
    @Volatile var clipboardEnabled = true
    val vncPort = 5900
    /** Preferred mode id for the TV window (read by ExternalWindowConfigurator). */
    @Volatile var preferredModeId: Int? = null

    private val embedded = EmbeddedX11Server(paths)
    private val vnc = VncServer(paths, proot)
    private var server: DisplayServer? = null
    private var serverProc: ManagedProcess? = null
    private var desktopProc: ManagedProcess? = null
    private var supervisor: Job? = null
    private var currentDisplay: ExternalDisplay? = null
    private var overlay: PerfOverlayWindow? = null
    private var overlayJob: Job? = null
    @Volatile private var stopping = false
    private val mutex = Mutex()
    private val failures = ArrayDeque<Long>()

    private fun set(transform: (SessionState) -> SessionState) = _state.update(transform)

    suspend fun startOrAttach(ext: ExternalDisplay?) = mutex.withLock {
        when (_state.value.phase) {
            Phase.RUNNING -> if (ext != null && ext.id != currentDisplay?.id) attachLocked(ext)
            Phase.DETACHED -> if (ext != null) attachLocked(ext)
            Phase.IDLE, Phase.ERROR -> startLocked(ext)
            else -> {}
        }
    }

    suspend fun detach() = mutex.withLock {
        if (_state.value.phase != Phase.RUNNING) return@withLock
        server?.dismiss(context)
        hideOverlay()
        currentDisplay = null
        set { it.copy(phase = Phase.DETACHED, displayName = null, message = "Running in the background – reconnect the display to resume") }
        Logs.i(Logs.Channel.SESSION, "display gone; session keeps running")
    }

    suspend fun stop() = mutex.withLock { stopLocked() }

    suspend fun restart() = mutex.withLock {
        val ext = currentDisplay
        stopLocked()
        startLocked(ext)
    }

    private suspend fun startLocked(ext: ExternalDisplay?) = withContext(Dispatchers.IO) {
        stopping = false
        val s = settings.current()
        clipboardEnabled = s.clipboardSync
        val mode = ext?.let { DisplayWatcher.pickMode(it, s.resolution, s.refreshRate) }
            ?: DisplayModeInfo(0, 1920, 1080, 60f)
        val dpi = if (s.dpi > 0) s.dpi else ext?.let { DisplayWatcher.autoDpi(it, mode) } ?: 96
        preferredModeId = mode.id.takeIf { ext != null }
        set { SessionState(phase = Phase.STARTING, mode = mode, dpi = dpi, displayName = ext?.name, message = "Starting X server…") }
        try {
            prepareRuntime()
            startServer(s, mode, dpi)
            set { it.copy(message = "Checking GPU…") }
            val env = chooseGpuEnv(s)
            writeSessionEnv(s, mode, dpi, env)
            set { it.copy(message = "Starting MATE…") }
            startDesktop(s)
            if (s.audio) audio.start()
            perf.start(scope)
            if (ext != null) attachLocked(ext) else set { it.copy(phase = Phase.DETACHED, message = "Running without a display") }
            supervise()
            scope.launch { pinThreads(s) }
        } catch (e: Exception) {
            Logs.e(Logs.Channel.SESSION, "session start failed", e)
            stopLocked()
            set { it.copy(phase = Phase.ERROR, error = e.message ?: e.toString(), message = "Start failed") }
        }
    }

    private suspend fun attachLocked(ext: ExternalDisplay) {
        val s = settings.current()
        currentDisplay = ext
        val srv = server ?: return
        val mode = DisplayWatcher.pickMode(ext, s.resolution, s.refreshRate)
        preferredModeId = mode.id
        withContext(Dispatchers.Main) {
            try {
                srv.present(context, ext.id)
            } catch (e: SecurityException) {
                // Some builds refuse third-party launches on secondary displays.
                Logs.w(Logs.Channel.SESSION, "launch on display ${ext.id} refused: ${e.message}")
            }
            if (s.perfOverlay && srv.kind == DisplayServer.Kind.EMBEDDED_X11) showOverlay(ext.id)
        }
        set { it.copy(phase = Phase.RUNNING, displayName = ext.name, message = "Running on ${ext.name}") }
        Logs.i(Logs.Channel.SESSION, "presenting on ${ext.name} (${ext.id}) via ${srv.kind}")
    }

    private fun prepareRuntime() {
        val rt = paths.runtimeDir
        rt.mkdirs()
        File(rt, "runtime-user").apply { mkdirs(); setReadable(true, true); setWritable(true, true); setExecutable(true, true) }
        paths.guestTmp.setWritable(true, false)
        // Stale sockets/locks from a crash would make the new servers bail out.
        listOf("dbus.env", "desktop.ready", "compositor.env", "request-stop", "request-restart").forEach { File(rt, it).delete() }
    }

    private suspend fun startServer(s: Settings, mode: DisplayModeInfo, dpi: Int) {
        val wanted = when (s.backend) {
            DisplayBackend.X11 -> listOf(embedded)
            DisplayBackend.VNC -> listOf(vnc)
            DisplayBackend.AUTO -> listOf(embedded, vnc)
        }.filter { it.isAvailable() }
        if (wanted.isEmpty()) throw IOException("No display backend available (Termux:X11 not embedded and Xvnc not installed)")
        var last: Exception? = null
        for (candidate in wanted) {
            try {
                val p = candidate.start(mode, dpi)
                waitForSocket(candidate.displayNumber, p)
                server = candidate; serverProc = p
                input.display = candidate.displayNumber
                input.reset()
                set { it.copy(backend = candidate.kind) }
                Logs.i(Logs.Channel.SESSION, "X server up: ${candidate.kind} ${mode.width}x${mode.height} @ $dpi dpi")
                return
            } catch (e: Exception) {
                Logs.w(Logs.Channel.SESSION, "${candidate.kind} failed: ${e.message}; trying next backend")
                last = e
            }
        }
        throw IOException("X server failed to start: ${last?.message}", last)
    }

    private suspend fun waitForSocket(display: Int, p: ManagedProcess) {
        val sock = paths.x11Socket(display)
        repeat(150) {
            if (sock.exists()) { delay(150); return }
            if (!p.isAlive) throw IOException("X server exited (${p.exitCode}): ${p.lastLines.takeLast(3).joinToString(" | ")}")
            delay(100)
        }
        p.stop()
        throw IOException("X server did not create ${sock.name} within 15 s")
    }

    /** Starts the GPU server and probes it; falls back VULKAN_WRAPPER → VIRGL → LLVMPIPE. */
    private suspend fun chooseGpuEnv(s: Settings): Map<String, String> {
        val mode = gpu.negotiate(s.gpuMode, server?.displayNumber ?: 0)
        if (mode != s.gpuMode) Logs.w(Logs.Channel.GPU, "GPU: ${s.gpuMode.name} unavailable, using ${mode.name}")
        return gpu.sessionEnv(mode)
    }

    private fun writeSessionEnv(s: Settings, mode: DisplayModeInfo, dpi: Int, gpuEnv: Map<String, String>) {
        val env = linkedMapOf(
            "DISPLAY" to ":${server?.displayNumber ?: 0}",
            "ARCHDOCK_BACKEND" to (server?.kind?.name ?: ""),
            "ARCHDOCK_WIDTH" to mode.width.toString(),
            "ARCHDOCK_HEIGHT" to mode.height.toString(),
            "ARCHDOCK_REFRESH" to mode.refresh.toInt().toString(),
            "ARCHDOCK_DPI" to dpi.toString(),
            "GDK_SCALE" to s.uiScale.toString(),
            "QT_SCALE_FACTOR" to s.uiScale.toString(),
            "ARCHDOCK_THEME" to s.theme,
            "ARCHDOCK_COMPOSITOR" to if (s.compositor) "1" else "0",
            "ARCHDOCK_BLUR" to if (s.blur) "1" else "0",
            "ARCHDOCK_SHADOWS" to if (s.shadows) "1" else "0",
        )
        env.putAll(gpuEnv)
        File(paths.runtimeDir, "session.env").writeText(
            env.entries.joinToString("") { (k, v) -> "export $k='${v.replace("'", "")}'\n" }
        )
        sessionEnv = env
    }

    /** Last written session environment (used for live theme switching / GPU test). */
    @Volatile var sessionEnv: Map<String, String> = emptyMap(); private set

    val liveDisplay: Int? get() = if (_state.value.active && serverProc?.isAlive == true) server?.displayNumber else null

    private fun startDesktop(s: Settings) {
        desktopProc = proot.spawn(
            "desktop", listOf("/usr/local/bin/start-desktop"),
            ProotRunner.Options(asUser = true, sharedDownloads = s.sharedFolder, env = mapOf("DISPLAY" to ":${server?.displayNumber ?: 0}")),
            Logs.Channel.SESSION,
        )
    }

    private fun supervise() {
        supervisor?.cancel()
        supervisor = scope.launch(Dispatchers.IO) {
            var backoff = 1000L
            var healthySince = System.currentTimeMillis()
            while (isActive && !stopping) {
                delay(1000)
                if (stopping) break
                // Requests from inside the desktop (ArchDock menu → Power).
                if (File(paths.runtimeDir, "request-stop").delete()) { scope.launch { stop() }; break }
                if (File(paths.runtimeDir, "request-restart").delete()) { scope.launch { restart() }; break }
                val srvDead = serverProc?.isAlive == false
                val deskDead = desktopProc?.isAlive == false
                if (!srvDead && !deskDead) {
                    if (System.currentTimeMillis() - healthySince > 60_000) backoff = 1000L
                    continue
                }
                val code = if (srvDead) serverProc?.exitCode else desktopProc?.exitCode
                val who = if (srvDead) "X server" else "desktop"
                if (PhantomProcessGuard.looksKilled(code)) {
                    Logs.e(Logs.Channel.SESSION, "$who was SIGKILLed – likely Android's phantom process killer")
                    set { it.copy(phantomKilled = true) }
                    PhantomNotifier.notify(context)
                } else Logs.w(Logs.Channel.SESSION, "$who exited with $code; restarting in ${backoff}ms")
                if (!recordFailure()) {
                    set { it.copy(phase = Phase.ERROR, error = "$who keeps crashing (exit $code). See Logs.") }
                    mutex.withLock { stopLocked(keepState = true, fromSupervisor = true) }
                    break
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
                mutex.withLock {
                    if (stopping) return@withLock
                    val s = settings.current()
                    val mode = _state.value.mode ?: DisplayModeInfo(0, 1920, 1080, 60f)
                    if (srvDead) {
                        desktopProc?.stop(); desktopProc = null
                        startServer(s, mode, _state.value.dpi)
                        currentDisplay?.let { attachLocked(it) }
                    }
                    startDesktop(s)
                    set { it.copy(restarts = it.restarts + 1) }
                }
                healthySince = System.currentTimeMillis()
            }
        }
    }

    /** Returns false when there were too many failures in the last two minutes. */
    private fun recordFailure(): Boolean {
        val now = System.currentTimeMillis()
        failures.addLast(now)
        while (failures.isNotEmpty() && now - failures.first() > 120_000) failures.removeFirst()
        return failures.size <= 6
    }

    private suspend fun pinThreads(s: Settings) {
        if (!s.pinBigCores) return
        val mask = NativePerf.bigCoreMask()
        delay(2000)
        serverProc?.pid?.let { NativePerf.pin(it, mask) }
        gpu.serverPid?.let { NativePerf.pin(it, mask) }
        NativePerf.findPids("Xvnc").forEach { NativePerf.pin(it, mask) }
        delay(15_000) // picom starts with the session
        NativePerf.findPids("picom").forEach { NativePerf.pin(it, mask) }
        Logs.i(Logs.Channel.SESSION, "pinned X server / compositor to CPU mask 0x${mask.toString(16)}")
    }

    private fun showOverlay(displayId: Int) {
        val o = overlay ?: PerfOverlayWindow(context).also { overlay = it }
        o.show(displayId)
        overlayJob?.cancel()
        overlayJob = scope.launch { perf.sample.collect { o.update(it) } }
    }

    private fun hideOverlay() {
        overlayJob?.cancel(); overlayJob = null
        overlay?.hide()
    }

    private suspend fun stopLocked(keepState: Boolean = false, fromSupervisor: Boolean = false) = withContext(Dispatchers.IO) {
        stopping = true
        if (!keepState) set { it.copy(phase = Phase.STOPPING, message = "Stopping…") }
        if (!fromSupervisor) supervisor?.cancel()
        supervisor = null
        audio.stop()
        perf.stop()
        withContext(Dispatchers.Main) { hideOverlay(); server?.dismiss(context) }
        input.reset()
        // Ask the session to log out cleanly first, then tear everything down.
        if (desktopProc?.isAlive == true) {
            runCatching {
                proot.capture(listOf("/bin/bash", "-c", "pkill -TERM -u user mate-session; sleep 1; pkill -TERM -u user pulseaudio"),
                    ProotRunner.Options(asUser = true))
            }
        }
        desktopProc?.stop(); desktopProc = null
        serverProc?.stop(); serverProc = null
        killStragglers()
        server = null
        currentDisplay = null
        gpu.clearActive()
        if (!keepState) set { SessionState(phase = Phase.IDLE, message = "Stopped") }
        Logs.i(Logs.Channel.SESSION, "session stopped")
    }

    /** Anything of ours still running from the rootfs (orphans after a crash). */
    private fun killStragglers() {
        val me = android.os.Process.myPid()
        val needles = listOf(paths.proot.absolutePath, "CmdEntryPoint", paths.rootfs.absolutePath)
        File("/proc").listFiles()?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            if (pid == me) return@forEach
            val cmd = runCatching { File(d, "cmdline").readText() }.getOrNull() ?: return@forEach
            if (needles.any { cmd.contains(it) }) runCatching { android.os.Process.killProcess(pid) }
        }
    }
}
