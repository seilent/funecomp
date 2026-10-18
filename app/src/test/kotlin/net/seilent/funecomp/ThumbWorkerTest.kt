package net.seilent.funecomp

import net.seilent.funecomp.art.ThumbWorker
import net.seilent.funecomp.art.Thumbs
import net.seilent.funecomp.link.Session
import net.seilent.funecomp.link.TcpTransport
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ThumbWorkerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val logs = CopyOnWriteArrayList<String>()
    private var sim: Sim? = null
    private var session: Session? = null

    @After
    fun tearDown() {
        session?.close("done")
        sim?.stop()
    }

    @Test
    fun aThumbFetchedForTheMediaSlotAlsoReachesNowPlaying() {
        val lib = Library.synthetic()
        val art = tmp.newFolder("art")
        val jpeg = java.awt.image.BufferedImage(600, 600, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (t in lib.tracks) javax.imageio.ImageIO.write(jpeg, "jpg", java.io.File(art, "${lib.albumKey(t.id)}.jpg"))
        val s = Sim(Options(Board.of("v1"), Faults(listOf("stall-ms=200")), artDir = art, log = { logs += it }))
        sim = s
        val port = s.listen(0)
        val ready = CountDownLatch(1)
        var hello: HelloReply? = null
        val link = Session(TcpTransport("127.0.0.1", port), object : Session.Listener {
            override fun onHello(h: HelloReply) {
                hello = h
                ready.countDown()
            }
        }, Session.Config())
        session = link
        Thread { link.run() }.apply { isDaemon = true }.start()
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        val worker = ThumbWorker(tmp.newFolder("thumb"), { link })
        hello!!.libRev?.let { worker.rev(it) }
        worker.session()
        val track = lib.tracks.first().id
        worker.want(Thumbs.MEDIA, track, MediaLink.ART_PX, null)
        val end = System.currentTimeMillis() + 15_000
        while (logs.none { it.startsWith("art get") }) {
            check(System.currentTimeMillis() < end) { "no art get" }
            Thread.sleep(10)
        }
        worker.want(Thumbs.NOW_PLAYING, track, 667, null)
        while (worker.image(track, 667) == null) {
            check(System.currentTimeMillis() < end) { "no now playing image; media=${worker.media(track, MediaLink.ART_PX) != null} ${logs.filter { it.startsWith("art get") }}" }
            Thread.sleep(50)
        }
        assertNotNull(worker.media(track, MediaLink.ART_PX))
        assertEquals(1, logs.count { it.startsWith("art get") && it.contains("offset=0 ") })
    }
}
