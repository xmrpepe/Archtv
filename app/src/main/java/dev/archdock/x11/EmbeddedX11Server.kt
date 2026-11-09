package dev.archdock.x11

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import dev.archdock.BuildConfig
import dev.archdock.core.Logs
import dev.archdock.core.Paths
import dev.archdock.display.DisplayModeInfo
import dev.archdock.proot.ManagedProcess
import java.io.File

/**
 * Termux:X11's X server ("lorie", GPL-3.0) embedded in the APK.
 *
 * The server runs in its own process started with `app_process` from our own
 * APK (CLASSPATH = our base.apk, main class com.termux.x11.CmdEntryPoint),
 * exactly like Termux starts it, but without the Termux app. It creates its
 * socket in $TMPDIR/.X11-unix, which we point at the guest /tmp, and connects
 * to Termux:X11's MainActivity (also in our APK) through a broadcast to our
 * package. That activity is launched directly on the external display.
 */
class EmbeddedX11Server(private val paths: Paths) : DisplayServer {
    override val kind = DisplayServer.Kind.EMBEDDED_X11
    override val displayNumber = 0

    override fun isAvailable(): Boolean = BuildConfig.X11_EMBEDDED &&
        runCatching { Class.forName(ENTRY_POINT) }.isSuccess

    private fun xkbRoot(): String {
        val candidates = listOf("usr/share/xkeyboard-config-2", "usr/share/X11/xkb")
            .map { File(paths.rootfs, it) }
        return (candidates.firstOrNull { File(it, "rules").isDirectory } ?: candidates.last()).canonicalPath
    }

    override fun start(mode: DisplayModeInfo, dpi: Int): ManagedProcess {
        paths.x11SocketDir.mkdirs()
        File(paths.x11SocketDir, "X$displayNumber").delete()
        File(paths.guestTmp, ".X$displayNumber-lock").delete()
        val env = mapOf(
            "CLASSPATH" to paths.apk,
            "TMPDIR" to paths.guestTmp.absolutePath,
            "XKB_CONFIG_ROOT" to xkbRoot(),
        )
        return ManagedProcess.startHost(
            "Xlorie",
            listOf(
                "/system/bin/app_process", "-Xnoimage-dex2oat", "/", ENTRY_POINT,
                ":$displayNumber", "-ac", "-nolisten", "tcp", "-dpi", dpi.toString(),
            ),
            env, Logs.Channel.X11,
        )
    }

    /** Writes Termux:X11's preferences (it reads <package>_preferences). */
    private fun configure(context: Context) {
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE).edit()
            .putBoolean("fullscreen", true)
            .putBoolean("hideCutout", true)
            .putString("displayResolutionMode", "native")
            .putString("screenIdleTimeout", "never")
            .putString("forceOrientation", "landscape")
            .putBoolean("showAdditionalKbd", false)
            .putBoolean("additionalKbdVisible", false)
            .putBoolean("clipboardEnable", true)
            .putBoolean("dexMetaKeyCapture", true)
            .putBoolean("filterOutWinkey", false)
            .putBoolean("pointerCapture", true)
            .putString("touchMode", "1")
            .putString("backButtonAction", "no action")
            .putBoolean("storeSecondaryDisplayPreferencesSeparately", false)
            .apply()
    }

    override fun present(context: Context, displayId: Int) {
        configure(context)
        val intent = Intent().setClassName(context.packageName, MAIN_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        context.startActivity(intent, options.toBundle())
        Logs.i(Logs.Channel.X11, "Termux:X11 activity launched on display $displayId")
    }

    override fun dismiss(context: Context) {
        // Termux:X11's own "exit" broadcast closes its activity but keeps the server.
        context.sendBroadcast(Intent("com.termux.x11.ACTION_STOP").setPackage(context.packageName))
    }

    companion object {
        const val ENTRY_POINT = "com.termux.x11.CmdEntryPoint"
        const val MAIN_ACTIVITY = "com.termux.x11.MainActivity"
    }
}
