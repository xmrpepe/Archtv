package dev.archdock.session

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.archdock.ArchDockApp
import dev.archdock.R
import dev.archdock.core.Logs
import dev.archdock.graph
import dev.archdock.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Foreground service (type specialUse) that owns the proot processes, the X
 * server and the audio bridge, so the desktop keeps running while the phone
 * sleeps or the app is in the background. It also runs the installer, so a
 * long install isn't killed when the user switches apps.
 */
class SessionService : LifecycleService() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var installJob: Job? = null
    private var watchJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification("Starting…", installing = false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        val g = graph
        lifecycleScope.launch {
            var wasBusy = false
            combine(g.session.state, g.installer.state) { s, i -> s to i }.collect { (s, i) ->
                val text = when {
                    i.running -> "Installing: ${i.message} (${(i.overall * 100).toInt()}%)"
                    s.phase == Phase.RUNNING -> "Desktop on ${s.displayName ?: "external display"} · ${s.backend?.name?.lowercase() ?: ""}"
                    s.phase == Phase.DETACHED -> "Desktop running in the background"
                    s.phase == Phase.STARTING -> s.message
                    s.phase == Phase.ERROR -> "Error: ${s.error}"
                    else -> "Idle – waiting for a display"
                }
                notifyUpdate(text, i.running)
                updateLocks(s.active)
                val busy = i.running || s.active || s.phase == Phase.STOPPING
                // Leave the foreground once a session/install that was running has ended.
                if (wasBusy && !busy) stopSelfIfIdle()
                wasBusy = busy
            }
        }
        watchDisplays()
    }

    private fun watchDisplays() {
        val g = graph
        watchJob = lifecycleScope.launch {
            g.displays.external.distinctUntilChanged { a, b -> a?.id == b?.id }.collect { ext ->
                val s = g.settings.current()
                val phase = g.session.state.value.phase
                if (ext != null) {
                    if (phase == Phase.DETACHED || phase == Phase.RUNNING ||
                        (s.autoStartOnConnect && g.installer.isInstalled && (phase == Phase.IDLE || phase == Phase.ERROR))) {
                        g.session.startOrAttach(ext)
                    }
                } else if (phase == Phase.RUNNING) {
                    g.session.detach()
                    if (!s.keepRunningOnDisconnect) g.session.stop()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val g = graph
        when (intent?.action) {
            ACTION_START -> lifecycleScope.launch { g.session.startOrAttach(g.displays.external.value) }
            ACTION_STOP -> lifecycleScope.launch { g.session.stop(); stopSelfIfIdle() }
            ACTION_RESTART -> lifecycleScope.launch { g.session.restart() }
            ACTION_INSTALL -> if (installJob?.isActive != true) {
                installJob = lifecycleScope.launch {
                    val ok = g.installer.run()
                    if (ok) {
                        Logs.i(Logs.Channel.INSTALL, "Installation complete")
                        g.displays.external.value?.let { ext ->
                            if (g.settings.current().autoStartOnConnect) g.session.startOrAttach(ext)
                        }
                    }
                    stopSelfIfIdle()
                }
            }
            ACTION_CANCEL_INSTALL -> installJob?.cancel()
        }
        return START_STICKY
    }

    private fun stopSelfIfIdle() {
        val g = graph
        if (!g.session.state.value.active && installJob?.isActive != true) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun updateLocks(active: Boolean) {
        if (active) {
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ArchDock:session").apply { setReferenceCounted(false); acquire() }
            }
            if (wifiLock == null) {
                wifiLock = getSystemService(WifiManager::class.java)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "ArchDock:session").apply { setReferenceCounted(false); acquire() }
            }
        } else {
            wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
            wifiLock?.let { if (it.isHeld) it.release() }; wifiLock = null
        }
    }

    private fun notifyUpdate(text: String, installing: Boolean) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text, installing))
    }

    private fun buildNotification(text: String, installing: Boolean): Notification {
        fun svc(action: String, req: Int) = PendingIntent.getService(
            this, req, Intent(this, SessionService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val terminal = PendingIntent.getActivity(this, 1,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "terminal")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(this, ArchDockApp.CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ArchDock")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(0xFF7AA2F7.toInt())
            .setContentIntent(open)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (installing) {
            b.addAction(Notification.Action.Builder(null, "Pause install", svc(ACTION_CANCEL_INSTALL, 14)).build())
        } else {
            b.addAction(Notification.Action.Builder(null, "Stop", svc(ACTION_STOP, 11)).build())
            b.addAction(Notification.Action.Builder(null, "Restart", svc(ACTION_RESTART, 12)).build())
            b.addAction(Notification.Action.Builder(null, "Open Terminal", terminal).build())
        }
        return b.build()
    }

    override fun onDestroy() {
        updateLocks(false)
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "dev.archdock.START"
        const val ACTION_STOP = "dev.archdock.STOP"
        const val ACTION_RESTART = "dev.archdock.RESTART"
        const val ACTION_INSTALL = "dev.archdock.INSTALL"
        const val ACTION_CANCEL_INSTALL = "dev.archdock.CANCEL_INSTALL"

        fun send(context: Context, action: String) {
            context.startForegroundService(Intent(context, SessionService::class.java).setAction(action))
        }

        /** Keeps the service (and its display watcher) alive while the app is idle on screen. */
        fun ensureRunning(context: Context) {
            context.startForegroundService(Intent(context, SessionService::class.java))
        }
    }
}
