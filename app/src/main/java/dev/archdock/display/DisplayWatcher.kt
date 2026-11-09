package dev.archdock.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import dev.archdock.core.Logs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

data class DisplayModeInfo(val id: Int, val width: Int, val height: Int, val refresh: Float) {
    override fun toString() = "${width}x$height@${"%.0f".format(refresh)}"
}

data class ExternalDisplay(
    val id: Int,
    val name: String,
    val current: DisplayModeInfo,
    val modes: List<DisplayModeInfo>,
    val xdpi: Float,
    val ydpi: Float,
    val densityDpi: Int,
)

/**
 * Watches for external (USB-C DisplayPort → HDMI) displays. Android exposes
 * them as "presentation" displays; wireless/virtual displays created by other
 * apps (screen recorders, Cast) are ignored.
 */
class DisplayWatcher(context: Context) {
    private val dm = context.getSystemService(DisplayManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val _external = MutableStateFlow<ExternalDisplay?>(null)
    val external: StateFlow<ExternalDisplay?> = _external.asStateFlow()

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { Logs.i(Logs.Channel.SESSION, "display added: $displayId"); scan() }
        override fun onDisplayRemoved(displayId: Int) { Logs.i(Logs.Channel.SESSION, "display removed: $displayId"); scan() }
        override fun onDisplayChanged(displayId: Int) { if (displayId != Display.DEFAULT_DISPLAY) scan() }
    }

    fun start() {
        dm.registerDisplayListener(listener, handler)
        scan()
    }

    fun stop() = dm.unregisterDisplayListener(listener)

    fun display(id: Int): Display? = dm.getDisplay(id)

    private fun scan() {
        val candidates = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .filter { it.displayId != Display.DEFAULT_DISPLAY && it.isValid && isPhysical(it) }
        val d = candidates.firstOrNull()
        val next = d?.let(::describe)
        if (next != _external.value) {
            Logs.i(Logs.Channel.SESSION, if (next == null) "no external display" else "external display: ${next.name} ${next.current}")
            _external.value = next
        }
    }

    /** Virtual displays (Cast, recorders, our own) have the private/virtual flags. */
    private fun isPhysical(d: Display): Boolean {
        val flags = d.flags
        if (flags and Display.FLAG_PRIVATE != 0) return false
        // Hidden Display.TYPE_EXTERNAL == 2, TYPE_OVERLAY == 4, TYPE_VIRTUAL == 5; exposed via toString on all versions.
        val desc = d.toString()
        return !desc.contains("type VIRTUAL") && !desc.contains("type OVERLAY") && !desc.contains("type WIFI")
    }

    private fun describe(d: Display): ExternalDisplay {
        val modes = d.supportedModes.map { DisplayModeInfo(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val m = d.mode
        val metrics = android.util.DisplayMetrics().also {
            @Suppress("DEPRECATION") d.getRealMetrics(it)
        }
        return ExternalDisplay(
            id = d.displayId,
            name = d.name,
            current = DisplayModeInfo(m.modeId, m.physicalWidth, m.physicalHeight, m.refreshRate),
            modes = modes.sortedWith(compareByDescending<DisplayModeInfo> { it.width * it.height }.thenByDescending { it.refresh }),
            xdpi = metrics.xdpi, ydpi = metrics.ydpi, densityDpi = metrics.densityDpi,
        )
    }

    companion object {
        /**
         * Picks the mode for the requested resolution ("auto" or "WxH") and
         * refresh rate (0 = highest). "auto" prefers the TV's native mode,
         * capped at 4K, and treats 59.94/60 as equal.
         */
        fun pickMode(display: ExternalDisplay, resolution: String, refresh: Int): DisplayModeInfo {
            val wanted = resolution.split('x').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 }
            val pool = if (wanted != null) {
                display.modes.filter { it.width == wanted[0] && it.height == wanted[1] }
                    .ifEmpty { display.modes.filter { it.width <= wanted[0] && it.height <= wanted[1] } }
            } else {
                val maxArea = display.modes.filter { it.width <= 3840 && it.height <= 2160 }
                    .maxOfOrNull { it.width * it.height }
                display.modes.filter { it.width * it.height == maxArea }
            }.ifEmpty { listOf(display.current) }
            return if (refresh > 0) pool.minBy { abs(it.refresh - refresh) } else pool.maxBy { it.refresh }
        }

        /** X DPI from the TV's reported physical density, clamped to sane values for a couch. */
        fun autoDpi(display: ExternalDisplay, mode: DisplayModeInfo): Int {
            // Many TVs report bogus EDID sizes; fall back to a heuristic per resolution.
            val fromEdid = display.xdpi.takeIf { it in 40f..400f }?.toInt()
            val heuristic = when {
                mode.height >= 2160 -> 192
                mode.height >= 1440 -> 120
                else -> 96
            }
            return fromEdid?.coerceIn(96, 192)?.let { if (abs(it - heuristic) > 48) heuristic else it } ?: heuristic
        }
    }
}
