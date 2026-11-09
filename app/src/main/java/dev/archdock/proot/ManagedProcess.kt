package dev.archdock.proot

import dev.archdock.core.Logs
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** A long-running child process whose output is pumped into the log. */
class ManagedProcess private constructor(
    val name: String,
    private val process: Process,
    private val channel: Logs.Channel,
) {
    @Volatile var lastLines: List<String> = emptyList(); private set
    private val listeners = mutableListOf<(String) -> Unit>()

    val isAlive get() = process.isAlive
    val exitCode: Int? get() = if (process.isAlive) null else process.exitValue()

    /** pid of the started command (reported by the sh wrapper, see [start]). */
    @Volatile var pid: Int? = null; private set

    fun onLine(listener: (String) -> Unit) = synchronized(listeners) { listeners += listener }

    private fun pump() = thread(name = "pump-$name", isDaemon = true) {
        runCatching {
            val reader = process.inputStream.bufferedReader()
            // First line is the pid printed by the wrapper; `exec` keeps it.
            pid = reader.readLine()?.trim()?.toIntOrNull()
            reader.forEachLine { line ->
                Logs.raw(channel, "[$name] $line")
                lastLines = (lastLines + line).takeLast(50)
                synchronized(listeners) { listeners.toList() }.forEach { runCatching { it(line) } }
            }
        }
    }

    fun waitFor(): Int = process.waitFor()

    fun stop(graceMs: Long = 3000) {
        if (!process.isAlive) return
        process.destroy()
        if (!process.waitFor(graceMs, TimeUnit.MILLISECONDS)) process.destroyForcibly()
    }

    companion object {
        fun start(name: String, pb: ProcessBuilder, channel: Logs.Channel): ManagedProcess {
            pb.redirectErrorStream(true)
            Logs.i(channel, "starting $name: ${pb.command().last()}")
            // java.lang.Process has no public pid() on Android, so let a tiny
            // shell report it before exec'ing the real command (same pid).
            pb.command(listOf("/system/bin/sh", "-c", "echo $$; exec \"$@\"", "archdock") + pb.command())
            val p = pb.start()
            runCatching { p.outputStream.close() }
            return ManagedProcess(name, p, channel).also { it.pump() }
        }

        /** Wraps an already-built ProcessBuilder for Android-side helpers (X server, virgl). */
        fun startHost(name: String, command: List<String>, env: Map<String, String>, channel: Logs.Channel): ManagedProcess {
            val pb = ProcessBuilder(command)
            pb.environment().putAll(env)
            return start(name, pb, channel)
        }
    }
}
