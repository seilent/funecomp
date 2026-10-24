package net.seilent.funecomp

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asAndroidBitmap
import net.seilent.funecomp.art.BitmapCodec
import net.seilent.funecomp.art.Gallery
import net.seilent.funecomp.artist.ArtBlocks
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.artist.ArtPicks
import net.seilent.funecomp.artist.Backdrop
import net.seilent.funecomp.artist.Codec
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Offer
import net.seilent.funecomp.artist.Picture
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.artist.Sources
import net.seilent.funecomp.artist.Tile
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.sim.Track
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.PickFor
import net.seilent.funecomp.ui.Previewed
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ArtPickTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val sims = ArrayList<Sim>()
    private val lines = CopyOnWriteArrayList<String>()
    private val pinned = "2e8436af-0604-42c6-84d6-68f8f5b4b93d"
    private val dz = "https://cdn-images.dzcdn.net/images/artist/248af7d86fec19be1b95301b3c7c1926/1000x1000-000000-80-0-0.jpg"
    private val thumb = "https://r2.theaudiodb.com/images/media/artist/thumb/p.jpg"
    private val avatar = "https://yt3.googleusercontent.com/0cNMCoNqAuBZzzxAwcjYYKMx_5KCtO0X1UYzzuVwaCP_Mf-EwDeYRZRebd-dvR3eJ_19szDpIQ=s900-c-k-c0x00ffffff-no-rj"
    private val picked = jpeg(java.awt.Color(30, 160, 60))
    private val slow = "https://example.org/slow.jpg"
    private val gate = CountDownLatch(1)
    private val http = Http { url, _ ->
        when {
            url == slow -> {
                gate.await(10, TimeUnit.SECONDS)
                Response(200, picked)
            }
            url.contains("/ws/2/artist/?query=") -> Response(200, "{\"artists\":[]}".toByteArray())
            url.contains("/ws/2/artist/$pinned") -> Response(200, "{\"id\":\"$pinned\",\"name\":\"tsuki\",\"relations\":[]}".toByteArray())
            url.contains("api.deezer.com/search/artist") -> Response(200, "{\"data\":[{\"name\":\"TSUKI\",\"nb_fan\":1,\"picture_medium\":\"$dz\",\"picture_xl\":\"$dz\"}]}".toByteArray())
            url.contains("artist-mb.php?i=$pinned") -> Response(200, "{\"artists\":[{\"strArtistThumb\":\"$thumb\"}]}".toByteArray())
            url == dz -> Response(200, jpeg(java.awt.Color(200, 40, 40)))
            url == thumb || url == "$thumb/small" -> Response(200, jpeg(java.awt.Color(40, 40, 200)))
            url == avatar -> Response(200, picked)
            else -> Response(404, ByteArray(0))
        }
    }

    @Before
    fun setUp() {
        scrobblesOff(app)
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        MediaLink.controls(app, false)
        app.getSharedPreferences(Main.ART_PREFS, Context.MODE_PRIVATE).edit().putInt("defaults", 2).putString("order", "commons,theaudiodb,fanarttv,discogs,youtube,deezer").putBoolean("deezer", true).putBoolean("theaudiodb", true).putBoolean("discogs", false).putBoolean("fanarttv", false).putBoolean("youtube", false).commit()
        Remote.resetArtist()
    }

    @After
    fun tearDown() {
        gate.countDown()
        Remote.stop()
        Remote.shown(false)
        sims.forEach { it.stop() }
        Remote.resetArtist()
        scrobblesReset()
    }

    private fun waitFor(ms: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
            Thread.sleep(20)
        }
    }

    private fun jpeg(color: java.awt.Color): ByteArray {
        val img = java.awt.image.BufferedImage(600, 800, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = color
        g.fillRect(0, 0, 600, 800)
        g.dispose()
        return ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    private fun track(id: Long, artist: String, mbid: String = "") = Track(id, artist, listOf(artist), "Album $id", artist, "Song $id", 200_000, "a$id", "a$id.flac", listOf(mbid))

    private fun sim(vararg tracks: Track, faults: List<String> = emptyList()): Sim {
        Remote.artist(app, http)
        val s = Sim(Options(Board.of("v1"), Faults(faults), log = { lines += it }, imgDir = File(app.cacheDir, "img").apply { deleteRecursively() }), Library(tracks.toList()))
        sims += s
        return s
    }

    private fun link(s: Sim, port: Int = s.listen(0)) =
        Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, "tcp:127.0.0.1:$port")).create().startCommand(0, 1)

    private fun stored(name: String) = lines.filter { it.startsWith("stored name=$name ") }.map { it.substringAfterLast('/').removeSuffix(".jpg") }

    private fun deleted(name: String) = lines.count { it == "deleted name=$name" }

    private fun offer() = Offer(ArtPicks.IMAGE, Sources.YOUTUBE, avatar, avatar.substringBefore('='), null, null, "月-Tsuki-", picked)

    private fun crc(b: ByteArray) = "%08x".format(CRC32().apply { update(b) }.value)

    @Test
    fun aPickWhileLivePushesAtOnceAndAnUndoPushesTheChainImageAgain() {
        val s = sim(track(1, "TSUKI"))
        Remote.shown(true)
        val c = link(s)
        waitFor { Remote.phase == Phase.LIVE && stored("TSUKI").isNotEmpty() }
        val chain = stored("TSUKI").last()
        Remote.pick(PickFor("TSUKI"), offer())
        waitFor { stored("TSUKI").size >= 2 }
        assertNotEquals(chain, stored("TSUKI").last())
        waitFor { Remote.backdrop?.entry?.key == "pick:name:tsuki" }
        assertEquals(1, Remote.picks.size)
        assertTrue(File(app.filesDir, "artpick.tsv").readText().contains(avatar))
        Remote.undoPick(Remote.picks.single())
        waitFor { stored("TSUKI").size >= 3 }
        assertEquals(chain, stored("TSUKI").last())
        assertTrue(Remote.picks.isEmpty())
        c.destroy()
    }

    @Test
    fun aPickWhileUnlinkedForAnArtistNotPlayingPushesAfterTheNextHello() {
        val s = sim(*(1L..8L).map { track(it, "Other $it") }.toTypedArray(), track(9, "TSUKI"))
        Remote.shown(true)
        Remote.pick(PickFor("TSUKI"), offer())
        waitFor { Remote.picks.isNotEmpty() }
        val c = link(s)
        waitFor { stored("TSUKI").isNotEmpty() }
        assertTrue("TSUKI" !in Remote.device.playback.state.artists)
        assertEquals(crc(ArtCache(File(app.filesDir, "art")).rendered("pick:name:tsuki", 240, 320)!!), stored("TSUKI").single())
        assertTrue("tsuki" in s.device.stored.keys)
        c.destroy()
    }

    @Test
    fun aDapBlockOfAPickedImageLeavesNoImage() {
        val s = sim(track(1, "TSUKI"))
        Remote.shown(true)
        val c = link(s)
        waitFor { Remote.phase == Phase.LIVE && stored("TSUKI").isNotEmpty() }
        Remote.pick(PickFor("TSUKI"), offer())
        waitFor { stored("TSUKI").size >= 2 }
        val q = s.block(Enums.BlockCmd.blockImage, "TSUKI")
        assertEquals("ok", s.askBlock(q))
        val r = Remote.blocked.single() as ArtBlocks.Block
        assertEquals("name:tsuki", r.key)
        assertEquals(Sources.YOUTUBE, r.source)
        waitFor { deleted("TSUKI") == 1 }
        assertEquals(ArtPicks.NONE, Remote.picks.single().kind)
        waitFor { File(app.filesDir, "artblock.tsv").readLines().count { it.startsWith("push\tTSUKI\t") } == 1 && File(app.filesDir, "artblock.tsv").readText().contains("origin\tTSUKI\t\tlibrary\n") }
        assertEquals("ok", s.askBlock(q))
        assertEquals(1, Remote.blocked.size)
        Thread.sleep(800)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, stored("TSUKI").size)
        waitFor { Remote.backdrop == null }
        Remote.unblock(r)
        waitFor { Remote.picks.singleOrNull()?.kind == ArtPicks.IMAGE }
        waitFor { stored("TSUKI").size >= 3 }
        assertEquals(crc(ArtCache(File(app.filesDir, "art")).rendered("pick:name:tsuki", 240, 320)!!), stored("TSUKI").last())
        c.destroy()
    }

    @Test
    fun aLibraryViewPickPushesLibraryWithoutReadingTheRows() {
        val s = sim(*(1L..8L).map { track(it, "Other $it") }.toTypedArray(), track(9, "TSUKI"))
        Remote.shown(true)
        val c = link(s)
        waitFor { Remote.phase == Phase.LIVE }
        Thread.sleep(500)
        Remote.pick(PickFor("TSUKI", library = true), offer())
        waitFor { stored("TSUKI").isNotEmpty() }
        assertEquals("TSUKI" to Enums.ImgOrigin.library, s.device.begins.single { it.first == "TSUKI" })
        assertTrue(lines.none { "rx LIB_GET" in it })
        c.destroy()
    }

    @Test
    fun aPickKeepsItsOriginAndItsUndoTakesTheRows() {
        val s = sim(track(1, "TSUKI"))
        Remote.shown(true)
        val c = link(s)
        waitFor { Remote.phase == Phase.LIVE && stored("TSUKI").isNotEmpty() }
        Thread.sleep(300)
        s.device.begins.clear()
        Remote.pick(PickFor("TSUKI", origin = Enums.ImgOrigin.phone), offer())
        waitFor { stored("TSUKI").size >= 2 }
        assertEquals("TSUKI" to Enums.ImgOrigin.phone, s.device.begins.first())
        assertTrue(lines.none { "rx LIB_GET" in it })
        s.device.begins.clear()
        Remote.undoPick(Remote.picks.single())
        waitFor { stored("TSUKI").size >= 3 }
        assertEquals("TSUKI" to Enums.ImgOrigin.library, s.device.begins.first())
        assertTrue(lines.any { "rx LIB_GET" in it })
        c.destroy()
    }

    @Test
    fun anArtistPickShowsAndPushesThePinnedEntry() {
        val s = sim(track(1, "TSUKI"))
        Remote.shown(true)
        Remote.pick(PickFor("TSUKI"), Offer(ArtPicks.ARTIST, Sources.MUSICBRAINZ, "https://musicbrainz.org/artist/$pinned", pinned, null, pinned, "tsuki", null))
        waitFor { Remote.picks.isNotEmpty() }
        val c = link(s)
        waitFor { Remote.backdrop?.entry?.key == pinned && stored("TSUKI").isNotEmpty() }
        assertEquals(Sources.THE_AUDIO_DB, Remote.backdrop!!.entry!!.source)
        assertEquals(crc(ArtCache(File(app.filesDir, "art")).rendered(pinned, 240, 320)!!), stored("TSUKI").last())
        assertTrue(lines.any { it.startsWith("stored name=TSUKI mbid=$pinned ") })
        c.destroy()
    }

    @Test
    fun aDapBlockOfALibraryPickBlocksBothKeysAndOneUnblockRestoresIt() {
        val dj = "91352441-9f79-48c2-81ad-ccdd1e46915a"
        val s = sim(track(1, "TSUKI", dj))
        Remote.shown(true)
        Remote.pick(PickFor("TSUKI", dj, library = true), offer())
        waitFor { Remote.picks.isNotEmpty() }
        val c = link(s)
        waitFor { stored("TSUKI").isNotEmpty() }
        assertEquals("ok", s.askBlock(s.block(Enums.BlockCmd.blockImage, "TSUKI")))
        assertEquals(setOf(dj, "name:tsuki"), Remote.blocked.map { it.key }.toSet())
        waitFor { Remote.picks.singleOrNull()?.kind == ArtPicks.NONE }
        Remote.unblock(Remote.blocked.first())
        waitFor { Remote.picks.singleOrNull()?.kind == ArtPicks.IMAGE && Remote.blocked.isEmpty() }
        c.destroy()
    }

    @Test
    fun aBrowseReachesTheCallbacksInOrderOnTheMainLooper() {
        Remote.artist(app, http)
        val saved = Remote.browseDecode
        val events = ArrayList<String>()
        val main = Looper.getMainLooper().thread
        try {
            Remote.browseDecode = { b, _ -> androidx.compose.ui.graphics.ImageBitmap(4, 4).takeIf { b.isNotEmpty() } }
            Remote.browse("TSUKI", pinned, 53, {
                check(Thread.currentThread() == main)
                events += "sections $it"
            }, { s, t ->
                check(Thread.currentThread() == main)
                events += "tile $s ${t.tile.preview.size}"
            }, {
                events += "done $it"
            }) {
                check(Thread.currentThread() == main)
                events += "end $it"
            }
            waitFor { events.lastOrNull()?.startsWith("end") == true }
        } finally {
            Remote.browseDecode = saved
        }
        assertEquals("sections [commons, theaudiodb, deezer]", events.first())
        assertEquals(setOf("tile theaudiodb 0", "tile deezer 0"), events.filter { it.startsWith("tile") }.toSet())
        assertEquals(7, events.size)
        assertEquals(setOf("done commons", "done theaudiodb", "done deezer"), events.filter { it.startsWith("done") }.toSet())
        assertTrue(events.indexOf("done theaudiodb") > events.indexOf("tile theaudiodb 0"))
        assertTrue(events.indexOf("done deezer") > events.indexOf("tile deezer 0"))
        assertEquals("end true", events.last())
    }

    @Test
    fun withNoArtistStoreTheCallsStillAnswer() {
        Remote.resetArtist()
        var offered: Previewed? = null
        var ended: Boolean? = null
        Remote.pickLink("https://example.org/a.jpg", "TSUKI") { offered = it }
        var sections: List<String>? = null
        Remote.browse("TSUKI", null, 53, { sections = it }, { _, _ -> }, {}) {
            assertEquals(emptyList<String>(), sections)
            ended = it
        }
        assertNull(offered)
        waitFor { offered != null }
        assertEquals("link not reachable", (offered as Previewed.Refused).reason)
        assertEquals(false, ended)
        assertNull(Remote.backdrop)
    }

    private fun previewed(start: ((Previewed) -> Unit) -> Unit): Previewed {
        var out: Previewed? = null
        start { out = it }
        waitFor { out != null }
        return out!!
    }

    private fun files(): Map<String, String> = listOf("artpick.tsv", "artblock.tsv", "art", "pickimg").flatMap { n ->
        File(app.filesDir, n).walkTopDown().filter { it.isFile }.map { it.relativeTo(app.filesDir).path to crc(it.readBytes()) }.toList()
    }.toMap()

    private fun structured(w: Int = 1600, h: Int = 1200): ByteArray {
        val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(240, 30, 30), w.toFloat(), h.toFloat(), java.awt.Color(20, 40, 230))
        g.fillRect(0, 0, w, h)
        g.color = java.awt.Color.WHITE
        for (i in 0 until 16) g.fillRect(i * 100, (i * 73) % 1100, 40, 90)
        g.dispose()
        return ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    @Test
    fun noResolveWritesAnythingAndOnlyThePickStoresAndPushes() {
        val s = sim(track(1, "TSUKI"))
        Remote.shown(true)
        val c = link(s)
        waitFor { Remote.phase == Phase.LIVE && stored("TSUKI").isNotEmpty() }
        Thread.sleep(800)
        shadowOf(Looper.getMainLooper()).idle()
        val before = files()
        val begins = lines.count { " rx IMG_BEGIN " in it }
        val picks = Remote.picks
        val direct = previewed { Remote.pickLink(avatar, "TSUKI", it) } as Previewed.Ok
        assertEquals(Sources.LINK, direct.previews.single().offer.source)
        assertNotNull(direct.previews.single().image)
        val mb = previewed { Remote.pickLink("https://musicbrainz.org/artist/$pinned", "TSUKI", it) } as Previewed.Ok
        assertEquals(ArtPicks.ARTIST, mb.previews.single().offer.kind)
        assertNotNull(mb.previews.single().image)
        val tile = previewed { Remote.takeTile(Tile(Sources.DEEZER, "TSUKI", ByteArray(0), dz, "dz", null), it) } as Previewed.Ok
        assertEquals(Sources.DEEZER, tile.previews.single().offer.source)
        val g = previewed { Remote.galleryPicked({ Gallery(picked, "IMG_1.jpg") }, it) } as Previewed.Ok
        val offer = g.previews.single().offer
        assertEquals(Sources.GALLERY, offer.source)
        assertEquals("IMG_1.jpg", offer.title)
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, files())
        assertEquals(begins, lines.count { " rx IMG_BEGIN " in it })
        assertEquals(picks, Remote.picks)
        Remote.pick(PickFor("TSUKI"), offer)
        waitFor { stored("TSUKI").size >= 2 }
        assertEquals(listOf(ArtCache.sha256(picked) + ".jpg"), File(app.filesDir, "pickimg").list()!!.toList())
        assertTrue(File(app.filesDir, "artpick.tsv").readText().contains("\tgallery\t"))
        assertEquals("TSUKI" to Enums.ImgOrigin.library, s.device.begins.last())
        Remote.undoPick(Remote.picks.single())
        waitFor { stored("TSUKI").size >= 3 && File(app.filesDir, "pickimg").list()!!.isEmpty() }
        c.destroy()
    }

    @Test
    fun aMusicBrainzLinkOffersThePinWhenALookupSourceFails() {
        Remote.artist(app, Http { url, h ->
            when {
                url.contains("artist-mb.php") -> throw IOException("503")
                url.contains("api.deezer.com/search/artist") -> Response(200, "{\"data\":[]}".toByteArray())
                else -> http.get(url, h)
            }
        })
        val shown = (previewed { Remote.pickLink("https://musicbrainz.org/artist/$pinned", "TSUKI", it) } as Previewed.Ok).previews.single()
        assertEquals(ArtPicks.ARTIST, shown.offer.kind)
        assertEquals(pinned, shown.offer.mbid)
        assertNull(shown.image)
    }

    @Test
    fun thePreviewFramesThePhotoAsTheDeviceDoes() {
        val src = structured(1200, 2000)
        val at = { w: Int, h: Int -> w * 0.3 to h * 0.45 }
        val shown = Remote.preview(src, 240, 320) { b, _ -> BitmapCodec().faced(b, 2048) { null }?.let { (bmp, _) -> bmp to at(bmp.width, bmp.height) } }!!.asAndroidBitmap()
        val faced = Codec { b, side -> BitmapCodec().decode(b, side)?.let { p -> object : Picture by p { override fun face() = at(p.w, p.h) } } }
        val device = Backdrop.render(faced, src, 240, 320)!!.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        assertEquals(3f / 4f, shown.width.toFloat() / shown.height, 0.01f)
        assertTrue(shown.width >= 240 * 2)
        val scaled = Bitmap.createScaledBitmap(shown, 240, 320, true)
        var diff = 0L
        for (y in 0 until 320) for (x in 0 until 240) {
            val a = scaled.getPixel(x, y)
            val b = device.getPixel(x, y)
            diff += abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) + abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))
        }
        assertTrue("diff ${diff / (240 * 320)}", diff / (240 * 320) < 12)
        val moved = Bitmap.createScaledBitmap(Remote.preview(src, 240, 320) { b, _ -> BitmapCodec().faced(b, 2048) { null } }!!.asAndroidBitmap(), 240, 320, true)
        var off = 0L
        for (y in 0 until 320) for (x in 0 until 240) off += abs(android.graphics.Color.red(moved.getPixel(x, y)) - android.graphics.Color.red(device.getPixel(x, y)))
        assertTrue(off / (240 * 320) > diff / (240 * 320))
    }

    @Test
    fun aLargePreviewIsScaledToTheScreen() {
        val img = java.awt.image.BufferedImage(2400, 3200, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val src = ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "jpg", it) }.toByteArray()
        val shown = Remote.preview(src, 240, 320) { b, _ -> BitmapCodec().faced(b, 2048) { null } }!!
        val side = maxOf(android.content.res.Resources.getSystem().displayMetrics.run { maxOf(widthPixels, heightPixels) }, 320 * 4)
        assertTrue(side < 2048)
        assertEquals(side, maxOf(shown.width, shown.height))
        assertEquals(3f / 4f, shown.width.toFloat() / shown.height, 0.01f)
    }

    @Test
    fun aCancelOrANewResolvePostsNothingForTheOldOne() {
        Remote.artist(app, http)
        var first: Previewed? = null
        val cancel = Remote.pickLink(slow, "TSUKI") { first = it }
        Thread.sleep(200)
        cancel()
        gate.countDown()
        Thread.sleep(500)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(first)
        var a: Previewed? = null
        Remote.pickLink(avatar, "TSUKI") { a = it }
        val b = previewed { Remote.pickLink(avatar, "TSUKI", it) }
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(b is Previewed.Ok)
        assertNull(a)
    }

    @Test
    fun aGalleryReadThatFailsClosesOrRefuses() {
        Remote.artist(app, http)
        assertEquals(Previewed.Closed, previewed { Remote.galleryPicked({ null }, it) })
        assertEquals("not an image", (previewed { Remote.galleryPicked({ Gallery(null, "x.heic") }, it) } as Previewed.Refused).reason)
        assertEquals("not an image", (previewed { Remote.galleryPicked({ throw IllegalArgumentException("odd uri") }, it) } as Previewed.Refused).reason)
        val small = ByteArrayOutputStream().also { javax.imageio.ImageIO.write(java.awt.image.BufferedImage(300, 200, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()
        assertEquals("image too small", (previewed { Remote.galleryPicked({ Gallery(small, "s.jpg") }, it) } as Previewed.Refused).reason)
        assertTrue(File(app.filesDir, "pickimg").list().isNullOrEmpty())
    }
}
