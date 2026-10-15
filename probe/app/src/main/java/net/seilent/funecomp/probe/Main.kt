package net.seilent.funecomp.probe

import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class Main : Activity() {
    private lateinit var out: TextView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        out = TextView(this)
        setContentView(ScrollView(this).apply { addView(out) })
        val addr = intent.getStringExtra("addr") ?: return say("no addr")
        val uuid = UUID.fromString(intent.getStringExtra("uuid") ?: return say("no uuid"))
        val mode = intent.getStringExtra("mode") ?: "push"
        val n = intent.getIntExtra("n", 0)
        val idle = intent.getIntExtra("idle", 0)
        val dev = getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(addr)
        Thread {
            try {
                when (mode) {
                    "sdp" -> sdp(dev, uuid)
                    "push" -> push(dev, uuid, n, idle)
                    else -> say("unknown mode $mode")
                }
            } catch (e: Exception) {
                say("error ${e.javaClass.simpleName}: ${e.message}")
            }
            say("END")
        }.start()
    }

    private fun say(s: String) {
        Log.i("LinkProbe", s)
        runOnUiThread { out.append("$s\n") }
    }

    private fun now() = SystemClock.elapsedRealtime()

    private fun sdp(dev: BluetoothDevice, uuid: UUID) {
        say("bond ${dev.bondState} cached ${dev.uuids?.joinToString() ?: "none"}")
        val done = CountDownLatch(1)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (d?.address != dev.address) return
                val u = i.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID, ParcelUuid::class.java)
                say("sdp ${u?.joinToString() ?: "none"}")
                say("sdp has probe uuid ${u?.any { it.uuid == uuid } == true}")
                done.countDown()
            }
        }
        registerReceiver(r, IntentFilter(BluetoothDevice.ACTION_UUID), RECEIVER_EXPORTED)
        val t0 = now()
        say("fetchUuidsWithSdp ${dev.fetchUuidsWithSdp()}")
        say(if (done.await(30, TimeUnit.SECONDS)) "sdp in ${now() - t0} ms" else "sdp timeout")
        unregisterReceiver(r)
    }

    private fun push(dev: BluetoothDevice, uuid: UUID, n: Int, idle: Int) {
        val s = dev.createRfcommSocketToServiceRecord(uuid)
        val t0 = now()
        s.connect()
        val t1 = now()
        say("connected in ${t1 - t0} ms")
        val inp = s.inputStream
        val buf = ByteArray(4096)
        var got = 0
        var bad = 0
        var first = 0L
        while (got < n) {
            val k = inp.read(buf)
            if (k < 0) break
            if (got == 0) first = now()
            for (i in 0 until k) if (buf[i] != ((got + i) % 1024 and 0xff).toByte()) bad++
            got += k
        }
        val t2 = now()
        if (n > 0) {
            val ms = t2 - first
            say("received $got of $n bytes, $bad bad, first byte ${first - t1} ms after connect, rest in $ms ms, ${if (ms > 0) got * 1000L / ms else 0} B/s")
        }
        if (idle > 0) {
            say("idle $idle s")
            Thread.sleep(idle * 1000L)
        }
        val cmd = ByteArray(20) { it.toByte() }
        val t3 = now()
        s.outputStream.write(cmd)
        s.outputStream.flush()
        var echo = 0
        while (echo < cmd.size) {
            val k = inp.read(buf)
            if (k < 0) break
            echo += k
        }
        say("20 byte command echoed $echo bytes in ${now() - t3} ms")
        s.close()
    }
}
