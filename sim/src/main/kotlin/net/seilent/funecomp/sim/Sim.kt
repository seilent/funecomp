package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Proto
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class Sim(private val opts: Options, lib: Library = Library.synthetic()) {
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
    val device = Device(opts, lib, clock)
    private val connects = ArrayDeque<Long>()
    private var server: ServerSocket? = null
    private var ids = 0
    @Volatile private var running = true

    @Volatile var current: Conn? = null
        private set

    fun listen(port: Int, bind: InetAddress = InetAddress.getLoopbackAddress()): Int {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(bind, port))
        server = s
        opts.log("sim ${opts.board.name} listening on ${s.localPort}")
        Thread({
            while (running) {
                val sock = try {
                    s.accept()
                } catch (_: Exception) {
                    break
                }
                accept(sock)
            }
        }, "sim-accept").apply {
            isDaemon = true
            start()
        }
        return s.localPort
    }

    fun connect(host: String, port: Int) {
        while (running) {
            val sock = Socket()
            try {
                sock.connect(InetSocketAddress(host, port), 5000)
                serve(sock).join()
            } catch (e: Exception) {
                opts.log("sim connect $host:$port failed: ${e.message}")
            }
            Thread.sleep(1000)
        }
    }

    private fun accept(sock: Socket) {
        val now = clock()
        synchronized(connects) {
            while (connects.isNotEmpty() && now - connects.first() > Proto.refuseWindowMs) connects.removeFirst()
            if (connects.size >= Proto.refuseCount) {
                opts.log("sim refused a connection past the rate")
                sock.close()
                return
            }
            connects.addLast(now)
        }
        current?.let { if (it.closed == null) it.bye(Enums.ByeReason.replaced) }
        serve(sock)
    }

    private fun serve(sock: Socket): Thread {
        if (opts.faults.restart) device.restart()
        sock.tcpNoDelay = true
        val c = Conn(++ids, sock.getInputStream(), sock.getOutputStream(), { sock.close() }, device, opts, clock)
        current = c
        return Thread({ c.run() }, "sim-conn-$ids").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        server?.close()
        current?.close("stopped")
    }
}
