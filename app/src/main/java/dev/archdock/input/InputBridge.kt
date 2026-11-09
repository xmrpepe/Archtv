package dev.archdock.input

import android.view.KeyEvent
import dev.archdock.core.Logs
import dev.archdock.core.Paths
import dev.archdock.x11.XTestClient
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Sends input to the Linux desktop. All calls are non-blocking: events are
 * queued to one worker thread that owns the XTEST connection and reconnects
 * when the X server restarts.
 */
class InputBridge(private val paths: Paths, private val fallbackTyper: (String) -> Unit) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "input-bridge").apply { isDaemon = true } }
    private val client = AtomicReference<XTestClient?>(null)
    @Volatile var display = 0
    /** Accumulated fractional motion so slow trackpad drags still move. */
    private var fx = 0f
    private var fy = 0f

    val screenSize: Pair<Int, Int>? get() = client.get()?.let { it.screenWidth to it.screenHeight }

    private fun conn(): XTestClient? {
        client.get()?.let { if (it.isOpen) return it }
        val sock = paths.x11Socket(display)
        if (!sock.exists()) return null
        return runCatching { XTestClient.connect(sock) }
            .onFailure { Logs.w(Logs.Channel.X11, "XTEST connect failed: ${it.message}") }
            .getOrNull()?.also { client.set(it) }
    }

    private fun post(block: XTestClient.() -> Unit) = worker.execute {
        val c = conn() ?: return@execute
        try { c.block() } catch (e: Exception) {
            Logs.w(Logs.Channel.X11, "input send failed: ${e.message}")
            c.close(); client.set(null)
        }
    }

    fun moveBy(dx: Float, dy: Float) {
        fx += dx; fy += dy
        val ix = fx.toInt(); val iy = fy.toInt()
        if (ix == 0 && iy == 0) return
        fx -= ix; fy -= iy
        post { moveRelative(ix, iy) }
    }

    fun moveTo(x: Int, y: Int) = post { moveAbsolute(x, y) }
    fun button(button: Int, down: Boolean) = post { button(button, down) }
    fun click(button: Int = 1) = post { click(button) }
    fun scroll(steps: Int, horizontal: Int = 0) = post { scroll(steps, horizontal) }

    fun evdevKey(code: Int, down: Boolean) = post { key(code + 8, down) }

    /** Returns true if the key was mapped and forwarded. */
    fun androidKey(event: KeyEvent): Boolean {
        val code = KeyMap.evdev(event.keyCode) ?: return false
        if (event.action == KeyEvent.ACTION_MULTIPLE) return false
        evdevKey(code, event.action == KeyEvent.ACTION_DOWN)
        return true
    }

    /** Presses a chord, e.g. Super+Return: modifiers down, key tap, modifiers up. */
    fun chord(vararg codes: Int) = post {
        codes.forEach { key(it + 8, true) }
        codes.reversed().forEach { key(it + 8, false) }
    }

    fun typeText(text: String) {
        val unmapped = StringBuilder()
        for (c in text) {
            val m = KeyMap.forChar(c)
            if (m == null) { unmapped.append(c); continue }
            val (code, shift) = m
            post {
                if (shift) key(KeyMap.EV_LEFTSHIFT + 8, true)
                key(code + 8, true); key(code + 8, false)
                if (shift) key(KeyMap.EV_LEFTSHIFT + 8, false)
            }
        }
        // Non-ASCII (accents, emoji, CJK) needs the keymap; let xdotool do it in the guest.
        if (unmapped.isNotEmpty()) fallbackTyper(unmapped.toString())
    }

    fun reset() = worker.execute { client.getAndSet(null)?.close() }
}
