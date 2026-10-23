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
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.PhoneLine
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.state.LyricsFetch
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
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

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
    private val seekBody = "{\"syncedLyrics\":\"[00:00.00]sk-001\\n[00:20.00]sk-002\\n[00:40.00]sk-003\\n[01:00.00]sk-004\\n\",\"instrumental\":false}"

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
                return when {
                    "track_name=Seek&" in url -> Response(200, seekBody.toByteArray())
                    "track_name=Idol&" in url || "track_name=Racing&" in url -> Response(200, body.toByteArray())
                    else -> Response(404, ByteArray(0))
                }
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

    private fun sim(lines: List<PhoneLine>, vararg faults: String) = Sim(Options(Board.of("v1"), Faults(faults.toList()), lrcDir = File(app.cacheDir.parentFile, "simlrc"), log = { logs += it }, phone = lines, receiver = true)).also { sims += it }

    private fun link(lines: List<PhoneLine>, vararg faults: String): org.robolectric.android.controller.ServiceController<LinkService> {
        val c = Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, "tcp:127.0.0.1:${sim(lines, *faults).listen(0)}")).create().startCommand(0, 1)
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

    private fun phoneShow() = Remote.dapLyrics.show as? LyricsFetch.Show.Phone

    private fun line(): Int {
        val c = phoneShow()?.copy ?: return Int.MIN_VALUE
        return LyricsFetch.index(c.lines, Remote.device.playback.state.positionMs ?: 0)
    }

    private fun states() = logs.count { " tx STATE " in it }

    private fun thumbMisses(): Set<String> {
        val w = Remote.thumbs(app)
        val t = w.javaClass.getDeclaredField("thumbs").apply { isAccessible = true }.get(w)
        val m = t.javaClass.getDeclaredField("misses").apply { isAccessible = true }.get(t) as Set<*>
        val exec = w.javaClass.getDeclaredField("exec").apply { isAccessible = true }.get(w) as java.util.concurrent.ExecutorService
        return exec.submit(java.util.concurrent.Callable { m.map { it.toString() }.toSet() }).get(10, TimeUnit.SECONDS)
    }

    @Test
    fun aPhoneSeekMovesTheLineWithinOneState() {
        val t0 = System.currentTimeMillis()
        val c = link(listOf(PhoneLine("Seek", "YOASOBI", "", 213_000, 0, true)), "phone-seek=5000:45000")
        Remote.nowPlayingShown(true)
        var before = -1
        waitFor {
            if (line() == 0) before = states()
            line() == 2
        }
        assertTrue(before >= 0)
        assertTrue(System.currentTimeMillis() - t0 < Proto.stateIntervalMs)
        assertTrue(states() - before <= 1)
        c.destroy()
    }

    @Test
    fun aPhoneSuspendFallsBackToTheLocalTrackAndClearsTheSettledKey() {
        val c = link(listOf(PhoneLine("Idol", "YOASOBI", "", 213_000, 0, false)), "phone-suspend=4000")
        Remote.nowPlayingShown(true)
        waitFor { Remote.phoneLyrics.settled == idol && phoneShow()?.copy != null }
        waitFor { Remote.device.playback.state.source == Enums.Source.local }
        drained()
        val s = Remote.device.playback.state
        assertEquals(Enums.Output.wired, s.output)
        assertTrue(s.title != "Idol")
        assertEquals(null, Remote.phoneLyrics.settled)
        assertTrue(Remote.dapLyrics.show !is LyricsFetch.Show.Phone)
        assertTrue(Remote.phoneLyrics.entry(idol)?.has == true)
        c.destroy()
    }

    @Test
    fun theSameSongPlayedAgainFromTheStartResetsTheLine() {
        val c = link(listOf(PhoneLine("Idol", "YOASOBI", "", 213_000, 150_000, true), PhoneLine("Idol", "YOASOBI", "", 213_000, 0, true)))
        Remote.nowPlayingShown(true)
        waitFor { phoneShow()?.copy != null }
        assertEquals(1, line())
        drained()
        val asked = calls.size
        Remote.transport(Enums.TransportCmd.next)
        waitFor { (Remote.device.playback.state.positionMs ?: Long.MAX_VALUE) < 150_000 }
        drained()
        assertEquals(-1, line())
        assertEquals(idol, Remote.phoneLyrics.settled)
        assertEquals(idol, phoneShow()?.key)
        assertEquals(asked, calls.size)
        c.destroy()
    }

    @Test
    fun aPauseThenAResumeKeepsOneKeyTheSettledKeyAndTheOffset() {
        val c = link(listOf(PhoneLine("Idol", "YOASOBI", "", 213_000, 0, true)))
        Remote.nowPlayingShown(true)
        waitFor { phoneShow()?.copy != null }
        Remote.phoneOffset(idol, 500) { _, _ -> }
        waitFor { phoneShow()?.copy?.lines?.map { it.ms } == listOf(500L, 1500L) }
        drained()
        val asked = calls.size
        val seen = ArrayList<Triple<PhoneKey?, PhoneKey?, Boolean>>()
        fun look() = seen.add(Triple(phoneShow()?.key, Remote.phoneLyrics.settled, phoneShow()?.copy != null))
        Remote.transport(Enums.TransportCmd.toggle)
        waitFor {
            look()
            Remote.device.playback.state.playing == false
        }
        drained()
        look()
        assertEquals(Enums.Source.phone, Remote.device.playback.state.source)
        Remote.transport(Enums.TransportCmd.toggle)
        waitFor {
            look()
            Remote.device.playback.state.playing == true
        }
        drained()
        look()
        assertTrue(seen.toString(), seen.all { it == Triple(idol, idol, true) })
        assertEquals(500, Remote.phoneLyrics.offset(idol))
        assertEquals(listOf(500L, 1500L), phoneShow()?.copy?.lines?.map { it.ms })
        assertEquals(asked, calls.size)
        c.destroy()
    }

    @Test
    fun thePhoneModeKeyLeavesTheStoredModeFileByteEqual() {
        LyricsMode.on(app, true)
        val c = link(listOf(PhoneLine("Idol", "YOASOBI", "", 213_000, 0, true)))
        Remote.nowPlayingShown(true)
        waitFor { phoneShow()?.copy != null }
        drained()
        val before = dataDir()["shared_prefs/lyricsmode.xml"]
        assertTrue(before != null)
        Remote.phoneLyricsMode(!Remote.phoneLyricsMode())
        Remote.phoneLyricsMode(!Remote.phoneLyricsMode())
        drained()
        assertEquals(before, dataDir()["shared_prefs/lyricsmode.xml"])
        c.destroy()
        LyricsMode.on(app, false)
    }

    @Test
    fun aSettledPhoneCopyIsPublishedWithItsOffsetAndNoLyrGet() {
        val c = link(listOf(PhoneLine("Idol", "YOASOBI", "THE BOOK 3", 213_000, 0, true)))
        Remote.nowPlayingShown(true)
        waitFor { phoneShow()?.copy != null }
        val first = phoneShow()!!
        assertEquals(idol, first.key)
        assertEquals(true, first.follow)
        assertEquals(Enums.LyricsKind.synced, first.copy!!.kind)
        assertEquals(listOf(1000L, 2000L), first.copy!!.lines.map { it.ms })
        var got: Int? = null
        Remote.phoneOffset(idol, 500) { _, ms -> got = ms }
        assertEquals(500, got)
        waitFor { phoneShow()?.copy?.lines?.map { it.ms } == listOf(500L, 1500L) }
        Remote.phoneOffset(idol, 70_000) { code, _ -> got = code }
        assertEquals(net.seilent.funecomp.proto.gen.Err.badValue, got)
        assertEquals(500, Remote.phoneLyrics.offset(idol))
        assertTrue(logs.none { " rx LYR_GET " in it || " rx LYR_OFFSET " in it || " rx LRC_PUT " in it })
        c.destroy()
    }

    @Test
    fun thePhoneModeIsSeededFromTheStoredStateAndNeverWritesIt() {
        LyricsMode.on(app, true)
        val c = link(listOf(PhoneLine("Idol", "YOASOBI", "THE BOOK 3", 213_000, 0, true)))
        assertTrue(Remote.phoneLyricsMode())
        Remote.phoneLyricsMode(false)
        assertTrue(LyricsMode.on(app))
        Remote.phoneLyricsMode(true)
        LyricsMode.on(app, false)
        assertTrue(Remote.phoneLyricsMode())
        c.destroy()
        LyricsMode.on(app, false)
    }

    @Test
    fun aPhoneCoverIsFetchedOnItsCrcOutsideThumbs() {
        val f = File(app.cacheDir, "cover.jpg")
        ImageIO.write(BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB), "jpeg", f)
        val thumbs = File(app.filesDir, "thumb")
        val before = thumbs.walkTopDown().map { it.relativeTo(thumbs).path }.toList()
        val c = link(listOf(PhoneLine("Idol", "YOASOBI", "", 213_000, 0, true, "Pixel", 1, 2, f)))
        val crc = Remote.device.playback.state.phoneArtCrc!!
        waitFor { Remote.phoneCover(crc) != null }
        assertTrue(logs.any { "art get track=0" in it })
        assertEquals(before, thumbs.walkTopDown().map { it.relativeTo(thumbs).path }.toList())
        assertTrue(thumbMisses().toString(), thumbMisses().none { it.startsWith("track:0:") })
        c.destroy()
    }

    private fun cover(): File {
        val f = File(app.cacheDir, "cover.jpg")
        ImageIO.write(BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB), "jpeg", f)
        return f
    }

    @Test
    fun aPhoneExitClearsTheCoverAndTheSettledKeyAndHandsBack() {
        val c = link(listOf(PhoneLine("Idol", "YOASOBI", "", 213_000, 0, true, "Pixel", 1, 2, cover())), "phone-exit=6000")
        val crc = Remote.device.playback.state.phoneArtCrc!!
        waitFor { Remote.phoneCover(crc) != null && Remote.phoneLyrics.settled == idol }
        waitFor(20_000) { Remote.device.playback.state.source == Enums.Source.local }
        drained()
        assertEquals(null, Remote.phoneCover(crc))
        assertEquals(null, Remote.phoneLyrics.settled)
        assertTrue(Remote.dapLyrics.show !is LyricsFetch.Show.Phone)
        assertEquals(Enums.Output.wired, Remote.device.playback.state.output)
        c.destroy()
    }

    @Test
    fun noArtGetTrackZeroWhileTheCrcIsAbsent() {
        val thumbs = File(app.filesDir, "thumb")
        val before = thumbs.walkTopDown().map { it.relativeTo(thumbs).path }.toList()
        val c = link(listOf(PhoneLine("Idol", "YOASOBI", "", 213_000, 0, true, "Pixel", 1, 2, cover())), "phone-art-late=3000")
        Remote.nowPlayingShown(true)
        waitFor { Remote.phoneLyrics.settled == idol }
        drained()
        assertEquals(null, Remote.device.playback.state.phoneArtCrc)
        assertTrue(logs.none { "art get track=0" in it || " rx ART_GET " in it })
        waitFor { Remote.device.playback.state.phoneArtCrc != null }
        waitFor { Remote.phoneCover(Remote.device.playback.state.phoneArtCrc!!) != null }
        assertEquals(before, thumbs.walkTopDown().map { it.relativeTo(thumbs).path }.toList())
        c.destroy()
    }

    @Test
    fun twoPhoneTracksGiveTwoCopiesAndABackStepAsksNothing() {
        val racing = PhoneKey("Racing", "YOASOBI")
        val c = link(listOf(PhoneLine("Idol", "YOASOBI", "", 213_000, 0, true), PhoneLine("Racing", "YOASOBI", "", 200_000, 0, true)))
        Remote.nowPlayingShown(true)
        waitFor { Remote.phoneLyrics.entry(idol)?.has == true }
        Remote.transport(Enums.TransportCmd.next)
        waitFor { Remote.phoneLyrics.entry(racing)?.has == true && (Remote.dapLyrics.show as? LyricsFetch.Show.Phone)?.key == racing }
        drained()
        val asked = calls.size
        Remote.transport(Enums.TransportCmd.prev)
        Remote.transport(Enums.TransportCmd.prev)
        waitFor { (Remote.dapLyrics.show as? LyricsFetch.Show.Phone)?.key == idol && (Remote.dapLyrics.show as LyricsFetch.Show.Phone).copy != null }
        drained()
        assertEquals(asked, calls.size)
        c.destroy()
    }

    @Test
    fun theHeartSendsNoFavoriteForAPhoneTrack() {
        val c = link(listOf(PhoneLine("Idol", "YOASOBI", "", 213_000, 0, true)))
        Remote.favorite(Remote.device.playback.state) { _, _ -> }
        Thread.sleep(300)
        assertTrue(logs.none { " rx ACTION " in it })
        c.destroy()
    }
}
