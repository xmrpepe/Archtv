package dev.archdock.proot

import java.io.File

/**
 * Android (SELinux) hides several /proc files from apps; tools such as top,
 * btop, neofetch and some installers read them. These replacements mirror
 * what proot-distro ships.
 */
enum class FakeProc(val path: String, val fileName: String, val content: String) {
    LOADAVG("/proc/loadavg", "loadavg", "0.12 0.07 0.02 2/165 765\n"),
    STAT(
        "/proc/stat", "stat",
        buildString {
            appendLine("cpu  1957 0 2877 93280 262 342 254 87 0 0")
            for (i in 0 until 8) appendLine("cpu$i 245 0 360 11660 33 43 32 11 0 0")
            appendLine("intr 63361 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0")
            appendLine("ctxt 38014093")
            appendLine("btime 1694292441")
            appendLine("processes 26442")
            appendLine("procs_running 1")
            appendLine("procs_blocked 0")
            appendLine("softirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677")
        }
    ),
    UPTIME("/proc/uptime", "uptime", "124.08 932.80\n"),
    VERSION(
        "/proc/version", "version",
        "Linux version 6.1.0-archdock (archdock@localhost) (gcc version 14.2.0) #1 SMP PREEMPT\n"
    ),
    VMSTAT(
        "/proc/vmstat", "vmstat",
        "nr_free_pages 1743136\nnr_zone_inactive_anon 179281\nnr_zone_active_anon 7183\n" +
            "nr_zone_inactive_file 22858\nnr_zone_active_file 51328\nnr_mlock 0\nnr_bounce 0\n"
    ),
    CAP_LAST_CAP("/proc/sys/kernel/cap_last_cap", "cap_last_cap", "40\n");

    companion object {
        fun ensure(dir: File) {
            dir.mkdirs()
            for (e in entries) {
                val f = File(dir, e.fileName)
                if (!f.exists()) f.writeText(e.content)
            }
        }
    }
}
