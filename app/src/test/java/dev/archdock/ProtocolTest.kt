package dev.archdock

import dev.archdock.display.DisplayModeInfo
import dev.archdock.display.DisplayWatcher
import dev.archdock.display.ExternalDisplay
import dev.archdock.x11.DirtyRect
import dev.archdock.x11.VncClient
import dev.archdock.x11.XTestClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

class ProtocolTest {

    /** Builds a minimal X11 connection-setup success reply for a 1920x1080 screen. */
    private fun setupReply(): ByteArray {
        val vendor = "ArchDockTest".toByteArray() // 12 bytes, already 4-aligned
        val body = ByteBuffer.allocate(32 + vendor.size + 8 + 40).order(ByteOrder.LITTLE_ENDIAN)
        body.putInt(12000000).putInt(0x200000).putInt(0x1FFFFF).putInt(256)
        body.putShort(vendor.size.toShort()).putShort(65535.toShort())
        body.put(1).put(1).put(0).put(0).put(32).put(32).put(8).put(255.toByte()).putInt(0)
        body.put(vendor)
        body.put(24).put(32).put(32).put(0).putInt(0) // one FORMAT
        body.putInt(0x1E5).putInt(0x20).putInt(0xFFFFFF).putInt(0).putInt(0) // root, cmap, white, black, masks
        body.putShort(1920).putShort(1080).putShort(508).putShort(285)       // size px, mm
        body.putShort(1).putShort(1).putInt(0x21).put(0).put(0).put(24).put(1) // maps, visual, store, saveunders, depth, ndepths
        val head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        head.put(1).put(0).putShort(11).putShort(0).putShort((body.capacity() / 4).toShort())
        return head.array() + body.array()
    }

    @Test fun xtestHandshakeAndFakeInput() {
        val toClient = PipedOutputStream(); val clientIn = PipedInputStream(toClient, 1 shl 16)
        val fromClient = PipedInputStream(1 shl 16); val clientOut = PipedOutputStream(fromClient)
        val captured = ArrayList<ByteArray>()
        val server = thread {
            val din = DataInputStream(fromClient)
            val setup = ByteArray(12); din.readFully(setup)
            assertEquals(0x6C, setup[0].toInt())
            toClient.write(setupReply()); toClient.flush()
            val q = ByteArray(16); din.readFully(q)
            assertEquals(98, q[0].toInt())
            assertEquals("XTEST", String(q, 8, 5))
            val reply = ByteArray(32); reply[0] = 1; reply[8] = 1; reply[9] = 132.toByte()
            toClient.write(reply); toClient.flush()
            repeat(3) { val r = ByteArray(36); din.readFully(r); captured += r }
        }
        val c = XTestClient.over(clientIn, clientOut)
        assertEquals(1920, c.screenWidth); assertEquals(1080, c.screenHeight); assertEquals(0x1E5, c.rootWindow)
        c.key(133, true)            // Super_L down (evdev 125 + 8)
        c.moveRelative(-5, 7)
        c.button(1, false)
        server.join(2000)

        val key = ByteBuffer.wrap(captured[0]).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(132, key.get(0).toInt() and 0xFF)  // XTEST major opcode
        assertEquals(2, key.get(1).toInt())             // X_XTestFakeInput
        assertEquals(9, key.getShort(2).toInt())        // 36 bytes
        assertEquals(XTestClient.KEY_PRESS, key.get(4).toInt())
        assertEquals(133, key.get(5).toInt() and 0xFF)
        val motion = ByteBuffer.wrap(captured[1]).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(XTestClient.MOTION, motion.get(4).toInt())
        assertEquals(1, motion.get(5).toInt())          // relative
        assertEquals(0x1E5, motion.getInt(12))
        assertEquals(-5, motion.getShort(24).toInt()); assertEquals(7, motion.getShort(26).toInt())
        assertEquals(XTestClient.BUTTON_RELEASE, captured[2][4].toInt())
    }

    @Test fun vncHandshakeRawAndCopyRect() {
        val toClient = PipedOutputStream(); val clientIn = PipedInputStream(toClient, 1 shl 20)
        val fromClient = PipedInputStream(1 shl 16); val clientOut = PipedOutputStream(fromClient)
        val server = thread {
            val out = DataOutputStream(toClient)
            val din = DataInputStream(fromClient)
            out.write("RFB 003.008\n".toByteArray()); out.flush()
            val v = ByteArray(12); din.readFully(v); assertEquals("RFB 003.008\n", String(v))
            out.writeByte(1); out.writeByte(1); out.flush()          // security types: None
            assertEquals(1, din.readUnsignedByte())
            out.writeInt(0); out.flush()                              // SecurityResult OK
            assertEquals(1, din.readUnsignedByte())                   // shared
            out.writeShort(4); out.writeShort(2); out.write(ByteArray(16))
            val name = "archdock:0".toByteArray(); out.writeInt(name.size); out.write(name); out.flush()
            val spf = ByteArray(20); din.readFully(spf); assertEquals(0, spf[0].toInt()); assertEquals(32, spf[4].toInt())
            assertEquals(2, din.readUnsignedByte()); din.readUnsignedByte(); val n = din.readUnsignedShort(); repeat(n) { din.readInt() }
            val req = ByteArray(10); din.readFully(req); assertEquals(3, req[0].toInt())
            // Keep reading (incremental update requests) so the client's writes don't fail.
            thread(isDaemon = true) { runCatching { while (din.read() >= 0) {} } }
            // FramebufferUpdate: one Raw 2x1 rect at (0,0), then CopyRect it to (2,1).
            out.writeByte(0); out.writeByte(0); out.writeShort(2)
            out.writeShort(0); out.writeShort(0); out.writeShort(2); out.writeShort(1); out.writeInt(0)
            out.write(byteArrayOf(0x33, 0x22, 0x11, 0, 0x66, 0x55, 0x44, 0)) // BGRX little-endian
            out.writeShort(2); out.writeShort(1); out.writeShort(2); out.writeShort(1); out.writeInt(1)
            out.writeShort(0); out.writeShort(0)
            // ServerCutText
            val clip = "from linux".toByteArray()
            out.writeByte(3); out.write(ByteArray(3)); out.writeInt(clip.size); out.write(clip)
            out.flush()
            Thread.sleep(300)
            toClient.close()
        }
        val c = VncClient.over(clientIn, clientOut)
        assertEquals(4, c.width); assertEquals(2, c.height); assertEquals("archdock:0", c.name)
        var dirty: DirtyRect? = null
        var clipboard: String? = null
        runCatching {
            c.run(object : VncClient.Listener {
                override fun onUpdate(dirty: DirtyRect) { if (dirty.width > 0) { /* capture */ }; captureDirty(dirty) }
                override fun onClipboard(text: String) { clipboard = text }
                fun captureDirty(d: DirtyRect) { dirty = d }
            })
        }
        server.join(2000)
        val fb = c.framebuffer
        assertArrayEquals(
            intArrayOf(0xFF112233.toInt(), 0xFF445566.toInt(), 0, 0, 0, 0, 0xFF112233.toInt(), 0xFF445566.toInt()),
            fb,
        )
        assertEquals(0, dirty!!.left); assertEquals(4, dirty!!.right); assertEquals(2, dirty!!.bottom)
        assertEquals("from linux", clipboard)
    }

    private val tv = ExternalDisplay(
        id = 2, name = "TV", current = DisplayModeInfo(1, 1920, 1080, 60f),
        modes = listOf(
            DisplayModeInfo(1, 1920, 1080, 60f), DisplayModeInfo(2, 1920, 1080, 120f),
            DisplayModeInfo(3, 3840, 2160, 60f), DisplayModeInfo(4, 3840, 2160, 30f),
            DisplayModeInfo(5, 2560, 1440, 60f), DisplayModeInfo(6, 7680, 4320, 30f),
        ).sortedWith(compareByDescending<DisplayModeInfo> { it.width * it.height }.thenByDescending { it.refresh }),
        xdpi = 50f, ydpi = 50f, densityDpi = 160,
    )

    @Test fun modeSelection() {
        assertEquals(3, DisplayWatcher.pickMode(tv, "auto", 0).id)          // native, capped at 4K, best refresh
        assertEquals(2, DisplayWatcher.pickMode(tv, "1920x1080", 0).id)     // highest refresh for 1080p
        assertEquals(1, DisplayWatcher.pickMode(tv, "1920x1080", 60).id)
        assertEquals(5, DisplayWatcher.pickMode(tv, "2560x1440", 0).id)
        assertEquals(192, DisplayWatcher.autoDpi(tv, DisplayModeInfo(3, 3840, 2160, 60f)))
        assertEquals(96, DisplayWatcher.autoDpi(tv, DisplayModeInfo(1, 1920, 1080, 60f)))
    }
}
