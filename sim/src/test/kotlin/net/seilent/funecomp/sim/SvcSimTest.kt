package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.Bye
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
import net.seilent.funecomp.proto.SvcPkce
import net.seilent.funecomp.proto.SvcPkceReply
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class SvcSimTest {
    private val sims = ArrayList<Sim>()
    private val apps = ArrayList<App>()
    private val logs = CopyOnWriteArrayList<String>()
    private val tidal = SvcInfo("tidal", "TIDAL", listOf("client_id", "client_secret", "pkce_client_id", "pkce_client_secret"), true)
    private val bit = Groups.mask("svcLogin")

    @After
    fun tearDown() {
        apps.forEach { it.sock.close() }
        sims.forEach { it.stop() }
    }

    private fun sim(vararg faults: String, minor: Int = Proto.minor, services: List<SvcInfo> = listOf(tidal)) =
        Sim(Options(Board.of("v1"), Faults(faults.toList()), minor = minor, log = { logs += it }, services = services)).also { sims += it }

    private class App(port: Int, features: Long?, val answer: (Frame) -> Frame?) {
        val sock = Socket("127.0.0.1", port)
        val replies = LinkedBlockingQueue<Frame>()
        val asks = LinkedBlockingQueue<Frame>()
        val pushes = LinkedBlockingQueue<Frame>()
        private var seq = 1
        lateinit var hello: HelloReply

        init {
            Thread {
                val r = FrameReader()
                val buf = ByteArray(8192)
                try {
                    while (true) {
                        val n = sock.getInputStream().read(buf)
                        if (n < 0) break
                        for (f in r.feed(buf, 0, n)) {
                            when {
                                f.isReply -> replies += f
                                f.wantsReply && f.type == Msg.SVC_PKCE -> {
                                    asks += f
                                    answer(f)?.let { send(it) }
                                }
                                !f.wantsReply -> pushes += f
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true }.start()
            hello = HelloReply.from(Node.decode("HELLO.reply", call(Msg.HELLO, Hello(Proto.major, Proto.minor, "t", features).encode()).payload))
        }

        @Synchronized
        fun send(f: Frame) {
            sock.getOutputStream().write(f.encode())
            sock.getOutputStream().flush()
        }

        fun call(type: Int, payload: ByteArray): Frame {
            val s = seq++
            send(Frame(type, Proto.flagWant, s, payload))
            while (true) {
                val f = replies.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no reply to ${Msg.info[type]?.name}")
                if (f.type == type && f.seq == s) return f
            }
        }

        fun pushed(type: Int): Frame? {
            val end = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < end) {
                val f = pushes.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (f.type == type) return f
            }
            return null
        }
    }

    private fun app(port: Int, features: Long? = Groups.all, answer: (Frame) -> Frame? = { null }) = App(port, features, answer).also { apps += it }

    private fun code(f: Frame) = if (f.isError) ErrorReply.from(Node.decode("error", f.payload)).code else 0

    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(20)
        }
    }

    @Test
    fun servicesGoOnlyToABit32AppOnAMinor16Sim() {
        assertEquals(listOf(tidal), app(sim().listen(0)).hello.services)
        assertTrue(app(sim().listen(0)).hello.has("svcLogin"))
        assertEquals(emptyList<SvcInfo>(), app(sim().listen(0), Groups.all and bit.inv()).hello.services)
        val old = app(sim(minor = 15).listen(0))
        assertFalse(old.hello.has("svcLogin"))
        assertEquals(emptyList<SvcInfo>(), old.hello.services)
        assertEquals(SvcInfo("demo", "Demo", emptyList(), false), Options.svc("demo:Demo:"))
        assertEquals(tidal, Options.svc("tidal:TIDAL:client_id+client_secret+pkce_client_id+pkce_client_secret:pkce"))
    }

    @Test
    fun theLoginFaultsPushTheLoginAndItsEnd() {
        val a = app(sim("svc-login=7:link.svcdemo.test:ABCDE@50", "svc-login-end=7:done@300").listen(0))
        val l = SvcLogin.from(Node.decode("SVC_LOGIN", a.pushed(Msg.SVC_LOGIN)!!.payload))
        assertEquals(SvcLogin("tidal", "https://link.svcdemo.test/ABCDE", "link.svcdemo.test", "ABCDE", 300, 7), l)
        assertEquals(SvcLoginEnd("tidal", 7, Enums.SvcLoginResult.done), SvcLoginEnd.from(Node.decode("SVC_LOGIN_END", a.pushed(Msg.SVC_LOGIN_END)!!.payload)))
        val bare = app(sim("svc-login=8:link.svcdemo.test:FGHIJ@0", "svc-login-bare").listen(0))
        assertEquals("link.svcdemo.test/FGHIJ", SvcLogin.from(Node.decode("SVC_LOGIN", bare.pushed(Msg.SVC_LOGIN)!!.payload)).uri)
        val plain = app(sim("svc-login=7:link.svcdemo.test:ABCDE@0").listen(0), Groups.all and bit.inv())
        assertNull(plain.pushed(Msg.SVC_LOGIN))
        val off = app(sim("svc-login=7:link.svcdemo.test:ABCDE@0", "svc-off").listen(0))
        assertNull(off.pushed(Msg.SVC_LOGIN))
    }

    @Test
    fun thePkceAskLogsOnlyTheRedirectLength() {
        val redirect = "https://svcdemo.test/cb?code=c0de&state=s"
        val a = app(sim("svc-pkce=7@50").listen(0)) { Frame(it.type, Proto.flagReply, it.seq, SvcPkceReply(redirect).encode()) }
        val q = SvcPkce.from(Node.decode("SVC_PKCE", a.asks.poll(3, TimeUnit.SECONDS)!!.payload))
        assertEquals(SvcPkce("tidal", "https://login.svcdemo.test/auth", "login.svcdemo.test", "https://svcdemo.test/cb", 7), q)
        waitFor { logs.any { it.endsWith("-> ok len=${redirect.length}") } }
        assertTrue(logs.none { "c0de" in it })
    }

    @Test
    fun anEndForTheAskSeqDropsItAndAnotherSeqLeavesItOpen() {
        val s = sim()
        val a = app(s.listen(0))
        var out: String? = null
        val t = Thread { out = s.askPkce(SvcPkce("tidal", "https://login.svcdemo.test/auth", "login.svcdemo.test", "https://svcdemo.test/cb", 9)) }.apply { start() }
        val f = a.asks.poll(3, TimeUnit.SECONDS)!!
        s.svcEnd(SvcLoginEnd("tidal", 8, Enums.SvcLoginResult.cancelled))
        Thread.sleep(200)
        assertTrue(t.isAlive)
        s.svcEnd(SvcLoginEnd("tidal", 9, Enums.SvcLoginResult.cancelled))
        t.join(3000)
        assertEquals("ended", out)
        a.send(Frame(f.type, Proto.flagReply, f.seq, SvcPkceReply("https://svcdemo.test/cb?code=x").encode()))
        Thread.sleep(200)
        assertTrue(logs.none { it.contains("ask SVC_PKCE") && it.contains("-> ok") })
        assertEquals(2, generateSequence { a.pushes.poll() }.count { it.type == Msg.SVC_LOGIN_END })
    }

    @Test
    fun theFaultEndsThePkceAsk() {
        val a = app(sim("svc-pkce=7@0", "svc-pkce-end=7@300").listen(0))
        assertTrue(a.asks.poll(3, TimeUnit.SECONDS) != null)
        assertEquals(7L, SvcLoginEnd.from(Node.decode("SVC_LOGIN_END", a.pushed(Msg.SVC_LOGIN_END)!!.payload)).seq)
        waitFor { logs.any { it.endsWith("-> ended") } }
    }

    @Test
    fun anEndAndTheNextAskAtOneTimeBothGoOut() {
        val a = app(sim("svc-pkce=7@0", "svc-pkce-end=7@300", "svc-pkce=8@300").listen(0))
        assertEquals(7L, SvcPkce.from(Node.decode("SVC_PKCE", a.asks.poll(3, TimeUnit.SECONDS)!!.payload)).seq)
        assertEquals(7L, SvcLoginEnd.from(Node.decode("SVC_LOGIN_END", a.pushed(Msg.SVC_LOGIN_END)!!.payload)).seq)
        assertEquals(8L, SvcPkce.from(Node.decode("SVC_PKCE", a.asks.poll(3, TimeUnit.SECONDS)!!.payload)).seq)
    }

    private fun keys(vararg kv: Pair<String, String>, service: String = "tidal") = Req.svcKeys(service, kv.map { SvcKey(it.first, it.second) })

    @Test
    fun keysAreCheckedByNameAndNeverLogged() {
        val a = app(sim().listen(0))
        assertEquals(0, code(a.call(Msg.SVC_KEYS, keys("client_id" to "v4lue1", "client_secret" to "v4lue2"))))
        assertEquals(Err.badValue, code(a.call(Msg.SVC_KEYS, keys("other" to "v4lue3"))))
        assertEquals(Err.badValue, code(a.call(Msg.SVC_KEYS, keys("client_id" to ""))))
        assertEquals(Err.notFound, code(a.call(Msg.SVC_KEYS, keys("client_id" to "v4lue4", service = "other"))))
        assertEquals(Err.badValue, code(a.call(Msg.SVC_KEYS, keys())))
        assertTrue(logs.any { it.startsWith("svc keys tidal names=[client_id, client_secret] bytes=") })
        assertTrue(logs.none { "v4lue" in it })
    }

    @Test
    fun theKeysFaultsAnswerTheirCodes() {
        val a = app(sim("svc-keys=4:1").listen(0))
        assertEquals(Err.refused, code(a.call(Msg.SVC_KEYS, keys("client_id" to "x"))))
        assertEquals(0, code(a.call(Msg.SVC_KEYS, keys("client_id" to "x"))))
        assertEquals(Err.off, code(app(sim("svc-off").listen(0)).call(Msg.SVC_KEYS, keys("client_id" to "x"))))
        assertEquals(Err.unknown, code(app(sim(minor = 15).listen(0)).call(Msg.SVC_KEYS, keys("client_id" to "x"))))
    }

    @Test
    fun replaceSendsByeReplaced() {
        val a = app(sim("replace@100").listen(0))
        assertEquals(Enums.ByeReason.replaced, Bye.from(Node.decode("BYE", a.pushed(Msg.BYE)!!.payload)).reason)
    }
}
