package dev.archdock.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Game controllers can't be handed to the guest as /dev/input devices (no
 * uinput without root), so they are mapped to mouse/keyboard:
 *  left stick → pointer, right stick → scroll, A → left click, B → right click,
 *  X → Super, Y → Return, D-pad → arrows, Start → Omarchy menu (Super+Alt+Space),
 *  Select → Escape, L1/R1 → previous/next workspace (Super+[ / Super+]).
 */
class GamepadMapper(private val input: InputBridge) {
    private var lx = 0f; private var ly = 0f; private var ry = 0f
    var speed = 18f

    fun isGamepad(source: Int) =
        source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK

    fun onMotion(e: MotionEvent): Boolean {
        if (!isGamepad(e.source)) return false
        lx = dz(e.getAxisValue(MotionEvent.AXIS_X)); ly = dz(e.getAxisValue(MotionEvent.AXIS_Y))
        ry = dz(e.getAxisValue(MotionEvent.AXIS_RZ))
        val hatX = e.getAxisValue(MotionEvent.AXIS_HAT_X); val hatY = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        if (hatX != 0f) input.chord(if (hatX < 0) KeyMap.EV_LEFT else KeyMap.EV_RIGHT)
        if (hatY != 0f) input.chord(if (hatY < 0) KeyMap.EV_UP else KeyMap.EV_DOWN)
        return true
    }

    /** Call ~60×/s while a stick is deflected (from a Choreographer callback). */
    fun tick() {
        if (lx != 0f || ly != 0f) input.moveBy(lx * abs(lx) * speed, ly * abs(ly) * speed)
        if (ry != 0f) input.scroll(if (ry > 0) 1 else -1)
    }

    val active get() = lx != 0f || ly != 0f || ry != 0f

    fun onKey(e: KeyEvent): Boolean {
        if (!isGamepad(e.source) && !KeyEvent.isGamepadButton(e.keyCode)) return false
        val down = e.action == KeyEvent.ACTION_DOWN
        if (e.repeatCount > 0) return true
        when (e.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> input.button(1, down)
            KeyEvent.KEYCODE_BUTTON_B -> input.button(3, down)
            KeyEvent.KEYCODE_BUTTON_X -> input.evdevKey(KeyMap.EV_LEFTMETA, down)
            KeyEvent.KEYCODE_BUTTON_Y -> input.evdevKey(KeyMap.EV_ENTER, down)
            KeyEvent.KEYCODE_BUTTON_SELECT -> input.evdevKey(KeyMap.EV_ESC, down)
            KeyEvent.KEYCODE_BUTTON_START -> if (down) input.chord(KeyMap.EV_LEFTMETA, KeyMap.EV_LEFTALT, KeyMap.EV_SPACE)
            KeyEvent.KEYCODE_BUTTON_L1 -> if (down) input.chord(KeyMap.EV_LEFTMETA, 26)
            KeyEvent.KEYCODE_BUTTON_R1 -> if (down) input.chord(KeyMap.EV_LEFTMETA, 27)
            KeyEvent.KEYCODE_DPAD_UP -> input.evdevKey(KeyMap.EV_UP, down)
            KeyEvent.KEYCODE_DPAD_DOWN -> input.evdevKey(KeyMap.EV_DOWN, down)
            KeyEvent.KEYCODE_DPAD_LEFT -> input.evdevKey(KeyMap.EV_LEFT, down)
            KeyEvent.KEYCODE_DPAD_RIGHT -> input.evdevKey(KeyMap.EV_RIGHT, down)
            else -> return false
        }
        return true
    }

    private fun dz(v: Float) = if (abs(v) < 0.15f) 0f else v
}
