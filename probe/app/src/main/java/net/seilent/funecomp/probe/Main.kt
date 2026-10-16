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
import java.lang.reflect.InvocationTargetException
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
        val uuid = intent.getStringExtra("uuid")?.let { UUID.fromString(it) }
        val mode = intent.getStringExtra("mode") ?: "push"
        val n = intent.getIntExtra("n", 0)
        val idle = intent.getIntExtra("idle", 0)
        val dev = getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(addr)
        Thread {
            try {
                when (mode) {
                    "sdp" -> sdp(dev, requireNotNull(uuid) { "no uuid" })
                    "push" -> push(dev, requireNotNull(uuid) { "no uuid" }, n, idle)
                    "bond" -> bond(dev)
                    "unbond" -> unbond(dev)
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

    private fun bond(dev: BluetoothDevice) {
        say("bond ${dev.bondState}")
        val done = CountDownLatch(1)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (d?.address != dev.address) return
                when (i.action) {
                    BluetoothDevice.ACTION_PAIRING_REQUEST -> say(
                        "pairing request variant ${i.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)} key ${i.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, -1)}",
                    )
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val from = i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                        val to = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                        say("bond state $from to $to")
                        if (to == BluetoothDevice.BOND_BONDED || (to == BluetoothDevice.BOND_NONE && from == BluetoothDevice.BOND_BONDING)) done.countDown()
                    }
                }
            }
        }
        registerReceiver(r, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED).apply { addAction(BluetoothDevice.ACTION_PAIRING_REQUEST) }, RECEIVER_EXPORTED)
        try {
            val t0 = now()
            val started = dev.createBond()
            say("createBond $started")
            if (!started) return
            say(if (done.await(BOND_WAIT_S, TimeUnit.SECONDS)) "bond ${dev.bondState} in ${now() - t0} ms" else "bond timeout, state ${dev.bondState}")
        } finally {
            unregisterReceiver(r)
        }
    }

    private fun unbond(dev: BluetoothDevice) {
        say("bond ${dev.bondState}")
        val result = try {
            dev.javaClass.getMethod("removeBond").invoke(dev)
        } catch (e: InvocationTargetException) {
            "threw ${e.cause}"
        }
        say("removeBond $result")
        Thread.sleep(UNBOND_WAIT_MS)
        say("bond ${dev.bondState}")
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

    companion object {
        const val BOND_WAIT_S = 90L
        const val UNBOND_WAIT_MS = 3000L
    }
}
