package dev.archdock.gpu

import android.content.Context
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import dev.archdock.core.Logs
import dev.archdock.core.Paths
import dev.archdock.proot.ManagedProcess
import dev.archdock.proot.ProotRunner
import dev.archdock.settings.GpuMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class GpuStatus(
    val androidRenderer: String? = null,
    val androidVulkan: String? = null,
    /** Android-side passthrough (virglrenderer + Mesa wrapper ICD) bundled in the APK. */
    val serverBundled: Boolean = false,
    /** Guest-side Venus ICD JSON installed. */
    val wrapperInstalled: Boolean = false,
    /** What the running desktop actually uses, e.g. "zink (Venus → Mali-G715)" or "llvmpipe". */
    val activeRenderer: String? = null,
    val activeMode: GpuMode? = null,
    val vulkanDevice: String? = null,
    val fallbackReason: String? = null,
)

/**
 * GPU strategy for Mali-G715 (Tensor G4) under proot.
 *
 * Constraints: Turnip is Adreno-only; Panfrost needs /dev/dri and a kernel
 * driver Android doesn't expose; and the guest is glibc while Android's
 * libvulkan.so and the Mali driver are bionic, so no Android GPU library can
 * be dlopen()ed inside the guest.
 *
 * So the passthrough is split at a socket:
 *   guest app ─ Vulkan ─▶ Mesa Venus ICD (VN_DEBUG=vtest)
 *             ─ vtest socket /tmp/.virgl_test ─▶
 *   Android:  virgl_test_server --venus (bionic, from the APK)
 *             ─▶ Mesa "wrapper" Vulkan ICD (bionic) ─▶ /system/lib64/libvulkan.so ─▶ Mali
 * OpenGL runs on Zink over that Vulkan device. Fallbacks: VirGL (GL over
 * the same server using Mali GLES), then llvmpipe/lavapipe.
 */
class GpuManager(private val context: Context, private val paths: Paths, private val proot: ProotRunner) {
    companion object {
        /** Our ICD JSON in the guest; points at Mesa's Venus driver. */
        const val GUEST_ICD = "/usr/share/vulkan/icd.d/archdock_wrapper_icd.aarch64.json"
        const val LAVAPIPE_ICD = "/usr/share/vulkan/icd.d/lvp_icd.aarch64.json"
        const val SOCKET = ".virgl_test"
    }

    private val _status = MutableStateFlow(GpuStatus())
    val status: StateFlow<GpuStatus> = _status.asStateFlow()
    private var server: ManagedProcess? = null

    private val serverBinary get() = File(paths.nativeLibDir, "libvirgl_test_server.so")
    private val wrapperLib get() = File(paths.nativeLibDir, "libvulkan_wrapper.so")
    val serverBundled get() = serverBinary.canExecute()
    val wrapperInstalled get() = File(paths.rootfs, GUEST_ICD.removePrefix("/")).exists()

    /** Host-side check shown on the idle screen. */
    fun quickCheck(): GpuStatus {
        val vk = context.packageManager.systemAvailableFeatures
            .firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
        val vkVersion = vk?.version?.let { "Vulkan ${it ushr 22}.${(it ushr 12) and 0x3FF}" }
        val s = _status.value.copy(
            androidRenderer = _status.value.androidRenderer ?: runCatching { glRenderer() }.getOrNull(),
            androidVulkan = vkVersion ?: "no Vulkan",
            serverBundled = serverBundled,
            wrapperInstalled = wrapperInstalled,
        )
        _status.value = s
        return s
    }

    private fun glRenderer(): String? {
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) return null
        try {
            val configs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
            EGL14.eglChooseConfig(dpy, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0, configs, 0, 1, n, 0)
            if (n[0] == 0) return null
            val ctx = EGL14.eglCreateContext(dpy, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            val surf = EGL14.eglCreatePbufferSurface(dpy, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(dpy, surf, surf, ctx)
            val r = GLES20.glGetString(GLES20.GL_RENDERER)
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(dpy, surf); EGL14.eglDestroyContext(dpy, ctx)
            return r
        } finally {
            EGL14.eglTerminate(dpy)
        }
    }

    /** Starts the Android-side virglrenderer server for [mode] (no-op for llvmpipe). */
    suspend fun startServer(mode: GpuMode): Boolean {
        stopServer()
        if (mode == GpuMode.LLVMPIPE) return true
        if (!serverBundled) {
            Logs.w(Logs.Channel.GPU, "virgl_test_server not bundled (run scripts/build-gpu.sh before building the APK)")
            return false
        }
        val sock = File(paths.guestTmp, SOCKET).apply { delete() }
        val icdDir = File(paths.files, "vulkan").apply { mkdirs() }
        // ICD manifest for the bundled Khronos loader → Mesa wrapper → system libvulkan.so.
        val icd = File(icdDir, "wrapper_icd.json").apply {
            writeText("""{"file_format_version":"1.0.1","ICD":{"library_path":"${wrapperLib.absolutePath}","api_version":"1.3.0"}}""")
        }
        val args = mutableListOf(serverBinary.absolutePath, "--use-gles", "--socket-path", sock.absolutePath)
        if (mode == GpuMode.VULKAN_WRAPPER) args += "--venus"
        server = ManagedProcess.startHost("virgl", args, mapOf(
            "LD_LIBRARY_PATH" to paths.nativeLibDir.absolutePath,
            "VK_ICD_FILENAMES" to icd.absolutePath,
            "VK_DRIVER_FILES" to icd.absolutePath,
            "TMPDIR" to paths.guestTmp.absolutePath,
            "MESA_VK_WSI_PRESENT_MODE" to "mailbox",
        ), Logs.Channel.GPU)
        repeat(50) {
            if (sock.exists()) return true
            if (server?.isAlive != true) return false
            delay(100)
        }
        return sock.exists()
    }

    fun stopServer() { server?.stop(); server = null }
    val serverPid get() = server?.pid

    /** Environment for the desktop session (written to /tmp/archdock/session.env). */
    fun sessionEnv(mode: GpuMode): Map<String, String> = when (mode) {
        GpuMode.VULKAN_WRAPPER -> mapOf(
            "VK_ICD_FILENAMES" to GUEST_ICD,
            "VK_DRIVER_FILES" to GUEST_ICD,
            "VN_DEBUG" to "vtest",
            "VTEST_SOCKET_NAME" to "/tmp/$SOCKET",
            "MESA_LOADER_DRIVER_OVERRIDE" to "zink",
            "GALLIUM_DRIVER" to "zink",
            "ZINK_DESCRIPTORS" to "lazy",
            // No DRI3/dma-buf through vtest: present through the CPU copy path.
            "MESA_VK_WSI_DEBUG" to "sw",
            "LIBGL_DRI3_DISABLE" to "1",
            "MESA_SHADER_CACHE_DIR" to "/home/user/.cache/mesa_shader_cache",
            "ARCHDOCK_RENDERER" to "zink",
        )
        GpuMode.VIRGL -> mapOf(
            "VK_ICD_FILENAMES" to LAVAPIPE_ICD,
            "GALLIUM_DRIVER" to "virpipe",
            "MESA_LOADER_DRIVER_OVERRIDE" to "virpipe",
            "VTEST_SOCKET_NAME" to "/tmp/$SOCKET",
            "MESA_GL_VERSION_OVERRIDE" to "4.0",
            "ARCHDOCK_RENDERER" to "virpipe",
        )
        GpuMode.LLVMPIPE -> mapOf(
            "VK_ICD_FILENAMES" to LAVAPIPE_ICD,
            "GALLIUM_DRIVER" to "llvmpipe",
            "LIBGL_ALWAYS_SOFTWARE" to "1",
            "LP_NUM_THREADS" to "4",
            "ARCHDOCK_RENDERER" to "llvmpipe",
        )
    }

    data class Probe(val ok: Boolean, val renderer: String?, val vulkanDevice: String?, val log: String)

    /**
     * Runs /opt/archdock/bin/archdock-gpu-probe in the guest against the live
     * X display: Vulkan device (for the wrapper path) and a real GL context.
     */
    suspend fun probe(mode: GpuMode, display: Int): Probe {
        val (code, out) = proot.capture(
            listOf("/opt/archdock/bin/archdock-gpu-probe", mode.name),
            ProotRunner.Options(asUser = true, env = sessionEnv(mode) + ("DISPLAY" to ":$display")),
        )
        val kv = out.lines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
        val ok = code == 0 && kv["gl_ok"] == "1" && (mode != GpuMode.VULKAN_WRAPPER || kv["vk_ok"] == "1")
        Logs.i(Logs.Channel.GPU, "probe ${mode.name}: ok=$ok gl=${kv["gl_renderer"]} vk=${kv["vk_device"]}")
        return Probe(ok, kv["gl_renderer"], kv["vk_device"], out)
    }

    /**
     * Picks the best working mode, starting from [preferred] and falling back
     * VULKAN_WRAPPER → VIRGL → LLVMPIPE. Leaves the matching server running.
     */
    suspend fun negotiate(preferred: GpuMode, display: Int): GpuMode {
        val chain = GpuMode.entries.dropWhile { it != preferred }
        var reason: String? = null
        for (mode in chain) {
            if (mode == GpuMode.LLVMPIPE) break
            if (!startServer(mode)) { reason = "${mode.label}: GPU server unavailable"; continue }
            val p = probe(mode, display)
            if (p.ok) {
                _status.value = _status.value.copy(activeMode = mode, vulkanDevice = p.vulkanDevice,
                    activeRenderer = p.renderer ?: mode.label, fallbackReason = reason, wrapperInstalled = wrapperInstalled)
                return mode
            }
            reason = "${mode.label}: probe failed (${p.log.lines().firstOrNull { it.startsWith("error=") }?.removePrefix("error=") ?: "see Logs → GPU"})"
            Logs.w(Logs.Channel.GPU, "$reason\n${p.log}")
        }
        stopServer()
        _status.value = _status.value.copy(activeMode = GpuMode.LLVMPIPE, activeRenderer = "llvmpipe",
            vulkanDevice = "lavapipe", fallbackReason = if (preferred == GpuMode.LLVMPIPE) null else reason)
        return GpuMode.LLVMPIPE
    }

    fun clearActive() {
        stopServer()
        _status.value = _status.value.copy(activeRenderer = null, activeMode = null, fallbackReason = null)
    }

    /** "GPU Test": vulkaninfo --summary and glxinfo -B, on the live display or a private Xvfb. */
    suspend fun gpuTest(mode: GpuMode, liveDisplay: Int?): String {
        val startedHere = liveDisplay == null || _status.value.activeMode != mode
        if (startedHere && mode != GpuMode.LLVMPIPE && !startServer(mode))
            return "Could not start the Android-side GPU server for ${mode.label}.\n" +
                "Is libvirgl_test_server.so bundled? (scripts/build-gpu.sh)"
        val env = sessionEnv(mode) + (liveDisplay?.let { mapOf("DISPLAY" to ":$it") } ?: emptyMap())
        val glCmd = if (liveDisplay != null) "glxinfo -B" else "xvfb-run -a -s '-screen 0 1280x720x24' glxinfo -B"
        val script = """
            echo "### GPU mode: ${mode.label}"
            echo "### VK_ICD_FILENAMES=${'$'}VK_ICD_FILENAMES GALLIUM_DRIVER=${'$'}GALLIUM_DRIVER"
            echo; echo "### vulkaninfo --summary"
            timeout 30 vulkaninfo --summary 2>&1 | head -80 || echo "vulkaninfo failed (exit ${'$'}?)"
            echo; echo "### $glCmd"
            timeout 30 $glCmd 2>&1 | head -60 || echo "glxinfo failed (exit ${'$'}?)"
        """.trimIndent()
        val (_, out) = proot.capture(listOf("/bin/bash", "-c", script), ProotRunner.Options(asUser = true, env = env))
        if (startedHere && liveDisplay != null && _status.value.activeMode != null) startServer(_status.value.activeMode!!)
        else if (startedHere && liveDisplay == null) stopServer()
        Logs.i(Logs.Channel.GPU, out)
        return out
    }
}
