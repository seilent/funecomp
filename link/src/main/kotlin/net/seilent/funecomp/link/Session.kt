package net.seilent.funecomp.link

import net.seilent.funecomp.proto.Bye
import net.seilent.funecomp.proto.ErrorReply
import net.seilent.funecomp.proto.Frame
import net.seilent.funecomp.proto.FrameReader
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.Hello
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.ImgAbort
import net.seilent.funecomp.proto.UpdAbort
import net.seilent.funecomp.proto.Inbound
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.ProtoException
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class Session(
    private val transport: Transport,
    private val listener: Listener,
    private val config: Config,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    data class Config(
        val appVersion: String,
        val helloTimeoutMs: Long = Proto.helloTimeoutMs.toLong(),
        val connectTimeoutMs: Long = Proto.requestTimeoutMs.toLong(),
        val requestTimeoutMs: Long = Proto.requestTimeoutMs.toLong(),
        val busyCapMs: Long = Proto.busyCapMs.toLong(),
        val tickMs: Long = 50,
        val outQueueBytes: Int = 65536,
        val features: Long = 0,
    )

    interface Listener {
        fun onHello(hello: HelloReply) {}
        fun onPush(type: Int, body: Node) {}
        fun onBye(bye: Bye) {}
        fun onRtt(ms: Long) {}
        fun onClosed(reason: String) {}
        fun onRequest(type: Int, body: Node, responder: Responder): Boolean = false
    }

    sealed class Result {
        class Ok(val body: Node) : Result()
        class Failed(val error: ErrorReply) : Result()
        object Timeout : Result()
        object Closed : Result()
        object NotReady : Result()
        object Unsupported : Result()
    }

    private class Pending(val type: Int, var sentAt: Long, val timeoutMs: Long, val done: (Result) -> Unit)

    private val lock = Any()
    private val pending = HashMap<Int, Pending>()
    private var nextSeq = 1
    private var rxAt = 0L
    private var pingSeq: Int? = null
    private var pingAt = 0L
    private var probeUntil: Long? = null
    private var busySince: Long? = null
    private var anchorRx: Long? = null
    private var anchorAt = 0L
    private var tickAt: Long? = null
    @Volatile private var ticker: ScheduledExecutorService? = null

    private val outLock = ReentrantLock()
    private val outReady = outLock.newCondition()
    private val outRoom = outLock.newCondition()
    private val out = ArrayDeque<ByteArray>()
    private var outBytes = 0
    private val own = HashSet<Thread>()

    @Volatile var hello: HelloReply? = null
        private set

    @Volatile var closedReason: String? = null
        private set

    private val pushers = AtomicInteger()
    val pushActive: Boolean get() = pushers.get() > 0

    fun pushBegin() = pushers.incrementAndGet()

    fun pushEnd() = pushers.decrementAndGet()

    @Volatile var abortListener: ((ImgAbort) -> Unit)? = null
    @Volatile var updAbortListener: ((UpdAbort) -> Unit)? = null

    fun run(): String {
        synchronized(own) { own += Thread.currentThread() }
        closedReason?.let { return it }
        val t = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "session-tick").apply {
                isDaemon = true
                synchronized(own) { own += this }
            }
        }
        ticker = t
        val opening = schedule { t.schedule({ close("connectTimeout") }, config.connectTimeoutMs, TimeUnit.MILLISECONDS) }
        try {
            transport.open()
        } catch (e: Exception) {
            close("connect: ${e.message}")
            t.shutdownNow()
            return closedReason!!
        }
        opening?.cancel(false)
        closedReason?.let {
            t.shutdownNow()
            try {
                transport.close()
            } catch (_: Exception) {
            }
            return it
        }
        synchronized(lock) { rxAt = clock() }
        val writer = Thread({ writeLoop() }, "session-write").apply { isDaemon = true }
        synchronized(own) { own += writer }
        writer.start()
        schedule { t.scheduleAtFixedRate({ tick() }, config.tickMs, config.tickMs, TimeUnit.MILLISECONDS) }
        if (closedReason != null) t.shutdownNow()
        enqueueRequest(Msg.HELLO, Hello(Proto.major, Proto.minor, config.appVersion, config.features.takeIf { it != 0L }).encode(), config.helloTimeoutMs) { r ->
            when (r) {
                is Result.Ok -> {
                    val h = HelloReply.from(r.body)
                    if (h.major != Proto.major) {
                        close("version")
                    } else {
                        hello = h
                        listener.onHello(h)
                    }
                }
                is Result.Timeout -> close("helloTimeout")
                is Result.Failed -> close("hello error ${r.error.code}")
                else -> {}
            }
        }
        val reader = FrameReader()
        val buf = ByteArray(8192)
        try {
            while (closedReason == null) {
                val n = transport.read(buf)
                if (n < 0) {
                    close("eof")
                    break
                }
                for (f in reader.feed(buf, 0, n)) handle(f)
            }
        } catch (e: ProtoException) {
            close(e.reason)
        } catch (e: Exception) {
            close("io: ${e.message}")
        }
        writer.join(1000)
        return closedReason!!
    }

    private fun <T> schedule(block: () -> T): T? = try {
        block()
    } catch (_: RejectedExecutionException) {
        null
    }

    fun request(type: Int, payload: ByteArray, timeoutMs: Long = config.requestTimeoutMs, done: (Result) -> Unit) {
        if (closedReason != null) return done(Result.Closed)
        val h = hello ?: return done(Result.NotReady)
        if (!Groups.allowed(type, h.features)) return done(Result.Unsupported)
        enqueueRequest(type, payload, timeoutMs, done)
    }

    private fun enqueueRequest(type: Int, payload: ByteArray, timeoutMs: Long, done: (Result) -> Unit) {
        val seq = synchronized(lock) {
            if (closedReason != null) null else seq().also { pending[it] = Pending(type, clock(), timeoutMs, done) }
        }
        if (seq == null) return done(Result.Closed)
        send(Frame(type, Proto.flagWant, seq, payload), wait = false)
    }

    fun call(type: Int, payload: ByteArray, timeoutMs: Long = config.requestTimeoutMs): Result {
        checkNotOwn("Session.call")
        val latch = CountDownLatch(1)
        var out: Result = Result.Closed
        request(type, payload, timeoutMs) {
            out = it
            latch.countDown()
        }
        latch.await()
        return out
    }

    internal fun checkNotOwn(what: String) = check(synchronized(own) { Thread.currentThread() !in own }) { "$what on a session thread" }

    fun push(type: Int, payload: ByteArray): Boolean {
        val seq = synchronized(lock) { if (closedReason != null) null else seq() } ?: return false
        return send(Frame(type, 0, seq, payload), wait = true)
    }

    fun close(reason: String) {
        val drop: List<Pending>
        synchronized(lock) {
            if (closedReason != null) return
            closedReason = reason
            drop = pending.values.toList()
            pending.clear()
        }
        ticker?.shutdownNow()
        outLock.withLock {
            out.clear()
            outBytes = 0
            outReady.signalAll()
            outRoom.signalAll()
        }
        try {
            transport.close()
        } catch (_: Exception) {
        }
        drop.forEach { it.done(Result.Closed) }
        listener.onClosed(reason)
    }

    private fun seq(): Int {
        val s = nextSeq
        nextSeq = if (nextSeq >= 0xFFFF) 1 else nextSeq + 1
        return s
    }

    private fun send(f: Frame, wait: Boolean): Boolean {
        val bytes = f.encode()
        outLock.withLock {
            while (wait && closedReason == null && outBytes > 0 && outBytes + bytes.size > config.outQueueBytes) outRoom.await()
            if (closedReason != null) return false
            out.addLast(bytes)
            outBytes += bytes.size
            outReady.signal()
        }
        return true
    }

    private fun writeLoop() {
        while (true) {
            val bytes = outLock.withLock {
                while (out.isEmpty() && closedReason == null) outReady.await()
                if (closedReason != null) return
                out.removeFirst()
            }
            try {
                transport.write(bytes)
            } catch (e: Exception) {
                close("io: ${e.message}")
                return
            }
            outLock.withLock {
                outBytes -= bytes.size
                outRoom.signalAll()
            }
        }
    }

    private fun reply(f: Frame, flags: Int, payload: ByteArray = ByteArray(0)) = send(Frame(f.type, flags, f.seq, payload), wait = false)

    internal fun answer(type: Int, seq: Int, flags: Int, payload: ByteArray) = send(Frame(type, flags, seq, payload), wait = false)

    private fun handle(f: Frame) {
        val now = clock()
        synchronized(lock) { rxAt = now }
        when (val c = Inbound.classify(f)) {
            is Inbound.Close -> close(c.reason)
            is Inbound.Skip -> {}
            is Inbound.Reply -> reply(f, Proto.flagReply or Proto.flagError, ErrorReply(c.code, c.text).encode())
            is Inbound.Accept -> accept(f, c.body, now)
        }
    }

    private fun accept(f: Frame, body: Node, now: Long) {
        if (f.isReply) {
            val p = synchronized(lock) {
                val p = pending[f.seq]?.takeIf { it.type == f.type } ?: return
                pending.remove(f.seq)
                if (pingSeq == f.seq) {
                    pingSeq = null
                    probeUntil = null
                }
                p
            }
            if (f.type == Msg.PING) listener.onRtt(now - pingAt)
            p.done(if (f.isError) Result.Failed(ErrorReply.from(body)) else Result.Ok(body))
            return
        }
        if (f.type == Msg.BYE) {
            listener.onBye(Bye.from(body))
            close("bye")
            return
        }
        if (f.wantsReply) {
            if (f.type == Msg.PING) reply(f, Proto.flagReply)
            else if (!asked(f, body)) reply(f, Proto.flagReply or Proto.flagError, ErrorReply(Err.unknown, "unknown").encode())
            return
        }
        val h = hello ?: return
        if (!Groups.allowed(f.type, h.features)) return
        if (f.type == Msg.IMG_ABORT) abortListener?.invoke(ImgAbort.from(body))
        if (f.type == Msg.UPD_ABORT) updAbortListener?.invoke(UpdAbort.from(body))
        listener.onPush(f.type, body)
    }

    private fun asked(f: Frame, body: Node): Boolean {
        val h = hello ?: return false
        if (Msg.info[f.type]?.dir !in setOf("dev", "both")) return false
        if (!Groups.allowed(f.type, h.features) || !Groups.allowed(f.type, config.features)) return false
        return listener.onRequest(f.type, body, Responder(this, f.type, f.seq))
    }

    private fun tick() {
        val now = clock()
        val expired = ArrayList<Pending>()
        var reason: String? = null
        var ping = false
        synchronized(lock) {
            if (closedReason != null) return
            val last = tickAt
            tickAt = now
            if (last != null && now - last > 2 * config.tickMs) {
                rxAt = now
                anchorAt = now
                pending.values.forEach { it.sentAt = now }
                probeUntil = probeUntil?.let { now + (hello?.busyMs ?: 0) }
                return
            }
            val it = pending.values.iterator()
            while (it.hasNext()) {
                val p = it.next()
                if (p.type != Msg.PING && now - p.sentAt >= p.timeoutMs) {
                    it.remove()
                    expired += p
                }
            }
            if (expired.any { it.type == Msg.HELLO }) reason = "helloTimeout"
            val h = hello
            if (reason == null && expired.isNotEmpty() && probeUntil == null && h != null) {
                probeUntil = now + h.busyMs
                ping = pingSeq == null
            }
            val probe = probeUntil
            if (reason == null && probe != null && now > probe) reason = "requestTimeout"
            if (reason == null && h != null) {
                val busy = pending.values.any { it.type != Msg.PING } || pushActive
                if (!busy) {
                    busySince = null
                } else if (busySince == null) {
                    busySince = now
                    if (anchorRx != rxAt) {
                        anchorRx = rxAt
                        anchorAt = now
                        if (now - rxAt >= h.busyMs && pingSeq == null) ping = true
                    }
                }
                val capped = busySince?.let { now - it > config.busyCapMs } ?: true
                val interval = if (busy && !capped) h.busyMs else h.idleMs
                val silence = now - maxOf(rxAt, anchorAt)
                if (silence >= Proto.deadIntervals * interval) reason = "dead"
                else if (silence >= interval && pingSeq == null) ping = true
            }
            if (reason == null && ping) {
                val s = seq()
                pending[s] = Pending(Msg.PING, now, Long.MAX_VALUE) {}
                pingSeq = s
                pingAt = now
                send(Frame(Msg.PING, Proto.flagWant, s), wait = false)
            }
        }
        expired.forEach { it.done(Result.Timeout) }
        reason?.let { close(it) }
    }
}
