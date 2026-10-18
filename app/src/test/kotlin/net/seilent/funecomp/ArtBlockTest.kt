package net.seilent.funecomp

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Looper
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.artist.Wikimedia
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
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
}
