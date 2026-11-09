package dev.archdock.perf

import java.io.File

/** JNI bridge to perf.cpp plus CPU-topology helpers. */
object NativePerf {
    private val loaded = runCatching { System.loadLibrary("archdock") }.isSuccess

    @JvmStatic private external fun pinProcess(pid: Int, mask: Long): Int
    @JvmStatic external fun currentCpu(): Int

    fun pin(pid: Int, mask: Long): Int = if (loaded) pinProcess(pid, mask) else -1

    /**
     * Mask of the "big" cores, derived from cpufreq max frequencies. On Tensor
     * G4 that is cpu4-6 (Cortex-A720) + cpu7 (Cortex-X4) → 0xF0.
     */
    fun bigCoreMask(): Long {
        val freqs = (0 until 16).mapNotNull { cpu ->
            File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").takeIf { it.canRead() }
                ?.readText()?.trim()?.toLongOrNull()?.let { cpu to it }
        }
        if (freqs.isEmpty()) return 0xF0L
        val minFreq = freqs.minOf { it.second }
        val big = freqs.filter { it.second > minFreq }.fold(0L) { m, (cpu, _) -> m or (1L shl cpu) }
        return if (big == 0L) 0xF0L else big
    }

    /** Finds pids whose cmdline contains [needle] (our uid only – others are hidden on Android). */
    fun findPids(needle: String): List<Int> =
        File("/proc").listFiles()?.mapNotNull { d ->
            val pid = d.name.toIntOrNull() ?: return@mapNotNull null
            val cmd = runCatching { File(d, "cmdline").readText().replace('\u0000', ' ') }.getOrNull()
            if (cmd != null && cmd.contains(needle)) pid else null
        } ?: emptyList()
}
