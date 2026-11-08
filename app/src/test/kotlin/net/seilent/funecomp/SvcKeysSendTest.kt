package net.seilent.funecomp

import net.seilent.funecomp.link.Session
import net.seilent.funecomp.proto.ErrorReply
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.SvcKey
import net.seilent.funecomp.proto.SvcKeys
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SvcKeysSendTest {
    private val logs = CopyOnWriteArrayList<String>()
    private val keys = listOf(SvcKey("client_id", "s3cretid"), SvcKey("client_secret", "s3cretkey"))

    private class Sent(val type: Int, val payload: ByteArray, val copy: ByteArray, val timeoutMs: Long, val done: (Session.Result) -> Unit)

    private fun send(): Pair<Sent, () -> String?> {
        var sent: Sent? = null
        var line: String? = null
        Svc.keys("tidal", keys, { type, payload, timeout, done -> sent = Sent(type, payload, payload.copyOf(), timeout, done) }, { logs += it }) { line = it }
        return sent!! to { line }
    }

    @Test
    fun theKeysGoOutWithTheServiceTimeoutAndThePayloadIsZeroedAfter() {
        val (s, _) = send()
        assertEquals(Msg.SVC_KEYS, s.type)
        assertEquals(Proto.svcReplyMs.toLong(), s.timeoutMs)
        assertTrue(s.timeoutMs > 60_000)
        assertTrue(s.payload.all { it == 0.toByte() })
        assertEquals(SvcKeys("tidal", keys), SvcKeys.from(Node.decode("SVC_KEYS", s.copy)))
    }

    @Test
    fun everyReplyHasItsLine() {
        fun line(r: Session.Result): String? {
            val (s, got) = send()
            s.done(r)
            return got()
        }
        fun failed(code: Int) = Session.Result.Failed(ErrorReply(code, "x"))
        assertEquals(Svc.KEYS_SENT, line(Session.Result.Ok(Node("SVC_KEYS.reply", emptyList()))))
        assertEquals(Svc.REFUSED, line(failed(Err.refused)))
        assertEquals(Svc.OFF, line(failed(Err.off)))
        assertEquals(Svc.BAD_KEY, line(failed(Err.badValue)))
        assertEquals(Svc.BUSY, line(failed(Err.busy)))
        assertEquals("not installed", line(failed(Err.notFound)))
        assertEquals(Svc.REFUSED, line(failed(Err.unknown)))
        assertEquals(Svc.NO_REPLY, line(Session.Result.Timeout))
        assertEquals(Svc.NO_REPLY, line(Session.Result.Closed))
        assertEquals(Svc.NO_REPLY, line(Session.Result.Unsupported))
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.none { "s3cret" in it || "client_" in it })
    }
}
