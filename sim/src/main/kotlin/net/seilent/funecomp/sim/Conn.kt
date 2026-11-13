package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.ArtBlock
import net.seilent.funecomp.proto.ArtPick
import net.seilent.funecomp.proto.ArtPickReply
import net.seilent.funecomp.proto.Bye
import net.seilent.funecomp.proto.ErrorReply
import net.seilent.funecomp.proto.Frame
import net.seilent.funecomp.proto.FrameReader
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.Hello
import net.seilent.funecomp.proto.Inbound
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.ProtoException
import net.seilent.funecomp.proto.SvcLogin
import net.seilent.funecomp.proto.SvcLoginEnd
import net.seilent.funecomp.proto.SvcPkce
import net.seilent.funecomp.proto.SvcPkceReply
import net.seilent.funecomp.proto.Tlv
import net.seilent.funecomp.proto.TlvWriter
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.proto.gen.Tags
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Conn(
    private val id: Int,
    private val input: InputStream,
    private val output: OutputStream,
    private val closer: () -> Unit,
    private val device: Device,
    private val opts: Options,
    private val clock: () -> Long,
) : Out {
    private val faults = opts.faults
    private val lock = Any()
    private var seq = 1
    private var helloDone = false
    private var silent = false
    private var rxBytes = 0L
    private var rxAt = clock()
    private val startAt = clock()
    private var pingSeq: Int? = null
    private var appFeatures = 0L
    private var asked = false
    private val ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sim-tick-$id").apply { isDaemon = true } }

    private class Ask(val type: Int, val seq: Int, val deadline: Long, val done: (String) -> Unit, val svcSeq: Long? = null)

    private var ask: Ask? = null
    private var batch: ByteArrayOutputStream? = null

    val asks: Boolean get() = Groups.allowed(Msg.ART_BLOCK, appFeatures) && Groups.allowed(Msg.ART_BLOCK, faults.features)

    val picks: Boolean get() = Groups.allowed(Msg.ART_PICK, appFeatures) && Groups.allowed(Msg.ART_PICK, faults.features)

    val svc: Boolean get() = device.svcBoth(appFeatures)

    fun ask(type: Int, body: ByteArray, timeoutMs: Long, svcSeq: Long? = null, done: (String) -> Unit): Boolean {
        val s = synchronized(lock) {
            if (closed != null || ask != null || faults.askHeldMs?.let { clock() - startAt < it } == true) return false
            nextSeq().also { ask = Ask(type, it, clock() + timeoutMs, done, svcSeq) }
        }
        write(Frame(type, Proto.flagWant, s, body))
        return true
    }

    override fun pkce(q: SvcPkce, done: (String) -> Unit): Boolean = ask(Msg.SVC_PKCE, q.encode(), Proto.svcPkceMs.toLong(), q.seq, done)

    override fun drop(reason: String) = close(reason)

    override val phone: String get() = faults.phone

    fun svcEnd(e: SvcLoginEnd) {
        push(Msg.SVC_LOGIN_END, e.encode())
        device.svcEnded(e)
        val a = synchronized(lock) { ask?.takeIf { it.type == Msg.SVC_PKCE && it.svcSeq == e.seq }?.also { ask = null } } ?: return
        log("ask ${name(a.type)} seq=${a.seq} -> ended")
        a.done("ended")
    }

    private fun answered(seq: Int?, result: String) {
        val a = synchronized(lock) { ask?.takeIf { seq == null || it.seq == seq }?.also { ask = null } } ?: return
        log("ask ${name(a.type)} seq=${a.seq} -> $result")
        a.done(result)
    }

    @Volatile var closed: String? = null
        private set

    private fun log(s: String) = opts.log("conn $id $s")

    private fun name(type: Int) = Msg.info[type]?.name ?: "0x%02x".format(type)

    private fun nextSeq(): Int {
        val s = seq
        seq = if (seq >= 0xFFFF) 1 else seq + 1
        return s
    }

    private fun write(f: Frame) {
        if (closed != null) return
        val payload = faults.enums.filter { it.type == f.type && !f.isReply }.fold(f.payload) { p, e ->
            val w = TlvWriter()
            for (t in Tlv.parse(p)) w.raw(t.tag, if (t.tag == e.tag) byteArrayOf(e.value.toByte()) else t.value)
            w.toByteArray()
        }
        val bytes = Frame(f.type, f.flags, f.seq, payload).encode()
        try {
            synchronized(lock) {
                val b = batch
                if (b != null) {
                    b.write(bytes)
                } else {
                    output.write(bytes)
                    output.flush()
                }
            }
            log("tx ${name(f.type)} flags=0x%02x seq=${f.seq} len=${payload.size}".format(f.flags))
            faults.slowBps?.let { Thread.sleep(bytes.size * 1000L / it) }
        } catch (e: Exception) {
            close("io: ${e.message}")
        }
    }

    override fun push(type: Int, payload: ByteArray) {
        if (silent) return
        val s = synchronized(lock) { nextSeq() }
        write(Frame(type, 0, s, payload))
    }

    private fun reply(f: Frame, payload: ByteArray = ByteArray(0)) = write(Frame(f.type, Proto.flagReply, f.seq, payload))

    private fun batched(f: () -> Unit) {
        synchronized(lock) { batch = ByteArrayOutputStream() }
        try {
            f()
        } finally {
            val b = synchronized(lock) { batch.also { batch = null } }
            try {
                synchronized(lock) {
                    output.write(b!!.toByteArray())
                    output.flush()
                }
            } catch (e: Exception) {
                close("io: ${e.message}")
            }
        }
    }

    private fun answer(f: Frame, a: Answer, at: Long, pushing: String?) {
        when (a) {
            is Answer.Ok -> {
                val send: () -> Unit = {
                    reply(f, a.payload)
                    if (a.state) push(Msg.STATE, device.state(at, this))
                    a.then?.invoke(this)
                }
                if (a.batch) batched(send) else send()
                if (pushing != null) askLater(pushing)
            }
            is Answer.Fail -> {
                a.before?.invoke(this)
                error(f, a.code, a.text, a.refused)
                a.then?.invoke(this)
            }
            is Answer.Later -> device.svc.timer.schedule({
                val r = device.resolve(a)
                if (closed == null) answer(f, r, clock(), null)
            }, a.ms, TimeUnit.MILLISECONDS)
        }
    }

    private fun error(f: Frame, code: Int, text: String, refused: List<String> = emptyList()) =
        write(Frame(f.type, Proto.flagReply or Proto.flagError, f.seq, ErrorReply(code, text.ifEmpty { null }, refused).encode()))

    fun bye(reason: Int, major: Int? = null, minor: Int? = null) {
        push(Msg.BYE, Bye(reason, major, minor).encode())
        close("bye ${Enums.ByeReason.names[reason]}")
    }

    fun close(reason: String) {
        synchronized(lock) {
            if (closed != null) return
            closed = reason
        }
        answered(null, "closed")
        device.unlinked(this)
        try {
            closer()
        } catch (_: Exception) {
        }
        log("closed $reason")
    }

    fun run() {
        log("open")
        ticker.scheduleAtFixedRate({ tick() }, 50, 50, TimeUnit.MILLISECONDS)
        val reader = FrameReader()
        val buf = ByteArray(8192)
        try {
            while (closed == null) {
                val n = input.read(buf)
                if (n < 0) {
                    close("eof")
                    break
                }
                rxBytes += n
                faults.slowBps?.let { Thread.sleep(n * 1000L / it) }
                for (f in reader.feed(buf, 0, n)) handle(f)
                val drop = faults.dropAfterBytes
                if (drop != null && rxBytes >= drop) close("drop-after-bytes")
                while (silent && closed == null) Thread.sleep(50)
            }
        } catch (e: ProtoException) {
            close(e.reason)
        } catch (e: Exception) {
            close("io: ${e.message}")
        } finally {
            ticker.shutdownNow()
        }
    }

    private fun tick() {
        if (closed != null) return
        val now = clock()
        if (synchronized(lock) { ask?.let { now >= it.deadline } } == true) answered(null, "timeout")
        if (!helloDone) {
            if (now - startAt >= opts.helloTimeoutMs) close("helloTimeout")
            return
        }
        if (silent) return
        val silence = now - rxAt
        if (silence >= Proto.deadIntervals * opts.idleMs) {
            close("dead")
            return
        }
        device.tick(this)
        if (silence >= opts.idleMs && pingSeq == null) {
            val s = synchronized(lock) { nextSeq() }
            pingSeq = s
            write(Frame(Msg.PING, Proto.flagWant, s))
        }
    }

    private fun handle(f: Frame) {
        rxAt = clock()
        log("rx ${name(f.type)} flags=0x%02x seq=${f.seq} len=${f.payload.size}".format(f.flags))
        when (val c = Inbound.classify(f)) {
            is Inbound.Close -> close(c.reason)
            is Inbound.Skip -> {}
            is Inbound.Reply -> if (helloDone) error(f, c.code, c.text)
            is Inbound.Accept -> accept(f, c.body)
        }
    }

    private fun accept(f: Frame, body: Node) {
        if (f.isReply) {
            if (f.type == Msg.PING && f.seq == pingSeq) pingSeq = null
            if (f.type == Msg.SVC_PKCE && faults.dropAfterReply.compareAndSet(true, false)) return close("drop-after-reply")
            if (synchronized(lock) { ask?.let { it.type == f.type && it.seq == f.seq } } == true) {
                answered(
                    f.seq,
                    when {
                        f.isError -> ErrorReply.from(body).let { "error ${it.code} ${it.text ?: ""}".trim() }
                        f.type == Msg.ART_PICK -> "ok shown=${ArtPickReply.from(body).shown}"
                        f.type == Msg.SVC_PKCE -> "ok len=${SvcPkceReply.from(body).redirect?.length ?: 0}"
                        else -> "ok"
                    },
                )
            }
            return
        }
        if (f.type == Msg.HELLO) return hello(f, body)
        if (!helloDone) return
        if (!f.wantsReply) {
            if (f.type == Msg.IMG_DATA && Groups.allowed(f.type, faults.features)) device.imgData(body, this)
            if (f.type == Msg.UPD_DATA && Groups.allowed(f.type, faults.features)) device.updData(body, this)
            if (f.type == Msg.APP_DATA && Groups.allowed(f.type, faults.features)) device.appData(body, this)
            return
        }
        if (f.type == Msg.PING) return reply(f)
        if (!Groups.allowed(f.type, faults.features) || !(Msg.info[f.type]?.dir in setOf("app", "both"))) {
            return error(f, Err.unknown, "unknown")
        }
        if (f.type in faults.refuse) return error(f, Err.refused, "refused")
        faults.fail[f.type]?.let { code -> if ((faults.failLeft[f.type]?.getAndDecrement() ?: 1) > 0) return error(f, code, "fault $code") }
        if ((faults.busy[f.type]?.getAndDecrement() ?: 0) > 0) return error(f, Err.busy, "busy")
        if (f.type == Msg.QUEUE_OP && body.int(Tags.QueueOp.cmd) == Enums.QueueCmd.page && (body.long(Tags.QueueOp.offset) ?: 0L) in faults.busyPages) return error(f, Err.busy, "busy")
        if (f.type == Msg.PLAYLOG_ACK && faults.dropAck.compareAndSet(true, false)) return close("drop-ack")
        val at = clock()
        if (faults.stallMs > 0) Thread.sleep(faults.stallMs)
        if (faults.muteCmd[f.type]?.let { it == body.int(Tags.QueueOp.cmd) } != false && (faults.mute[f.type]?.getAndDecrement() ?: 0) > 0) {
            device.request(f.type, body, this)
            log("mute ${name(f.type)} seq=${f.seq}")
            return
        }
        val pushing = if (f.type == Msg.IMG_END) device.pushing else null
        val a = device.request(f.type, body, this)
        if (f.type == Msg.LIB_GET) faults.libOkHold?.let { check(it.await(10, TimeUnit.SECONDS)) { "lib-ok-hold: not released in 10 s" } }
        answer(f, a, at, pushing)
    }

    private fun askLater(name: String) {
        val (cmd, ms) = faults.askBlock ?: return
        if (asked || !asks) return
        asked = true
        ticker.schedule({
            val (n, mbid, crc) = device.drawn(name) ?: return@schedule log("ask-block: $name not stored")
            ask(Msg.ART_BLOCK, ArtBlock(cmd, n, mbid?.takeIf { it.isNotEmpty() }, crc).encode(), Proto.requestTimeoutMs.toLong()) {}
        }, ms, TimeUnit.MILLISECONDS)
    }

    private fun hello(f: Frame, body: Node) {
        if (helloDone || !f.wantsReply) return
        val h = Hello.from(body)
        appFeatures = h.features ?: 0
        log("hello app major=${h.major} minor=${h.minor} version=${h.appVersion} features=$appFeatures")
        val at = clock()
        if (faults.stallMs > 0) Thread.sleep(faults.stallMs)
        if (closed != null) return
        if (h.major != faults.major) return bye(Enums.ByeReason.version, faults.major, Proto.minor)
        if (faults.bye == Enums.ByeReason.refused) return bye(Enums.ByeReason.refused)
        device.linked(this, appFeatures, h.appVersion)
        reply(f, device.helloReply(this).encode())
        helloDone = true
        device.greet(this, at)
        faults.bye?.let { return bye(it) }
        faults.eqHeadsetAt?.let { (name, ms) -> ticker.schedule({ device.eqHeadset(name, this) }, ms, TimeUnit.MILLISECONDS) }
        faults.askPick?.let { (name, ms) ->
            if (picks) ticker.schedule({ ask(Msg.ART_PICK, ArtPick(name, device.lib.tracks.firstNotNullOfOrNull { it.mbidOf(name) }).encode(), Proto.requestTimeoutMs.toLong()) {} }, ms, TimeUnit.MILLISECONDS)
        }
        svcFaults()
        faults.replaceMs?.let { ms -> ticker.schedule({ bye(Enums.ByeReason.replaced) }, ms, TimeUnit.MILLISECONDS) }
        if (faults.noRead) {
            silent = true
            log("no-read: silent")
        }
    }

    private fun svcFaults() {
        if (!svc || faults.svcOff || device.streamingOff()) return
        val id = opts.services.firstOrNull()?.id ?: "svc"
        fun later(ms: Long, f: () -> Unit) = ticker.schedule({ if (closed == null) f() }, ms, TimeUnit.MILLISECONDS)
        for (e in faults.svcLoginEnds) later(e.ms) { svcEnd(SvcLoginEnd(id, e.seq, e.result)) }
        for (e in faults.svcPkceEnds) later(e.ms) { svcEnd(SvcLoginEnd(id, e.seq, e.result)) }
        for (l in faults.svcLogins) {
            val uri = if (faults.svcLoginBare) "${l.host}/${l.code}" else "https://${l.host}/${l.code}"
            later(l.ms) {
                val s = SvcLogin(id, uri, l.host, l.code, 300, l.seq)
                device.svcSeen(s)
                push(Msg.SVC_LOGIN, s.encode())
            }
        }
        for (p in faults.svcPkces) later(p.ms) { device.svcAsk(this, SvcPkce(id, "https://login.${p.host}/auth", "login.${p.host}", "https://${p.host}/cb", p.seq)) }
        for ((a, ms) in faults.svcStates) later(ms) { device.svcSet(this, a) }
    }
}
