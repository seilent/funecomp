package net.seilent.funecomp.artist

import net.seilent.funecomp.link.Session
import net.seilent.funecomp.link.TcpTransport
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.ImgListReply
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.SourceJpeg
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import javax.imageio.ImageIO

class FullPushTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val sims = ArrayList<Sim>()
    private val sessions = ArrayList<Session>()
    private val app = Groups.mask("artBlock", "imgOrigin", "imgSource")

    @After
    fun tearDown() {
        sessions.forEach { it.close("done") }
        sims.forEach { it.stop() }
    }

    private var faults = Faults(emptyList())

    private fun sim(vararg specs: String): Pair<Sim, Int> {
        faults = Faults(specs.toList())
        val s = Sim(Options(Board.of("v1"), faults, 30_000, 5_000, 5_000))
        sims += s
        return s to s.listen(0)
    }

    private fun session(port: Int, features: Long = app): Session {
        val ready = CountDownLatch(1)
        val s = Session(TcpTransport("127.0.0.1", port), object : Session.Listener {
            override fun onHello(hello: HelloReply) = ready.countDown()
        }, Session.Config("test", features = features))
        sessions += s
        Thread { s.run() }.apply { isDaemon = true }.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        return s
    }

    private class Setup(val cache: ArtCache, val codec: AwtCodec, val source: FullSource) {
        val sizes = ArrayList<Pair<Int, Int>>()

        fun hit(name: String, src: ByteArray) {
            cache.putHit(ArtCache.Entry(key(name), name, null, "test", null, "u", 1L), src)
        }

        fun lookup(name: String, mbid: String?, w: Int, h: Int): Art? {
            sizes += w to h
            val src = cache.source(key(name)) ?: return null
            val jpeg = Backdrop.render(codec, src, w, h) ?: return null
            return Art(jpeg, w, h, name, mbid, "test", null, key = key(name))
        }

        fun key(name: String) = "name:" + Names.fold(name)
    }

    private fun setup(codec: AwtCodec = AwtCodec(), maxBytes: Int = 4_194_304, maxSide: Int = 8192, dir: java.io.File = tmp.newFolder()): Setup {
        val cache = ArtCache(dir)
        return Setup(cache, codec, FullSource(cache, codec, maxBytes, maxSide))
    }

    private fun quads(w: Int, h: Int): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.RED
        g.fillRect(0, 0, w / 2, h / 2)
        g.color = Color.GREEN
        g.fillRect(w / 2, 0, w - w / 2, h / 2)
        g.color = Color.BLUE
        g.fillRect(0, h / 2, w / 2, h - h / 2)
        g.color = Color.WHITE
        g.fillRect(w / 2, h / 2, w - w / 2, h - h / 2)
        g.dispose()
        return img
    }

    private fun crc(b: ByteArray) = CRC32().apply { update(b) }.value

    private fun pusher(s: Session, u: Setup, bluetooth: () -> Boolean = { false }) = ArtPusher(s, u::lookup, FullPush(u.source, bluetooth), { true }) { true }

    @Test
    fun aBaselineSourceGoesByteEqualWithTheNoLinkFit() {
        val (sim, port) = sim("img-source")
        val s = session(port)
        val u = setup()
        val src = Images.jpeg(Images.photo(900, 600), 90)
        u.hit("Mili", src)
        val r = pusher(s, u).run(listOf("Mili" to null))
        assertEquals(ArtPusher.Stop.DONE, r.stop)
        assertEquals(listOf("Mili"), r.pushed.keys.toList())
        assertEquals(listOf(ArtPusher.FIT_W to ArtPusher.FIT_H), u.sizes)
        val stored = sim.device.sources.getValue("mili")
        assertArrayEquals(src, stored.bytes)
        assertEquals(900 to 600, stored.w to stored.h)
        assertTrue(sim.device.stored.isEmpty())
        assertEquals(crc(src), u.cache.full(u.key("Mili"))!!.crc)
        assertTrue(u.cache.full(u.key("Mili"))!!.copy)
    }

    @Test
    fun progressivePngAndRotatedSourcesAreReencodedBaselineAtTheirOwnSize() {
        val (sim, port) = sim("img-source")
        val s = session(port)
        val u = setup()
        u.hit("Prog", Images.progressive(Images.photo(800, 500)))
        u.hit("Png", Images.png(Images.photo(700, 900)))
        u.hit("Turned", Images.exif(Images.jpeg(quads(1200, 600), 95), 6))
        assertFalse(SourceJpeg.read(u.cache.source(u.key("Prog"))!!)!!.baseline)
        val r = pusher(s, u).run(listOf("Prog" to null, "Png" to null, "Turned" to null))
        assertEquals(ArtPusher.Stop.DONE, r.stop)
        val got = sim.device.sources
        for ((k, size) in listOf("prog" to (800 to 500), "png" to (700 to 900), "turned" to (600 to 1200))) {
            val st = got.getValue(k)
            val i = SourceJpeg.read(st.bytes)!!
            assertTrue(k, i.baseline && i.upright)
            assertEquals(k, size, st.w to st.h)
            assertEquals(k, size, i.w to i.h)
        }
        val turned = ImageIO.read(ByteArrayInputStream(got.getValue("turned").bytes))
        val tl = Color(turned.getRGB(20, 20))
        val br = Color(turned.getRGB(turned.width - 20, turned.height - 20))
        assertTrue("$tl", tl.blue > 200 && tl.red < 60)
        assertTrue("$br", br.green > 200 && br.red < 60 && br.blue < 60)
        assertFalse(u.cache.full(u.key("Png"))!!.copy)
        assertEquals(3, sim.device.fulls.size)
    }

    @Test
    fun aSourceOverTheSideCapIsBroughtWithinIt() {
        val (sim, port) = sim("img-source")
        val s = session(port)
        val u = setup(maxSide = 1000)
        u.hit("Wide", Images.jpeg(Images.photo(1500, 1000), 90))
        pusher(s, u).run(listOf("Wide" to null))
        val st = sim.device.sources.getValue("wide")
        assertEquals(1000 to 666, st.w to st.h)
        assertTrue(SourceJpeg.read(st.bytes)!!.baseline)
    }

    @Test
    fun aSourceOverTheByteCapStepsTheQualityDownAtItsOwnSize() {
        val img = Images.photo(1200, 900)
        val high = Images.jpeg(img, 90)
        val low = Images.jpeg(img, 40)
        val cap = (high.size + low.size) / 2
        val (sim, port) = sim("img-source")
        val s = session(port)
        val u = setup(maxBytes = cap)
        u.hit("Big", high)
        pusher(s, u).run(listOf("Big" to null))
        val st = sim.device.sources.getValue("big")
        assertEquals(1200 to 900, st.w to st.h)
        assertTrue("${st.bytes.size} $cap", st.bytes.size <= cap)
    }

    @Test
    fun theFocusIsTakenOnTheUprightImageAndKeptAcrossARestart() {
        val (sim, port) = sim("img-source")
        val s = session(port)
        val dir = tmp.newFolder()
        val u = setup(AwtCodec { w, h -> if (h > w) w * 0.2 to h * 0.25 else null }, dir = dir)
        u.hit("Face", Images.exif(Images.jpeg(Images.photo(1200, 900), 90), 6))
        pusher(s, u).run(listOf("Face" to null))
        assertEquals(Triple("Face", sim.device.sources.getValue("face").bytes.size.toLong(), 500 to 250), sim.device.fulls.single())
        assertEquals(500 to 250, sim.device.sources.getValue("face").focus)
        val pushed = sim.device.sources.getValue("face").bytes

        val again = setup(AwtCodec { _, _ -> throw AssertionError("detector after a restart") }, dir = dir)
        val f = again.source.of(again.key("Face"))!!
        assertEquals(500 to 250, f.focus)
        assertArrayEquals(pushed, f.bytes)
        assertEquals(0, again.codec.faces)
    }

    @Test
    fun noFaceSendsNoFocus() {
        val (sim, port) = sim("img-source")
        val s = session(port)
        val u = setup()
        u.hit("Plain", Images.jpeg(Images.photo(600, 800), 90))
        pusher(s, u).run(listOf("Plain" to null))
        assertNull(sim.device.fulls.single().third)
        assertNull(u.cache.full(u.key("Plain"))!!.focus)
    }

    @Test
    fun aFirstRefusalReencodesAtTheFloorAndASecondPushesACrop() {
        val (sim, port) = sim("img-source", "src-refuse=1")
        val s = session(port)
        val u = setup()
        u.hit("Once", Images.jpeg(Images.photo(3000, 2000), 90))
        val r = pusher(s, u).run(listOf("Once" to null))
        assertEquals(listOf("Once"), r.pushed.keys.toList())
        assertEquals(2048 to 1365, sim.device.sources.getValue("once").let { it.w to it.h })
        assertEquals(1, u.cache.refusals(u.key("Once"), u.cache.sha(u.cache.hit(u.key("Once"))!!)))

        val (sim2, port2) = sim("img-source", "src-refuse=2")
        val s2 = session(port2)
        val v = setup()
        v.hit("Twice", Images.jpeg(Images.photo(900, 600), 90))
        val r2 = pusher(s2, v).run(listOf("Twice" to null))
        assertEquals(listOf("Twice"), r2.pushed.keys.toList())
        assertTrue(sim2.device.sources.isEmpty())
        assertTrue(sim2.device.stored.containsKey("twice"))
        assertEquals(2, sim2.device.fulls.size)
        assertNull(v.source.of(v.key("Twice")))
        pusher(s2, v).run(listOf("Twice" to null))
        assertEquals(2, sim2.device.fulls.size)
    }

    @Test
    fun noSpacePushesACropAndCropsUntilTheListShowsRoom() {
        val (sim, port) = sim("img-source", "src-nospace", "src-room=0")
        val s = session(port)
        val u = setup()
        u.hit("Ado", Images.jpeg(Images.photo(900, 600), 90))
        u.hit("Mili", Images.jpeg(Images.photo(900, 600, 3), 90))
        val fp = FullPush(u.source)
        val r = ArtPusher(s, u::lookup, fp, { true }) { true }.run(listOf("Ado" to null, "Mili" to null))
        assertEquals(listOf("Ado", "Mili"), r.pushed.keys.toList())
        assertEquals(1, sim.device.fulls.size)
        assertEquals(setOf("ado", "mili"), sim.device.stored.keys)
        assertTrue(fp.low(s))
        faults.srcNoSpace = false
        faults.srcRoom = 6_442_450_944L
        val l = ArtPusher(s, u::lookup, fp, { true }) { true }.listing()!!
        assertTrue(l.srcRoom!! > 4_194_304)
        assertFalse(fp.low(s))
    }

    @Test
    fun bluetoothPushesACropUnlessTheDeviceShowsOrHoldsTheSource() {
        val (sim, port) = sim("img-source")
        val s = session(port)
        val u = setup()
        u.hit("Ado", Images.jpeg(Images.photo(900, 600), 90))
        u.hit("Mili", Images.jpeg(Images.photo(900, 600, 3), 90))
        u.hit("Gemma", Images.jpeg(Images.photo(900, 600, 5), 90))
        pusher(s, u).run(listOf("Ado" to null, "Gemma" to null))
        assertEquals(setOf("ado", "gemma"), sim.device.sources.keys)
        val gemma = u.lookup("Gemma", null, 240, 320)!!
        assertTrue(ArtPusher(s, { _, _, _, _ -> gemma }) { true }.run(listOf("Gemma" to null)).pushed.containsKey("Gemma"))
        assertTrue(sim.device.sources.getValue("gemma").aside)
        val fulls = sim.device.fulls.size

        val r = pusher(s, u) { true }.run(listOf("Ado" to null, "Mili" to null, "Gemma" to null))
        assertEquals(ArtPusher.Stop.DONE, r.stop)
        assertEquals(listOf("Ado", "Gemma"), r.stored)
        assertEquals(listOf("Mili"), r.pushed.keys.toList())
        assertTrue(sim.device.stored.containsKey("mili"))
        assertFalse(sim.device.sources.containsKey("mili"))
        assertFalse(sim.device.sources.getValue("gemma").aside)
        assertEquals(fulls + 1, sim.device.fulls.size)
        assertEquals("Gemma", sim.device.fulls.last().first)
    }

    @Test
    fun afterANoSpaceAnArtistTheDeviceShowsIsLeftAsIs() {
        val (sim, port) = sim("img-source")
        val s = session(port)
        val u = setup()
        u.hit("Ado", Images.jpeg(Images.photo(900, 600), 90))
        u.hit("Mili", Images.jpeg(Images.photo(900, 600, 3), 90))
        val fp = FullPush(u.source)
        ArtPusher(s, u::lookup, fp, { true }) { true }.run(listOf("Mili" to null))
        faults.srcNoSpace = true
        faults.srcRoom = 0
        val r = ArtPusher(s, u::lookup, fp, { true }) { true }.run(listOf("Ado" to null, "Mili" to null))
        assertEquals(listOf("Ado"), r.pushed.keys.toList())
        assertEquals(listOf("Mili"), r.stored)
        assertFalse(sim.device.sources.getValue("mili").aside)
        assertFalse(sim.device.stored.containsKey("mili"))
    }

    @Test
    fun bluetoothStartingMidPushKeepsThePartialForTheNextRun() {
        val (sim, port) = sim("img-source")
        val s = session(port)
        val u = setup()
        u.hit("Big", Images.png(Images.noise(500, 500)))
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val r = pusher(s, u) { calls.incrementAndGet() > 3 }.run(listOf("Big" to null))
        assertEquals(ArtPusher.Stop.GATE, r.stop)
        assertTrue(sim.device.sources.isEmpty())
        assertTrue(sim.device.stored.isEmpty())
        val again = pusher(s, u).run(listOf("Big" to null))
        assertTrue("${again.pushed}", (again.pushed["Big"] ?: 0) > 0)
        assertArrayEquals(u.cache.fullBytes(u.key("Big")), sim.device.sources.getValue("big").bytes)
    }

    @Test
    fun theListEntryMatchesTheArtistByItsFoldedName() {
        val (sim, port) = sim("img-source")
        val s = session(port)
        val u = setup()
        u.hit("MILI", Images.jpeg(Images.photo(900, 600), 90))
        pusher(s, u).run(listOf("MILI" to null))
        val n = sim.device.fulls.size
        val r = pusher(s, u) { true }.run(listOf("Mili" to null))
        assertEquals(listOf("Mili"), r.stored)
        assertEquals(n, sim.device.fulls.size)
    }

    @Test
    fun aRefreshWithTheSameBytesKeepsTheRefusalCount() {
        val u = setup()
        val src = Images.jpeg(Images.photo(900, 600), 90)
        u.hit("Mili", src)
        u.source.refused(u.key("Mili"))
        val sha = ArtCache.sha256(src)
        u.hit("Mili", src)
        assertEquals(1, u.cache.refusals(u.key("Mili"), sha))
        val other = Images.jpeg(Images.photo(900, 600, 9), 90)
        u.hit("Mili", other)
        assertEquals(0, u.cache.refusals(u.key("Mili"), ArtCache.sha256(other)))
    }

    @Test
    fun aReadOnlyCardEndsTheRun() {
        val (_, port) = sim("img-source", "fail=IMG_BEGIN:11")
        val s = session(port)
        val u = setup()
        u.hit("Ado", Images.jpeg(Images.photo(900, 600), 90))
        u.hit("Mili", Images.jpeg(Images.photo(900, 600, 3), 90))
        val r = pusher(s, u).run(listOf("Ado" to null, "Mili" to null))
        assertEquals(ArtPusher.Stop.ABORTED, r.stop)
        assertTrue(r.failed.isEmpty())
    }

    @Test
    fun anUpgradedSourceDropsTheEncodeAndPushesTheNewSourceFull() {
        val (sim, port) = sim("img-source")
        val s = session(port)
        val u = setup()
        u.hit("Mili", Images.png(Images.photo(700, 900)))
        pusher(s, u).run(listOf("Mili" to null))
        val first = u.cache.full(u.key("Mili"))!!
        assertFalse(first.copy)
        val e = u.cache.hit(u.key("Mili"))!!
        val big = Images.jpeg(Images.photo(1400, 1800, 4), 90)
        assertTrue(u.cache.replaceHit(e.at, ArtCache.Entry(e.key, e.name, null, "test", null, "u2", e.at + 1, "", ArtCache.sha256(big)), big))
        assertNull(u.cache.full(u.key("Mili")))
        assertNull(u.cache.fullBytes(u.key("Mili")))
        val r = pusher(s, u).run(listOf("Mili" to null))
        assertEquals(listOf("Mili"), r.pushed.keys.toList())
        assertArrayEquals(big, sim.device.sources.getValue("mili").bytes)
        assertEquals(crc(big), u.cache.full(u.key("Mili"))!!.crc)
        assertEquals(2, sim.device.fulls.size)
    }

    @Test
    fun anOldDeviceGetsTheCropAtItsSize() {
        val (sim, port) = sim()
        val s = session(port)
        val u = setup()
        u.hit("Mili", Images.jpeg(Images.photo(900, 600), 90))
        val r = pusher(s, u).run(listOf("Mili" to null))
        assertEquals(listOf("Mili"), r.pushed.keys.toList())
        assertEquals(listOf(240 to 320), u.sizes)
        assertTrue(sim.device.fulls.isEmpty())
        assertTrue(sim.device.stored.containsKey("mili"))
        assertNull(u.cache.full(u.key("Mili")))
    }

    @Test
    fun aFullBeginFromAnAppWithoutBit28IsRefusedAndTheListHasNoSourceTags() {
        val (_, port) = sim("img-source")
        val s = session(port, Groups.mask("artBlock"))
        val r = s.call(Msg.IMG_BEGIN, Req.imgBegin(Enums.ImgKind.artist, "Mili", null, 1000, 1, 900, 600, full = true))
        assertEquals(Err.badValue, (r as Session.Result.Failed).error.code)
        val l = ImgListReply.from((s.call(Msg.IMG_LIST, Req.imgList(0, 100)) as Session.Result.Ok).body)
        assertNull(l.srcRoom)
    }

    @Test
    fun aRerunAnswersHaveAndTheListShowsTheSource() {
        val (sim, port) = sim("img-source")
        val s = session(port)
        val u = setup()
        val src = Images.jpeg(Images.photo(900, 600), 90)
        u.hit("Mili", src)
        pusher(s, u).run(listOf("Mili" to null))
        val again = pusher(s, u).run(listOf("Mili" to null))
        assertEquals(listOf("Mili"), again.stored)
        assertTrue(again.pushed.isEmpty())
        val e = pusher(s, u).listing()!!.entries.single()
        assertEquals(true, e.full)
        assertEquals(crc(src), e.crc)
        assertNull(e.srcCrc)
        assertNotNull(e.shownS)
        assertEquals(1, sim.device.sources.size)
    }

    @Test
    fun aCutFullPushResumesOnTheSameBytes() {
        val (sim, port) = sim("img-source", "abort-at=20000")
        val s = session(port)
        val u = setup()
        u.hit("Big", Images.png(Images.noise(500, 500)))
        val r = pusher(s, u).run(listOf("Big" to null))
        assertTrue("${r.pushed}", (r.pushed["Big"] ?: 0) > 0)
        assertArrayEquals(u.cache.fullBytes(u.key("Big")), sim.device.sources.getValue("big").bytes)
    }
}
