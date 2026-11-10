package dev.archdock.input

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.archdock.graph
import dev.archdock.session.Phase
import dev.archdock.session.SessionService
import dev.archdock.settings.Settings
import dev.archdock.ui.theme.ArchDockTheme
import dev.archdock.ui.theme.TokyoNight
import kotlin.math.hypot

/**
 * While the desktop is on the TV, the phone becomes a trackpad plus an
 * on-screen keyboard with a Super key and sticky modifiers.
 *
 * Gestures: 1-finger move = pointer, tap = left click, 2-finger tap = right
 * click, 3-finger tap = middle click, 2-finger drag = scroll, long-press then
 * drag = drag-and-drop.
 */
class ControlActivity : ComponentActivity() {
    private lateinit var gamepad: GamepadMapper
    private val latched = mutableStateOf(setOf<Int>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gamepad = GamepadMapper(graph.input)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent { ArchDockTheme { ControlScreen() } }
    }

    /** Sends a key with any latched modifiers, then releases the latch. */
    private fun sendWithModifiers(code: Int) {
        val mods = latched.value.toIntArray()
        graph.input.chord(*mods, code)
        latched.value = emptySet()
    }

    private fun toggleLatch(code: Int) {
        latched.value = if (code in latched.value) latched.value - code else latched.value + code
    }

    @Composable
    private fun ControlScreen() {
        val g = graph
        val session by g.session.state.collectAsState()
        val settings by g.settings.settings.collectAsState(Settings())
        val perf by g.perf.sample.collectAsState()
        var keyboard by remember { mutableStateOf(false) }
        val mods by latched

        Column(Modifier.fillMaxSize().background(TokyoNight.bgDarker).safeDrawingPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Key("⌨", active = keyboard) { keyboard = !keyboard }
                Key("Super", active = KeyMap.EV_LEFTMETA in mods) { toggleLatch(KeyMap.EV_LEFTMETA) }
                Key("Ctrl", active = KeyMap.EV_LEFTCTRL in mods) { toggleLatch(KeyMap.EV_LEFTCTRL) }
                Key("Alt", active = KeyMap.EV_LEFTALT in mods) { toggleLatch(KeyMap.EV_LEFTALT) }
                Key("Shift", active = KeyMap.EV_LEFTSHIFT in mods) { toggleLatch(KeyMap.EV_LEFTSHIFT) }
                Key("Esc") { sendWithModifiers(KeyMap.EV_ESC) }
                Key("Tab") { sendWithModifiers(KeyMap.EV_TAB) }
                Key("←") { sendWithModifiers(KeyMap.EV_LEFT) }
                Key("↑") { sendWithModifiers(KeyMap.EV_UP) }
                Key("↓") { sendWithModifiers(KeyMap.EV_DOWN) }
                Key("→") { sendWithModifiers(KeyMap.EV_RIGHT) }
                Key("Launcher") { g.input.chord(KeyMap.EV_LEFTMETA, KeyMap.EV_SPACE) }
                Key("Menu") { g.input.chord(KeyMap.EV_LEFTMETA, KeyMap.EV_LEFTALT, KeyMap.EV_SPACE) }
                Key("Term") { g.input.chord(KeyMap.EV_LEFTMETA, KeyMap.EV_ENTER) }
                Key("Close") { g.input.chord(KeyMap.EV_LEFTMETA, 17 /* W */) }
                Key("PrtSc") { g.input.chord(KeyMap.EV_PRINT) }
                Key("BT") {
                    startActivity(android.content.Intent(this@ControlActivity, dev.archdock.ui.MainActivity::class.java)
                        .putExtra(dev.archdock.ui.MainActivity.EXTRA_ROUTE, "bluetooth"))
                }
                Key("Stop", color = TokyoNight.red) { SessionService.send(this@ControlActivity, SessionService.ACTION_STOP); finish() }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp)) {
                Trackpad(Modifier.fillMaxSize())
                Column(Modifier.align(Alignment.TopStart).padding(12.dp)) {
                    Text(
                        when (session.phase) {
                            Phase.RUNNING -> "Trackpad · ${session.displayName}"
                            Phase.STARTING -> session.message
                            Phase.DETACHED -> "No display – session in background"
                            else -> "Session ${session.phase.name.lowercase()}"
                        }, color = TokyoNight.fgDark, fontSize = 12.sp,
                    )
                    if (settings.perfOverlay) Text(
                        "CPU %.0f%% · %s · %s".format(perf.cpuPercent, perf.batteryTempC?.let { "%.1f°C".format(it) } ?: "", perf.thermalLabel),
                        color = if (perf.throttling) TokyoNight.red else TokyoNight.fgDark, fontSize = 11.sp)
                }
            }
            if (keyboard) KeyboardSink()
        }
    }

    @Composable
    private fun Key(label: String, active: Boolean = false, color: Color = TokyoNight.fgBright, onClick: () -> Unit) {
        FilledTonalButton(onClick = onClick, modifier = Modifier.height(38.dp),
            colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                containerColor = if (active) TokyoNight.accent else TokyoNight.bgLight,
                contentColor = if (active) TokyoNight.bgDark else color)) {
            Text(label, fontSize = 13.sp)
        }
    }

    @Composable
    private fun Trackpad(modifier: Modifier) {
        val input = graph.input
        val speed = 1.6f
        Box(
            modifier.clip(RoundedCornerShape(16.dp)).background(TokyoNight.bg)
                .border(1.dp, TokyoNight.selection, RoundedCornerShape(16.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false)
                        val start = first.uptimeMillis
                        var maxPointers = 1
                        var moved = 0f
                        var dragging = false
                        var scrollAcc = 0f
                        val longPressMs = 450L
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            maxPointers = maxOf(maxPointers, pressed.size)
                            if (pressed.isEmpty()) break
                            if (ev.type == PointerEventType.Move) {
                                val dx = pressed.map { it.position.x - it.previousPosition.x }.average().toFloat()
                                val dy = pressed.map { it.position.y - it.previousPosition.y }.average().toFloat()
                                moved += hypot(dx, dy)
                                if (pressed.size >= 2) {
                                    scrollAcc += dy
                                    val steps = (scrollAcc / 40f).toInt()
                                    if (steps != 0) { input.scroll(-steps); scrollAcc -= steps * 40f }
                                } else {
                                    if (!dragging && moved < 12f && ev.changes.first().uptimeMillis - start > longPressMs) {
                                        dragging = true; input.button(1, true)
                                    }
                                    // Simple acceleration: faster swipes travel further.
                                    val v = hypot(dx, dy)
                                    val accel = 1f + (v / 25f).coerceAtMost(2.5f)
                                    input.moveBy(dx * speed * accel, dy * speed * accel)
                                }
                            }
                            ev.changes.forEach { it.consume() }
                        }
                        if (dragging) input.button(1, false)
                        else if (moved < 18f) when (maxPointers) {
                            1 -> input.click(1)
                            2 -> input.click(3)
                            else -> input.click(2)
                        }
                    }
                },
        ) {
            Text("1 finger: move · tap: click · 2 fingers: scroll / right-click · long-press: drag",
                color = TokyoNight.muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp))
        }
    }

    /**
     * Invisible text field that turns IME input into key events. It always
     * holds one sentinel char so Backspace on "empty" input is still seen.
     */
    @Composable
    private fun KeyboardSink() {
        val sentinel = " "
        var value by remember { mutableStateOf(TextFieldValue(sentinel, TextRange(1))) }
        val focus = remember { FocusRequester() }
        val kb = LocalSoftwareKeyboardController.current
        BasicTextField(
            value = value,
            onValueChange = { nv ->
                val text = nv.text
                when {
                    text.isEmpty() -> sendWithModifiers(KeyMap.EV_BACKSPACE)
                    text.length > 1 -> {
                        val typed = text.substring(1)
                        if (latched.value.isNotEmpty() && typed.length == 1 && KeyMap.forChar(typed[0].lowercaseChar()) != null)
                            sendWithModifiers(KeyMap.forChar(typed[0].lowercaseChar())!!.first)
                        else graph.input.typeText(typed)
                    }
                }
                value = TextFieldValue(sentinel, TextRange(1))
            },
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false, keyboardType = KeyboardType.Password,
                capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { sendWithModifiers(KeyMap.EV_ENTER) }),
            modifier = Modifier.size(1.dp).focusRequester(focus),
        )
        androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus(); kb?.show() }
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { kb?.hide() } }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (gamepad.onKey(event)) return true
        // Hardware keyboards (Bluetooth/USB): forward everything we can map,
        // including the Meta/Super key. Volume keys stay with Android.
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN &&
            event.keyCode != KeyEvent.KEYCODE_BACK && event.device?.isVirtual == false && graph.input.androidKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (gamepad.onMotion(ev)) {
            window.decorView.postOnAnimation(object : Runnable {
                override fun run() { if (gamepad.active) { gamepad.tick(); window.decorView.postOnAnimation(this) } }
            })
            return true
        }
        if (ev.isFromSource(android.view.InputDevice.SOURCE_MOUSE) && ev.actionMasked == MotionEvent.ACTION_SCROLL) {
            graph.input.scroll(-ev.getAxisValue(MotionEvent.AXIS_VSCROLL).toInt()); return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }
}
