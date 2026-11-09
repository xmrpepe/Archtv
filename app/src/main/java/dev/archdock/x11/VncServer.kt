package dev.archdock.x11

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import dev.archdock.core.Logs
import dev.archdock.core.Paths
import dev.archdock.display.DisplayModeInfo
import dev.archdock.display.ExternalDisplayActivity
import dev.archdock.proot.ManagedProcess
import dev.archdock.proot.ProotRunner
import java.io.File

/**
 * Fallback backend: TigerVNC's Xvnc inside the guest, listening on
 * 127.0.0.1:5900 only, rendered by our own RFB client on the external display.
 */
class VncServer(private val paths: Paths, private val proot: ProotRunner) : DisplayServer {
    override val kind = DisplayServer.Kind.VNC
    override val displayNumber = 0
    val port = 5900

    override fun isAvailable() = File(paths.rootfs, "usr/bin/Xvnc").exists()

    override fun start(mode: DisplayModeInfo, dpi: Int): ManagedProcess {
        File(paths.x11SocketDir, "X$displayNumber").delete()
        File(paths.guestTmp, ".X$displayNumber-lock").delete()
        return proot.spawn(
            "Xvnc",
            listOf(
                "/usr/bin/Xvnc", ":$displayNumber",
                "-geometry", "${mode.width}x${mode.height}", "-depth", "24", "-dpi", dpi.toString(),
                "-SecurityTypes", "None", "-localhost", "-rfbport", port.toString(),
                "-AlwaysShared", "-ac", "-nolisten", "tcp",
                "-FrameRate", mode.refresh.toInt().coerceIn(30, 120).toString(),
                "-CompareFB", "0",
            ),
            ProotRunner.Options(asUser = true),
            Logs.Channel.X11,
        )
    }

    override fun present(context: Context, displayId: Int) {
        val intent = Intent(context, ExternalDisplayActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
    }

    override fun dismiss(context: Context) {
        ExternalDisplayActivity.finishAll()
    }
}
