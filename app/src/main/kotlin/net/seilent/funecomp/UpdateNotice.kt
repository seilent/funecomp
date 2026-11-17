package net.seilent.funecomp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import net.seilent.funecomp.ui.UPDATE_APP

object UpdateNotice {
    const val CHANNEL = "update"
    const val ID = 5
    private const val REQUEST = 3

    fun post(c: Context, version: String): Boolean {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return false
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App update", NotificationManager.IMPORTANCE_DEFAULT))
        if (!nm.areNotificationsEnabled() || nm.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val intent = Intent(c, Main::class.java).setAction(Main.ACTION_UPDATE)
        val open = PendingIntent.getActivity(c, REQUEST, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(
            ID,
            Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("funeCOMP $version")
                .setContentText(UPDATE_APP)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
        return true
    }

    fun clear(c: Context) {
        c.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }
}
