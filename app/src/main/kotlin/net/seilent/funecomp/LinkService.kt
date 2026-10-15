package net.seilent.funecomp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

class LinkService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Link", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("funecomp")
            .setOngoing(true)
            .build()
        try {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: RuntimeException) {
            Log.w(Remote.TAG, "startForeground: ${e.message}")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val spec = intent?.getStringExtra(Main.EXTRA_LINK)
        if (spec == null || !Remote.start(this, spec)) stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Remote.stop()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "net.seilent.funecomp.STOP"
        private const val CHANNEL = "link"
    }
}
