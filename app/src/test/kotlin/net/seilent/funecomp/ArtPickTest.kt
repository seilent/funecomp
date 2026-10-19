package net.seilent.funecomp

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Looper
import net.seilent.funecomp.artist.ArtBlocks
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.artist.ArtPicks
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Offer
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.artist.Sources
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.sim.Track
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.PickFor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.CRC32

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
    private val http = Http { url, _ ->
        when {
            url.contains("/ws/2/artist/?query=") -> Response(200, "{\"artists\":[]}".toByteArray())
            url.contains("/ws/2/artist/$pinned") -> Response(200, "{\"id\":\"$pinned\",\"name\":\"tsuki\",\"relations\":[]}".toByteArray())
            url.contains("api.deezer.com/search/artist") -> Response(200, "{\"data\":[{\"name\":\"TSUKI\",\"nb_fan\":1,\"picture_xl\":\"$dz\"}]}".toByteArray())
            url.contains("artist-mb.php?i=$pinned") -> Response(200, "{\"artists\":[{\"strArtistThumb\":\"$thumb\"}]}".toByteArray())
            url == dz -> Response(200, jpeg(java.awt.Color(200, 40, 40)))
            url == thumb -> Response(200, jpeg(java.awt.Color(40, 40, 200)))
            url == avatar -> Response(200, picked)
            else -> Response(404, ByteArray(0))
        }
    }

    @Before
    fun setUp() {
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        MediaLink.controls(app, false)
        app.getSharedPreferences(Main.ART_PREFS, Context.MODE_PRIVATE).edit().putBoolean("deezer", true).putBoolean("theaudiodb", true).commit()
        Remote.resetArtist()
    }

    @After
    fun tearDown() {
        Remote.stop()
        Remote.shown(false)
        sims.forEach { it.stop() }
        Remote.resetArtist()
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
    fun withNoArtistStoreTheCallsStillAnswer() {
        Remote.resetArtist()
        var offered: net.seilent.funecomp.artist.Offered? = null
        var ended: Boolean? = null
        Remote.pickLink("https://example.org/a.jpg") { offered = it }
        Remote.browse("TSUKI", null, 53, {}) { ended = it }
        assertEquals("link not reachable", (offered as net.seilent.funecomp.artist.Offered.Refused).reason)
        assertEquals(false, ended)
        assertNull(Remote.backdrop)
    }
}
