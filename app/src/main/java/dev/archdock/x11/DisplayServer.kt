package dev.archdock.x11

import android.content.Context
import dev.archdock.display.DisplayModeInfo
import dev.archdock.proot.ManagedProcess

/** An X server implementation plus the way it is shown on the external display. */
interface DisplayServer {
    enum class Kind { EMBEDDED_X11, VNC }

    val kind: Kind
    val displayNumber: Int

    /** Whether this backend can run on this build/device at all. */
    fun isAvailable(): Boolean

    /** Starts the X server sized for [mode]. */
    fun start(mode: DisplayModeInfo, dpi: Int): ManagedProcess

    /** Shows the desktop on the given Android display. */
    fun present(context: Context, displayId: Int)

    /** Removes the desktop from the external display (the server keeps running). */
    fun dismiss(context: Context)
}
