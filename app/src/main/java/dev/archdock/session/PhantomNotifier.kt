package dev.archdock.session

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.archdock.ArchDockApp
import dev.archdock.R
import dev.archdock.ui.MainActivity

object PhantomNotifier {
    private const val ID = 4242

    fun notify(context: Context) {
        val open = PendingIntent.getActivity(
            context, 3,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "home"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, ArchDockApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Android killed the Linux desktop")
            .setContentText("Disable \"child process restrictions\" in Developer options")
            .setStyle(Notification.BigTextStyle().bigText(PhantomProcessGuard.instructions()))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID, n)
    }
}
