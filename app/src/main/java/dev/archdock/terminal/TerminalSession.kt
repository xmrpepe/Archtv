package dev.archdock.terminal

import dev.archdock.core.Logs
import dev.archdock.proot.ProotRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * The phone-side terminal for quick commands without the TV. util-linux
 * `script` gives the guest shell a real pty (so sudo, pacman and less behave),
 * and the output is shown line by line with ANSI sequences stripped.
 */
class TerminalSession(private val proot: ProotRunner) {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private var process: Process? = null
    private var stdin: OutputStream? = null
    private val current = StringBuilder()

    fun start() {
        if (_running.value) return
        val pb = proot.processBuilder(
            listOf("/usr/bin/script", "-qfec", "/bin/bash --login", "/dev/null"),
            ProotRunner.Options(asUser = true, sharedDownloads = true, env = mapOf(
                "TERM" to "dumb", "PS1" to "\\u@archdock:\\w\\$ ", "PAGER" to "cat", "SYSTEMD_PAGER" to "",
                "COLUMNS" to "100", "LINES" to "40",
            )),
        )
        val p = pb.start()
        process = p; stdin = p.outputStream
        _running.value = true
        append("ArchDock terminal – commands run as 'user' (sudo works without a password).\n")
        thread(name = "terminal-reader", isDaemon = true) {
            val buf = ByteArray(8192)
            val input = p.inputStream
            try {
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    append(String(buf, 0, r, Charsets.UTF_8))
                }
            } catch (_: Exception) {}
            _running.value = false
            append("\n[session ended]\n")
        }
    }

    fun send(line: String) {
        val out = stdin ?: return
        runCatching { out.write((line + "\n").toByteArray()); out.flush() }
            .onFailure { Logs.w(Logs.Channel.TERMINAL, "write failed: ${it.message}") }
    }

    fun interrupt() = runCatching { stdin?.write(3); stdin?.flush() }

    fun stop() { process?.destroy(); process = null; stdin = null; _running.value = false }

    fun clear() { synchronized(current) { current.clear(); committed.clear() }; _lines.value = emptyList() }

    /** Completed lines; the partial line being typed/printed lives in [current]. */
    private val committed = ArrayDeque<String>()

    private fun append(text: String) = synchronized(current) {
        current.append(stripAnsi(text))
        var idx = current.indexOf("\n")
        while (idx >= 0) {
            committed.addLast(current.substring(0, idx).trimEnd('\r'))
            current.delete(0, idx + 1)
            idx = current.indexOf("\n")
        }
        while (committed.size > MAX_LINES) committed.removeFirst()
        _lines.value = if (current.isNotEmpty()) committed.toList() + current.toString() else committed.toList()
    }

    companion object {
        private const val MAX_LINES = 1500
        private val ANSI = Regex("""\u001B(\[[0-?]*[ -/]*[@-~]|\][^\u0007\u001B]*(\u0007|\u001B\\)|[()][0-9A-Za-z]|[=>78MDEHc])""")
        fun stripAnsi(s: String): String = ANSI.replace(s, "").replace("\u0007", "")
    }
}
