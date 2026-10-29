package net.seilent.funecomp.link

import net.seilent.funecomp.proto.Bye
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import javax.imageio.ImageIO

class ArtGetTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val sims = ArrayList<Sim>()
    private val sessions = ArrayList<Session>()
    private val lib = Library.synthetic()
    private val track = lib.tracks.first().id

    @After
    fun tearDown() {
        sessions.forEach { it.close("done") }
        sims.forEach { it.stop() }
    }

    private class Hello : Session.Listener {
        val hello = CountDownLatch(1)
        override fun onHello(hello: HelloReply) = this.hello.countDown()
        override fun onPush(type: Int, body: Node) {}
        override fun onBye(bye: Bye) {}
    }

    private fun cover(seed: Long) {
        val img = BufferedImage(900, 700, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        val r = java.util.Random(seed)
        repeat(300) {
            g.color = Color(r.nextInt(0xffffff))
            g.fillRect(r.nextInt(900), r.nextInt(700), 40, 40)
        }
        g.dispose()
        val f = File(tmp.root.apply { mkdirs() }, "${lib.albumKey(track)}.jpg")
        ImageIO.write(img, "jpg", f)
        f.setLastModified(1_000_000_000_000L + seed * 10_000)
    }

    private fun session(art: Boolean = true, minor: Int = Proto.minor): Session {
        if (art) {
            val img = BufferedImage(900, 700, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            val r = java.util.Random(3)
            repeat(300) {
                g.color = Color(r.nextInt(0xffffff))
                g.fillRect(r.nextInt(900), r.nextInt(700), 40, 40)
            }
            g.dispose()
            ImageIO.write(img, "jpg", File(tmp.root.apply { mkdirs() }, "${lib.albumKey(track)}.jpg"))
        }
        val sim = Sim(Options(Board.of("v1"), Faults(emptyList()), minor = minor, artDir = tmp.root), lib)
        sims += sim
        val h = Hello()
        val s = Session(TcpTransport("127.0.0.1", sim.listen(0)), h, Session.Config("test"))
        sessions += s
        Thread { s.run() }.apply { isDaemon = true }.start()
        assertTrue(h.hello.await(5, TimeUnit.SECONDS))
        return s
    }

    @Test
    fun chunksReassembleAndHaveSkipsTheData() {
        val s = session()
        val r = ArtGet(s).run(track, 512) as ArtGet.Outcome.Image
        assertEquals(512, r.w)
        assertTrue(r.jpeg.size > Proto.artChunk)
        assertEquals(r.crc, CRC32().apply { update(r.jpeg) }.value)
        val have = ArtGet(s).run(track, 512, have = r.crc) as ArtGet.Outcome.Have
        assertEquals(r.crc, have.crc)
        assertTrue(ArtGet(s).run(track, 512, have = r.crc + 1) is ArtGet.Outcome.Image)
    }

    @Test
    fun noArtIsNotFoundAndOldPeersAreUnsupported() {
        assertEquals(Err.notFound, (ArtGet(session(art = false)).run(track, 256) as ArtGet.Outcome.Failed).code)
        assertEquals(null, (ArtGet(session(minor = 1)).run(track, 256) as ArtGet.Outcome.Failed).code)
    }

    @Test
    fun cancelStopsBetweenChunks() {
        val s = session()
        var calls = 0
        assertTrue(ArtGet(s) { calls++ > 1 }.run(track, 512) is ArtGet.Outcome.Cancelled)
    }

    @Test
    fun aCrcChangeMidTransferRestartsFromTheNewHeader() {
        val s = session()
        val before = (ArtGet(s).run(track, 512) as ArtGet.Outcome.Image).crc
        var calls = 0
        val r = ArtGet(s) {
            if (++calls == 1) cover(9)
            false
        }.run(track, 512) as ArtGet.Outcome.Image
        assertTrue(r.crc != before)
        assertEquals(r.crc, CRC32().apply { update(r.jpeg) }.value)
        assertEquals(r.crc, (ArtGet(s).run(track, 512, have = r.crc) as ArtGet.Outcome.Have).crc)
    }
}
