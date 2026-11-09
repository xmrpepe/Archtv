package dev.archdock.display

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Display
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import dev.archdock.core.Logs

/**
 * Applies the TV-side window policy to *every* activity of ours that lands on
 * an external display – our VNC activity and the embedded Termux:X11
 * MainActivity alike (it runs in our process):
 *  - immersive full screen, no system bars, cutout-agnostic,
 *  - FLAG_KEEP_SCREEN_ON so the TV stays on while the phone screen is off,
 *  - the preferred display mode (resolution + refresh rate) chosen in Settings.
 */
class ExternalWindowConfigurator(private val preferredMode: () -> Int?) : Application.ActivityLifecycleCallbacks {

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = apply(activity)
    override fun onActivityResumed(activity: Activity) = apply(activity)

    fun apply(activity: Activity) {
        val display = activity.display ?: return
        if (display.displayId == Display.DEFAULT_DISPLAY) return
        val window = activity.window
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            preferredMode()?.let { if (it > 0) preferredDisplayModeId = it }
        }
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        Logs.i(Logs.Channel.X11, "configured ${activity.javaClass.simpleName} on display ${display.displayId}")
    }

    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
