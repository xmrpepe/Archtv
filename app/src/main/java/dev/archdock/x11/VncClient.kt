package dev.archdock.x11

import dev.archdock.core.Logs
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A small RFB 3.8 client used for the VNC fallback against TigerVNC's Xvnc
 * on localhost. Supports Raw, CopyRect and DesktopSize – on loopback Raw is
 * the cheapest encoding (no compression work on the A520 little cores).
 * Pointer and keyboard input normally go through [XTestClient]; the RFB input
 * messages are implemented too, for completeness.
 */
class VncClient private constructor(
    inStream: InputStream,
    outStream: OutputStream,
    private val closer: Closeable?,
) : Closeable {

    interface Listener {
        fun onResize(width: Int, height: Int) {}
        /** Called on the client thread after a complete FramebufferUpdate. */
        fun onUpdate(dirty: DirtyRect) {}
        fun onClipboard(text: String) {}
        fun onBell() {}
    }

    private val input = DataInputStream(BufferedInputStream(inStream, 1 shl 16))
    private val output = DataOutputStream(BufferedOutputStream(outStream, 4096))

    var width = 0; private set
    var height = 0; private set
    var name = ""; private set
    /** ARGB_8888 framebuffer; guard with synchronized(framebufferLock). */
    @Volatile var framebuffer = IntArray(0); private set
    val framebufferLock = Any()
    @Volatile private var running = true
    private var rowBytes = ByteArray(0)

    fun handshake() {
        val version = ByteArray(12)
        input.readFully(version)
        val serverVersion = String(version)
        if (!serverVersion.startsWith("RFB ")) throw IOException("not an RFB server: $serverVersion")
        val minor = serverVersion.substring(8, 11).toIntOrNull() ?: 3
        val useMinor = if (minor >= 8) 8 else if (minor >= 7) 7 else 3
        output.write("RFB 003.00$useMinor\n".toByteArray()); output.flush()

        if (useMinor >= 7) {
            val n = input.readUnsignedByte()
            if (n == 0) throw IOException("server refused: ${readString()}")
            val types = ByteArray(n).also { input.readFully(it) }.map { it.toInt() and 0xFF }
            if (1 !in types) throw IOException("server requires authentication (types $types); start Xvnc with -SecurityTypes None")
            output.writeByte(1); output.flush()
            if (useMinor == 8 && input.readInt() != 0) throw IOException("security handshake failed: ${readString()}")
        } else {
            val type = input.readInt()
            if (type != 1) throw IOException("unsupported security type $type")
        }

        output.writeByte(1) // shared
        output.flush()
        width = input.readUnsignedShort()
        height = input.readUnsignedShort()
        input.skipBytes(16) // server pixel format; we set our own below
        name = readString()
        allocate(width, height)

        // 32bpp, depth 24, little-endian, true colour, R<<16 G<<8 B → ARGB int minus alpha.
        output.writeByte(0); output.write(ByteArray(3))
        output.write(byteArrayOf(32, 24, 0, 1, 0, 255.toByte(), 0, 255.toByte(), 0, 255.toByte(), 16, 8, 0, 0, 0, 0))
        val encodings = intArrayOf(ENC_COPYRECT, ENC_RAW, ENC_DESKTOP_SIZE)
        output.writeByte(2); output.writeByte(0); output.writeShort(encodings.size)
        encodings.forEach { output.writeInt(it) }
        requestUpdate(false)
        Logs.i(Logs.Channel.X11, "VNC connected to \"$name\" ${width}x$height")
    }

    private fun allocate(w: Int, h: Int) = synchronized(framebufferLock) {
        width = w; height = h
        framebuffer = IntArray(w * h)
        rowBytes = ByteArray(w * 4)
    }

    private fun readString(): String {
        val len = input.readInt()
        if (len < 0 || len > (1 shl 24)) throw IOException("bad string length $len")
        val b = ByteArray(len); input.readFully(b)
        return String(b, Charsets.ISO_8859_1)
    }

    fun requestUpdate(incremental: Boolean) = synchronized(output) {
        output.writeByte(3); output.writeByte(if (incremental) 1 else 0)
        output.writeShort(0); output.writeShort(0); output.writeShort(width); output.writeShort(height)
        output.flush()
    }

    /** Message loop; returns when the connection closes. */
    fun run(listener: Listener) {
        try {
            while (running) {
                when (val type = input.readUnsignedByte()) {
                    0 -> { readFramebufferUpdate(listener); requestUpdate(true) }
                    1 -> { input.skipBytes(3); val n = input.readUnsignedShort(); input.skipBytes(n * 6) }
                    2 -> listener.onBell()
                    3 -> { input.skipBytes(3); listener.onClipboard(readString()) }
                    else -> throw IOException("unknown server message $type")
                }
            }
        } catch (e: IOException) {
            if (running) throw e
        }
    }

    private fun readFramebufferUpdate(listener: Listener) {
        input.skipBytes(1)
        val rects = input.readUnsignedShort()
        val dirty = DirtyRect()
        repeat(rects) {
            val x = input.readUnsignedShort(); val y = input.readUnsignedShort()
            val w = input.readUnsignedShort(); val h = input.readUnsignedShort()
            when (val enc = input.readInt()) {
                ENC_RAW -> { readRaw(x, y, w, h); dirty.union(x, y, x + w, y + h) }
                ENC_COPYRECT -> {
                    val sx = input.readUnsignedShort(); val sy = input.readUnsignedShort()
                    copyRect(sx, sy, x, y, w, h); dirty.union(x, y, x + w, y + h)
                }
                ENC_DESKTOP_SIZE -> { allocate(w, h); listener.onResize(w, h); dirty.union(0, 0, w, h); requestUpdate(false) }
                else -> throw IOException("server sent unrequested encoding $enc")
            }
        }
        if (!dirty.isEmpty) listener.onUpdate(dirty)
    }

    private fun readRaw(x: Int, y: Int, w: Int, h: Int) {
        val fb = framebuffer
        val stride = width
        val bytes = if (rowBytes.size >= w * 4) rowBytes else ByteArray(w * 4).also { rowBytes = it }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        for (row in 0 until h) {
            input.readFully(bytes, 0, w * 4)
            synchronized(framebufferLock) {
                val off = (y + row) * stride + x
                if (off + w > fb.size) return@synchronized
                bb.position(0)
                bb.get(fb, off, w)
                for (i in off until off + w) fb[i] = fb[i] or OPAQUE
            }
        }
    }

    private fun copyRect(sx: Int, sy: Int, dx: Int, dy: Int, w: Int, h: Int) = synchronized(framebufferLock) {
        val fb = framebuffer
        val stride = width
        if (dy <= sy) {
            for (row in 0 until h) System.arraycopy(fb, (sy + row) * stride + sx, fb, (dy + row) * stride + dx, w)
        } else {
            for (row in h - 1 downTo 0) System.arraycopy(fb, (sy + row) * stride + sx, fb, (dy + row) * stride + dx, w)
        }
    }

    fun sendPointer(x: Int, y: Int, buttonMask: Int) = synchronized(output) {
        output.writeByte(5); output.writeByte(buttonMask); output.writeShort(x); output.writeShort(y); output.flush()
    }

    fun sendKey(keysym: Int, down: Boolean) = synchronized(output) {
        output.writeByte(4); output.writeByte(if (down) 1 else 0); output.writeShort(0); output.writeInt(keysym); output.flush()
    }

    fun sendClipboard(text: String) = synchronized(output) {
        val b = text.toByteArray(Charsets.ISO_8859_1)
        output.writeByte(6); output.write(ByteArray(3)); output.writeInt(b.size); output.write(b); output.flush()
    }

    override fun close() {
        running = false
        runCatching { closer?.close() }
    }

    companion object {
        const val ENC_RAW = 0
        const val ENC_COPYRECT = 1
        const val ENC_DESKTOP_SIZE = -223
        private const val OPAQUE = -0x1000000 // 0xFF000000

        fun connect(host: String = "127.0.0.1", port: Int = 5900, timeoutMs: Int = 3000): VncClient {
            val s = Socket()
            s.tcpNoDelay = true
            s.receiveBufferSize = 4 shl 20
            s.connect(InetSocketAddress(host, port), timeoutMs)
            return VncClient(s.getInputStream(), s.getOutputStream(), s).apply { handshake() }
        }

        internal fun over(input: InputStream, output: OutputStream) = VncClient(input, output, null).apply { handshake() }
    }
}

/** Plain dirty-region accumulator (android.graphics.Rect is unavailable in JVM tests). */
class DirtyRect {
    var left = Int.MAX_VALUE; var top = Int.MAX_VALUE; var right = Int.MIN_VALUE; var bottom = Int.MIN_VALUE
    val isEmpty get() = left >= right || top >= bottom
    val width get() = right - left
    val height get() = bottom - top
    fun union(l: Int, t: Int, r: Int, b: Int) {
        left = minOf(left, l); top = minOf(top, t); right = maxOf(right, r); bottom = maxOf(bottom, b)
    }
}
