package dev.archdock.core

import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes uncaught exceptions to files/logs/crash-*.txt for the crash log viewer. */
object CrashHandler {
    fun install(logDir: File) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                File(logDir, "crash-$stamp.txt").writeText(buildString {
                    appendLine("ArchDock crash on thread ${thread.name}")
                    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.SOC_MODEL}) Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
                    appendLine()
                    append(error.stackTraceToString())
                })
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun crashFiles(logDir: File): List<File> =
        logDir.listFiles { f -> f.name.startsWith("crash-") }?.sortedByDescending { it.name } ?: emptyList()
}
