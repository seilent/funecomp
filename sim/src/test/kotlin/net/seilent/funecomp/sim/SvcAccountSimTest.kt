package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.ErrorReply
import net.seilent.funecomp.proto.Frame
import net.seilent.funecomp.proto.FrameReader
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.Hello
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.SvcInfo
import net.seilent.funecomp.proto.SvcKey
import net.seilent.funecomp.proto.SvcLogin
import net.seilent.funecomp.proto.SvcLoginEnd
import net.seilent.funecomp.proto.SvcLoginStart
import net.seilent.funecomp.proto.SvcLoginStartReply
import net.seilent.funecomp.proto.SvcPkce
import net.seilent.funecomp.proto.SvcPkceReply
import net.seilent.funecomp.proto.SvcState
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class SvcAccountSimTest {
    private val sims = ArrayList<Sim>()
    private val apps = ArrayList<App>()
    private val logs = CopyOnWriteArrayList<String>()
    private val tidal = SvcInfo("tidal", "TIDAL", listOf("client_id"), true)
    private val radio = SvcInfo("radio", "Night Radio", emptyList(), false)
    private val noAccount = Groups.all and Groups.mask("svcAccount").inv()

    @After
    fun tearDown() {
        apps.forEach { it.sock.close() }
        sims.forEach { it.stop() }
    }

    private fun sim(vararg faults: String, minor: Int = Proto.minor, accounts: List<String> = emptyList(), clock: (() -> Long)? = null) =
        Sim(Options(Board.of("v1"), Faults(faults.toList()), minor = minor, log = { logs += it }, services = listOf(tidal, radio), accounts = accounts.map { SvcAcct.parse(it) }, deviceClock = clock)).also { sims += it }

    private class App(port: Int, features: Long, val answer: (Frame) -> Frame?) {
        val sock = Socket("127.0.0.1", port)
        val frames = CopyOnWriteArrayList<Pair<Int, Frame>>()
        val replies = LinkedBlockingQueue<Frame>()
        val asks = LinkedBlockingQueue<Frame>()
        @Volatile var closed = false
        private var seq = 1
        lateinit var hello: HelloReply

        init {
            Thread {
                val r = FrameReader()
                val buf = ByteArray(8192)
                var reads = 0
                try {
                    while (true) {
                        val n = sock.getInputStream().read(buf)
                        if (n < 0) break
                        reads++
                        for (f in r.feed(buf, 0, n)) {
                            frames += reads to f
                            when {
                                f.isReply -> replies += f
                                f.wantsReply && f.type == Msg.SVC_PKCE -> {
                                    asks += f
                                    answer(f)?.let { send(it) }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
                closed = true
            }.apply { isDaemon = true }.start()
            hello = HelloReply.from(Node.decode("HELLO.reply", call(Msg.HELLO, Hello(Proto.major, Proto.minor, "t", features).encode()).payload))
        }

        @Synchronized
        fun send(f: Frame) {
            sock.getOutputStream().write(f.encode())
            sock.getOutputStream().flush()
        }

        @Synchronized
        fun request(type: Int, payload: ByteArray): Int = seq++.also { send(Frame(type, Proto.flagWant, it, payload)) }

        fun reply(type: Int, s: Int, ms: Long = 5000): Frame {
            val end = System.currentTimeMillis() + ms
            while (true) {
                val left = end - System.currentTimeMillis()
                val f = replies.poll(maxOf(left, 1), TimeUnit.MILLISECONDS) ?: throw AssertionError("no reply to ${Msg.info[type]?.name}")
                if (f.type == type && f.seq == s) return f
            }
        }

        fun call(type: Int, payload: ByteArray): Frame = reply(type, request(type, payload))

        fun ping() = call(Msg.PING, ByteArray(0))

        fun pushes(type: Int) = frames.map { it.second }.filter { it.type == type && !it.isReply && !it.wantsReply }

        fun states() = pushes(Msg.SVC_STATE).map { SvcState.from(Node.decode("SVC_STATE", it.payload)) }

        fun index(f: Frame) = frames.indexOfFirst { it.second === f }
    }

    private fun app(port: Int, features: Long = Groups.all, answer: (Frame) -> Frame? = { null }) = App(port, features, answer).also { apps += it }

    private fun code(f: Frame) = if (f.isError) ErrorReply.from(Node.decode("error", f.payload)).code else 0

    private fun text(f: Frame) = ErrorReply.from(Node.decode("error", f.payload)).text

    private fun seq(f: Frame) = SvcLoginStartReply.from(Node.decode("SVC_LOGIN_START.reply", f.payload)).seq

    private fun waitFor(ms: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(20)
        }
    }

    private fun start(a: App, id: String = "tidal") = a.call(Msg.SVC_LOGIN_START, Req.svcLoginStart(id))

    private fun logout(a: App, account: String, downloads: Long?, id: String = "tidal") = a.call(Msg.SVC_LOGOUT, Req.svcLogout(id, account, downloads))

    private fun redirect(f: Frame) = Frame(f.type, Proto.flagReply, f.seq, SvcPkceReply("https://svcdemo.test/cb?code=c0de").encode())

    @Test
    fun bit35FromMinor17AndTheAccountOnlyInSvcState() {
        val a = app(sim(accounts = listOf("tidal:in:Night Owl:12")).listen(0))
        assertTrue(a.hello.has("svcAccount"))
        assertEquals(listOf(tidal, radio), a.hello.services)
        waitFor { a.states().isNotEmpty() }
        assertEquals(listOf(SvcInfo("tidal", "TIDAL", listOf("client_id"), true, true, "Night Owl", 12), radio), a.states().first().services)
        val old = app(sim(minor = 16, accounts = listOf("tidal:in:Night Owl:12")).listen(0))
        assertFalse(old.hello.has("svcAccount"))
        assertTrue(old.hello.has("svcLogin"))
        val plain = app(sim(accounts = listOf("tidal:in:Night Owl:12")).listen(0), noAccount)
        old.ping()
        plain.ping()
        assertTrue(old.states().isEmpty())
        assertTrue(plain.states().isEmpty())
        assertTrue(logs.none { "Night Owl" in it })
    }

    @Test
    fun theGateAnswers4Then2Then1() {
        val refused = app(sim("refuse=SVC_LOGIN_START", "refuse=SVC_LOGOUT").listen(0), noAccount)
        assertEquals(Err.refused, code(start(refused)))
        assertEquals(Err.refused, code(refused.call(Msg.SVC_LOGIN_START, SvcLoginStart("").encode())))
        assertEquals(Err.refused, code(refused.call(Msg.SVC_LOGIN_START, SvcLoginStart("Bad Id").encode())))
        assertEquals(Err.refused, code(logout(refused, "", null, id = "")))
        val no35 = app(sim().listen(0), noAccount)
        assertEquals(Err.badValue, code(no35.call(Msg.SVC_LOGIN_START, SvcLoginStart("").encode())))
        assertEquals(Err.badValue, code(no35.call(Msg.SVC_LOGIN_START, SvcLoginStart("Bad Id").encode())))
        assertEquals(Err.unknown, code(start(no35)))
        assertEquals(Err.badValue, code(logout(no35, "", null, id = "")))
        assertEquals(Err.unknown, code(logout(no35, "", null)))
        val dev16 = app(sim(minor = 16).listen(0))
        assertEquals(Err.unknown, code(start(dev16)))
        assertEquals(Err.unknown, code(logout(dev16, "", null)))
    }

    @Test
    fun aStartAnswersItsSeqThenAsksAndAPendingPkceIsJoinedWithNoNewAsk() {
        val a = app(sim("svc-start=ok:5@0", accounts = listOf("tidal:out", "radio:out")).listen(0))
        val r = start(a)
        assertEquals(5L, seq(r))
        val ask = a.asks.poll(3, TimeUnit.SECONDS)!!
        assertEquals(SvcPkce("tidal", "https://login.svcdemo.test/auth?state=b0s5", "login.svcdemo.test", "https://svcdemo.test/cb", 5), SvcPkce.from(Node.decode("SVC_PKCE", ask.payload)))
        assertTrue(a.index(r) < a.index(ask))
        assertEquals(5L, seq(start(a)))
        assertEquals(Err.busy to "busy", start(a, "radio").let { code(it) to text(it) })
        a.ping()
        assertTrue(a.asks.isEmpty())
    }

    @Test
    fun aCodeLoginStartPushesItsLoginAndAJoinPushesItAgain() {
        val a = app(sim(accounts = listOf("tidal:out", "radio:out")).listen(0))
        val r = start(a, "radio")
        assertEquals(1L, seq(r))
        waitFor { a.pushes(Msg.SVC_LOGIN).isNotEmpty() }
        val l = a.pushes(Msg.SVC_LOGIN).single()
        assertEquals(SvcLogin("radio", "https://link.svcdemo.test/C1", "link.svcdemo.test", "C1", 300, 1), SvcLogin.from(Node.decode("SVC_LOGIN", l.payload)))
        assertTrue(a.index(r) < a.index(l))
        assertEquals(1L, seq(start(a, "radio")))
        waitFor { a.pushes(Msg.SVC_LOGIN).size == 2 }
        assertEquals(l.payload.toList(), a.pushes(Msg.SVC_LOGIN)[1].payload.toList())
        assertEquals(Err.busy, code(start(a)))
        assertTrue(a.asks.isEmpty())
        val bare = app(sim("svc-login-bare", accounts = listOf("radio:out")).listen(0))
        start(bare, "radio")
        waitFor { bare.pushes(Msg.SVC_LOGIN).isNotEmpty() }
        assertEquals("link.svcdemo.test/C1", SvcLogin.from(Node.decode("SVC_LOGIN", bare.pushes(Msg.SVC_LOGIN).single().payload)).uri)
    }

    @Test
    fun aCodeLoginFromTheFaultIsJoinedBySeq() {
        val a = app(sim("svc-login=4:link.svcdemo.test:ABCDE@0", accounts = listOf("tidal:out")).listen(0))
        waitFor { a.pushes(Msg.SVC_LOGIN).isNotEmpty() }
        assertEquals(4L, seq(start(a)))
        waitFor { a.pushes(Msg.SVC_LOGIN).size == 2 }
        assertEquals(a.pushes(Msg.SVC_LOGIN)[0].payload.toList(), a.pushes(Msg.SVC_LOGIN)[1].payload.toList())
    }

    @Test
    fun theStartErrorsInTheDeviceOrderAndADeferredReply() {
        val a = app(sim("svc-start=4:no keys@0", "svc-start=ok@400", accounts = listOf("tidal:out", "radio:out")).listen(0))
        assertEquals(Err.refused to "no keys", start(a, "radio").let { code(it) to text(it) })
        val t0 = System.currentTimeMillis()
        val s = a.request(Msg.SVC_LOGIN_START, Req.svcLoginStart("tidal"))
        assertEquals(Err.busy to "busy", start(a).let { code(it) to text(it) })
        val r = a.reply(Msg.SVC_LOGIN_START, s)
        assertTrue(System.currentTimeMillis() - t0 >= 400)
        assertEquals(1L, seq(r))
        assertEquals(Err.busy to "busy", start(a, "radio").let { code(it) to text(it) })
        assertEquals(Err.notFound to "not in firmware", start(a, "nope").let { code(it) to text(it) })
        assertEquals(Err.exists to "signed in", start(app(sim(accounts = listOf("tidal:in")).listen(0))).let { code(it) to text(it) })
        assertEquals(Err.off to "off", start(app(sim("svc-off").listen(0))).let { code(it) to text(it) })
        assertEquals(Err.busy to "busy", start(app(sim("svc-removing", accounts = listOf("tidal:out")).listen(0))).let { code(it) to text(it) })
        assertEquals(Err.refused to "refused", start(app(sim("svc-stopped=tidal", accounts = listOf("tidal:out")).listen(0))).let { code(it) to text(it) })
        assertEquals(Err.noCard to "card not available", start(app(sim("card-seq=busy:5000,ready", accounts = listOf("tidal:out::?")).listen(0))).let { code(it) to text(it) })
        val k = app(sim("svc-keys-wait=600", accounts = listOf("tidal:out")).listen(0))
        val keys = k.request(Msg.SVC_KEYS, Req.svcKeys("tidal", listOf(SvcKey("client_id", "x"))))
        assertEquals(Err.busy, code(start(k)))
        assertEquals(0, code(k.reply(Msg.SVC_KEYS, keys)))
        assertEquals(0, code(start(k)))
    }

    @Test
    fun theStartGapPutsAStateBetweenTheReplyAndTheLoginInOneRead() {
        val a = app(sim("svc-start=ok:5@0", "svc-start-gap", accounts = listOf("radio:out")).listen(0))
        val r = start(a, "radio")
        waitFor { a.pushes(Msg.SVC_LOGIN).isNotEmpty() }
        val i = a.index(r)
        val j = a.index(a.pushes(Msg.SVC_LOGIN).single())
        assertEquals(5L, SvcLogin.from(Node.decode("SVC_LOGIN", a.frames[j].second.payload)).seq)
        assertTrue(a.frames.subList(i + 1, j).any { it.second.type == Msg.STATE })
        assertEquals(a.frames[i].first, a.frames[j].first)
    }

    @Test
    fun aLogoutChecksTheAccountTheSignedInStateAndTheCount() {
        val a = app(sim(accounts = listOf("tidal:in:Night Owl:3")).listen(0))
        waitFor { a.states().isNotEmpty() }
        assertEquals(Err.staleRev to "stale", logout(a, "Other", 3).let { code(it) to text(it) })
        assertEquals(Err.staleRev to "stale", logout(a, "", 3).let { code(it) to text(it) })
        val pushed = a.states().size
        val changed = logout(a, "Night Owl", 2)
        assertEquals(Err.staleRev to "downloads changed", code(changed) to text(changed))
        waitFor { a.states().size == pushed + 1 }
        assertTrue(a.index(changed) < a.index(a.pushes(Msg.SVC_STATE).last()))
        assertEquals(3L, a.states().last().services[0].downloads)
        assertEquals(Err.staleRev to "downloads changed", logout(a, "Night Owl", null).let { code(it) to text(it) })
        assertEquals(0, code(logout(a, "Night Owl", 3)))
        waitFor { a.states().last().services[0].signedIn == false }
        assertEquals(SvcInfo("tidal", "TIDAL", listOf("client_id"), true, false, null, 0), a.states().last().services[0])
        assertEquals(0, code(logout(a, "", null)))
        assertEquals(Err.staleRev, code(logout(a, "Night Owl", 0)))
        assertEquals(Err.busy to "busy", logout(app(sim(accounts = listOf("tidal:unknown")).listen(0)), "", null).let { code(it) to text(it) })
        assertEquals(Err.busy to "downloads not counted", logout(app(sim(accounts = listOf("tidal:in:Night Owl:?")).listen(0)), "Night Owl", null).let { code(it) to text(it) })
        assertEquals(0, code(logout(app(sim(accounts = listOf("tidal:in:Night Owl")).listen(0)), "Night Owl", null)))
        assertEquals(Err.refused to "stopped", logout(app(sim("svc-stopped=tidal", accounts = listOf("tidal:in:Night Owl")).listen(0)), "Night Owl", null).let { code(it) to text(it) })
        assertEquals(0, code(logout(app(sim(accounts = listOf("tidal:in:Night Owl:0")).listen(0)), "Night Owl", null)))
        assertEquals(0, code(logout(app(sim(accounts = listOf("tidal:in:Night Owl:0")).listen(0)), "Night Owl", 0)))
        assertTrue(logs.none { "Night Owl" in it })
    }

    @Test
    fun aDeferredLogoutWaitsAndATimeoutComesAfterTheSignedOutState() {
        val s = sim("svc-logout=ok@400", "svc-logout=3:timeout@100", accounts = listOf("tidal:in:Night Owl"))
        val a = app(s.listen(0))
        waitFor { a.states().isNotEmpty() }
        val t0 = System.currentTimeMillis()
        val r = a.request(Msg.SVC_LOGOUT, Req.svcLogout("tidal", "Night Owl", null))
        assertEquals(Err.busy, code(logout(a, "Night Owl", null)))
        assertEquals(0, code(a.reply(Msg.SVC_LOGOUT, r)))
        assertTrue(System.currentTimeMillis() - t0 >= 400)
        waitFor { a.states().last().services[0].signedIn == false }
        s.svcState(SvcAcct.parse("tidal:in:Night Owl"))
        waitFor { a.states().last().services[0].signedIn == true }
        val timeout = logout(a, "Night Owl", null)
        assertEquals(Err.busy to "timeout", code(timeout) to text(timeout))
        val out = a.pushes(Msg.SVC_STATE).last()
        assertEquals(false, SvcState.from(Node.decode("SVC_STATE", out.payload)).services[0].signedIn)
        assertTrue(a.index(out) < a.index(timeout))
    }

    @Test
    fun aDownloadsOnlyChangePushesAtMostEvery10s() {
        val t = AtomicLong(1_000_000)
        val s = sim(accounts = listOf("tidal:in:Night Owl:?"), clock = { t.get() })
        val a = app(s.listen(0))
        waitFor { a.states().isNotEmpty() }
        assertNull(a.states().single().services[0].downloads)
        t.addAndGet(3000)
        s.svcState(SvcAcct.parse("tidal:in:Night Owl:5"))
        s.device.tick(s.current!!)
        a.ping()
        assertEquals(1, a.states().size)
        t.set(1_000_000 + Proto.stateIntervalMs - 1L)
        s.device.tick(s.current!!)
        a.ping()
        assertEquals(1, a.states().size)
        t.set(1_000_000 + Proto.stateIntervalMs.toLong())
        waitFor { a.states().size == 2 }
        assertEquals(5L, a.states().last().services[0].downloads)
        t.addAndGet(500)
        s.svcState(SvcAcct.parse("tidal:out"))
        waitFor { a.states().size == 3 }
        assertEquals(false, a.states().last().services[0].signedIn)
    }

    @Test
    fun aCardHoldMakesTheCountUnknownUntilTheNewCardsCount() {
        val t = AtomicLong(1_000_000)
        val s = sim("card-seq=busy:800,ready", accounts = listOf("tidal:in:Night Owl:3"), clock = { t.get() })
        val a = app(s.listen(0))
        waitFor { a.states().isNotEmpty() }
        assertNull(a.states().single().services[0].downloads)
        assertEquals(Err.noCard to "card not available", logout(a, "Night Owl", 3).let { code(it) to text(it) })
        t.addAndGet(800)
        assertEquals(Err.busy to "downloads not counted", logout(a, "Night Owl", 3).let { code(it) to text(it) })
        s.svcState(SvcAcct.parse("tidal:in:Night Owl:5"))
        t.addAndGet(Proto.stateIntervalMs.toLong())
        waitFor { a.states().last().services[0].downloads == 5L }
        val pushed = a.states().size
        val changed = logout(a, "Night Owl", 3)
        assertEquals(Err.staleRev to "downloads changed", code(changed) to text(changed))
        waitFor { a.states().size == pushed + 1 }
        assertTrue(a.index(changed) < a.index(a.pushes(Msg.SVC_STATE).last()))
        assertEquals(5L, a.states().last().services[0].downloads)
    }

    @Test
    fun theOpenPkceIsAskedAgainOnTheNextLinkUntilARedirect() {
        val s = sim("svc-start=ok:3@0", "svc-reask", accounts = listOf("tidal:out"))
        val a1 = app(s.listen(0))
        start(a1)
        val q1 = a1.asks.poll(3, TimeUnit.SECONDS)!!
        a1.sock.close()
        val a2 = app(s.listen(0))
        val q2 = a2.asks.poll(3, TimeUnit.SECONDS)!!
        assertEquals(q1.payload.toList(), q2.payload.toList())
        waitFor { a2.states().isNotEmpty() }
        assertTrue(a2.index(q2) < a2.index(a2.pushes(Msg.SVC_STATE).first()))
        a2.send(redirect(q2))
        waitFor { logs.any { it.contains("ask SVC_PKCE") && it.contains("-> ok") } }
        a2.sock.close()
        val a3 = app(s.listen(0))
        s.device.tick(s.current!!)
        a3.ping()
        assertTrue(a3.asks.isEmpty())
    }

    @Test
    fun aReaskWaitsForTheAskSlotAndComesAfterTheFirstState() {
        val s = sim("svc-start=ok:3@0", "svc-reask", "ask-held=600", accounts = listOf("tidal:out"))
        val a1 = app(s.listen(0))
        start(a1)
        val q1 = a1.asks.poll(3, TimeUnit.SECONDS)!!
        a1.sock.close()
        val a2 = app(s.listen(0))
        val q2 = a2.asks.poll(3, TimeUnit.SECONDS)!!
        assertEquals(q1.payload.toList(), q2.payload.toList())
        assertTrue(a2.index(a2.pushes(Msg.SVC_STATE).first()) < a2.index(q2))
    }

    @Test
    fun aReconnectSendsAPendingCodeLoginAgain() {
        val s = sim("svc-reask", accounts = listOf("radio:out"))
        val a1 = app(s.listen(0))
        start(a1, "radio")
        waitFor { a1.pushes(Msg.SVC_LOGIN).isNotEmpty() }
        a1.sock.close()
        val a2 = app(s.listen(0))
        waitFor { a2.pushes(Msg.SVC_LOGIN).isNotEmpty() }
        assertEquals(a1.pushes(Msg.SVC_LOGIN).single().payload.toList(), a2.pushes(Msg.SVC_LOGIN).single().payload.toList())
    }

    @Test
    fun aReplacedLinkKeepsTheLoginForTheReask() {
        val s = sim("svc-start=ok:3@0", "svc-reask", accounts = listOf("tidal:out"))
        val port = s.listen(0)
        val a1 = app(port)
        start(a1)
        a1.asks.poll(3, TimeUnit.SECONDS)!!
        val a2 = app(port)
        waitFor { a1.pushes(Msg.BYE).isNotEmpty() }
        assertEquals(3L, SvcPkce.from(Node.decode("SVC_PKCE", a2.asks.poll(3, TimeUnit.SECONDS)!!.payload)).seq)
    }

    @Test
    fun aDropAfterTheReplyKeepsTheLoginForTheReask() {
        val s = sim("svc-start=ok:3@0", "svc-reask", "drop-after-reply", accounts = listOf("tidal:out"))
        val port = s.listen(0)
        val a1 = app(port) { redirect(it) }
        start(a1)
        waitFor { a1.closed }
        val a2 = app(port)
        assertEquals(3L, SvcPkce.from(Node.decode("SVC_PKCE", a2.asks.poll(3, TimeUnit.SECONDS)!!.payload)).seq)
    }

    @Test
    fun theLoginEndsDoneAfterTheRedirectOrTheEndIsLostInADrop() {
        val a = app(sim("svc-start=ok:3@0", "svc-done=100", accounts = listOf("tidal:out:Night Owl")).listen(0)) { redirect(it) }
        start(a)
        waitFor { a.pushes(Msg.SVC_LOGIN_END).isNotEmpty() }
        assertEquals(SvcLoginEnd("tidal", 3, Enums.SvcLoginResult.done), SvcLoginEnd.from(Node.decode("SVC_LOGIN_END", a.pushes(Msg.SVC_LOGIN_END).single().payload)))
        waitFor { a.states().last().services[0].signedIn == true }
        assertTrue(a.index(a.pushes(Msg.SVC_LOGIN_END).single()) < a.index(a.pushes(Msg.SVC_STATE).last()))
        assertEquals("Night Owl", a.states().last().services[0].account)
        val s = sim("svc-start=ok:3@0", "svc-done=100", "drop-before-end", "svc-reask", accounts = listOf("tidal:out"))
        val port = s.listen(0)
        val b1 = app(port) { redirect(it) }
        start(b1)
        waitFor { b1.closed }
        assertTrue(b1.pushes(Msg.SVC_LOGIN_END).isEmpty())
        val b2 = app(port)
        s.device.tick(s.current!!)
        b2.ping()
        assertTrue(b2.asks.isEmpty())
        assertTrue(b2.pushes(Msg.SVC_LOGIN_END).isEmpty())
    }

    @Test
    fun anErrorAnswerCancelsTheLogin() {
        val s = sim("svc-start=ok:3@0", "svc-reask", accounts = listOf("tidal:out"))
        val port = s.listen(0)
        val a = app(port) { Frame(it.type, Proto.flagReply or Proto.flagError, it.seq, ErrorReply(Err.cancelled, "cancelled").encode()) }
        start(a)
        waitFor { a.pushes(Msg.SVC_LOGIN_END).isNotEmpty() }
        assertEquals(SvcLoginEnd("tidal", 3, Enums.SvcLoginResult.cancelled), SvcLoginEnd.from(Node.decode("SVC_LOGIN_END", a.pushes(Msg.SVC_LOGIN_END).single().payload)))
        a.sock.close()
        val a2 = app(port)
        s.device.tick(s.current!!)
        a2.ping()
        assertTrue(a2.asks.isEmpty())
    }

    @Test
    fun aPlayerBootRestartsTheSeqWithANewLink() {
        val s = sim(accounts = listOf("tidal:out"))
        val a = app(s.listen(0)) { Frame(it.type, Proto.flagReply or Proto.flagError, it.seq, ErrorReply(Err.cancelled, "cancelled").encode()) }
        assertEquals(1L, seq(start(a)))
        waitFor { a.pushes(Msg.SVC_LOGIN_END).isNotEmpty() }
        s.svcBoot()
        assertEquals(1L, seq(start(a)))
        waitFor { a.asks.size == 2 }
        val urls = a.asks.toList().map { SvcPkce.from(Node.decode("SVC_PKCE", it.payload)).authorizeUrl }
        assertEquals(listOf("https://login.svcdemo.test/auth?state=b0s1", "https://login.svcdemo.test/auth?state=b1s1"), urls)
    }

    @Test
    fun theStateFaultChangesTheStateOnTime() {
        val a = app(sim("svc-state=tidal:in:Night Owl@200", accounts = listOf("tidal:out")).listen(0))
        waitFor { a.states().isNotEmpty() }
        assertEquals(false, a.states().first().services[0].signedIn)
        waitFor { a.states().last().services[0].signedIn == true }
        assertEquals("Night Owl", a.states().last().services[0].account)
    }

    @Test
    fun theNewFlagsParse() {
        assertEquals(SvcAcct("tidal", true, "Night Owl", 12, true), SvcAcct.parse("tidal:in:Night Owl:12"))
        assertEquals(SvcAcct("tidal", false, null, null, true), SvcAcct.parse("tidal:out::?"))
        assertEquals(SvcAcct("tidal", null), SvcAcct.parse("tidal:unknown"))
        for (bad in listOf("tidal", "tidal:maybe", "tidal:in:x:many")) assertThrows(bad, IllegalArgumentException::class.java) { SvcAcct.parse(bad) }
        for (bad in listOf("svc-start=yes@0", "svc-start=ok:x@0", "svc-start=ok", "svc-logout=ok", "svc-done=x", "svc-done@100", "svc-keys-wait=x", "ask-held=x")) assertThrows(bad, IllegalArgumentException::class.java) { Faults(listOf(bad)) }
    }
}
