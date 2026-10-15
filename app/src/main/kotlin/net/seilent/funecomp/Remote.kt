package net.seilent.funecomp

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.seilent.funecomp.link.Link
import net.seilent.funecomp.link.Session
import net.seilent.funecomp.link.TcpTransport
import net.seilent.funecomp.link.Transport
import net.seilent.funecomp.proto.Bye
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.state.Reconnect

object Remote {
    const val TAG = "Funecomp"

    var device by mutableStateOf(DeviceState())
        private set

    private val lock = Any()
    private val stateLock = Any()
    @Volatile private var link: Link? = null
    @Volatile private var generation = 0
    private var spec: String? = null

    fun start(context: Context, spec: String): Boolean {
        synchronized(lock) {
            if (spec == this.spec && link != null) {
                link?.foreground()
                return true
            }
            drop()
            val connect = connector(context.applicationContext, spec)
            if (connect == null) {
                Log.w(TAG, "link $spec not usable")
                return false
            }
            this.spec = spec
            update { DeviceState() }
            Log.i(TAG, "link $spec")
            link = Link(connect, listener(generation), clock = SystemClock::elapsedRealtime).also { it.start() }
            return true
        }
    }

    fun stop() {
        synchronized(lock) { drop() }
        update { DeviceState() }
    }

    private fun drop() {
        generation++
        spec = null
        val old = link ?: return
        link = null
        Log.i(TAG, "link stop")
        Thread({ old.stop() }, "link-stop").apply { isDaemon = true }.start()
    }

    fun transport(cmd: Int) = send(Msg.TRANSPORT, Req.transport(cmd), "transport ${Enums.TransportCmd.names[cmd]}")

    fun volumeStep(detents: Int) = send(Msg.VOLUME, Req.volume(step = detents), "volume step $detents")

    private fun send(type: Int, payload: ByteArray, what: String) {
        val s = link?.session
        if (s == null) {
            Log.i(TAG, "$what: no session")
            return
        }
        s.request(type, payload) { r ->
            val out = when (r) {
                is Session.Result.Ok -> "ok"
                is Session.Result.Failed -> "error ${r.error.code} ${r.error.text}"
                else -> r.javaClass.simpleName.lowercase()
            }
            Log.i(TAG, "$what -> $out")
        }
    }

    private fun connector(context: Context, spec: String): (() -> Transport)? {
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (spec.startsWith("rfcomm:")) {
            val addr = spec.removePrefix("rfcomm:")
            return { RfcommTransport(context, addr) }
        }
        if (!debuggable || !spec.startsWith("tcp:")) return null
        val hostPort = spec.removePrefix("tcp:")
        val at = hostPort.lastIndexOf(':')
        if (at <= 0) return null
        val host = hostPort.substring(0, at)
        val port = hostPort.substring(at + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return { TcpTransport(host, port) }
    }

    private fun update(f: (DeviceState) -> DeviceState) = synchronized(stateLock) { device = f(device) }

    private fun listener(gen: Int) = object : Link.Listener {
        private val live get() = gen == generation

        override fun onAttempt(at: Long) {
            if (!live) return
            Log.i(TAG, "attempt at=$at")
        }

        override fun onHello(hello: HelloReply) {
            if (!live) return
            update { it.withHello(hello) }
            Log.i(TAG, "hello board=${hello.board} version=${hello.version} idle=${hello.idleMs} busy=${hello.busyMs}")
        }

        override fun onPush(type: Int, body: Node) {
            if (!live) return
            val now = SystemClock.elapsedRealtime()
            var old = device.playback.state
            update {
                old = it.playback.state
                it.reduce(type, body, now)
            }
            if (type != Msg.STATE) return
            val s = State.from(body)
            Log.i(TAG, "state applied title=${s.title} artist=${s.artist} playing=${s.playing} volume=${s.volume}/${s.maxVolume}")
            if (old.title != null && old.title != s.title) Log.i(TAG, "title changed ${old.title} -> ${s.title}")
        }

        override fun onRtt(ms: Long) {
            if (live) update { it.withRtt(ms) }
        }

        override fun onBye(bye: Bye) {
            if (!live) return
            Log.i(TAG, "bye ${Enums.ByeReason.names[bye.reason] ?: bye.reason}")
        }

        override fun onClosed(reason: String) {
            if (!live) return
            update { it.copy(hello = null) }
            Log.i(TAG, "closed $reason")
        }

        override fun onStopped(stop: Reconnect.Stop) {
            if (!live) return
            Log.i(TAG, "stopped $stop")
        }
    }
}
