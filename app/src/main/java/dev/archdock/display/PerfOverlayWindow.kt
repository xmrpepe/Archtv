package dev.archdock.display

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import dev.archdock.perf.PerfOverlayView
import dev.archdock.perf.PerfSample

/**
 * Perf overlay above the embedded Termux:X11 activity on the TV. Needs the
 * "display over other apps" permission; without it the overlay only shows on
 * the phone's control screen.
 */
class PerfOverlayWindow(private val context: Context) {
    private var view: PerfOverlayView? = null
    private var wm: WindowManager? = null

    fun canShow() = Settings.canDrawOverlays(context)

    fun show(displayId: Int) {
        if (view != null || !canShow()) return
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return
        val displayCtx = context.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val manager = displayCtx.getSystemService(WindowManager::class.java)
        val v = PerfOverlayView(displayCtx)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 24; y = 48 }
        manager.addView(v, lp)
        view = v; wm = manager
    }

    fun update(s: PerfSample) { view?.post { view?.show(s) } }

    fun hide() {
        view?.let { runCatching { wm?.removeView(it) } }
        view = null; wm = null
    }
}
