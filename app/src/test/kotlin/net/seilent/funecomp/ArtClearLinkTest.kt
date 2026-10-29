package net.seilent.funecomp

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import net.seilent.funecomp.artist.Art
import net.seilent.funecomp.artist.ArtBlocks
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.artist.ArtPicks
import net.seilent.funecomp.artist.ArtPusher
import net.seilent.funecomp.artist.Full
import net.seilent.funecomp.artist.FullPush
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.ImageIds
import net.seilent.funecomp.artist.LyricSources
import net.seilent.funecomp.artist.Names
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.artist.Sources
import net.seilent.funecomp.artist.Wikimedia
import net.seilent.funecomp.link.Session
import net.seilent.funecomp.link.TcpTransport
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.ui.Phase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ArtClearLinkTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val sims = ArrayList<Sim>()
    private val lines = CopyOnWriteArrayList<String>()
    private var port = 0
    private val asked = CopyOnWriteArrayList<String>()
    private val name = Library.synthetic().tracks.first().artists.first()
    private val key = "name:${Names.fold(name)}"

    private val http = Http { url, _ ->
        asked += url
        if (url == PICKED) Response(200, jpeg()) else Response(404, ByteArray(0))
    }

    @Before
    fun setUp() {
        scrobblesOff(app)
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        MediaLink.controls(app, false)
        Remote.resetArtist()
        Lyrics.init(app, Http { _, _ -> Response(404, ByteArray(0)) })
        Lyrics.set(LyricSources.LRCLIB, false)
        Lyrics.set(LyricSources.NETEASE, false)
    }

    @After
    fun tearDown() {
        Remote.stop()
        Remote.shown(false)
        sims.forEach { it.stop() }
        Remote.resetArtist()
        Remote.forgetting = {}
        Lyrics.reset()
        scrobblesReset()
    }

    private fun waitFor(ms: Long = 30_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun jpeg(): ByteArray {
        val img = java.awt.image.BufferedImage(600, 800, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = java.awt.Color(120, 80, 40)
        g.fillRect(0, 0, 600, 800)
        g.dispose()
        return ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    private fun prefs() = app.getSharedPreferences(Main.ART_PREFS, Context.MODE_PRIVATE)

    private fun sources(vararg on: String) {
        val e = prefs().edit().putInt(Sources.DEFAULTS, Sources.DEFAULTS_REV)
        for (k in listOf(Sources.THE_AUDIO_DB, Sources.DISCOGS, Sources.FANART_TV, Sources.DEEZER, Sources.YOUTUBE, Wikimedia.SOURCE)) e.putBoolean(k, k in on)
        e.commit()
    }

    private fun network(free: Boolean) {
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (free) shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        for (cb in shadowOf(app.getSystemService(ConnectivityManager::class.java)).networkCallbacks) cb.onCapabilitiesChanged(ShadowNetwork.newInstance(7), caps)
    }

    private fun cache() = ArtCache(File(app.filesDir, "art"))

    private fun keep(artist: String = name, source: String = Sources.DEEZER) =
        cache().putHit(ArtCache.Entry("name:${Names.fold(artist)}", artist, null, source, null, "https://e/$artist.jpg", System.currentTimeMillis(), artist), jpeg())

    private fun records() = ArtBlocks(File(app.filesDir, "artblock.tsv")).drops()

    private fun stored(artist: String) = lines.filter { it.startsWith("stored name=$artist ") }

    private fun link(free: Boolean? = null, vararg faults: String): Sim {
        Remote.artist(app, http)
        free?.let { network(it) }
        val s = Sim(Options(Board.of("v1"), Faults(faults.toList()), log = { lines += it }, imgDir = File(app.cacheDir, "img").apply { deleteRecursively() }))
        sims += s
        port = s.listen(0)
        Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, "tcp:127.0.0.1:$port")).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE }
        return s
    }

    private fun otherApp(artist: String) {
        val ready = CountDownLatch(1)
        val s = Session(TcpTransport("127.0.0.1", port), object : Session.Listener {
            override fun onHello(hello: HelloReply) = ready.countDown()
            override fun onPush(type: Int, body: Node) {}
        }, Session.Config("test", features = Groups.mask("imgOrigin", "imgSource")))
        Thread { s.run() }.apply { isDaemon = true }.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        val b = ByteArrayOutputStream().also { javax.imageio.ImageIO.write(java.awt.image.BufferedImage(900, 1200, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()
        val full = FullPush({ Full(b, 900, 1200, CRC32().apply { update(b) }.value, null) }, { 1 })
        assertEquals(setOf(artist), ArtPusher(s, { n, m, w, h -> Art(b, w, h, n, m, Sources.DEEZER, null, key = "other") }, full, { true }) { true }.run(listOf(artist to null)).pushed.keys)
        s.close("other app")
    }

    private fun logged() = ShadowLog.getLogsForTag(Remote.TAG).map { it.msg }

    private fun cleared(mark: String = CLEAR) = logged().any { it.startsWith(mark) }

    private fun after(prefix: String, mark: String = CLEAR) = logged().let { l -> l.drop(l.indexOfFirst { it.startsWith(mark) } + 1).count { it.startsWith(prefix) } }

    @Test
    fun aClearedPhotoLeavesTheBackdropAtOnce() {
        sources(Sources.DEEZER)
        keep()
        Remote.shown(true)
        link()
        waitFor { Remote.backdrop?.entry?.key == key }
        Remote.clearArt(Sources.DEEZER)
        waitFor { Remote.backdrop?.entry?.key != key }
        repeat(3) {
            Remote.reordered()
            Thread.sleep(300)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(Remote.backdrop?.entry?.key != key)
        }
        assertNull(cache().hit(key))
    }

    @Test
    fun aClearWaitsForTheBlockExecutor() {
        keep()
        val held = CountDownLatch(1)
        Remote.forgetting = { held.await(30, TimeUnit.SECONDS) }
        Remote.artist(app, http)
        Remote.clearArt(Sources.DEEZER)
        Thread.sleep(300)
        assertNotNull(cache().hit(key))
        held.countDown()
        waitFor { cleared(DEEZER_CLEAR) }
        assertNull(cache().hit(key))
    }

    @Test
    fun aWholeClearOnAMeteredNetworkWaitsWithTheSweep() {
        sources(Sources.THE_AUDIO_DB)
        prefs().edit().putInt("sweep", 2).commit()
        link(false)
        waitFor { logged().any { it.startsWith("art sweep ") } }
        val n = asked.size
        Remote.clearArt(null)
        waitFor { cleared() }
        assertFalse(prefs().contains("sweep"))
        waitFor { after("art DONE ") > 0 }
        Thread.sleep(1000)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(asked.size > n)
        assertTrue(after("art sweep ") == 0)
        assertFalse(prefs().contains("sweep"))
    }

    @Test
    fun aWholeClearOnAFreeNetworkSweepsAgain() {
        sources(Sources.THE_AUDIO_DB)
        link(true)
        waitFor { logged().any { it.startsWith("art sweep ") } }
        Remote.clearArt(null)
        waitFor { cleared() }
        waitFor { after("art sweep ") > 0 }
    }

    @Test
    fun aSourceClearDeletesItsPhotosOnTheDapAndLeavesTheOthers() {
        val other = "Ichiko Aoba"
        sources(Sources.DEEZER, Sources.THE_AUDIO_DB)
        keep()
        keep(other, Sources.THE_AUDIO_DB)
        link(true)
        waitFor { stored(name).isNotEmpty() && stored(other).isNotEmpty() }
        Remote.clearArt(Sources.DEEZER)
        waitFor { "deleted name=$name" in lines }
        waitFor { records().any { it.key == key && it.applied.isNotEmpty() } }
        Thread.sleep(500)
        assertFalse("deleted name=$other" in lines)
        assertNotNull(cache().hit("name:${Names.fold(other)}"))
    }

    @Test
    fun aClearKeepsTheDapPhotoOfAPickedName() {
        sources(Sources.DEEZER)
        keep()
        val (k, k2) = ArtPicks.keys(name, null, false)
        ArtPicks(File(app.filesDir, "artpick.tsv")).apply {
            add(ArtPicks.Pick(k, k2, name, ArtPicks.IMAGE, Sources.LINK, PICKED, ImageIds.of(Sources.LINK, PICKED), "", null, null, 1))
            save()
        }
        link(true)
        waitFor { stored(name).isNotEmpty() }
        Remote.clearArt(Sources.DEEZER)
        waitFor { cleared(DEEZER_CLEAR) }
        waitFor { after("art DONE ", DEEZER_CLEAR) > 0 }
        Thread.sleep(500)
        assertNull(cache().hit(key))
        assertTrue(records().none { it.key == key })
        assertTrue(lines.none { " rx IMG_DEL " in it })
    }

    @Test
    fun aClearOnACrcDeviceDeletesTheClearedPhoto() {
        sources(Sources.DEEZER)
        keep()
        link(true, "img-source")
        waitFor { Remote.device.hello?.has("imgSource") == true && sims.single().device.sources.isNotEmpty() }
        Remote.clearArt(Sources.DEEZER)
        waitFor { "deleted name=$name" in lines }
        waitFor { records().any { it.key == key && it.applied.isNotEmpty() } }
        assertTrue(records().single { it.key == key }.crcs.isNotEmpty())
    }

    @Test
    fun aClearOnACrcDeviceDeletesAPushedCropAfterItsRenderWasRedone() {
        sources(Sources.DEEZER)
        keep()
        link(true, "img-source", "src-nospace")
        waitFor { stored(name).isNotEmpty() }
        val other = ByteArrayOutputStream().also { javax.imageio.ImageIO.write(java.awt.image.BufferedImage(240, 320, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()
        val renders = File(app.filesDir, "art").listFiles { f -> Regex("\\.\\d+x\\d+\\.jpg$").containsMatchIn(f.name) }!!.toList()
        assertTrue(renders.isNotEmpty())
        renders.forEach { it.writeBytes(other) }
        Remote.clearArt(Sources.DEEZER)
        waitFor { "deleted name=$name" in lines }
        waitFor { records().any { it.key == key && it.applied.isNotEmpty() } }
    }

    @Test
    fun aClearOnACrcDeviceKeepsAPhotoAnotherAppPushedSince() {
        val other = "Ichiko Aoba"
        val otherKey = "name:${Names.fold(other)}"
        sources(Sources.DEEZER)
        keep(other)
        link(true, "img-source")
        waitFor { sims.single().device.sources.values.any { it.name == other } }
        Remote.stop()
        otherApp(other)
        Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, "tcp:127.0.0.1:$port")).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE }
        val done = logged().count { it.startsWith("art DONE ") }
        waitFor { logged().count { it.startsWith("art DONE ") } > done }
        val del = lines.count { " rx IMG_DEL " in it }
        Remote.clearArt(Sources.DEEZER)
        waitFor { records().any { it.key == otherKey && it.applied.isNotEmpty() } }
        assertTrue(records().single { it.key == otherKey }.crcs.isNotEmpty())
        assertEquals(del, lines.count { " rx IMG_DEL " in it })
        assertTrue(sims.single().device.sources.values.any { it.name == other && it.w == 900 && it.h == 1200 })
    }

    private companion object {
        const val CLEAR = "art clear all "
        const val DEEZER_CLEAR = "art clear deezer "
        const val PICKED = "https://example.org/pick.jpg"
    }
}
