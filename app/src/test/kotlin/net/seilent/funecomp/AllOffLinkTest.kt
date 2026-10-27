package net.seilent.funecomp

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.artist.ArtPicks
import net.seilent.funecomp.artist.Credit
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.ImageIds
import net.seilent.funecomp.artist.LyricSources
import net.seilent.funecomp.artist.Names
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.artist.Sources
import net.seilent.funecomp.artist.Wikimedia
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.sim.Board
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AllOffLinkTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val sims = ArrayList<Sim>()
    private val lines = CopyOnWriteArrayList<String>()
    private val denied = CopyOnWriteArrayList<String>()
    private val asked = CopyOnWriteArrayList<String>()
    @Volatile private var open = false
    @Volatile private var plain = false

    private val utada = "Utada Hikaru"
    private val mbid = "b539e453-c4fe-47e3-8a07-8517eac74429"
    private val thumb = "https://r2.theaudiodb.com/images/media/artist/thumb/a.jpg"
    private val picked = "https://example.org/pick.jpg"
    private val irisMbid = "6a3b5c7d-0000-4000-8000-0000000000aa"

    private val http = Http { url, _ ->
        when {
            url == picked -> {
                asked += url
                Response(200, jpeg())
            }
            plain -> {
                asked += url
                Response(404, ByteArray(0))
            }
            !open -> {
                denied += url
                throw AssertionError("request $url")
            }
            else -> {
                asked += url
                when {
                    url.contains("/ws/2/artist/?query=") -> Response(200, "{\"artists\":[{\"id\":\"$mbid\",\"name\":\"$utada\",\"score\":100}]}".toByteArray())
                    url.contains("artist-mb.php?i=$mbid") -> Response(200, "{\"artists\":[{\"strArtistThumb\":\"$thumb\"}]}".toByteArray())
                    url == thumb -> Response(200, jpeg())
                    else -> Response(404, ByteArray(0))
                }
            }
        }
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
        Lyrics.reset()
        scrobblesReset()
    }

    private fun waitFor(ms: Long = 15_000, cond: () -> Boolean) {
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

    private fun network() {
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        for (cb in shadowOf(app.getSystemService(ConnectivityManager::class.java)).networkCallbacks) cb.onCapabilitiesChanged(ShadowNetwork.newInstance(7), caps)
    }

    private fun link(): Sim {
        Remote.artist(app, http)
        val s = Sim(Options(Board.of("v1"), log = { lines += it }, imgDir = File(app.cacheDir, "img").apply { deleteRecursively() }))
        sims += s
        Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, "tcp:127.0.0.1:${s.listen(0)}")).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE }
        return s
    }

    private fun logged() = ShadowLog.getLogsForTag(Remote.TAG).map { it.msg }

    private fun runs() = logged().count { it.startsWith("art ${net.seilent.funecomp.artist.ArtPusher.Stop.DONE} ") }

    private fun stored(name: String) = lines.filter { it.startsWith("stored name=$name ") }

    private fun begins() = lines.count { " rx IMG_BEGIN " in it }

    private fun allOffRun() {
        waitFor { "art bare scan skipped" in logged() && runs() > 0 }
        Thread.sleep(500)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, begins())
        assertEquals(0, lines.count { " rx IMG_DEL " in it })
        assertTrue(denied.toString(), denied.isEmpty())
        assertFalse(prefs().contains("sweep"))
        assertFalse(prefs().contains("barescan"))
    }

    @Test
    fun everySourceOffAsksNothingPushesNothingAndASourceOnPushes() {
        sources()
        network()
        link()
        allOffRun()
        open = true
        sources(Sources.THE_AUDIO_DB)
        Remote.sourcesChanged()
        waitFor { stored(utada).isNotEmpty() }
        assertTrue(denied.isEmpty())
    }

    @Test
    fun theBareScanWaitsForASourceThenDropsTheOldBareHit() {
        val iris = "name:${Names.fold("Iris Official")}"
        sources()
        ArtCache(File(app.filesDir, "art")).putHit(ArtCache.Entry(iris, "Iris Official", irisMbid, Sources.DEEZER, null, "https://e/iris.jpg", 1, resolvedBy = "Iris"), jpeg())
        network()
        link()
        allOffRun()
        assertNotNull(ArtCache(File(app.filesDir, "art")).hit(iris))
        open = true
        sources(Sources.THE_AUDIO_DB)
        Remote.sourcesChanged()
        waitFor { prefs().getBoolean("barescan", false) }
        assertNull(ArtCache(File(app.filesDir, "art")).hit(iris))
        assertTrue(denied.isEmpty())
    }

    @Test
    fun anImagePickIsPushedWithEverySourceOff() {
        sources()
        val (k, k2) = ArtPicks.keys(utada, null, false)
        ArtPicks(File(app.filesDir, "artpick.tsv")).apply {
            add(ArtPicks.Pick(k, k2, utada, ArtPicks.IMAGE, Sources.LINK, picked, ImageIds.of(Sources.LINK, picked), "", null, null, 1))
            save()
        }
        link()
        waitFor { stored(utada).isNotEmpty() }
        assertEquals(listOf(picked), asked.distinct())
        assertTrue(denied.toString(), denied.isEmpty())
    }

    @Test
    fun aDeviceBlockWithEverySourceOffDeletesAndPushesNoNextCandidate() {
        sources(Sources.THE_AUDIO_DB)
        open = true
        Remote.shown(true)
        val s = link()
        waitFor { stored(utada).isNotEmpty() && runs() > 0 }
        open = false
        sources()
        Remote.sourcesChanged()
        val n = begins()
        assertEquals("ok", s.askBlock(Enums.BlockCmd.blockImage, utada))
        waitFor { lines.contains("deleted name=$utada") }
        Thread.sleep(1000)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(n, begins())
        assertEquals(1, stored(utada).size)
        assertTrue(denied.toString(), denied.isEmpty())
    }

    @Test
    fun commonsOffDropsItsPhotoFromTheBackdropAndKeepsTheCredit() {
        sources(Wikimedia.SOURCE)
        plain = true
        val key = "name:${Names.fold(utada)}"
        val credit = Credit("A", "CC BY 4.0", "https://commons.wikimedia.org/wiki/File:A.jpg")
        ArtCache(File(app.filesDir, "art")).putHit(ArtCache.Entry(key, utada, null, Wikimedia.SOURCE, credit, "https://upload.wikimedia.org/a.jpg", System.currentTimeMillis(), "File:A.jpg"), jpeg())
        Remote.shown(true)
        link()
        waitFor { Remote.backdrop?.entry?.key == key }
        val n = asked.size
        sources()
        Remote.sourcesChanged()
        waitFor { Remote.backdrop?.entry?.source != Wikimedia.SOURCE }
        waitFor { Remote.credits.any { it.key == key && it.credit == credit } }
        assertNotNull(ArtCache(File(app.filesDir, "art")).hit(key))
        assertEquals(n, asked.size)
    }
}
