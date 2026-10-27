package net.seilent.funecomp

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Looper
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.sim.Track
import net.seilent.funecomp.ui.Phase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class BareScanTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val sims = ArrayList<Sim>()
    private val lines = CopyOnWriteArrayList<String>()
    private val http = Refusing()
    private val utada = "Utada Hikaru"
    private val utadaMbid = "b539e453-c4fe-47e3-8a07-8517eac74429"
    private val iris = "Iris Official"
    private val irisMbid = "a2aeb4e5-0d7c-4c4e-9b39-6f1a7e3c2d10"

    @Before
    fun setUp() {
        scrobblesOff(app)
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        MediaLink.controls(app, false)
        Remote.resetArtist()
        Lyrics.init(app, Http { _, _ -> Response(404, ByteArray(0)) })
    }

    @After
    fun tearDown() {
        Remote.stop()
        Remote.shown(false)
        sims.forEach { it.stop() }
        Remote.resetArtist()
        scrobblesReset()
    }

    private fun waitFor(ms: Long = 5000, cond: () -> Boolean) {
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

    private fun prefs() = app.getSharedPreferences(Main.ART_PREFS, Context.MODE_PRIVATE)

    private fun logs() = ShadowLog.getLogsForTag(Remote.TAG).map { it.msg }

    private fun track(id: Long, name: String, mbid: String) = Track(id, name, listOf(name), "Album", name, "Song", 200_000, "a$id", "a$id.flac", listOf(mbid))

    private fun seed(scanned: Boolean) {
        prefs().edit().putInt("defaults", 2).putString("order", "commons,theaudiodb,fanarttv,discogs,youtube,deezer").putBoolean("theaudiodb", true).putBoolean("commons", false).putBoolean("discogs", false).putBoolean("fanarttv", false).putBoolean("deezer", false).putBoolean("youtube", false).putBoolean("barescan", scanned).commit()
        val c = ArtCache(File(app.filesDir, "art"))
        val now = System.currentTimeMillis()
        c.putMbid(utada, utadaMbid, now)
        c.putMbid(iris, irisMbid, now)
        c.putHit(ArtCache.Entry(utadaMbid, utada, utadaMbid, "theaudiodb", null, "https://r2.theaudiodb.com/u.jpg", now, "u", "", utada), jpeg(java.awt.Color(200, 40, 40)))
        c.putHit(ArtCache.Entry(irisMbid, iris, irisMbid, "theaudiodb", null, "https://r2.theaudiodb.com/i.jpg", now, "i", "", "Iris"), jpeg(java.awt.Color(40, 40, 200)))
        c.putBare(iris, null, now)
    }

    private fun sim(lib: Library): Int {
        Remote.artist(app, http)
        val s = Sim(Options(Board.of("v1"), log = { lines += it }, imgDir = File(app.cacheDir, "img").apply { deleteRecursively() }), lib)
        sims += s
        return s.listen(0)
    }

    private fun link(port: Int) =
        Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, "tcp:127.0.0.1:$port")).create().startCommand(0, 1)

    @Test
    fun aBareScanDropDoesNotHoldBackThePlayingArtist() {
        seed(false)
        val c = link(sim(Library(listOf(track(1, utada, utadaMbid)))))
        waitFor(10_000) { lines.any { it.startsWith("stored name=$utada ") } }
        waitFor { logs().any { it.startsWith("art current DONE") } }
        assertTrue(logs().contains("art bare scan dropped 1"))
        assertFalse(logs().any { it.startsWith("art current PREEMPTED") })
        assertEquals(0, http.asked.get())
        c.destroy()
    }

    @Test
    fun aBareScanDropDeletesTheImageOnTheDap() {
        seed(true)
        val port = sim(Library(listOf(track(1, iris, irisMbid))))
        val c = link(port)
        waitFor(10_000) { lines.any { it.startsWith("stored name=$iris ") } }
        c.destroy()
        waitFor { Remote.phase != Phase.LIVE }
        prefs().edit().putBoolean("barescan", false).commit()
        val again = link(port)
        waitFor(10_000) { lines.contains("deleted name=$iris") }
        assertTrue(logs().contains("art bare scan dropped 1"))
        assertFalse(logs().any { it.startsWith("art current PREEMPTED") })
        assertFalse(logs().contains("art del list failed"))
        assertTrue(prefs().getBoolean("barescan", false))
        again.destroy()
    }

    @Test
    fun aBareScanDropClearsTheShownBackdrop() {
        seed(true)
        val port = sim(Library(listOf(track(1, iris, irisMbid))))
        Remote.shown(true)
        val c = link(port)
        waitFor(10_000) { Remote.phase == Phase.LIVE && Remote.backdrop?.entry?.key == irisMbid }
        prefs().edit().putBoolean("barescan", false).commit()
        Remote.reordered()
        waitFor(10_000) { logs().contains("art bare scan dropped 1") }
        waitFor { Remote.backdrop?.entry?.key != irisMbid }
        c.destroy()
    }
}
