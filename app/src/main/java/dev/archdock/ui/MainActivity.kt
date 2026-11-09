package dev.archdock.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.archdock.graph
import dev.archdock.input.ControlActivity
import dev.archdock.session.Phase
import dev.archdock.session.SessionService
import dev.archdock.ui.screens.GpuTestScreen
import dev.archdock.ui.screens.HomeScreen
import dev.archdock.ui.screens.InstallerScreen
import dev.archdock.ui.screens.LogsScreen
import dev.archdock.ui.screens.SettingsScreen
import dev.archdock.ui.screens.TerminalScreen
import dev.archdock.ui.theme.ArchDockTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

enum class Route { HOME, INSTALLER, SETTINGS, GPU_TEST, LOGS, TERMINAL }

class MainActivity : ComponentActivity() {
    private var route by mutableStateOf(Route.HOME)
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { pushClipboard() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        val g = graph
        if (!g.installer.isInstalled && route == Route.HOME) route = Route.INSTALLER
        setContent {
            ArchDockTheme {
                BackHandler(enabled = route != Route.HOME) { route = Route.HOME }
                when (route) {
                    Route.HOME -> HomeScreen(onNavigate = { route = it }, onOpenTrackpad = ::openTrackpad)
                    Route.INSTALLER -> InstallerScreen(onDone = { route = Route.HOME })
                    Route.SETTINGS -> SettingsScreen(onBack = { route = Route.HOME }, onNavigate = { route = it })
                    Route.GPU_TEST -> GpuTestScreen(onBack = { route = Route.HOME })
                    Route.LOGS -> LogsScreen(onBack = { route = Route.HOME })
                    Route.TERMINAL -> TerminalScreen(onBack = { route = Route.HOME })
                }
            }
        }
        requestPermissionsOnce()
        observeDisplay()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_ROUTE)) {
            "terminal" -> route = Route.TERMINAL
            "settings" -> route = Route.SETTINGS
            "home" -> route = Route.HOME
        }
    }

    /**
     * Idle until a display shows up, then auto-start (or resume) the session
     * and switch the phone into trackpad mode. Landscape is forced only while
     * a display is connected.
     */
    private fun observeDisplay() {
        val g = graph
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(g.displays.external, g.session.state) { ext, s -> ext to s.phase }
                    .distinctUntilChanged()
                    .collect { (ext, phase) ->
                        requestedOrientation = if (ext != null) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        else ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                        val s = g.settings.current()
                        if (ext != null && g.installer.isInstalled && s.autoStartOnConnect &&
                            (phase == Phase.IDLE || phase == Phase.DETACHED)) {
                            SessionService.send(this@MainActivity, SessionService.ACTION_START)
                        }
                        if (ext != null && phase == Phase.RUNNING && route == Route.HOME) openTrackpad()
                    }
            }
        }
    }

    fun openTrackpad() = startActivity(Intent(this, ControlActivity::class.java))

    override fun onResume() {
        super.onResume()
        getSystemService(ClipboardManager::class.java).addPrimaryClipChangedListener(clipListener)
        graph.gpu.quickCheck()
        graph.installer.refresh()
    }

    override fun onPause() {
        getSystemService(ClipboardManager::class.java).removePrimaryClipChangedListener(clipListener)
        super.onPause()
    }

    private fun pushClipboard() {
        val text = getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text != null && graph.session.clipboardEnabled) graph.session.androidClipboard.tryEmit(text)
    }

    @SuppressLint("BatteryLife")
    private fun requestPermissionsOnce() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        lifecycleScope.launch {
            val g = graph
            val s = g.settings.current()
            val pm = getSystemService(PowerManager::class.java)
            if (!s.batteryPromptShown && !pm.isIgnoringBatteryOptimizations(packageName)) {
                g.settings.update { it.copy(batteryPromptShown = true) }
                runCatching {
                    startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                }
            }
        }
    }

    companion object {
        const val EXTRA_ROUTE = "route"
    }
}
