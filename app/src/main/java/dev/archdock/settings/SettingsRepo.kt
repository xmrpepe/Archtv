package dev.archdock.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class GpuMode(val label: String) {
    /** Venus (guest) → vtest → virglrenderer + Mesa wrapper ICD (Android) → Mali; GL via Zink. */
    VULKAN_WRAPPER("Vulkan wrapper (Mali) + Zink"),
    /** OpenGL only: virpipe (guest) → vtest → virglrenderer on Mali GLES. */
    VIRGL("VirGL OpenGL (Mali GLES)"),
    LLVMPIPE("Software (llvmpipe)"),
}

enum class DisplayBackend(val label: String) {
    AUTO("Automatic (X11, VNC fallback)"),
    X11("Embedded X11 (Termux:X11)"),
    VNC("VNC (TigerVNC on localhost)"),
}

enum class ExternalLaunchMode(val label: String) {
    ACTIVITY("Activity on the external display"),
    PRESENTATION("Presentation window"),
}

data class Settings(
    /** "auto" or "WIDTHxHEIGHT". */
    val resolution: String = "auto",
    /** 0 = highest the TV offers for the chosen resolution. */
    val refreshRate: Int = 0,
    /** 0 = derived from the TV's physical size; otherwise the X DPI. */
    val dpi: Int = 0,
    /** Extra UI scale for MATE (1 or 2) – GDK_SCALE. */
    val uiScale: Int = 1,
    val gpuMode: GpuMode = GpuMode.VULKAN_WRAPPER,
    val theme: String = "tokyo-night",
    val autoStartOnConnect: Boolean = true,
    val keepRunningOnDisconnect: Boolean = true,
    val compositor: Boolean = true,
    val blur: Boolean = true,
    val shadows: Boolean = true,
    val backend: DisplayBackend = DisplayBackend.AUTO,
    val launchMode: ExternalLaunchMode = ExternalLaunchMode.ACTIVITY,
    val perfOverlay: Boolean = false,
    val clipboardSync: Boolean = true,
    val sharedFolder: Boolean = true,
    val audio: Boolean = true,
    val pinBigCores: Boolean = true,
    val mirror: String = DEFAULT_MIRROR,
    val batteryPromptShown: Boolean = false,
) {
    companion object {
        const val DEFAULT_MIRROR = "http://os.archlinuxarm.org/os/"
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepo(private val context: Context) {
    private object K {
        val resolution = stringPreferencesKey("resolution")
        val refresh = intPreferencesKey("refresh")
        val dpi = intPreferencesKey("dpi")
        val uiScale = intPreferencesKey("ui_scale")
        val gpu = stringPreferencesKey("gpu_mode")
        val theme = stringPreferencesKey("theme")
        val autoStart = booleanPreferencesKey("auto_start")
        val keepRunning = booleanPreferencesKey("keep_running")
        val compositor = booleanPreferencesKey("compositor")
        val blur = booleanPreferencesKey("blur")
        val shadows = booleanPreferencesKey("shadows")
        val backend = stringPreferencesKey("backend")
        val launchMode = stringPreferencesKey("launch_mode")
        val overlay = booleanPreferencesKey("perf_overlay")
        val clipboard = booleanPreferencesKey("clipboard")
        val shared = booleanPreferencesKey("shared_folder")
        val audio = booleanPreferencesKey("audio")
        val pin = booleanPreferencesKey("pin_big_cores")
        val mirror = stringPreferencesKey("mirror")
        val batteryPrompt = booleanPreferencesKey("battery_prompt")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { fromPrefs(it) }

    private fun fromPrefs(p: Preferences): Settings {
        val d = Settings()
        return Settings(
            resolution = p[K.resolution] ?: d.resolution,
            refreshRate = p[K.refresh] ?: d.refreshRate,
            dpi = p[K.dpi] ?: d.dpi,
            uiScale = p[K.uiScale] ?: d.uiScale,
            gpuMode = p[K.gpu]?.let { runCatching { GpuMode.valueOf(it) }.getOrNull() } ?: d.gpuMode,
            theme = p[K.theme] ?: d.theme,
            autoStartOnConnect = p[K.autoStart] ?: d.autoStartOnConnect,
            keepRunningOnDisconnect = p[K.keepRunning] ?: d.keepRunningOnDisconnect,
            compositor = p[K.compositor] ?: d.compositor,
            blur = p[K.blur] ?: d.blur,
            shadows = p[K.shadows] ?: d.shadows,
            backend = p[K.backend]?.let { runCatching { DisplayBackend.valueOf(it) }.getOrNull() } ?: d.backend,
            launchMode = p[K.launchMode]?.let { runCatching { ExternalLaunchMode.valueOf(it) }.getOrNull() } ?: d.launchMode,
            perfOverlay = p[K.overlay] ?: d.perfOverlay,
            clipboardSync = p[K.clipboard] ?: d.clipboardSync,
            sharedFolder = p[K.shared] ?: d.sharedFolder,
            audio = p[K.audio] ?: d.audio,
            pinBigCores = p[K.pin] ?: d.pinBigCores,
            mirror = p[K.mirror] ?: d.mirror,
            batteryPromptShown = p[K.batteryPrompt] ?: d.batteryPromptShown,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { p ->
            val s = transform(fromPrefs(p))
            p[K.resolution] = s.resolution
            p[K.refresh] = s.refreshRate
            p[K.dpi] = s.dpi
            p[K.uiScale] = s.uiScale
            p[K.gpu] = s.gpuMode.name
            p[K.theme] = s.theme
            p[K.autoStart] = s.autoStartOnConnect
            p[K.keepRunning] = s.keepRunningOnDisconnect
            p[K.compositor] = s.compositor
            p[K.blur] = s.blur
            p[K.shadows] = s.shadows
            p[K.backend] = s.backend.name
            p[K.launchMode] = s.launchMode.name
            p[K.overlay] = s.perfOverlay
            p[K.clipboard] = s.clipboardSync
            p[K.shared] = s.sharedFolder
            p[K.audio] = s.audio
            p[K.pin] = s.pinBigCores
            p[K.mirror] = s.mirror
            p[K.batteryPrompt] = s.batteryPromptShown
        }
    }
}
