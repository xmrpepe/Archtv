package dev.archdock.display

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.archdock.core.Logs
import dev.archdock.x11.DirtyRect
import dev.archdock.x11.VncClient
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Draws a VNC framebuffer full-screen. Xvnc is created at the TV's exact
 * resolution, so this is a 1:1 blit – no scaling, no letterboxing. Updates are
 * coalesced to the display's vsync.
 */
class VncSurfaceView(context: Context, private val port: Int) : SurfaceView(context), SurfaceHolder.Callback {
    private var client: VncClient? = null
    private var bitmap: Bitmap? = null
    private val pending = AtomicBoolean(false)
    private val dirty = Rect()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    @Volatile private var surfaceReady = false
    @Volatile private var stopped = false
    var onFrame: () -> Unit = {}
    var onClipboard: (String) -> Unit = {}

    init { holder.addCallback(this) }

    fun startClient() = thread(name = "vnc-client", isDaemon = true) {
        var attempt = 0
        while (!stopped) {
            try {
                val c = VncClient.connect(port = port)
                client = c
                attempt = 0
                c.run(object : VncClient.Listener {
                    override fun onResize(width: Int, height: Int) { synchronized(dirty) { bitmap = null } }
                    override fun onUpdate(dirty: DirtyRect) = schedule(dirty)
                    override fun onClipboard(text: String) = this@VncSurfaceView.onClipboard(text)
                })
            } catch (e: Exception) {
                if (stopped) break
                Logs.w(Logs.Channel.X11, "VNC connection lost: ${e.message}")
                Thread.sleep(minOf(250L shl minOf(attempt++, 4), 4000L))
            } finally {
                client?.close(); client = null
            }
        }
    }

    fun sendClipboard(text: String) = runCatching { client?.sendClipboard(text) }

    private fun schedule(r: DirtyRect) {
        synchronized(dirty) {
            if (dirty.isEmpty) dirty.set(r.left, r.top, r.right, r.bottom) else dirty.union(r.left, r.top, r.right, r.bottom)
        }
        if (pending.compareAndSet(false, true)) post { Choreographer.getInstance().postFrameCallback { draw() } }
    }

    private fun draw() {
        pending.set(false)
        val c = client ?: return
        if (!surfaceReady) return
        val r: Rect
        synchronized(dirty) { r = Rect(dirty); dirty.setEmpty() }
        synchronized(c.framebufferLock) {
            val w = c.width; val h = c.height
            if (w == 0 || h == 0) return
            val bmp = bitmap?.takeIf { it.width == w && it.height == h }
                ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap = it; r.set(0, 0, w, h) }
            r.intersect(0, 0, w, h)
            if (!r.isEmpty) bmp.setPixels(c.framebuffer, r.top * w + r.left, w, r.left, r.top, r.width(), r.height())
        }
        val bmp = bitmap ?: return
        val canvas = runCatching { holder.surface.lockHardwareCanvas() }.getOrNull() ?: return
        try {
            canvas.drawBitmap(bmp, null, Rect(0, 0, width, height), paint)
        } finally {
            holder.surface.unlockCanvasAndPost(canvas)
        }
        onFrame()
    }

    override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true; synchronized(dirty) { dirty.set(0, 0, 1 shl 14, 1 shl 14) }; post { draw() } }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { post { draw() } }
    override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false }

    fun stop() { stopped = true; client?.close() }
}
