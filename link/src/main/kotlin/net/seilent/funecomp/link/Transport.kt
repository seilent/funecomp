package net.seilent.funecomp.link

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

interface Transport {
    fun open()
    fun read(buf: ByteArray): Int
    fun write(bytes: ByteArray)
    fun close()
}

class TcpTransport(private val host: String, private val port: Int, private val connectTimeoutMs: Int = 5000) : Transport {
    private val socket = Socket()
    private lateinit var input: InputStream
    private lateinit var output: OutputStream

    override fun open() {
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        input = socket.getInputStream()
        output = socket.getOutputStream()
    }

    override fun read(buf: ByteArray): Int = input.read(buf)

    override fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    override fun close() = socket.close()

    companion object {
        fun parse(spec: String): TcpTransport {
            val at = spec.lastIndexOf(':')
            return TcpTransport(spec.substring(0, at), spec.substring(at + 1).toInt())
        }
    }
}
