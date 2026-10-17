package net.seilent.funecomp

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.statusText

class LinkService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var posted: String? = null
    private var foreground = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_OFF -> Remote.bluetooth(false)
                    BluetoothAdapter.STATE_ON -> Remote.bluetooth(true)
                }
                Intent.ACTION_SCREEN_ON -> Remote.kick("screen")
                ACTION_ALARM -> Remote.kick("alarm")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(ACTION_ALARM)
        }
        registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        Remote.onPhase = { main.post { post() } }
        Remote.onTail = { at -> main.post { arm(at) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(Remote.TAG, "service stop")
            foreground = false
            Remote.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Link", NotificationManager.IMPORTANCE_MIN))
        try {
            startForeground(ID, notification(text()), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            foreground = true
        } catch (e: RuntimeException) {
            Log.w(Remote.TAG, "startForeground: ${e.message}")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val spec = intent?.getStringExtra(Main.EXTRA_LINK)
        if (spec == null || !Remote.start(this, spec)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == false) Remote.bluetooth(false)
        posted = null
        post()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        foreground = false
        Remote.onPhase = null
        Remote.onTail = null
        unregisterReceiver(receiver)
        getSystemService(AlarmManager::class.java)?.cancel(alarm())
        Remote.stop()
        super.onDestroy()
    }

    private fun text(): String {
        val phase = Remote.phase
        return when {
            Remote.bluetoothOff -> "bluetooth off"
            phase != Phase.LIVE && getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true -> "restricted"
            else -> statusText(LinkView(phase, "", Remote.bye))
        }
    }

    private fun post() {
        if (!foreground) return
        val t = text()
        if (t == posted) return
        posted = t
        getSystemService(NotificationManager::class.java).notify(ID, notification(t))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, Main::class.java).setAction(Main.ACTION_OPEN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, LinkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("funecomp")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "stop", stop).build())
            .setOngoing(true)
            .build()
    }

    private fun alarm() = PendingIntent.getBroadcast(
        this, 0,
        Intent(ACTION_ALARM).setPackage(packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun arm(at: Long) {
        getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarm())
    }

    companion object {
        const val ACTION_STOP = "net.seilent.funecomp.STOP"
        private const val ACTION_ALARM = "net.seilent.funecomp.ALARM"
        private const val CHANNEL = "link"
        private const val ID = 1
    }
}
