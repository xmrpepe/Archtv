package dev.archdock.proot

import android.os.Environment
import dev.archdock.core.Logs
import dev.archdock.core.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Builds and runs proot command lines for the Arch rootfs.
 *
 * proot (Termux fork) is shipped as `libproot.so` in nativeLibraryDir together
 * with its loader. Options used:
 *  - `--link2symlink`: Android forbids hard links in app storage; proot emulates them.
 *  - `-0` / `--change-id`: fake root for pacman, uid 1000 for the desktop.
 *  - `--sysvipc`: SysV shared memory emulation (MIT-SHM, PulseAudio, some apps).
 *  - `--kill-on-exit`: no orphaned guest processes when a session stops.
 */
class ProotRunner(private val paths: Paths) {

    data class Options(
        /** Run as `user` (uid 1000) instead of fake root. */
        val asUser: Boolean = false,
        val env: Map<String, String> = emptyMap(),
        val cwd: String? = null,
        /** Bind /sdcard/Download to ~/Android-Downloads. */
        val sharedDownloads: Boolean = false,
        /** Extra host:guest binds. */
        val binds: List<Pair<String, String>> = emptyList(),
    )

    val isAvailable: Boolean get() = paths.proot.canExecute()

    fun hostEnvironment(): Map<String, String> = buildMap {
        put("PROOT_TMP_DIR", paths.prootTmp.absolutePath)
        put("PROOT_LOADER", paths.prootLoader.absolutePath)
        if (paths.prootLoader32.exists()) put("PROOT_LOADER_32", paths.prootLoader32.absolutePath)
        // The Android side must not leak its own LD_PRELOAD (e.g. from a
        // debugging wrapper) into the guest.
        put("LD_PRELOAD", "")
        put("HOME", paths.files.absolutePath)
    }

    fun guestEnvironment(opts: Options): Map<String, String> = buildMap {
        val user = opts.asUser
        put("HOME", if (user) "/home/user" else "/root")
        put("USER", if (user) "user" else "root")
        put("LOGNAME", if (user) "user" else "root")
        put("SHELL", if (user) "/usr/bin/zsh" else "/bin/bash")
        put("TERM", "xterm-256color")
        put("LANG", "en_US.UTF-8")
        put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/bin:/usr/bin/site_perl:/usr/bin/vendor_perl:/usr/bin/core_perl")
        put("TMPDIR", "/tmp")
        put("ARCHDOCK", "1")
        put("XDG_RUNTIME_DIR", if (user) "/tmp/archdock/runtime-user" else "/tmp/archdock/runtime-root")
        putAll(opts.env)
    }

    /** The full argv for running [command] inside the rootfs. */
    fun buildArgs(command: List<String>, opts: Options = Options()): List<String> {
        val rootfs = paths.rootfs.absolutePath
        val args = mutableListOf(
            paths.proot.absolutePath,
            "--kill-on-exit",
            "--link2symlink",
            "--sysvipc",
            "--ashmem-memfd",
            "-L",
            "--kernel-release=6.1.0-archdock",
            "--rootfs=$rootfs",
            "--cwd=${opts.cwd ?: if (opts.asUser) "/home/user" else "/root"}",
        )
        if (opts.asUser) args += "--change-id=1000:1000" else args += "--root-id"

        fun bind(host: String, guest: String = host) { args += "--bind=$host:$guest" }

        bind("/dev"); bind("/proc"); bind("/sys")
        bind("/dev/urandom", "/dev/random")
        bind("/proc/self/fd", "/dev/fd")
        bind("/proc/self/fd/0", "/dev/stdin")
        bind("/proc/self/fd/1", "/dev/stdout")
        bind("/proc/self/fd/2", "/dev/stderr")
        bind("$rootfs/tmp", "/dev/shm")
        // Android's own GPU stack, needed by the Vulkan wrapper ICD: it loads
        // /system/lib64/libvulkan.so and the vendor Mali driver through the
        // bionic linker, which resolves these absolute paths itself. Arch has
        // none of these directories, so they are bound at the same path.
        listOf("/system", "/vendor", "/apex", "/odm", "/linkerconfig", "/system_ext", "/product")
            .filter { File(it).exists() }
            .forEach { bind(it) }
        // Android denies apps several /proc files; give guests plausible copies.
        for (fake in FakeProc.entries) {
            val real = File(fake.path)
            if (!real.canRead()) bind(File(paths.fakeProc, fake.fileName).absolutePath, fake.path)
        }
        if (opts.sharedDownloads) {
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (dl.canRead()) bind(dl.absolutePath, "/home/user/Android-Downloads")
        }
        opts.binds.forEach { (h, g) -> bind(h, g) }

        args += "/usr/bin/env"
        args += "-i"
        guestEnvironment(opts).forEach { (k, v) -> args += "$k=$v" }
        args += command
        return args
    }

    fun processBuilder(command: List<String>, opts: Options = Options()): ProcessBuilder =
        ProcessBuilder(buildArgs(command, opts)).apply {
            environment().clear()
            environment().putAll(hostEnvironment())
            directory(paths.files)
            redirectErrorStream(true)
        }

    /**
     * Runs a bash script inside the guest and streams each output line to
     * [onLine] and the log. Returns the exit code. Cancellation kills proot,
     * and --kill-on-exit takes every guest child with it.
     */
    suspend fun runScript(
        script: String,
        opts: Options = Options(),
        channel: Logs.Channel = Logs.Channel.INSTALL,
        onLine: (String) -> Unit = {},
    ): Int = run(listOf("/bin/bash", "-c", script), opts, channel, onLine)

    suspend fun run(
        command: List<String>,
        opts: Options = Options(),
        channel: Logs.Channel = Logs.Channel.INSTALL,
        onLine: (String) -> Unit = {},
    ): Int = withContext(Dispatchers.IO) {
        FakeProc.ensure(paths.fakeProc)
        val process = processBuilder(command, opts).start()
        try {
            process.outputStream.close()
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    currentCoroutineContext().ensureActive()
                    Logs.raw(channel, line)
                    onLine(line)
                }
            }
            process.waitFor()
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /** Runs a command and returns its combined output (for short queries). */
    suspend fun capture(command: List<String>, opts: Options = Options()): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            FakeProc.ensure(paths.fakeProc)
            val process = processBuilder(command, opts).start()
            process.outputStream.close()
            val out = process.inputStream.bufferedReader().readText()
            process.waitFor() to out
        }

    /** Starts a long-running guest process (desktop, Xvnc, PulseAudio). */
    fun spawn(name: String, command: List<String>, opts: Options, channel: Logs.Channel): ManagedProcess {
        FakeProc.ensure(paths.fakeProc)
        return ManagedProcess.start(name, processBuilder(command, opts), channel)
    }
}
