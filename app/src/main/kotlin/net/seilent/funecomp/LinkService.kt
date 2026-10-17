package net.seilent.funecomp

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
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
    private var address: String? = null
    private var sinkToDap = false
    private val media by lazy { MediaLink(this, Remote) { stop() } }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_OFF -> Remote.bluetooth(false)
                    BluetoothAdapter.STATE_ON -> Remote.bluetooth(true)
                }
                Intent.ACTION_SCREEN_ON -> Remote.kick("screen")
                ACTION_ALARM -> Remote.kick("alarm")
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    val d = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (d != null && d.address.equals(address, ignoreCase = true)) {
                        sinkToDap = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED
                        refresh()
                    }
                }
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
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        }
        registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        Remote.onPhase = { main.post { refresh() } }
        Remote.onState = { main.post { refresh() } }
        Remote.onDrop = {
            main.post {
                media.closed(Remote.device)
                post()
            }
        }
        Remote.onTail = { at -> main.post { arm(at) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_MEDIA) {
            if (foreground) refresh() else stopSelf(startId)
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Link", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(MEDIA, "Media controls", NotificationManager.IMPORTANCE_LOW))
        try {
            startForeground(ID, notification(text()), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            foreground = true
        } catch (e: RuntimeException) {
            Log.w(Remote.TAG, "startForeground: ${e.message}")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val spec = intent?.getStringExtra(Main.EXTRA_LINK)
        address = spec?.takeIf { it.startsWith(RFCOMM) }?.removePrefix(RFCOMM)
        address?.let { sink(it) }
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
        media.off()
        Remote.onPhase = null
        Remote.onTail = null
        Remote.onState = null
        Remote.onDrop = null
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

    private fun sink(a: String) {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val on = try {
                    proxy.connectedDevices.any { it.address.equals(a, ignoreCase = true) }
                } catch (_: SecurityException) {
                    false
                }
                adapter.closeProfileProxy(profile, proxy)
                main.post {
                    sinkToDap = on
                    refresh()
                }
            }

            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)
    }

    private fun stop() {
        Log.i(Remote.TAG, "service stop")
        foreground = false
        media.off()
        Remote.stop()
        stopSelf()
    }

    private fun refresh() {
        when (Remote.phase) {
            Phase.LIVE -> media.live(Remote.device, MediaLink.controls(this), sinkToDap)
            Phase.DOWN, Phase.CONNECTING -> {}
            else -> media.off()
        }
        post()
    }

    private fun post() {
        if (!foreground) return
        val device = Remote.device
        val st = device.playback.state
        val t = if (media.active) "media ${st.title} ${MediaLink.artist(st)}" else text()
        if (t == posted) return
        posted = t
        val m = if (media.active) media.notification(MEDIA, open(), device) else null
        getSystemService(NotificationManager::class.java).notify(ID, m ?: notification(text()))
    }

    private fun open() = PendingIntent.getActivity(
        this, 0,
        Intent(this, Main::class.java).setAction(Main.ACTION_OPEN),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notification(text: String): Notification {
        val open = open()
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
        const val ACTION_MEDIA = "net.seilent.funecomp.MEDIA"
        private const val MEDIA = "media"
        private const val RFCOMM = "rfcomm:"
        private const val ACTION_ALARM = "net.seilent.funecomp.ALARM"
        private const val CHANNEL = "link"
        private const val ID = 1
    }
}
