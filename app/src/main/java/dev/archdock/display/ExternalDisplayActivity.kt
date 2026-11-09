package dev.archdock.display

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.activity.ComponentActivity
import dev.archdock.graph
import dev.archdock.input.GamepadMapper
import dev.archdock.perf.PerfOverlayView
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Runs on the external display and shows the VNC framebuffer full screen.
 * Hardware keyboards/mice that send input to this display are forwarded to
 * the guest; the mouse is captured so it moves the X pointer relatively.
 */
class ExternalDisplayActivity : ComponentActivity() {
    private lateinit var surface: VncSurfaceView
    private lateinit var gamepad: GamepadMapper
    private var overlay: PerfOverlayView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instances += WeakReference(this)
        val g = graph
        gamepad = GamepadMapper(g.input)
        surface = VncSurfaceView(this, g.session.vncPort).apply {
            onFrame = { g.perf.frame() }
            onClipboard = { text -> if (g.session.clipboardEnabled) setAndroidClipboard(text) }
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val root = FrameLayout(this)
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        g.perf.fpsSource = true

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                g.settings.settings.collect { s ->
                    if (s.perfOverlay && overlay == null) {
                        overlay = PerfOverlayView(this@ExternalDisplayActivity).also {
                            root.addView(it, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(24, 48, 24, 0) })
                        }
                    } else if (!s.perfOverlay) { overlay?.let { root.removeView(it) }; overlay = null }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { g.perf.sample.collect { overlay?.show(it) } }
        }
        lifecycleScope.launch {
            g.session.androidClipboard.collect { text -> if (text != null) surface.sendClipboard(text) }
        }
        surface.startClient()
    }

    private fun setAndroidClipboard(text: String) = runOnUiThread {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Linux", text))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) surface.requestPointerCapture()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (gamepad.onKey(event)) return true
        if (graph.input.androidKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (gamepad.onMotion(ev)) { if (gamepad.active) surface.post(::gamepadLoop); return true }
        val input = graph.input
        if (ev.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE) || ev.isFromSource(InputDevice.SOURCE_TOUCHPAD)) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> input.moveBy(ev.getAxisValue(MotionEvent.AXIS_RELATIVE_X), ev.getAxisValue(MotionEvent.AXIS_RELATIVE_Y))
                MotionEvent.ACTION_SCROLL -> input.scroll(-ev.getAxisValue(MotionEvent.AXIS_VSCROLL).toInt(), ev.getAxisValue(MotionEvent.AXIS_HSCROLL).toInt())
                MotionEvent.ACTION_BUTTON_PRESS -> input.button(xButton(ev.actionButton), true)
                MotionEvent.ACTION_BUTTON_RELEASE -> input.button(xButton(ev.actionButton), false)
            }
            return true
        }
        if (ev.isFromSource(InputDevice.SOURCE_MOUSE)) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE ->
                    input.moveTo((ev.x * scaleX()).toInt(), (ev.y * scaleY()).toInt())
                MotionEvent.ACTION_SCROLL -> input.scroll(-ev.getAxisValue(MotionEvent.AXIS_VSCROLL).toInt())
                MotionEvent.ACTION_BUTTON_PRESS -> input.button(xButton(ev.actionButton), true)
                MotionEvent.ACTION_BUTTON_RELEASE -> input.button(xButton(ev.actionButton), false)
            }
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun gamepadLoop() {
        if (!gamepad.active) return
        gamepad.tick()
        surface.postOnAnimation(::gamepadLoop)
    }

    // Captured pointer events arrive via onCapturedPointerEvent on the focused view.
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        surface.setOnCapturedPointerListener { _, ev -> dispatchGenericMotionEvent(ev) }
    }

    private fun scaleX() = (graph.input.screenSize?.first ?: surface.width).toFloat() / surface.width.coerceAtLeast(1)
    private fun scaleY() = (graph.input.screenSize?.second ?: surface.height).toFloat() / surface.height.coerceAtLeast(1)

    private fun xButton(b: Int) = when (b) {
        MotionEvent.BUTTON_SECONDARY -> 3
        MotionEvent.BUTTON_TERTIARY -> 2
        MotionEvent.BUTTON_BACK -> 8
        MotionEvent.BUTTON_FORWARD -> 9
        else -> 1
    }

    override fun onDestroy() {
        surface.stop()
        graph.perf.fpsSource = false
        instances.removeAll { it.get() == null || it.get() == this }
        super.onDestroy()
    }

    companion object {
        private val instances = mutableListOf<WeakReference<Activity>>()
        fun finishAll() = instances.mapNotNull { it.get() }.forEach { it.finish() }
    }
}
