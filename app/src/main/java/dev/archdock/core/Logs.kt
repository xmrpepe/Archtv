package dev.archdock.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small log hub: every component writes here; the UI observes a bounded
 * in-memory tail per channel and everything is appended to files/logs.
 */
object Logs {
    enum class Channel { APP, INSTALL, SESSION, X11, GPU, AUDIO, THEME, TERMINAL }

    private const val MAX_LINES = 2000
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val tails = Channel.entries.associateWith { MutableStateFlow<List<String>>(emptyList()) }
    private var dir: File? = null
    private val writers = HashMap<Channel, FileWriter>()

    fun init(logDir: File) {
        logDir.mkdirs()
        dir = logDir
    }

    fun tail(channel: Channel): StateFlow<List<String>> = tails.getValue(channel).asStateFlow()

    fun i(channel: Channel, msg: String) = write(channel, 'I', msg)
    fun w(channel: Channel, msg: String, t: Throwable? = null) =
        write(channel, 'W', if (t == null) msg else "$msg: ${t.stackTraceToString()}")
    fun e(channel: Channel, msg: String, t: Throwable? = null) =
        write(channel, 'E', if (t == null) msg else "$msg: ${t.stackTraceToString()}")

    /** Raw output from a child process (pacman, proot, Xvnc …). */
    fun raw(channel: Channel, line: String) = write(channel, ' ', line)

    @Synchronized
    private fun write(channel: Channel, level: Char, msg: String) {
        val line = "${fmt.format(Date())} $level $msg"
        when (level) {
            'E' -> Log.e("ArchDock/$channel", msg)
            'W' -> Log.w("ArchDock/$channel", msg)
            else -> Log.i("ArchDock/$channel", msg)
        }
        val flow = tails.getValue(channel)
        val cur = flow.value
        flow.value = if (cur.size >= MAX_LINES) cur.drop(cur.size - MAX_LINES + 1) + line else cur + line
        try {
            val w = writers.getOrPut(channel) {
                FileWriter(File(dir ?: return, "${channel.name.lowercase()}.log"), true)
            }
            w.write(line); w.write("\n"); w.flush()
        } catch (_: Exception) {
        }
    }

    fun files(): List<File> = dir?.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()

    @Synchronized
    fun clear() {
        writers.values.forEach { runCatching { it.close() } }
        writers.clear()
        files().forEach { it.delete() }
        tails.values.forEach { it.value = emptyList() }
    }
}
