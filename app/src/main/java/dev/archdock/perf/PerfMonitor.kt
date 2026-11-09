package dev.archdock.perf

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import dev.archdock.core.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

data class PerfSample(
    /** CPU used by ArchDock's own process tree (proot, X server, guest apps), % of one core × cores. */
    val cpuPercent: Float = 0f,
    val coreFreqMhz: List<Int> = emptyList(),
    val gpuPercent: Float? = null,
    val gpuFreqMhz: Int? = null,
    val batteryTempC: Float? = null,
    val thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE,
    val thermalHeadroom: Float? = null,
    val fps: Float? = null,
) {
    val throttling get() = thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
    val thermalLabel get() = when (thermalStatus) {
        PowerManager.THERMAL_STATUS_NONE -> "normal"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate (throttling)"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe (throttling)"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        else -> "emergency"
    }
}

/**
 * Samples performance counters once a second. Android hides /proc/stat from
 * apps, so CPU% is computed from our own processes' /proc/<pid>/stat.
 * Mali utilisation is only readable on some builds; it shows as n/a otherwise.
 */
class PerfMonitor(private val context: Context) {
    private val _sample = MutableStateFlow(PerfSample())
    val sample: StateFlow<PerfSample> = _sample.asStateFlow()
    private val frames = AtomicInteger()
    private var job: Job? = null
    private val pm = context.getSystemService(PowerManager::class.java)
    private val clockTicks = 100f // USER_HZ on Android kernels

    /** Renderers call this per presented frame. */
    fun frame() { frames.incrementAndGet() }
    @Volatile var fpsSource = false

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        pm.addThermalStatusListener(context.mainExecutor) { status ->
            if (status >= PowerManager.THERMAL_STATUS_MODERATE)
                Logs.w(Logs.Channel.SESSION, "Thermal status $status: the phone is throttling")
        }
        job = scope.launch {
            var lastTicks = ownCpuTicks()
            var lastTime = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(1000)
                val now = SystemClock.elapsedRealtime()
                val ticks = ownCpuTicks()
                val dt = (now - lastTime) / 1000f
                val cpu = ((ticks - lastTicks) / clockTicks) / dt * 100f
                lastTicks = ticks; lastTime = now
                val f = frames.getAndSet(0)
                _sample.value = PerfSample(
                    cpuPercent = cpu,
                    coreFreqMhz = coreFreqs(),
                    gpuPercent = readGpuUtil(),
                    gpuFreqMhz = readGpuFreq(),
                    batteryTempC = batteryTemp(),
                    thermalStatus = pm.currentThermalStatus,
                    thermalHeadroom = pm.getThermalHeadroom(10).takeIf { !it.isNaN() },
                    fps = if (fpsSource) f / dt else null,
                )
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    private fun ownCpuTicks(): Long {
        val uid = android.os.Process.myUid()
        var total = 0L
        File("/proc").listFiles()?.forEach { d ->
            if (d.name.toIntOrNull() == null) return@forEach
            val stat = runCatching { File(d, "stat").readText() }.getOrNull() ?: return@forEach
            // Only our processes are visible on Android (hidepid), but be explicit.
            if (runCatching { File(d, "status").readLines().first { it.startsWith("Uid:") } }
                    .getOrNull()?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull() != uid) return@forEach
            val fields = stat.substringAfterLast(')').trim().split(' ')
            // fields[11] = utime, fields[12] = stime (after pid and comm)
            total += (fields.getOrNull(11)?.toLongOrNull() ?: 0) + (fields.getOrNull(12)?.toLongOrNull() ?: 0)
        }
        return total
    }

    private fun coreFreqs(): List<Int> = (0 until 8).map { cpu ->
        runCatching {
            File("/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_cur_freq").readText().trim().toInt() / 1000
        }.getOrDefault(0)
    }

    private val gpuUtilPaths = listOf(
        "/sys/devices/platform/1f000000.mali/utilization",
        "/sys/class/misc/mali0/device/utilization",
        "/sys/kernel/gpu/gpu_busy",
    )
    private val gpuFreqPaths = listOf(
        "/sys/devices/platform/1f000000.mali/cur_freq",
        "/sys/class/misc/mali0/device/cur_freq",
        "/sys/kernel/gpu/gpu_clock",
    )

    private fun readGpuUtil(): Float? = gpuUtilPaths.firstNotNullOfOrNull { p ->
        runCatching { File(p).readText().trim().removeSuffix("%").trim().toFloat() }.getOrNull()
    }

    private fun readGpuFreq(): Int? = gpuFreqPaths.firstNotNullOfOrNull { p ->
        runCatching { File(p).readText().trim().toLong() }.getOrNull()?.let { if (it > 100_000) (it / 1000).toInt() else it.toInt() }
    }

    private fun batteryTemp(): Float? {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (t == Int.MIN_VALUE) null else t / 10f
    }
}
