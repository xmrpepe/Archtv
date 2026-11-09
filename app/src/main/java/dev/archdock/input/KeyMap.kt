package dev.archdock.input

import android.view.KeyEvent

/**
 * Android KeyEvent keycodes → Linux evdev codes. X servers using the evdev
 * XKB rules (Termux:X11, Xvnc) use keycode = evdev + 8.
 * META_LEFT/META_RIGHT map to Super_L/Super_R so Omarchy-style Super
 * bindings work from Bluetooth/USB keyboards.
 */
object KeyMap {
    const val EV_LEFTSHIFT = 42
    const val EV_LEFTCTRL = 29
    const val EV_LEFTALT = 56
    const val EV_LEFTMETA = 125
    const val EV_ENTER = 28
    const val EV_SPACE = 57
    const val EV_ESC = 1
    const val EV_TAB = 15
    const val EV_BACKSPACE = 14
    const val EV_UP = 103
    const val EV_DOWN = 108
    const val EV_LEFT = 105
    const val EV_RIGHT = 106
    const val EV_PRINT = 99

    private val table: Map<Int, Int> = buildMap {
        put(KeyEvent.KEYCODE_ESCAPE, 1)
        val digits = listOf(KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4,
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_9)
        digits.forEachIndexed { i, k -> put(k, 2 + i) }
        put(KeyEvent.KEYCODE_0, 11)
        put(KeyEvent.KEYCODE_MINUS, 12); put(KeyEvent.KEYCODE_EQUALS, 13)
        put(KeyEvent.KEYCODE_DEL, 14); put(KeyEvent.KEYCODE_TAB, 15)
        "qwertyuiop".forEachIndexed { i, c -> put(letter(c), 16 + i) }
        put(KeyEvent.KEYCODE_LEFT_BRACKET, 26); put(KeyEvent.KEYCODE_RIGHT_BRACKET, 27)
        put(KeyEvent.KEYCODE_ENTER, 28); put(KeyEvent.KEYCODE_CTRL_LEFT, 29)
        "asdfghjkl".forEachIndexed { i, c -> put(letter(c), 30 + i) }
        put(KeyEvent.KEYCODE_SEMICOLON, 39); put(KeyEvent.KEYCODE_APOSTROPHE, 40)
        put(KeyEvent.KEYCODE_GRAVE, 41); put(KeyEvent.KEYCODE_SHIFT_LEFT, 42)
        put(KeyEvent.KEYCODE_BACKSLASH, 43)
        "zxcvbnm".forEachIndexed { i, c -> put(letter(c), 44 + i) }
        put(KeyEvent.KEYCODE_COMMA, 51); put(KeyEvent.KEYCODE_PERIOD, 52); put(KeyEvent.KEYCODE_SLASH, 53)
        put(KeyEvent.KEYCODE_SHIFT_RIGHT, 54); put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, 55)
        put(KeyEvent.KEYCODE_ALT_LEFT, 56); put(KeyEvent.KEYCODE_SPACE, 57); put(KeyEvent.KEYCODE_CAPS_LOCK, 58)
        val f = listOf(KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_F3, KeyEvent.KEYCODE_F4,
            KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F6, KeyEvent.KEYCODE_F7, KeyEvent.KEYCODE_F8,
            KeyEvent.KEYCODE_F9, KeyEvent.KEYCODE_F10)
        f.forEachIndexed { i, k -> put(k, 59 + i) }
        put(KeyEvent.KEYCODE_NUM_LOCK, 69); put(KeyEvent.KEYCODE_SCROLL_LOCK, 70)
        put(KeyEvent.KEYCODE_NUMPAD_7, 71); put(KeyEvent.KEYCODE_NUMPAD_8, 72); put(KeyEvent.KEYCODE_NUMPAD_9, 73)
        put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, 74)
        put(KeyEvent.KEYCODE_NUMPAD_4, 75); put(KeyEvent.KEYCODE_NUMPAD_5, 76); put(KeyEvent.KEYCODE_NUMPAD_6, 77)
        put(KeyEvent.KEYCODE_NUMPAD_ADD, 78)
        put(KeyEvent.KEYCODE_NUMPAD_1, 79); put(KeyEvent.KEYCODE_NUMPAD_2, 80); put(KeyEvent.KEYCODE_NUMPAD_3, 81)
        put(KeyEvent.KEYCODE_NUMPAD_0, 82); put(KeyEvent.KEYCODE_NUMPAD_DOT, 83)
        put(KeyEvent.KEYCODE_F11, 87); put(KeyEvent.KEYCODE_F12, 88)
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, 96); put(KeyEvent.KEYCODE_CTRL_RIGHT, 97)
        put(KeyEvent.KEYCODE_NUMPAD_DIVIDE, 98); put(KeyEvent.KEYCODE_SYSRQ, 99)
        put(KeyEvent.KEYCODE_ALT_RIGHT, 100)
        put(KeyEvent.KEYCODE_MOVE_HOME, 102); put(KeyEvent.KEYCODE_DPAD_UP, 103); put(KeyEvent.KEYCODE_PAGE_UP, 104)
        put(KeyEvent.KEYCODE_DPAD_LEFT, 105); put(KeyEvent.KEYCODE_DPAD_RIGHT, 106); put(KeyEvent.KEYCODE_MOVE_END, 107)
        put(KeyEvent.KEYCODE_DPAD_DOWN, 108); put(KeyEvent.KEYCODE_PAGE_DOWN, 109)
        put(KeyEvent.KEYCODE_INSERT, 110); put(KeyEvent.KEYCODE_FORWARD_DEL, 111)
        put(KeyEvent.KEYCODE_VOLUME_MUTE, 113); put(KeyEvent.KEYCODE_MUTE, 113)
        put(KeyEvent.KEYCODE_BREAK, 119)
        put(KeyEvent.KEYCODE_META_LEFT, 125); put(KeyEvent.KEYCODE_META_RIGHT, 126)
        put(KeyEvent.KEYCODE_MENU, 127)
        put(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 164); put(KeyEvent.KEYCODE_MEDIA_NEXT, 163)
        put(KeyEvent.KEYCODE_MEDIA_PREVIOUS, 165)
        put(KeyEvent.KEYCODE_BRIGHTNESS_DOWN, 224); put(KeyEvent.KEYCODE_BRIGHTNESS_UP, 225)
    }

    private fun letter(c: Char) = KeyEvent.KEYCODE_A + (c - 'a')

    /** evdev code for an Android keycode, or null. */
    fun evdev(androidKeyCode: Int): Int? = table[androidKeyCode]

    fun x11(androidKeyCode: Int): Int? = evdev(androidKeyCode)?.plus(8)

    /** US-layout chars → (evdev code, needs shift). */
    private val chars: Map<Char, Pair<Int, Boolean>> = buildMap {
        "1234567890".forEachIndexed { i, c -> put(c, (2 + i) to false) }
        "!@#$%^&*()".forEachIndexed { i, c -> put(c, (2 + i) to true) }
        "qwertyuiop".forEachIndexed { i, c -> put(c, (16 + i) to false); put(c.uppercaseChar(), (16 + i) to true) }
        "asdfghjkl".forEachIndexed { i, c -> put(c, (30 + i) to false); put(c.uppercaseChar(), (30 + i) to true) }
        "zxcvbnm".forEachIndexed { i, c -> put(c, (44 + i) to false); put(c.uppercaseChar(), (44 + i) to true) }
        put('-', 12 to false); put('_', 12 to true); put('=', 13 to false); put('+', 13 to true)
        put('[', 26 to false); put('{', 26 to true); put(']', 27 to false); put('}', 27 to true)
        put(';', 39 to false); put(':', 39 to true); put('\'', 40 to false); put('"', 40 to true)
        put('`', 41 to false); put('~', 41 to true); put('\\', 43 to false); put('|', 43 to true)
        put(',', 51 to false); put('<', 51 to true); put('.', 52 to false); put('>', 52 to true)
        put('/', 53 to false); put('?', 53 to true)
        put(' ', 57 to false); put('\n', 28 to false); put('\t', 15 to false)
    }

    fun forChar(c: Char): Pair<Int, Boolean>? = chars[c]
}
