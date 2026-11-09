package dev.archdock.session

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Android 12+ "phantom process killer": the system kills child processes of
 * apps once more than 32 exist device-wide, or when they use excessive CPU in
 * the background. A MATE desktop easily has 40+ processes, so this must be
 * disabled. On Android 14+ it is the developer option
 * "Disable child process restrictions"; on 12L/13 the global setting below
 * (also settable via adb).
 */
object PhantomProcessGuard {
    const val ADB_FIX = "adb shell \"settings put global settings_enable_monitor_phantom_procs false\""
    const val ADB_FIX_A12 =
        "adb shell \"/system/bin/device_config put activity_manager max_phantom_processes 2147483647\""

    enum class Status { DISABLED, ENABLED, UNKNOWN, NOT_APPLICABLE }

    fun status(context: Context): Status {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return Status.NOT_APPLICABLE
        val v = runCatching {
            Settings.Global.getString(context.contentResolver, "settings_enable_monitor_phantom_procs")
        }.getOrNull()
        return when (v?.lowercase()) {
            "false", "0" -> Status.DISABLED
            "true", "1" -> Status.ENABLED
            // Unset means the platform default, which is "enabled".
            null -> Status.ENABLED
            else -> Status.UNKNOWN
        }
    }

    /** Exit codes that look like SIGKILL from outside (not our own stop). */
    fun looksKilled(exitCode: Int?) = exitCode == 137 || exitCode == 9 || exitCode == -9

    fun instructions(): String = buildString {
        appendLine("Android killed ArchDock's Linux processes (phantom process killer).")
        appendLine()
        appendLine("Fix (pick one):")
        appendLine("• Settings → System → Developer options → enable \"Disable child process restrictions\".")
        appendLine("• Or from a computer:")
        appendLine("  $ADB_FIX")
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.S) appendLine("  $ADB_FIX_A12")
        appendLine()
        appendLine("Then press Restart.")
    }
}
