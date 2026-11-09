package dev.archdock.perf

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.widget.TextView

/** Small Tokyo Night–styled text overlay used on the TV and the phone. */
class PerfOverlayView(context: Context) : TextView(context) {
    init {
        setTextColor(Color.parseColor("#C0CAF5"))
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        val pad = (8 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, pad / 2)
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#CC1A1B26"))
            cornerRadius = 10 * resources.displayMetrics.density
            setStroke(2, Color.parseColor("#7AA2F7"))
        }
    }

    fun show(s: PerfSample) {
        val big = s.coreFreqMhz.drop(4).joinToString("/")
        text = buildString {
            append("CPU %.0f%%  [%s MHz]".format(s.cpuPercent, big.ifEmpty { "?" }))
            append("\nGPU ").append(s.gpuPercent?.let { "%.0f%%".format(it) } ?: "n/a")
            s.gpuFreqMhz?.let { append(" @ ${it} MHz") }
            append("\nTemp ").append(s.batteryTempC?.let { "%.1f°C".format(it) } ?: "n/a")
            append(" · ").append(s.thermalLabel)
            s.fps?.let { append("\nFPS %.0f".format(it)) }
        }
        setTextColor(Color.parseColor(if (s.throttling) "#F7768E" else "#C0CAF5"))
    }
}
