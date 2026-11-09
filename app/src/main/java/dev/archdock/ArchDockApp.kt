package dev.archdock

import android.app.NotificationChannel
import android.app.NotificationManager
import dev.archdock.core.CrashHandler
import dev.archdock.core.Logs
import dev.archdock.core.Paths
import dev.archdock.display.ExternalWindowConfigurator

class ArchDockApp : AppBase() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        val paths = Paths(this)
        paths.ensureDirs()
        Logs.init(paths.logs)
        CrashHandler.install(paths.logs)
        createChannels()
        graph = AppGraph(this, paths)
        graph.displays.start()
        registerActivityLifecycleCallbacks(ExternalWindowConfigurator { graph.session.preferredModeId })
        Logs.i(Logs.Channel.APP, "ArchDock ${BuildConfig.VERSION_NAME} started (X11 embedded: ${BuildConfig.X11_EMBEDDED})")
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SESSION, getString(R.string.channel_session), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    companion object {
        const val CHANNEL_SESSION = "session"
        const val CHANNEL_ALERTS = "alerts"
    }
}

val android.content.Context.graph: AppGraph get() = (applicationContext as ArchDockApp).graph
