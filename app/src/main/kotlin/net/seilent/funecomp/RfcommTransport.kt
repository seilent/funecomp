package net.seilent.funecomp

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import net.seilent.funecomp.link.Transport
import net.seilent.funecomp.proto.gen.Proto
import java.io.IOException
import java.util.UUID

class RfcommTransport(private val context: Context, private val address: String) : Transport {
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var closed = false

    override fun open() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            throw IOException("no BLUETOOTH_CONNECT")
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: throw IOException("no adapter")
        if (!adapter.isEnabled) throw IOException("bluetooth off")
        val device = adapter.bondedDevices.firstOrNull { it.address.equals(address, ignoreCase = true) }
            ?: throw IOException("not bonded $address")
        val s = device.createRfcommSocketToServiceRecord(UUID.fromString(Proto.uuid))
        socket = s
        if (closed) {
            s.close()
            throw IOException("closed")
        }
        s.connect()
        if (closed) {
            s.close()
            throw IOException("closed")
        }
    }

    override fun read(buf: ByteArray): Int = socket?.inputStream?.read(buf) ?: -1

    override fun write(bytes: ByteArray) {
        val out = socket?.outputStream ?: throw IOException("closed")
        out.write(bytes)
        out.flush()
    }

    override fun close() {
        closed = true
        socket?.close()
    }
}
