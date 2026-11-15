package net.seilent.funecomp

import net.seilent.funecomp.link.Session
import net.seilent.funecomp.proto.BindAskReply
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.gen.Msg
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BindAskTest {
    private val key = "rfcomm:AA:BB:CC:00:00:01"
    private val session = Any()
    private val token = ByteArray(16) { (it + 5).toByte() }
    private var now = 1_000L
    private val sent = ArrayList<(Session.Result) -> Unit>()
    private val request = BindAsk.Request { _, type, _, _, done -> if (type == Msg.BIND_ASK) sent += done }

    @Before
    fun setUp() = BindTokens.reset()

    @After
    fun tearDown() = BindTokens.reset()

    @Test
    fun aTapAsksOnceAndASecondAskWithItSendsNothing() {
        val bind = BindAsk(request)
        val tap = Tap(now) { now }
        val results = ArrayList<Session.Result>()
        bind.ask(key, tap, session) { results += it }
        assertEquals(1, sent.size)
        sent.single()(Session.Result.Ok(Node.decode("BIND_ASK.reply", BindAskReply(token).encode())))
        assertArrayEquals(token, BindTokens.get(key))
        bind.ask(key, tap, session) { results += it }
        assertEquals(1, sent.size)
        assertEquals(BindAsk.NO_TAP, (results.last() as Session.Result.Failed).error.text)
    }

    @Test
    fun aRequestTimeoutUnder3SecondsIsRefused() {
        assertTrue(runCatching { BindAsk(request, 2_999) }.isFailure)
        assertEquals(3_000L, BindAsk(request, 3_000).requestTimeoutMs)
    }
}
