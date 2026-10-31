package net.seilent.funecomp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.UUID

object SvcNotice {
    const val CHANNEL = "sign_in"
    const val OLD_CHANNEL = "signin"
    const val ID = 4
    const val TEXT = "sign in"
    private const val REQUEST = 2
    private const val SILENT = "silent"
    const val PREFS = "signin"
    private const val NONCE = "nonce"

    fun post(c: Context, name: String, silent: Boolean = false): Boolean {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return false
        if (nm.getNotificationChannel(OLD_CHANNEL) != null) nm.deleteNotificationChannel(OLD_CHANNEL)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sign in", NotificationManager.IMPORTANCE_HIGH))
        if (!nm.areNotificationsEnabled() || nm.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val nonce = UUID.randomUUID().toString()
        prefs(c).edit().putString(NONCE, nonce).apply()
        val intent = Intent(c, Main::class.java).setAction(Main.ACTION_SIGN_IN).putExtra(Main.EXTRA_SIGN_IN_NONCE, nonce)
        val open = PendingIntent.getActivity(c, REQUEST, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(
            ID,
            Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(name)
                .setContentText(TEXT)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .apply { if (silent) setGroup(SILENT).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY) }
                .build(),
        )
        return true
    }

    fun take(c: Context, nonce: String?): Boolean {
        val p = prefs(c)
        if (nonce == null || nonce != p.getString(NONCE, null)) return false
        p.edit().remove(NONCE).apply()
        return true
    }

    fun clear(c: Context) {
        c.getSystemService(NotificationManager::class.java)?.cancel(ID)
        prefs(c).edit().remove(NONCE).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
