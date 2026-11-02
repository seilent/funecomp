package net.seilent.funecomp

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import net.seilent.funecomp.proto.gen.Proto
import java.util.UUID

class BoardScan(
    private val context: Context,
    private val boards: Boards,
    private val linked: () -> String?,
    private val busy: () -> Boolean,
    private val toast: (String, String) -> Unit,
    private val bonded: (String) -> Unit,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val askProxy: (BluetoothProfile.ServiceListener) -> Boolean = { l -> context.getSystemService(BluetoothManager::class.java)?.adapter?.getProfileProxy(context, l, BluetoothProfile.A2DP) == true },
) {
    private val handler = Handler(Looper.getMainLooper())
    private val companion = UUID.fromString(Proto.uuid)
    private var started = false
    private var registered = false
    private var ready = false
    private var proxy: BluetoothA2dp? = null
    @Volatile private var ours = false
    private var roundAt = 0L
    private var sdpAt: String? = null
    private val sdpQueue = ArrayDeque<String>()
    private var pairing: Bonding? = null
    private val cancelHook = {
        if (ours) {
            try {
                adapter()?.cancelDiscovery()
            } catch (_: SecurityException) {
            }
            handler.post { cancel() }
        }
    }

    private class Bonding(val address: String, val name: String, val done: () -> Unit, var bonded: Boolean = false)

    private val roundTask = Runnable { round() }
    private val sdpTimeout = Runnable { sdpAt?.let { sdpDone(it, false) } }
    private val uuidTimeout = Runnable { pairing?.let { bondChecked(it, false) } }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_FOUND -> found(i)
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> finished()
                BluetoothDevice.ACTION_UUID -> uuids(i)
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> bond(i)
            }
        }
    }

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
            val a = p as? BluetoothA2dp
            if (!started || (proxy != null && proxy !== a)) {
                a?.let { x -> runCatching { adapter()?.closeProfileProxy(BluetoothProfile.A2DP, x) } }
                return
            }
            proxy = a
            Remote.mainA2dp = a
            becomeReady()
        }

        override fun onServiceDisconnected(profile: Int) {
            proxy = null
            Remote.mainA2dp = null
        }
    }

    private fun adapter(): BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    fun allowed() = granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT)

    private fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (started || !granted(Manifest.permission.BLUETOOTH_CONNECT)) return
        started = true
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_UUID)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        if (!registered) ContextCompat.registerReceiver(context.applicationContext, receiver, f, ContextCompat.RECEIVER_EXPORTED)
        registered = true
        Remote.scanCancel = cancelHook
        val asked = try {
            askProxy(listener)
        } catch (_: SecurityException) {
            false
        }
        if (!asked) becomeReady()
    }

    fun stop() {
        if (!started) return
        started = false
        cancel()
        ready = false
        handler.removeCallbacks(roundTask)
        handler.removeCallbacks(sdpTimeout)
        if (pairing == null) unregister()
        if (Remote.scanCancel === cancelHook) Remote.scanCancel = null
        proxy?.let { p -> runCatching { adapter()?.closeProfileProxy(BluetoothProfile.A2DP, p) } }
        proxy = null
        Remote.mainA2dp = null
        sdpAt?.let { boards.sdp(it, false, clock()) }
        sdpAt = null
        sdpQueue.clear()
    }

    private fun unregister() {
        if (!registered) return
        registered = false
        context.applicationContext.unregisterReceiver(receiver)
    }

    fun connectionOpened() {
        if (ready) round()
    }

    private fun becomeReady() {
        if (ready || !started) return
        ready = true
        round()
    }

    private fun round() {
        handler.removeCallbacks(roundTask)
        handler.postDelayed(roundTask, PERIOD_MS)
        boards.tick(clock())
        val a = adapter() ?: return
        if (!allowed() || pairing != null || sdpAt != null || busy() || boards.picking) return
        if (sdpQueue.isNotEmpty()) {
            sdpQueue.clear()
            return nextSdp(boards.pending(clock()))
        }
        try {
            if (!a.isEnabled || a.isDiscovering) return
            roundAt = clock()
            ours = a.startDiscovery()
        } catch (_: SecurityException) {
        }
    }

    fun cancel() {
        if (!ours) return
        ours = false
        try {
            adapter()?.cancelDiscovery()
        } catch (_: SecurityException) {
        }
        if (started && pairing == null) nextSdp(boards.pending(clock()))
    }

    private fun device(i: Intent) = IntentCompat.getParcelableExtra(i, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)

    private fun found(i: Intent) {
        if (!ours) return
        val d = device(i) ?: return
        try {
            val cls = IntentCompat.getParcelableExtra(i, BluetoothDevice.EXTRA_CLASS, BluetoothClass::class.java) ?: d.bluetoothClass
            val f = Find(d.address, d.name, cls?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO, d.uuids?.any { it.uuid == companion } == true, d.bondState != BluetoothDevice.BOND_NONE, d.address == linked())
            boards.found(f, clock(), roundAt)
        } catch (_: SecurityException) {
        }
    }

    private fun finished() {
        if (!ours) return
        ours = false
        nextSdp(boards.pending(clock()))
    }

    private fun nextSdp(more: List<String> = emptyList()) {
        more.filter { it != sdpAt && it !in sdpQueue }.let { sdpQueue.addAll(it) }
        if (sdpAt != null || busy() || boards.picking) return
        val a = sdpQueue.removeFirstOrNull() ?: return
        sdpAt = a
        handler.postDelayed(sdpTimeout, SDP_MS)
        val ok = try {
            adapter()?.getRemoteDevice(a)?.fetchUuidsWithSdp() == true
        } catch (_: SecurityException) {
            false
        }
        if (!ok) sdpDone(a, false)
    }

    private fun sdpDone(address: String, board: Boolean) {
        if (sdpAt != address) return
        handler.removeCallbacks(sdpTimeout)
        sdpAt = null
        boards.sdp(address, board, clock())
        nextSdp()
    }

    private fun uuids(i: Intent) {
        val d = device(i) ?: return
        val got = IntentCompat.getParcelableArrayExtra(i, BluetoothDevice.EXTRA_UUID, ParcelUuid::class.java)
        val board = got?.any { (it as? ParcelUuid)?.uuid == companion } == true
        val p = pairing
        if (p != null && p.bonded && p.address == d.address) return bondChecked(p, board)
        if (d.address == sdpAt && got != null) sdpDone(d.address, board)
    }

    private fun bond(i: Intent) {
        val d = device(i) ?: return
        val candidate = try {
            Boards.candidate(d.name, false)
        } catch (_: SecurityException) {
            false
        }
        if (candidate) boards.bump()
        val p = pairing?.takeIf { it.address == d.address } ?: return
        when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
            BluetoothDevice.BOND_BONDED -> uuidCheck(p, d)
            BluetoothDevice.BOND_NONE -> {
                toast(p.name, PAIR_FAILED)
                settled(p)
            }
        }
    }

    private fun uuidCheck(p: Bonding, d: BluetoothDevice) {
        p.bonded = true
        boards.paired(p.address)
        handler.postDelayed(uuidTimeout, SDP_MS)
        val asked = try {
            d.fetchUuidsWithSdp()
        } catch (_: SecurityException) {
            false
        }
        if (!asked) bondChecked(p, false)
    }

    private fun bondChecked(p: Bonding, board: Boolean) {
        if (pairing !== p) return
        handler.removeCallbacks(uuidTimeout)
        if (board) bonded(p.address) else toast(p.name, TURN_ON)
        settled(p)
    }

    private fun settled(p: Bonding) {
        pairing = null
        boards.settled(p.address)
        p.done()
        if (!started) unregister()
    }

    fun pair(address: String, name: String, done: () -> Unit = {}) {
        pairing?.let {
            toast(it.name, PAIRING)
            return done()
        }
        boards.taken(address)
        val p = Bonding(address, name, done)
        pairing = p
        boards.pairing(address, name, clock())
        cancel()
        val ok = try {
            val a = adapter()
            a?.cancelDiscovery()
            val d = a?.getRemoteDevice(address)
            when (d?.bondState) {
                BluetoothDevice.BOND_BONDED -> return uuidCheck(p, d)
                BluetoothDevice.BOND_BONDING -> true
                else -> d?.createBond() == true
            }
        } catch (_: SecurityException) {
            false
        }
        if (!ok) {
            toast(name, PAIR_FAILED)
            settled(p)
        }
    }

    companion object {
        const val PERIOD_MS = 30_000L
        const val SDP_MS = 10_000L
        const val PAIR_FAILED = "pair failed"
        const val TURN_ON = "turn on phone app"
        const val PAIRING = "pairing"
    }
}
