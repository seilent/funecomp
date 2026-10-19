package net.seilent.funecomp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.UUID
import net.seilent.funecomp.artist.Names
import net.seilent.funecomp.ui.PICK_IMAGE
import net.seilent.funecomp.ui.PickFor

object PickNotice {
    const val CHANNEL = "pick"
    const val ID = 3
    private const val REQUEST = 1
    private const val PREFS = "pick"
    private const val NONCE = "nonce"

    fun post(c: Context, target: PickFor): Boolean {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return false
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Art pick", NotificationManager.IMPORTANCE_DEFAULT))
        if (!nm.areNotificationsEnabled() || nm.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val nonce = UUID.randomUUID().toString()
        prefs(c).edit().putString(NONCE, nonce).apply()
        val open = PendingIntent.getActivity(
            c, REQUEST,
            Intent(c, Main::class.java).setAction(Main.ACTION_PICK_ART).putExtra(Main.EXTRA_PICK_NAME, target.name)
                .putExtra(Main.EXTRA_PICK_MBID, target.mbid).putExtra(Main.EXTRA_PICK_NONCE, nonce),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            ID,
            Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(target.name)
                .setContentText(PICK_IMAGE)
                .setContentIntent(open)
                .setAutoCancel(true)
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

    fun done(c: Context, name: String) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        val shown = nm.activeNotifications.any { it.id == ID && Names.fold(it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()) == Names.fold(name) }
        if (shown) clear(c)
    }

    fun clear(c: Context) {
        c.getSystemService(NotificationManager::class.java)?.cancel(ID)
        prefs(c).edit().remove(NONCE).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
