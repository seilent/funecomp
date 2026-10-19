package net.seilent.funecomp

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Looper
import net.seilent.funecomp.artist.ArtBlocks
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Names
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.artist.Wikimedia
import net.seilent.funecomp.proto.ArtBlock
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.sim.Track
import net.seilent.funecomp.ui.Phase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ArtBlockTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val sims = ArrayList<Sim>()
    private val faced = Remote.faced
    private val asked = CopyOnWriteArrayList<String>()
    private val http = Http { url, _ ->
        asked += url
        Response(404, ByteArray(0))
    }

    @Before
    fun setUp() {
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        MediaLink.controls(app, false)
        Remote.resetArtist()
    }

    @After
    fun tearDown() {
        Remote.stop()
        Remote.shown(false)
        sims.forEach { it.stop() }
        Remote.resetArtist()
        Remote.faced = faced
        Remote.forgetting = {}
        Remote.queuedAsk = {}
    }

    private fun waitFor(ms: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
            Thread.sleep(20)
        }
    }

    private fun jpeg(color: java.awt.Color = java.awt.Color(120, 80, 40)): ByteArray {
        val img = java.awt.image.BufferedImage(600, 800, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = color
        g.fillRect(0, 0, 600, 800)
        g.dispose()
        return ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    @Test
    fun aBlockedBackdropIsNotShownAgain() {
        val name = net.seilent.funecomp.sim.Library.synthetic().tracks.first().artists.first()
        val key = "name:${net.seilent.funecomp.artist.Names.fold(name)}"
        ArtCache(File(app.filesDir, "art")).putHit(ArtCache.Entry(key, name, null, Wikimedia.SOURCE, null, "https://upload.wikimedia.org/a.jpg", 1, "File:A.jpg"), jpeg())
        Remote.artist(app, http)
        val s = Sim(Options(Board.of("v1")))
        sims += s
        Remote.shown(true)
        val c = Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, "tcp:127.0.0.1:${s.listen(0)}")).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && Remote.backdrop?.entry?.key == key }
        val e = Remote.backdrop!!.entry!!
        assertTrue(e.sha.isNotEmpty())
        Remote.blockArt(e)
        assertNull(Remote.backdrop)
        assertEquals(1, Remote.blocked.size)
        repeat(3) {
            Remote.reordered()
            Thread.sleep(300)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(Remote.backdrop?.entry?.key != key)
        }
        assertTrue(File(app.filesDir, "artblock.tsv").readText().contains("File:A.jpg"))
        Remote.unblock(Remote.blocked.single())
        assertTrue(Remote.blocked.isEmpty())
        c.destroy()
    }

    @Test
    fun aBlockLandingDuringTheBackdropDecodeIsNotShown() {
        val name = net.seilent.funecomp.sim.Library.synthetic().tracks.first().artists.first()
        val key = "name:${net.seilent.funecomp.artist.Names.fold(name)}"
        val cache = ArtCache(File(app.filesDir, "art"))
        cache.putHit(ArtCache.Entry(key, name, null, Wikimedia.SOURCE, null, "https://upload.wikimedia.org/a.jpg", 1, "File:A.jpg"), jpeg())
        val e = cache.hit(key)!!
        val decoding = CountDownLatch(1)
        val release = CountDownLatch(1)
        Remote.faced = { src, face ->
            decoding.countDown()
            release.await(5, TimeUnit.SECONDS)
            faced(src, face)
        }
        Remote.artist(app, http)
        val s = Sim(Options(Board.of("v1")))
        sims += s
        Remote.shown(true)
        val c = Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, "tcp:127.0.0.1:${s.listen(0)}")).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE }
        assertTrue(decoding.await(5, TimeUnit.SECONDS))
        Remote.blockArt(e)
        release.countDown()
        repeat(5) {
            Thread.sleep(200)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(Remote.backdrop?.entry?.key != key)
        }
        c.destroy()
    }

    private val mbid = "b539e453-c4fe-47e3-8a07-8517eac74429"
    private val thumb = "https://r2.theaudiodb.com/images/media/artist/thumb/a.jpg"
    private val fanart = "https://r2.theaudiodb.com/images/media/artist/fanart/b.jpg"
    private val lines = CopyOnWriteArrayList<String>()
    private val chain = Http { url, _ ->
        asked += url
        when {
            url.contains("/ws/2/artist/?query=") -> Response(200, "{\"artists\":[{\"id\":\"$mbid\",\"name\":\"Utada Hikaru\",\"score\":100}]}".toByteArray())
            url.contains("artist-mb.php?i=$mbid") -> Response(200, "{\"artists\":[{\"strArtistThumb\":\"$thumb\",\"strArtistFanart\":\"$fanart\"}]}".toByteArray())
            url == thumb -> Response(200, jpeg(java.awt.Color(200, 40, 40)))
            url == fanart -> Response(200, jpeg(java.awt.Color(40, 40, 200)))
            else -> Response(404, ByteArray(0))
        }
    }

    private fun stored() = lines.filter { it.startsWith("stored name=Utada Hikaru ") }.map { it.substringAfterLast('/') }

    private fun deleted() = lines.filter { it == "deleted name=Utada Hikaru" }

    private fun chainSim(): Pair<Sim, Int> {
        app.getSharedPreferences(Main.ART_PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean("theaudiodb", true).commit()
        Remote.artist(app, chain)
        val s = Sim(Options(Board.of("v1"), log = { lines += it }, imgDir = File(app.cacheDir, "img").apply { deleteRecursively() }))
        sims += s
        return s to s.listen(0)
    }

    private fun link(port: Int) =
        Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, "tcp:127.0.0.1:$port")).create().startCommand(0, 1)

    @Test
    fun aBlockWhileLiveDeletesTheImageAndPushesTheNextCandidate() {
        val (_, port) = chainSim()
        Remote.shown(true)
        val c = link(port)
        waitFor(10_000) { Remote.backdrop?.entry?.key == mbid && stored().isNotEmpty() }
        Remote.blockArt(Remote.backdrop!!.entry!!)
        waitFor(10_000) { deleted().isNotEmpty() && stored().size >= 2 }
        val first = stored()[0]
        assertTrue(stored()[1] != first)
        assertTrue(lines.indexOf("deleted name=Utada Hikaru") < lines.indexOfLast { it.startsWith("stored name=Utada Hikaru ") })
        c.destroy()
    }

    @Test
    fun aBlockWithNoLinkDeletesAfterTheNextHello() {
        val (_, port) = chainSim()
        Remote.shown(true)
        val c = link(port)
        waitFor(10_000) { Remote.backdrop?.entry?.key == mbid && stored().isNotEmpty() }
        val e = Remote.backdrop!!.entry!!
        c.destroy()
        waitFor { Remote.phase != Phase.LIVE }
        Remote.blockArt(e)
        Thread.sleep(500)
        assertTrue(deleted().isEmpty())
        val again = link(port)
        waitFor(10_000) { deleted().isNotEmpty() && stored().size >= 2 }
        assertTrue(stored()[1] != stored()[0])
        again.destroy()
    }

    @Test
    fun anUndoPushesTheUnblockedImageAgainEvenWithASlowForget() {
        val (_, port) = chainSim()
        Remote.shown(true)
        val c = link(port)
        waitFor(10_000) { Remote.backdrop?.entry?.key == mbid && stored().isNotEmpty() }
        Remote.blockArt(Remote.backdrop!!.entry!!)
        waitFor(10_000) { stored().size >= 2 }
        val first = stored()[0]
        Remote.forgetting = { Thread.sleep(1500) }
        Remote.unblock(Remote.blocked.single())
        assertTrue(Remote.blocked.isEmpty())
        waitFor(10_000) { stored().size >= 3 }
        assertEquals(first, stored().last())
        c.destroy()
    }

    private val utadaJa = "宇多田ヒカル"
    private val utadaEn = "Utada Hikaru"
    private val vtuber = "空星きらめ/Sorahoshi Kirame【にじさんじ】"
    private val kirame = "0e7b3c4f-1f2a-4b5c-8d6e-7f8091a2b3c4"
    private val block = Enums.BlockCmd.blockImage
    private val wrong = Enums.BlockCmd.wrongArtist

    private fun cache() = ArtCache(File(app.filesDir, "art"))

    private fun seed(key: String, name: String, mbid: String?, by: String, vararg names: Pair<String, String?>): ArtCache.Entry {
        val c = cache()
        names.forEach { (n, m) -> c.putMbid(n, m, System.currentTimeMillis()) }
        c.putHit(ArtCache.Entry(key, name, mbid, Wikimedia.SOURCE, null, "https://upload.wikimedia.org/${key.hashCode()}.jpg", 1, "File:${key.hashCode()}.jpg", "", by), jpeg())
        return c.hit(key)!!
    }

    private fun records(vararg r: ArtBlocks.Record) = ArtBlocks(File(app.filesDir, "artblock.tsv")).apply {
        r.forEach { add(it) }
        save()
    }

    private fun track(vararg artists: String, mbids: List<String> = artists.map { "" }) =
        Library(listOf(Track(1, artists.joinToString(", "), artists.toList(), "Album", artists.first(), "Song", 200_000, "a", "a.flac", mbids)))

    private fun asking(lib: Library, vararg names: String, faults: List<String> = emptyList()): Sim {
        Remote.artist(app, http)
        val s = Sim(Options(Board.of("v1"), Faults(faults), log = { lines += it }, imgDir = File(app.cacheDir, "img").apply { deleteRecursively() }), lib)
        sims += s
        Remote.shown(true)
        link(s.listen(0))
        waitFor(10_000) { Remote.phase == Phase.LIVE && names.all { n -> s.device.stored.values.any { it.name == n } } }
        return s
    }

    private fun blocks() = Remote.blocked

    @Test
    fun aDeviceBlockRecordsOnceAndTheRepeatIsOk() {
        val (s, port) = chainSim()
        Remote.shown(true)
        val c = link(port)
        waitFor(10_000) { stored().isNotEmpty() }
        val q = s.block(block, utadaEn)
        assertEquals("ok", s.askBlock(q))
        assertEquals("ok", s.askBlock(q))
        val r = blocks().single() as ArtBlocks.Block
        assertEquals(mbid, r.key)
        assertEquals(q.crc, r.crc)
        waitFor(10_000) { deleted().isNotEmpty() && stored().size >= 2 }
        assertTrue(stored()[1] != stored()[0])
        assertTrue(File(app.filesDir, "artblock.tsv").readText().contains("\t${q.crc}\n"))
        c.destroy()
    }

    @Test
    fun noRenderedFileIsNotFoundAndANewImageIsStale() {
        val (s, port) = chainSim()
        Remote.shown(true)
        val c = link(port)
        waitFor(10_000) { stored().isNotEmpty() }
        val q = s.block(block, utadaEn)
        assertEquals("error 7 stale rev", s.askBlock(q.copy(crc = q.crc!! + 1)))
        File(app.filesDir, "art").listFiles { f -> f.name.endsWith(".240x320.jpg") }!!.forEach { it.delete() }
        assertEquals("error 10 not found", s.askBlock(q))
        assertTrue(blocks().isEmpty())
        c.destroy()
    }

    @Test
    fun wrongArtistWithATagMbidIsRefused() {
        seed(mbid, utadaEn, mbid, utadaEn, utadaEn to mbid)
        val s = asking(track(utadaEn, mbids = listOf(mbid)), utadaEn)
        assertEquals("error 4 wrong artist not offered", s.askBlock(wrong, utadaEn))
        assertTrue(blocks().isEmpty())
    }

    @Test
    fun twoNamesOnOneTagMbidBlockTheSharedKey() {
        seed(mbid, utadaJa, mbid, "", utadaJa to mbid, utadaEn to mbid)
        val s = asking(track(utadaJa, utadaEn, mbids = listOf(mbid, mbid)), utadaJa, utadaEn)
        val q = s.block(block, utadaEn)
        assertEquals(mbid, q.mbid)
        assertEquals("ok", s.askBlock(q))
        assertEquals(mbid, blocks().single().key)
        assertEquals("ok", s.askBlock(q))
        assertEquals(1, blocks().size)
    }

    @Test
    fun anAskWithoutTheMbidFindsTheEntryByTheResolvedName() {
        seed(mbid, utadaJa, mbid, utadaJa, utadaJa to mbid, utadaEn to mbid)
        val s = asking(track(utadaEn), utadaEn)
        assertEquals("ok", s.askBlock(s.block(block, utadaEn).copy(mbid = null)))
        assertEquals(mbid, blocks().single().key)
    }

    @Test
    fun anAskByMbidAloneFindsAnEntryUnderAnotherKey() {
        val e = seed("name:${Names.fold(vtuber)}", vtuber, kirame, "空星きらめ", vtuber to null, "空星きらめ" to kirame)
        val s = asking(track(vtuber), vtuber)
        val crc = s.block(block, vtuber).crc
        assertEquals("ok", s.askBlock(ArtBlock(block, "Kirame", kirame, crc)))
        assertEquals(e.key, blocks().single().key)
    }

    @Test
    fun aVariantWinIsBlockedUnderItsOwnKey() {
        records(ArtBlocks.Reject("name:${Names.fold(vtuber)}", "6a3b5c7d-0000-4000-8000-000000000009", "空星きらめ", vtuber, 1))
        val e = seed("name:${Names.fold(vtuber)}", vtuber, kirame, "空星きらめ", vtuber to null, "空星きらめ" to kirame)
        val s = asking(track(vtuber), vtuber)
        waitFor { Remote.backdrop?.entry?.key == e.key }
        assertTrue(Remote.backdrop!!.wrongArtist)
        val q = s.block(block, vtuber)
        assertEquals(kirame, q.mbid)
        assertEquals("ok", s.askBlock(q))
        val b = blocks().filterIsInstance<ArtBlocks.Block>().single()
        assertEquals(e.key, b.key)
        assertEquals(2, blocks().size)
    }

    @Test
    fun aVariantWinTakesWrongArtistUnderTheCleanedName() {
        val e = seed("name:${Names.fold(vtuber)}", vtuber, kirame, "空星きらめ", vtuber to null, "空星きらめ" to kirame)
        val s = asking(track(vtuber), vtuber)
        assertEquals("ok", s.askBlock(wrong, vtuber))
        val r = blocks().single() as ArtBlocks.Reject
        assertEquals("空星きらめ", r.resolvedBy)
        assertEquals(e.key, r.key)
    }

    @Test
    fun wrongArtistFromTheDapFollowsTheResolverName() {
        seed(mbid, utadaJa, mbid, utadaJa, utadaJa to mbid, utadaEn to mbid)
        val s = asking(track(utadaEn, utadaJa), utadaEn, utadaJa)
        waitFor { Remote.backdrop?.entry?.key == mbid }
        assertTrue(!Remote.backdrop!!.wrongArtist)
        val en = s.block(wrong, utadaEn)
        val ja = s.block(wrong, utadaJa)
        assertEquals("error 4 wrong artist not offered", s.askBlock(en))
        assertTrue(blocks().isEmpty())
        assertEquals("ok", s.askBlock(ja))
        assertEquals(utadaJa, (blocks().single() as ArtBlocks.Reject).resolvedBy)
        assertEquals("ok", s.askBlock(ja))
        assertEquals("error 4 wrong artist not offered", s.askBlock(en))
        assertEquals(1, blocks().size)
    }

    @Test
    fun aRejectUnderAnotherResolverDoesNotCoverTheAsk() {
        records(ArtBlocks.Reject(mbid, mbid, utadaJa, utadaJa, 1))
        seed(mbid, utadaJa, mbid, utadaJa, utadaJa to mbid, utadaEn to mbid)
        val s = asking(track(utadaEn), utadaEn)
        assertEquals("error 4 wrong artist not offered", s.askBlock(wrong, utadaEn))
        assertEquals(1, blocks().size)
    }

    @Test
    fun aRecordOfTheOtherKindDoesNotCoverTheAsk() {
        seed(mbid, utadaJa, mbid, utadaJa, utadaJa to mbid)
        val s = asking(track(utadaJa), utadaJa)
        val q = s.block(block, utadaJa)
        assertEquals("ok", s.askBlock(q))
        waitFor { deleted(utadaJa) }
        assertTrue(s.askBlock(q.copy(cmd = wrong)).startsWith("error "))
        assertEquals(1, blocks().size)
    }

    @Test
    fun aRejectDoesNotCoverABlockImageAsk() {
        seed(mbid, utadaJa, mbid, utadaJa, utadaJa to mbid)
        val s = asking(track(utadaJa), utadaJa)
        val q = s.block(wrong, utadaJa)
        assertEquals("ok", s.askBlock(q))
        waitFor { deleted(utadaJa) }
        assertTrue(s.askBlock(q.copy(cmd = block)).startsWith("error "))
        assertEquals(1, blocks().size)
    }

    @Test
    fun aSheetBlockThenADeviceAskBeforeTheDeleteIsOneRecord() {
        seed(mbid, utadaJa, mbid, utadaJa, utadaJa to mbid)
        val s = asking(track(utadaJa), utadaJa, faults = listOf("busy=IMG_DEL:1000"))
        waitFor { Remote.backdrop?.entry?.key == mbid }
        val q = s.block(block, utadaJa)
        Remote.blockArt(Remote.backdrop!!.entry!!)
        assertEquals("ok", s.askBlock(q))
        assertEquals(1, blocks().size)
        assertTrue(!deleted(utadaJa))
    }

    private fun held(): Pair<CountDownLatch, Sim> {
        val radiohead = seed("name:radiohead", "Radiohead", null, "")
        seed(mbid, utadaJa, mbid, utadaJa, utadaJa to mbid)
        val s = asking(track(utadaJa), utadaJa)
        val latch = CountDownLatch(1)
        Remote.forgetting = { latch.await(30, TimeUnit.SECONDS) }
        Remote.blockArt(radiohead)
        return latch to s
    }

    private fun ja() = blocks().count { it.key == mbid }

    @Test
    fun aSheetBlockQueuedBehindADeviceAskIsOneRecord() {
        val (latch, s) = held()
        val q = s.block(block, utadaJa)
        val queued = CountDownLatch(1)
        Remote.queuedAsk = { queued.countDown() }
        var answer = ""
        val t = Thread { answer = s.askBlock(q) }.apply { start() }
        assertTrue(queued.await(5, TimeUnit.SECONDS))
        Remote.blockArt(cache().hit(mbid)!!)
        latch.countDown()
        t.join(10_000)
        assertEquals("ok", answer)
        assertEquals(1, ja())
        assertTrue(recorded(utadaJa))
    }

    @Test
    fun aRetriedAskQueuedBehindTheFirstIsOneRecord() {
        val (latch, s) = held()
        val q = s.block(block, utadaJa)
        val queued = Semaphore(0)
        Remote.queuedAsk = { queued.release() }
        assertEquals("timeout", s.askBlock(q))
        var answer = ""
        val t = Thread { answer = s.askBlock(q) }.apply { start() }
        assertTrue(queued.tryAcquire(2, 5, TimeUnit.SECONDS))
        latch.countDown()
        t.join(10_000)
        assertEquals("ok", answer)
        assertEquals(1, ja())
        assertTrue(recorded(utadaJa))
    }

    private fun recorded(name: String) = ShadowLog.getLogsForTag(Remote.TAG).any { it.msg == "art ask $name already recorded" }

    private fun deleted(name: String) = lines.contains("deleted name=$name")
}
