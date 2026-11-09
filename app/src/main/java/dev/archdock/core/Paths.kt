package dev.archdock.core

import android.content.Context
import java.io.File

/** Every on-disk location ArchDock uses. All of it is app-private storage. */
class Paths(context: Context) {
    val files: File = context.filesDir
    /** The Arch Linux ARM root filesystem. */
    val rootfs = File(files, "arch")
    /** Extraction happens here first and is renamed to [rootfs] once complete. */
    val rootfsStaging = File(files, "arch.partial")
    val downloads = File(files, "dl")
    val state = File(files, "state")
    val logs = File(files, "logs")
    val backups = File(files, "backups")
    /** PROOT_TMP_DIR – proot's own scratch space (not the guest /tmp). */
    val prootTmp = File(files, "proot-tmp")
    /** Fake /proc entries for things Android hides from apps. */
    val fakeProc = File(files, "fake-proc")

    /** Guest /tmp seen from Android. X11 and PulseAudio sockets live here. */
    val guestTmp get() = File(rootfs, "tmp")
    val x11SocketDir get() = File(guestTmp, ".X11-unix")
    fun x11Socket(display: Int) = File(x11SocketDir, "X$display")
    val runtimeDir get() = File(guestTmp, "archdock")
    val userHome get() = File(rootfs, "home/user")

    val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
    val apk: String = context.applicationInfo.sourceDir

    // Executables bundled as lib*.so so Android extracts them as executable.
    val proot get() = File(nativeLibDir, "libproot.so")
    val prootLoader get() = File(nativeLibDir, "libproot-loader.so")
    val prootLoader32 get() = File(nativeLibDir, "libproot-loader32.so")

    val installedFlag get() = File(state, "installed")

    fun ensureDirs() {
        listOf(downloads, state, logs, backups, prootTmp, fakeProc).forEach { it.mkdirs() }
    }
}
