package net.seilent.funecomp.link

import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Feature
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UpdPushTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val sims = ArrayList<Sim>()
    private val sessions = ArrayList<Session>()
    private val image = ByteArray(200_000) { (it * 31 + 7).toByte() }
    private val md5 = MessageDigest.getInstance("MD5").digest(image).joinToString("") { "%02x".format(it) }

    @After
    fun tearDown() {
        sessions.forEach { it.close("done") }
        sims.forEach { it.stop() }
    }

    private fun sim(vararg faults: String): Int {
        val s = Sim(Options(Board.of("v1"), Faults(faults.toList() + "features=${Groups.all and (1L shl Feature.updSig).inv()}"), updDir = tmp.root))
        sims += s
        return s.listen(0)
    }

    private fun session(port: Int, config: Session.Config = Session.Config("test")): Session {
        val ready = CountDownLatch(1)
        val s = Session(TcpTransport("127.0.0.1", port), object : Session.Listener {
            override fun onHello(hello: HelloReply) = ready.countDown()
        }, config)
        sessions += s
        Thread { s.run() }.apply { isDaemon = true }.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        return s
    }

    private fun push(s: Session, board: String = "v1", sum: String = md5, gate: () -> Boolean = { true }, seen: MutableList<Long> = ArrayList()) =
        UpdPush(s, board, image.size.toLong(), sum, { off -> ByteArrayInputStream(image, off.toInt(), image.size - off.toInt()) }, gate) { seen += it }.run()

    @Test
    fun wholeImageLandsUnderTheBoardName() {
        val seen = ArrayList<Long>()
        val r = push(session(sim()), seen = seen) as UpdPush.Outcome.Done
        assertEquals(0L, r.from)
        assertEquals((image.size + Proto.updChunk - 1) / Proto.updChunk, r.chunks)
        assertArrayEquals(image, File(tmp.root, "v1.upt").readBytes())
        assertEquals(image.size.toLong(), seen.last())
    }

    @Test
    fun aDropResumesFromThePart() {
        val port = sim("drop-after-bytes=100000")
        assertTrue(push(session(port)) is UpdPush.Outcome.Paused)
        assertFalse(File(tmp.root, "v1.upt").exists())
        val r = push(session(sim())) as UpdPush.Outcome.Done
        assertTrue(r.from > 0)
        assertTrue(r.from % Proto.updChunk == 0L && r.from < image.size)
        assertArrayEquals(image, File(tmp.root, "v1.upt").readBytes())
    }

    @Test
    fun aBusyAbortAfterTheLastChunkIsAnAbortThenResumesAtThePart() {
        val port = sim("upd-abort-at=${image.size}")
        val s = session(port)
        val r = push(s) as UpdPush.Outcome.Aborted
        assertEquals(Enums.AbortCode.busy, r.abort.code)
        assertEquals(image.size.toLong(), r.abort.offset)
        val again = push(s) as UpdPush.Outcome.Done
        assertEquals(image.size.toLong(), again.from)
        assertArrayEquals(image, File(tmp.root, "v1.upt").readBytes())
    }

    @Test
    fun aTimeoutOrCloseOnBeginPauses() {
        val slow = session(sim("stall-ms=600"), Session.Config("test", requestTimeoutMs = 200))
        assertEquals(0L, (push(slow) as UpdPush.Outcome.Paused).at)
        val s = session(sim())
        s.close("gone")
        assertTrue(push(s) is UpdPush.Outcome.Paused)
    }

    @Test
    fun gateClosedPausesAndKeepsThePart() {
        val s = session(sim())
        var n = 0
        assertTrue(push(s, gate = { n++ < 3 }) is UpdPush.Outcome.Paused)
        val r = push(s) as UpdPush.Outcome.Done
        assertEquals(3L * Proto.updChunk, r.from)
    }

    @Test
    fun refusals() {
        val s = session(sim())
        assertEquals(Err.badValue, (push(s, board = "ap80") as UpdPush.Outcome.Failed).error?.code)
        assertEquals(Err.crc, (push(s, sum = "0".repeat(32)) as UpdPush.Outcome.Failed).error?.code)
        assertFalse(File(tmp.root, "v1.upt").exists())
    }
}
