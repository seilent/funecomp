package net.seilent.funecomp

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Looper
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.LyricSources
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.PhoneLine
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.state.PhoneKey
import net.seilent.funecomp.ui.Phase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RxLyricsTest {
    private val sims = ArrayList<Sim>()
    private val logs = CopyOnWriteArrayList<String>()
    private val calls = CopyOnWriteArrayList<String>()
    private val art = CopyOnWriteArrayList<String>()
    private val app get() = RuntimeEnvironment.getApplication()
    private val idol = PhoneKey("Idol", "YOASOBI")
    @Volatile private var down = false
    private val body = "{\"syncedLyrics\":\"[00:01.00]rx-001\\n[00:02.00]rx-002\\n\",\"instrumental\":false}"

    @Before
    fun setUp() {
        scrobblesOff(app)
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        MediaLink.controls(app, false)
        val e = app.getSharedPreferences(Main.ART_PREFS, Context.MODE_PRIVATE).edit().putInt("defaults", 2)
        for (k in listOf("theaudiodb", "discogs", "fanarttv", "deezer", "youtube", "commons")) e.putBoolean(k, false)
        e.commit()
        Remote.resetArtist()
        Remote.artist(app, Http { u, _ ->
            art += u
            Response(404, ByteArray(0))
        })
        Lyrics.init(app, object : Http {
            override fun get(url: String, headers: Map<String, String>): Response {
                calls += url
                if (down) throw IOException("down")
                return if ("track_name=Idol&" in url) Response(200, body.toByteArray()) else Response(404, ByteArray(0))
            }

            override fun post(url: String, headers: Map<String, String>, body: ByteArray): Response {
                calls += "POST $url ${String(body, Charsets.UTF_8)}"
                return Response(404, ByteArray(0))
            }
        })
        Lyrics.set(LyricSources.LRCLIB, true)
        Lyrics.set(LyricSources.NETEASE, true)
    }

    @After
    fun tearDown() {
        Remote.nowPlayingShown(false)
        Remote.stop()
        Lyrics.reset()
        Remote.resetArtist()
        sims.forEach { it.stop() }
        scrobblesReset()
    }

    private fun waitFor(ms: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out: $calls" }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun sim(lines: List<PhoneLine>) = Sim(Options(Board.of("v1"), Faults(emptyList()), lrcDir = File(app.cacheDir.parentFile, "simlrc"), log = { logs += it }, phone = lines, receiver = true)).also { sims += it }

    private fun link(lines: List<PhoneLine>): org.robolectric.android.controller.ServiceController<LinkService> {
        val c = Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, "tcp:127.0.0.1:${sim(lines).listen(0)}")).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.source == Enums.Source.phone }
        Remote.queuePage(0)
        return c
    }

    private fun drained() {
        repeat(2) {
            Remote.dapLyrics.idle()
            val done = CountDownLatch(1)
            assertTrue(Lyrics.phone { done.countDown() })
            assertTrue(done.await(10, TimeUnit.SECONDS))
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun dataDir(): Map<String, String> {
        val root = app.filesDir.parentFile!!
        val out = sortedMapOf<String, String>()
        for (d in listOf("files", "cache", "shared_prefs", "databases", "no_backup")) {
            File(root, d).walkTopDown().filter { it.isFile }.forEach { f ->
                out[f.relativeTo(root).path] = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            }
        }
        return out
    }

    @Test
    fun aPhoneTrackIsLookedUpInMemoryWhileTheQueueStaysGated() {
        val c = link(listOf(PhoneLine("Idol", "YOASOBI", "THE BOOK 3", 213_000, 0, true)))
        assertEquals(Enums.Output.phoneReceiver, Remote.device.playback.state.output)
        waitFor { Remote.phoneLyrics.settled == idol }
        drained()
        val before = dataDir()
        calls.clear()
        Remote.nowPlayingShown(true)
        waitFor { Remote.phoneLyrics.entry(idol)?.has == true }
        drained()
        val e = Remote.phoneLyrics.entry(idol)!!
        assertEquals(true, e.follow)
        assertEquals(listOf("rx-001", "rx-002"), e.copy!!.lines.map { it.text })
        assertTrue(calls.toString(), calls.isNotEmpty() && calls.all { "track_name=Idol&" in it })
        assertTrue(logs.none { " rx LRC_PUT " in it || " rx LYR_GET " in it })
        assertEquals(before, dataDir())
        assertTrue(File(app.filesDir, "lyrics").list().orEmpty().isEmpty())
        c.destroy()
    }

    @Test
    fun aDurationZeroTrackAsksLrclibAloneWithNoDuration() {
        val c = link(listOf(PhoneLine("Nothing", "Nobody", "", 0, 0, true)))
        calls.clear()
        Remote.nowPlayingShown(true)
        waitFor { Remote.phoneLyrics.entry(PhoneKey("Nothing", "Nobody"))?.kind == PhoneLyrics.Kind.Miss }
        assertEquals(listOf("https://lrclib.net/api/get?track_name=Nothing&artist_name=Nobody"), calls.toList())
        c.destroy()
    }

    @Test
    fun aFailedLookupIsAskedAgainWhenTheNetworkReturns() {
        down = true
        val c = link(listOf(PhoneLine("Idol", "YOASOBI", "THE BOOK 3", 213_000, 0, true)))
        Remote.nowPlayingShown(true)
        waitFor { Remote.phoneLyrics.entry(idol)?.kind == PhoneLyrics.Kind.Unavailable }
        drained()
        val failed = calls.size
        assertEquals(PhoneLyrics.Kind.Unavailable, Remote.phoneLyrics.entry(idol)?.kind)
        down = false
        Lyrics.network()
        waitFor { Remote.phoneLyrics.entry(idol)?.has == true }
        assertTrue(calls.size > failed)
        c.destroy()
    }

    @Test
    fun aFailedLookupIsAskedAgainWhenTheLinkIsLiveAgain() {
        down = true
        val line = listOf(PhoneLine("Idol", "YOASOBI", "THE BOOK 3", 213_000, 0, true))
        val first = sim(line)
        val port = first.listen(0)
        val c = Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, "tcp:127.0.0.1:$port")).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.source == Enums.Source.phone }
        Remote.nowPlayingShown(true)
        waitFor { Remote.phoneLyrics.entry(idol)?.kind == PhoneLyrics.Kind.Unavailable }
        drained()
        down = false
        first.stop()
        waitFor { Remote.phase != Phase.LIVE }
        drained()
        assertEquals(PhoneLyrics.Kind.Unavailable, Remote.phoneLyrics.entry(idol)?.kind)
        sim(line).listen(port)
        waitFor { Remote.phoneLyrics.entry(idol)?.has == true }
        assertEquals(Phase.LIVE, Remote.phase)
        c.destroy()
    }
}
