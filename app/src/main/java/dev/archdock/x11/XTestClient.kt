package dev.archdock.x11

import android.net.LocalSocket
import android.net.LocalSocketAddress
import dev.archdock.core.Logs
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Minimal X11 protocol client that injects input with the XTEST extension.
 * It talks to whichever X server is running (embedded Termux:X11 or Xvnc)
 * over its Unix socket in the guest /tmp, so the phone trackpad, on-screen
 * keyboard and hardware keyboards/mice work the same with both backends.
 *
 * The X servers run with `-ac` and listen only on the app-private socket.
 */
class XTestClient private constructor(
    private val input: InputStream,
    private val output: OutputStream,
    private val closer: () -> Unit,
) {
    var screenWidth = 0; private set
    var screenHeight = 0; private set
    var rootWindow = 0; private set
    var minKeycode = 8; private set
    var maxKeycode = 255; private set
    private var xtestOpcode = 0
    @Volatile var isOpen = true; private set

    private fun handshake() {
        // Connection setup, little-endian, protocol 11.0, no auth.
        val req = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        req.put(0x6C).put(0).putShort(11).putShort(0).putShort(0).putShort(0).putShort(0)
        output.write(req.array()); output.flush()

        val din = DataInputStream(input)
        val head = ByteArray(8); din.readFully(head)
        val hb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val status = hb.get(0).toInt()
        val extraLen = (hb.getShort(6).toInt() and 0xFFFF) * 4
        val extra = ByteArray(extraLen); din.readFully(extra)
        if (status != 1) {
            val reasonLen = head[1].toInt() and 0xFF
            throw IOException("X server refused connection: " + String(extra, 0, minOf(reasonLen, extra.size)))
        }
        val b = ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN)
        val vendorLen = b.getShort(16).toInt() and 0xFFFF
        val numFormats = b.get(21).toInt() and 0xFF
        minKeycode = b.get(26).toInt() and 0xFF
        maxKeycode = b.get(27).toInt() and 0xFF
        val screenOffset = 32 + pad4(vendorLen) + numFormats * 8
        rootWindow = b.getInt(screenOffset)
        screenWidth = b.getShort(screenOffset + 20).toInt() and 0xFFFF
        screenHeight = b.getShort(screenOffset + 22).toInt() and 0xFFFF

        // QueryExtension("XTEST")
        val name = "XTEST".toByteArray()
        val q = ByteBuffer.allocate(8 + pad4(name.size)).order(ByteOrder.LITTLE_ENDIAN)
        q.put(98).put(0).putShort(((8 + pad4(name.size)) / 4).toShort()).putShort(name.size.toShort()).putShort(0).put(name)
        output.write(q.array()); output.flush()
        val reply = ByteArray(32); din.readFully(reply)
        if (reply[0].toInt() != 1 || reply[8].toInt() == 0) throw IOException("XTEST extension not available")
        xtestOpcode = reply[9].toInt() and 0xFF

        // Drain anything the server sends from now on (errors/events) so its
        // output buffer never fills up and blocks us.
        thread(name = "xtest-drain", isDaemon = true) {
            val buf = ByteArray(4096)
            try { while (input.read(buf) >= 0) {} } catch (_: IOException) {}
            isOpen = false
        }
        Logs.i(Logs.Channel.X11, "XTEST connected: ${screenWidth}x$screenHeight, opcode $xtestOpcode")
    }

    private fun fake(type: Int, detail: Int, x: Int = 0, y: Int = 0) {
        val b = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN)
        b.put(xtestOpcode.toByte()).put(2).putShort(9)             // X_XTestFakeInput, length 9
        b.put(type.toByte()).put(detail.toByte()).putShort(0)     // type, detail
        b.putInt(0)                                                // time = CurrentTime
        b.putInt(if (type == MOTION) rootWindow else 0)            // root
        b.putInt(0).putInt(0)                                      // pad
        b.putShort(x.toShort()).putShort(y.toShort())              // rootX, rootY
        b.putInt(0).putShort(0).put(0).put(0)                      // pad, deviceid
        synchronized(this) {
            try { output.write(b.array()); output.flush() } catch (e: IOException) { isOpen = false; throw e }
        }
    }

    fun key(xKeycode: Int, down: Boolean) = fake(if (down) KEY_PRESS else KEY_RELEASE, xKeycode)
    fun button(button: Int, down: Boolean) = fake(if (down) BUTTON_PRESS else BUTTON_RELEASE, button)
    fun moveAbsolute(x: Int, y: Int) = fake(MOTION, 0, x, y)
    fun moveRelative(dx: Int, dy: Int) = fake(MOTION, 1, dx, dy)

    fun click(button: Int) { button(button, true); button(button, false) }

    /** Scroll: buttons 4/5 vertical, 6/7 horizontal. */
    fun scroll(dy: Int, dx: Int = 0) {
        repeat(kotlin.math.abs(dy)) { click(if (dy < 0) 4 else 5) }
        repeat(kotlin.math.abs(dx)) { click(if (dx < 0) 6 else 7) }
    }

    fun close() { isOpen = false; runCatching(closer) }

    companion object {
        const val KEY_PRESS = 2
        const val KEY_RELEASE = 3
        const val BUTTON_PRESS = 4
        const val BUTTON_RELEASE = 5
        const val MOTION = 6

        private fun pad4(n: Int) = (n + 3) and 3.inv()

        fun connect(socket: File): XTestClient {
            val s = LocalSocket()
            s.connect(LocalSocketAddress(socket.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            return XTestClient(s.inputStream, s.outputStream) { s.close() }.apply { handshake() }
        }

        /** For tests: any stream pair speaking X11. */
        internal fun over(input: InputStream, output: OutputStream): XTestClient =
            XTestClient(input, output) {}.apply { handshake() }
    }
}
