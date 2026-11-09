package dev.archdock.installer

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.StatFs
import dev.archdock.core.Logs
import dev.archdock.core.Paths
import dev.archdock.proot.ProotRunner
import dev.archdock.settings.SettingsRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.TimeZone

enum class StepStatus { PENDING, RUNNING, DONE, FAILED, SKIPPED }

data class InstallStep(
    val id: String,
    val title: String,
    /** Guest script under /opt/archdock/install, or null for host-side steps. */
    val script: String? = null,
    /** Relative share of the overall progress bar. */
    val weight: Float = 1f,
)

data class StepState(val step: InstallStep, val status: StepStatus, val detail: String = "")

data class InstallState(
    val running: Boolean = false,
    val steps: List<StepState> = emptyList(),
    val current: Int = -1,
    val overall: Float = 0f,
    val stepProgress: Float? = null,
    val message: String = "",
    val error: String? = null,
    val installed: Boolean = false,
    val checks: List<PreflightCheck> = emptyList(),
)

data class PreflightCheck(val name: String, val ok: Boolean, val detail: String, val fatal: Boolean = true)

/**
 * The one-time guided installer. Every step is checkpointed; steps are
 * idempotent so an interrupted install resumes at the first unfinished step.
 */
class InstallerManager(
    private val context: Context,
    private val paths: Paths,
    private val proot: ProotRunner,
    private val settings: SettingsRepo,
) {
    companion object {
        const val TARBALL = "ArchLinuxARM-aarch64-latest.tar.gz"
        const val MIN_FREE_BYTES = 10L * 1024 * 1024 * 1024
        /** "Arch Linux ARM Build System <builder@archlinuxarm.org>" */
        const val ALARM_SIGNING_KEY = "68B3537F39A313B3E574D06777193F152BDBE6A6"
        val FALLBACK_MIRRORS = listOf(
            "http://os.archlinuxarm.org/os/",
            "http://fl.us.mirror.archlinuxarm.org/os/",
            "http://de.mirror.archlinuxarm.org/os/",
            "http://dk.mirror.archlinuxarm.org/os/",
        )

        val STEPS = listOf(
            InstallStep("preflight", "Check device and storage", weight = 0.2f),
            InstallStep("download", "Download Arch Linux ARM", weight = 4f),
            InstallStep("verify", "Verify checksum", weight = 0.6f),
            InstallStep("extract", "Extract root filesystem", weight = 3f),
            InstallStep("prepare", "Prepare rootfs for proot", weight = 0.3f),
            InstallStep("keys", "Initialise pacman keyring", "10-pacman-keys.sh", 1f),
            InstallStep("upgrade", "Full system upgrade (pacman -Syu)", "20-upgrade.sh", 4f),
            InstallStep("packages", "Install MATE, Firefox, Mesa and tools", "30-packages.sh", 12f),
            InstallStep("configure", "Create user, locales, timezone, proot fixes", "40-configure.sh", 1f),
            InstallStep("gpu", "Set up Vulkan wrapper + Zink", "50-gpu.sh", 3f),
            InstallStep("theming", "Apply Omarchy theming", "60-theming.sh", 3f),
            InstallStep("shell", "Shell tools, Neovim + LazyVim", "70-shell.sh", 2f),
            InstallStep("finalize", "Finish", "90-finalize.sh", 0.3f),
        )
        private val PACMAN_PROGRESS = Regex("""\((\s*\d+)/(\s*\d+)\)""")
        private val SCRIPT_PROGRESS = Regex("""^@@progress\s+(\d+)\s*(.*)$""")
    }

    private val checkpoints = CheckpointStore(paths.state)
    private val downloader = Downloader()
    private val mutex = Mutex()

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<InstallState> = _state.asStateFlow()

    val isInstalled: Boolean get() = paths.installedFlag.exists() && File(paths.rootfs, "usr/bin/bash").exists()

    private fun initialState(): InstallState {
        val done = checkpoints.completed()
        return InstallState(
            steps = STEPS.map { StepState(it, if (it.id in done) StepStatus.DONE else StepStatus.PENDING) },
            installed = isInstalledQuick(),
            overall = progressFor(done),
        )
    }

    private fun isInstalledQuick() = paths.installedFlag.exists()

    private fun progressFor(done: Set<String>): Float {
        val total = STEPS.sumOf { it.weight.toDouble() }.toFloat()
        return STEPS.filter { it.id in done }.sumOf { it.weight.toDouble() }.toFloat() / total
    }

    fun refresh() { if (!_state.value.running) _state.value = initialState() }

    /** Clears checkpoints so the guest configuration steps run again; keeps the download. */
    fun prepareRepair() {
        listOf("prepare", "keys", "upgrade", "packages", "configure", "gpu", "theming", "shell", "finalize")
            .forEach { checkpoints.clear(it) }
        paths.installedFlag.delete()
        refresh()
    }

    /** Deletes the rootfs and every checkpoint. */
    suspend fun prepareReinstall() = withContext(Dispatchers.IO) {
        Logs.i(Logs.Channel.INSTALL, "Reinstall requested: deleting rootfs")
        deleteTree(paths.rootfs)
        deleteTree(paths.rootfsStaging)
        checkpoints.clearAll()
        paths.installedFlag.delete()
        refresh()
    }

    fun preflightChecks(): List<PreflightCheck> {
        val checks = mutableListOf<PreflightCheck>()
        val arm64 = Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")
        checks += PreflightCheck("CPU architecture", arm64, Build.SUPPORTED_ABIS.joinToString())
        checks += PreflightCheck(
            "Android version", Build.VERSION.SDK_INT >= 31,
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        )
        val free = StatFs(paths.files.absolutePath).availableBytes
        val alreadyExtracted = File(paths.rootfs, "usr/bin").exists()
        checks += PreflightCheck(
            "Free storage", free >= MIN_FREE_BYTES || alreadyExtracted,
            "%.1f GB free, 10 GB needed".format(free / 1e9),
            fatal = !alreadyExtracted,
        )
        checks += PreflightCheck(
            "proot binary", proot.isAvailable,
            if (proot.isAvailable) paths.proot.name else "libproot.so missing – run scripts/build-proot.sh before building the APK"
        )
        val soc = "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
        checks += PreflightCheck("Device", true, "${Build.MANUFACTURER} ${Build.MODEL} · $soc", fatal = false)
        return checks
    }

    /** Runs (or resumes) the installer. Safe to call repeatedly. */
    suspend fun run(): Boolean = mutex.withLock {
        paths.ensureDirs()
        _state.update { initialState().copy(running = true, error = null) }
        try {
            STEPS.forEachIndexed { index, step ->
                if (checkpoints.isDone(step.id)) return@forEachIndexed
                setStep(index, StepStatus.RUNNING, "")
                Logs.i(Logs.Channel.INSTALL, "=== ${step.title} ===")
                runStep(step)
                checkpoints.markDone(step.id)
                setStep(index, StepStatus.DONE, "")
            }
            _state.update { it.copy(running = false, installed = true, overall = 1f, message = "Installed", current = -1) }
            true
        } catch (e: CancellationException) {
            Logs.w(Logs.Channel.INSTALL, "Installer interrupted; it will resume from the last completed step")
            _state.update { it.copy(running = false, message = "Paused") }
            throw e
        } catch (e: Throwable) {
            Logs.e(Logs.Channel.INSTALL, "Installer failed", e)
            val idx = _state.value.current
            if (idx >= 0) setStep(idx, StepStatus.FAILED, e.message ?: e.javaClass.simpleName)
            _state.update { it.copy(running = false, error = e.message ?: e.toString()) }
            false
        }
    }

    private fun setStep(index: Int, status: StepStatus, detail: String) {
        _state.update { s ->
            val steps = s.steps.toMutableList()
            steps[index] = steps[index].copy(status = status, detail = detail)
            val done = steps.filter { it.status == StepStatus.DONE }.map { it.step.id }.toSet()
            s.copy(
                steps = steps, current = index, overall = progressFor(done),
                stepProgress = if (status == StepStatus.RUNNING) null else s.stepProgress,
                message = if (status == StepStatus.RUNNING) steps[index].step.title else s.message,
            )
        }
    }

    private fun stepProgress(fraction: Float?, message: String? = null) {
        _state.update { s ->
            val total = STEPS.sumOf { it.weight.toDouble() }.toFloat()
            val cur = s.steps.getOrNull(s.current)?.step
            val base = progressFor(s.steps.filter { it.status == StepStatus.DONE }.map { it.step.id }.toSet())
            val extra = if (cur != null && fraction != null) cur.weight * fraction / total else 0f
            s.copy(stepProgress = fraction, overall = base + extra, message = message ?: s.message)
        }
    }

    private suspend fun runStep(step: InstallStep) {
        when (step.id) {
            "preflight" -> preflight()
            "download" -> download()
            "verify" -> verify()
            "extract" -> extract()
            "prepare" -> prepare()
            else -> guestScript(step)
        }
    }

    private fun preflight() {
        val checks = preflightChecks()
        _state.update { it.copy(checks = checks) }
        checks.forEach { Logs.i(Logs.Channel.INSTALL, "${if (it.ok) "✓" else "✗"} ${it.name}: ${it.detail}") }
        val failed = checks.filter { !it.ok && it.fatal }
        if (failed.isNotEmpty()) throw IOException("Preflight failed: " + failed.joinToString { "${it.name} (${it.detail})" })
    }

    private suspend fun mirrors(): List<String> {
        val configured = settings.current().mirror.let { if (it.endsWith("/")) it else "$it/" }
        return (listOf(configured) + FALLBACK_MIRRORS).distinct()
    }

    private suspend fun download() {
        val dest = File(paths.downloads, TARBALL)
        var lastError: Exception? = null
        for (mirror in mirrors()) {
            try {
                Logs.i(Logs.Channel.INSTALL, "Mirror: $mirror")
                val md5 = downloader.fetchText(mirror + TARBALL + ".md5")
                File(paths.downloads, "$TARBALL.md5").writeText(md5)
                runCatching {
                    downloader.download(mirror + TARBALL + ".sig", File(paths.downloads, "$TARBALL.sig.part")) { _, _ -> }
                    File(paths.downloads, "$TARBALL.sig.part").renameTo(File(paths.downloads, "$TARBALL.sig"))
                }
                downloader.download(mirror + TARBALL, dest) { done, total ->
                    val mb = done / 1_048_576
                    stepProgress(if (total > 0) done.toFloat() / total else null,
                        if (total > 0) "Downloading… $mb / ${total / 1_048_576} MB" else "Downloading… $mb MB")
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(Logs.Channel.INSTALL, "Mirror $mirror failed: ${e.message}")
                lastError = e
            }
        }
        throw IOException("All mirrors failed", lastError)
    }

    private suspend fun verify() = withContext(Dispatchers.IO) {
        val tar = File(paths.downloads, TARBALL)
        val expected = Checksums.parse(File(paths.downloads, "$TARBALL.md5").readText(), TARBALL)
            ?: throw IOException("Could not parse checksum file")
        val algo = Checksums.algorithmFor(expected)
        val total = tar.length()
        val actual = Checksums.digest(tar, algo) { stepProgress(it.toFloat() / total, "Verifying ($algo)…") }
        if (actual != expected) {
            tar.delete()
            checkpoints.clear("download")
            throw IOException("Checksum mismatch ($algo): expected $expected, got $actual. The download was deleted; retry to download it again.")
        }
        Logs.i(Logs.Channel.INSTALL, "$algo OK: $actual")
    }

    private suspend fun extract() = withContext(Dispatchers.IO) {
        if (File(paths.rootfs, "usr/bin/bash").exists()) {
            Logs.i(Logs.Channel.INSTALL, "Rootfs already present, skipping extraction"); return@withContext
        }
        deleteTree(paths.rootfsStaging)
        val tar = File(paths.downloads, TARBALL)
        val stats = TarExtractor().extract(
            tar, paths.rootfsStaging,
            onProgress = { done, total -> stepProgress(done.toFloat() / total, "Extracting… ${done * 100 / total}%") },
            isCancelled = { false },
        )
        currentCoroutineContext().let { if (!it.isActive) throw CancellationException() }
        Logs.i(Logs.Channel.INSTALL, "Extracted ${stats.files} files, ${stats.dirs} dirs, ${stats.symlinks} symlinks, ${stats.hardlinks} hard links (${stats.skipped} skipped)")
        deleteTree(paths.rootfs)
        if (!paths.rootfsStaging.renameTo(paths.rootfs)) throw IOException("Could not move rootfs into place")
    }

    private suspend fun prepare() = withContext(Dispatchers.IO) {
        val root = paths.rootfs
        File(root, "tmp").apply { mkdirs(); setWritable(true, false); setReadable(true, false); setExecutable(true, false) }
        // DNS: the rootfs points resolv.conf at systemd-resolved, which doesn't run.
        val resolv = File(root, "etc/resolv.conf")
        runCatching { java.nio.file.Files.deleteIfExists(resolv.toPath()) }
        resolv.writeText(dnsServers().joinToString("") { "nameserver $it\n" })
        File(root, "etc/hosts").writeText(
            "127.0.0.1 localhost archdock\n::1 localhost ip6-localhost ip6-loopback\n"
        )
        File(root, "etc/hostname").writeText("archdock\n")
        val pacmanConf = File(root, "etc/pacman.conf")
        if (pacmanConf.exists()) pacmanConf.writeText(PacmanConf.patch(pacmanConf.readText()))
        // Android facts the guest scripts need.
        File(root, "etc/archdock.env").writeText(buildString {
            appendLine("ARCHDOCK_TZ='${TimeZone.getDefault().id}'")
            appendLine("ARCHDOCK_LOCALE='${localeName()}'")
            appendLine("ARCHDOCK_DEVICE='${Build.MANUFACTURER} ${Build.MODEL}'")
            appendLine("ARCHDOCK_SOC='${Build.SOC_MODEL}'")
            appendLine("ARCHDOCK_SIGNING_KEY='$ALARM_SIGNING_KEY'")
        })
        DotfilesInstaller(context).install(File(root, "opt/archdock"))
        Logs.i(Logs.Channel.INSTALL, "Rootfs prepared (DNS: ${dnsServers().joinToString()})")
    }

    private suspend fun guestScript(step: InstallStep) {
        val s = settings.current()
        val env = mapOf(
            "ARCHDOCK_THEME" to s.theme,
            "ARCHDOCK_GPU_MODE" to s.gpuMode.name,
            "ARCHDOCK_TZ" to TimeZone.getDefault().id,
            "ARCHDOCK_LOCALE" to localeName(),
            "ARCHDOCK_DL" to "/mnt/archdock-dl",
            "DEBIAN_FRONTEND" to "noninteractive",
        )
        // Same shell: lib.sh's helpers must be visible to the step script.
        val script = "set -eo pipefail; source /opt/archdock/install/lib.sh; source /opt/archdock/install/${step.script}"
        val code = proot.runScript(
            script,
            ProotRunner.Options(env = env, binds = listOf(paths.downloads.absolutePath to "/mnt/archdock-dl")),
        ) { line ->
            SCRIPT_PROGRESS.find(line)?.let {
                stepProgress(it.groupValues[1].toInt() / 100f, it.groupValues[2].ifBlank { null })
                return@runScript
            }
            if (PACMAN_PROGRESS.containsMatchIn(line)) _state.update { st -> st.copy(message = line.trim().take(120)) }
        }
        if (code != 0) throw IOException("${step.script} exited with code $code (see Logs → Install)")
        if (step.id == "finalize") {
            paths.installedFlag.writeText("${System.currentTimeMillis()}\n")
            runCatching { File(paths.downloads, TARBALL).delete() }
        }
    }

    private fun dnsServers(): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val fromAndroid = runCatching {
            cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.mapNotNull { it.hostAddress }
        }.getOrNull().orEmpty().filter { !it.contains('%') }
        return (fromAndroid + listOf("1.1.1.1", "8.8.8.8")).distinct()
    }

    private fun localeName(): String {
        val l = Locale.getDefault()
        return if (l.country.isNotEmpty()) "${l.language}_${l.country}" else "en_US"
    }

    private fun deleteTree(dir: File) {
        if (!dir.exists()) return
        // walkBottomUp follows no symlinks into the host – File.delete removes the link itself.
        dir.walkBottomUp().onEnter { d -> !java.nio.file.Files.isSymbolicLink(d.toPath()) || run { d.delete(); false } }
            .forEach { f ->
                if (!f.delete()) { f.setWritable(true); f.parentFile?.setWritable(true); f.delete() }
            }
    }
}

/** Edits to /etc/pacman.conf that make pacman work under proot. Idempotent. */
object PacmanConf {
    private val ADDITIONS = linkedMapOf(
        // pacman 7's download sandbox (landlock + DownloadUser) cannot work in proot.
        "DisableSandbox" to "DisableSandbox",
        "ParallelDownloads" to "ParallelDownloads = 5",
        "Color" to "Color",
        "ILoveCandy" to "ILoveCandy",
    )

    fun patch(conf: String): String {
        val out = ArrayList<String>()
        val present = HashSet<String>()
        for (line in conf.lines()) {
            val t = line.trim()
            val key = t.removePrefix("#").trim().substringBefore('=').trim()
            when {
                t.startsWith("DownloadUser") -> out += "#$line  # ArchDock: no sandbox user under proot"
                // statfs under proot is unreliable, so pacman's space check misfires.
                t == "CheckSpace" -> out += "#CheckSpace  # ArchDock"
                key in ADDITIONS && t.startsWith("#") -> {} // replaced by the active line below
                key in ADDITIONS -> { present += key; out += line }
                else -> out += line
            }
        }
        val idx = out.indexOfFirst { it.trim() == "[options]" }
        if (idx >= 0) out.addAll(idx + 1, ADDITIONS.filterKeys { it !in present }.values)
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.lastIndex)
        return out.joinToString("\n") + "\n"
    }
}
