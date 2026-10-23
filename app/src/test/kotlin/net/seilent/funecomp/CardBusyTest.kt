package net.seilent.funecomp

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Looper
import net.seilent.funecomp.art.Thumbs
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.LyricSources
import net.seilent.funecomp.artist.Names
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.artist.Wikimedia
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.state.Heart
import net.seilent.funecomp.state.LyricsFetch
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CardBusyTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val sims = ArrayList<Sim>()
    private val lines = CopyOnWriteArrayList<String>()
    private val img = Regex("rx IMG_(BEGIN|END|LIST|DEL|TAG) ")
    private val cached = listOf("Cardone", "Cardtwo", "Cardthree")
    private val names = cached + "Cardnone"

    @Before
    fun setUp() {
        scrobblesOff(app)
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        MediaLink.controls(app, false)
        Remote.resetArtist()
        ShadowLog.clear()
        Remote.artist(app, Http { _, _ -> Response(404, ByteArray(0)) })
        Lyrics.init(app, Http { _, _ -> Response(404, ByteArray(0)) })
    }

    @After
    fun tearDown() {
        Remote.dapLyrics.shown(false)
        Remote.stop()
        Remote.shown(false)
        Lyrics.reset()
        Firmwares.reset()
        sims.forEach { it.stop() }
        Remote.resetArtist()
        scrobblesReset()
    }

    private fun waitFor(ms: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out: ${lines.filter { "card" in it || "IMG" in it || "LYR" in it || "LRC" in it }.takeLast(40)}" }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
            Thread.sleep(20)
        }
    }

    private fun settle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
            Thread.sleep(20)
        }
    }

    private fun jpeg(): ByteArray {
        val i = java.awt.image.BufferedImage(600, 800, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = i.createGraphics()
        g.color = java.awt.Color(120, 80, 40)
        g.fillRect(0, 0, 600, 800)
        g.dispose()
        return ByteArrayOutputStream().also { javax.imageio.ImageIO.write(i, "jpg", it) }.toByteArray()
    }

    private fun cache(vararg names: String) {
        val c = ArtCache(File(app.filesDir, "art"))
        for (n in names) c.putHit(ArtCache.Entry("name:${Names.fold(n)}", n, null, Wikimedia.SOURCE, null, "https://upload.wikimedia.org/$n.jpg", 1, "File:$n.jpg"), jpeg())
    }

    private val http = Http { url, _ ->
        lines += "http $url"
        Response(404, ByteArray(0))
    }

    private fun lib(names: List<String>) = Library(names.mapIndexed { i, n -> Library.track(n, "Album $i", "Song $i", 1, 200_000) })

    private fun sim(lib: Library, faults: List<String>, lrcDir: File? = null, minor: Int = Proto.minor, updDir: File? = null, artDir: File? = null): Sim {
        val s = Sim(Options(Board.of("v1"), Faults(faults), lrcDir = lrcDir, minor = minor, updDir = updDir, artDir = artDir, log = { lines += it }), lib)
        sims += s
        return s
    }

    private fun link(s: Sim) = Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, "tcp:127.0.0.1:${s.listen(0)}")).create().startCommand(0, 1)

    private fun index(prefix: String, from: Int = 0) = lines.withIndex().drop(from).firstOrNull { it.value.startsWith(prefix) }?.index ?: -1

    private fun between(from: Int, to: Int, r: Regex) = lines.toList().subList(from, to).filter { r.containsMatchIn(it) }

    private fun lookups(from: Int, to: Int) = names.filter { n -> lines.toList().subList(from, to).any { it.startsWith("http ") && "query=$n" in it } }

    private fun stored(s: Sim) = s.device.stored.values.map { it.name }.toSet()

    private fun edges() = ShadowLog.getLogsForTag(Remote.TAG).count { it.msg.startsWith("card ready edge") }

    private fun artRun(faults: List<String>): Pair<Int, Int> {
        cache(*cached.toTypedArray())
        Remote.resetArtist()
        Remote.artist(app, http)
        val s = sim(lib(names), faults)
        val c = link(s)
        waitFor(30_000) { stored(s).containsAll(cached) }
        val busy = index("card gate busy")
        val ready = index("card gate ready", busy)
        assertTrue("$busy $ready", busy in 0 until ready)
        c.destroy()
        return busy to ready
    }

    private fun artStops(device: String, coalesce: Boolean = false) {
        val (busy, ready) = artRun(listOf("card-run=5000+3000@img:1", "card-device=$device") + if (coalesce) listOf("card-coalesce") else emptyList())
        assertTrue(between(busy, ready, img).filterNot { "rx IMG_END " in it }.isEmpty())
        assertTrue(between(busy, ready, img).size <= 1)
        assertTrue(lookups(busy, ready).size <= 1)
        assertEquals(1, lines.count { it.startsWith("card stop img") })
    }

    @Test
    fun artStopsAtALandedRunAndResumesAtTheReadyEdge() = artStops("landed")

    @Test
    fun artStopsAtAnOldRunAndResumesAtTheReadyEdge() = artStops("old")

    @Test
    fun artStopsAtACoalescedRunAndResumesAtTheFirstReadyState() = artStops("landed", true)

    @Test
    fun aConnectDuringTheRunSendsNoImageRequestUntilReady() {
        val (_, ready) = artRun(listOf("card-run=4000+2000@hello"))
        assertEquals(emptyList<String>(), between(0, ready, img))
        assertEquals(emptyList<String>(), lookups(0, ready))
    }

    @Test
    fun aLateStateLeavesTheAbortedRunToTheEdge() {
        val (busy, ready) = artRun(listOf("card-run=4000+2000@img:1", "late-state=500"))
        assertTrue(ShadowLog.getLogsForTag(Remote.TAG).any { it.msg.startsWith("art ABORTED") })
        assertTrue(between(busy, ready, img).size <= 1)
        assertEquals(1, lines.count { it.startsWith("card stop img") })
    }

    private fun blockOnBluetooth(down: Boolean) {
        val name = Library.synthetic().tracks.first().artists.first()
        val key = "name:${Names.fold(name)}"
        cache(name)
        Remote.resetArtist()
        Remote.artist(app, http)
        val s = sim(Library.synthetic(), if (down) listOf("card-run=6000+0@manual") else emptyList())
        Remote.shown(true)
        val c = link(s)
        waitFor { Remote.phase == Phase.LIVE && Remote.backdrop?.entry?.key == key && name in stored(s) }
        s.output(Enums.Output.btHeadset)
        waitFor { Remote.device.playback.state.output == Enums.Output.btHeadset }
        if (down) s.device.startCard()
        if (down) waitFor { Remote.device.cardDown }
        Remote.blockArt(Remote.backdrop!!.entry!!)
        if (down) {
            settle(1500)
            assertTrue(Remote.device.cardDown)
            assertEquals(emptyList<String>(), lines.filter { "rx IMG_DEL" in it })
            waitFor { !Remote.device.cardDown }
        }
        waitFor { lines.contains("deleted name=$name") }
        if (down) assertTrue(index("card gate ready") < index("deleted name=$name"))
        c.destroy()
    }

    @Test
    fun aBlockOnBluetoothOutputDeletesWithTheCardReady() = blockOnBluetooth(false)

    @Test
    fun aBlockOnBluetoothOutputSendsNoDeleteWhileTheCardIsDown() = blockOnBluetooth(true)

    private fun lrcDir(lib: Library): File {
        val dir = kotlin.io.path.createTempDirectory("lrc").toFile()
        for (t in lib.tracks) File(dir, "${java.lang.Long.toUnsignedString(t.id)}.lrc").writeText("[00:01.00]one\n[00:02.00]two\n")
        return dir
    }

    private fun lyrGets(from: Int, to: Int) = between(from, to, Regex("rx LYR_GET "))

    private fun lyrRefusals(from: Int, to: Int) = between(from, to, Regex(" tx LYR_GET flags=0x03 "))

    @Test
    fun theLyricsViewShowsThePlayingTrackDuringATwentySecondRun() {
        val lib = Library.synthetic()
        val s = sim(lib, listOf("card-run=12000+8000@manual"), lrcDir(lib))
        val c = link(s)
        waitFor { Remote.phase == Phase.LIVE }
        s.device.startCard()
        waitFor { Remote.device.cardDown }
        Remote.dapLyrics.shown(true)
        waitFor { Remote.dapLyrics.show is LyricsFetch.Show.Lines }
        assertTrue(Remote.device.cardDown)
        waitFor(30_000) { index("card gate ready") >= 0 }
        val busy = index("card gate busy")
        val ready = index("card gate ready", busy)
        assertEquals(1, lyrGets(busy, ready).size)
        assertEquals(emptyList<String>(), lyrRefusals(0, lines.size))
        c.destroy()
    }

    @Test
    fun aRunFromTheHelloShowsThePlayingTrackBeforeReady() {
        val lib = Library.synthetic()
        val s = sim(lib, listOf("card-run=60000+0@hello"), lrcDir(lib))
        Remote.dapLyrics.shown(true)
        val c = link(s)
        waitFor { Remote.dapLyrics.show is LyricsFetch.Show.Lines }
        assertEquals(-1, index("card gate ready"))
        assertEquals(1, lyrGets(0, lines.size).size)
        assertEquals(emptyList<String>(), lyrRefusals(0, lines.size))
        c.destroy()
    }

    private fun aTrackChangeDuringTheRunWaits(late: Long, coalesce: Boolean = false) {
        val lib = Library((1..4).map { Library.track("Lyrartist", "Lyralbum", "Lyrsong $it", it, if (it == 1) 2_500L else 60_000L) })
        val s = sim(lib, listOf("card-run=6000+1000@play") + (if (late > 0) listOf("late-state=$late") else emptyList()) + (if (coalesce) listOf("card-coalesce") else emptyList()), lrcDir(lib))
        Remote.dapLyrics.shown(true)
        val c = link(s)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.track != null }
        val first = Remote.device.playback.state.track
        Remote.transport(Enums.TransportCmd.play)
        waitFor(20_000) { index("card gate ready") >= 0 && Remote.dapLyrics.show.let { it is LyricsFetch.Show.Lines && it.track != first } }
        val busy = index("card gate busy")
        val ready = index("card gate ready", busy)
        assertTrue(lyrRefusals(busy, ready).isNotEmpty())
        assertEquals(emptyList<String>(), lyrRefusals(ready, lines.size))
        val track = (Remote.dapLyrics.show as LyricsFetch.Show.Lines).track
        val asks = lyrGets(ready, lines.size)
        assertEquals("$asks", 1, asks.size)
        assertEquals(track, Remote.device.playback.state.track)
        c.destroy()
    }

    @Test
    fun aTrackChangeDuringTheRunGets14WaitsAndAsksOnceAfterReady() = aTrackChangeDuringTheRunWaits(0)

    @Test
    fun aTrackChangeDuringACoalescedRunWaitsForTheFirstReadyState() = aTrackChangeDuringTheRunWaits(0, true)

    @Test
    fun aTrackChangeDuringTheRunWithALateStateWaitsAndAsksOnceAfterReady() = aTrackChangeDuringTheRunWaits(500)

    @Test
    fun aRunDuringTheFirstPutHoldsTheLyricsSourcesUntilReadyThenPutsAll() {
        val three = lib(listOf("Lyrone", "Lyrtwo", "Lyrthree"))
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        var started: Sim? = null
        Lyrics.init(app, Http { url, _ ->
            lines += "http $url"
            if (first.getAndSet(false)) {
                started!!.device.startCard()
                val end = System.currentTimeMillis() + 10_000
                while (!lines.contains("card gate busy") && System.currentTimeMillis() < end) Thread.sleep(20)
            }
            Response(200, "{\"syncedLyrics\":\"[00:01.00]line\\n\",\"instrumental\":false}".toByteArray())
        })
        Lyrics.set(LyricSources.NETEASE, false)
        Lyrics.set(LyricSources.LRCLIB, true)
        val s = sim(three, listOf("card-run=4000+2000@manual"), kotlin.io.path.createTempDirectory("lrc").toFile())
        started = s
        val c = link(s)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.track != null }
        Remote.queuePage(0)
        waitFor(30_000) { lines.count { it.startsWith("stored lrc") } == 3 }
        val busy = index("card gate busy")
        val ready = index("card gate ready", busy)
        assertEquals(emptyList<String>(), between(busy, ready, Regex("^http ")))
        assertEquals(1, between(busy, ready, Regex("rx LRC_PUT ")).size)
        assertEquals(0, lines.toList().subList(0, busy).count { it.startsWith("stored lrc") })
        Lyrics.set(LyricSources.LRCLIB, false)
        c.destroy()
    }

    @Test
    fun aDropDuringTheRunResumesArtAndLyricsAtTheNewSessionsReady() {
        val lib = Library.synthetic()
        val name = lib.tracks.first().artists.first()
        cache(name)
        Remote.resetArtist()
        Remote.artist(app, http)
        val s = sim(lib, listOf("card-run=15000+2000@hello"), lrcDir(lib))
        Remote.dapLyrics.shown(true)
        val c = link(s)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.cardDown }
        val hellos = lines.count { "rx HELLO" in it }
        s.current!!.close("drop")
        waitFor(15_000) { lines.count { "rx HELLO" in it } > hellos && Remote.phase == Phase.LIVE }
        waitFor { Remote.device.cardDown }
        assertEquals(-1, index("card gate ready"))
        waitFor(20_000) { name in stored(s) && Remote.dapLyrics.show is LyricsFetch.Show.Lines }
        val ready = index("card gate ready")
        assertEquals(emptyList<String>(), between(0, ready, img))
        assertEquals(emptyList<String>(), lyrRefusals(0, ready))
        assertTrue(edges() >= 1)
        c.destroy()
    }

    @Test
    fun aBusyOutBusyReadySequenceResumesOnceAtTheReady() {
        val lib = Library.synthetic()
        val name = lib.tracks.first().artists.first()
        cache(name)
        Remote.resetArtist()
        Remote.artist(app, http)
        val s = sim(lib, listOf("card-seq=busy:2000,out:2000,busy:2000,ready"), lrcDir(lib))
        Remote.dapLyrics.shown(true)
        val c = link(s)
        waitFor(20_000) { name in stored(s) && Remote.dapLyrics.show is LyricsFetch.Show.Lines }
        val out = index("card gate out")
        val ready = index("card gate ready")
        assertTrue("$out $ready", out in 0 until ready)
        assertEquals(emptyList<String>(), between(0, ready, img))
        assertEquals(emptyList<String>(), lyrRefusals(0, ready))
        settle(1000)
        assertEquals(1, edges())
        c.destroy()
    }

    private class Upd(val image: File, val card: File)

    private fun upd(): Upd {
        val dir = kotlin.io.path.createTempDirectory("upd").toFile()
        val image = File(dir, "image.upt").apply { writeBytes(ByteArray(400_000) { (it * 7 + 3).toByte() }) }
        shadowOf(app.getSystemService(android.os.BatteryManager::class.java)).setIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY, 80)
        Firmwares.init(app)
        return Upd(image, File(dir, "card"))
    }

    private fun stored(u: Upd) {
        val done = File(u.card, "v1.upt")
        val end = System.currentTimeMillis() + 60_000
        while (!done.exists()) {
            check(System.currentTimeMillis() < end) { "timed out: ${lines.filter { it.startsWith("upd") || it.startsWith("card") }} ${Firmwares.view}" }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            Thread.sleep(100)
        }
        assertTrue(u.image.readBytes().contentEquals(done.readBytes()))
        waitFor { Firmwares.view.info == "ready on the dap" }
    }

    private fun updStop(minor: Int) {
        val u = upd()
        val c = link(sim(Library.synthetic(), listOf("card-run=8000+4000@upd:150000", "late-state=1500"), minor = minor, updDir = u.card))
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.battery != null }
        Firmwares.send(u.image)
        waitFor(20_000) { Firmwares.view.info == Heart.CARD }
        assertTrue(Firmwares.view.send, Firmwares.view.send.startsWith("paused"))
        waitFor { Remote.device.cardDown }
        val stop = lines.first { it.startsWith("card stop upd") }
        stored(u)
        val begins = lines.filter { it.startsWith("upd begin") }
        assertTrue("$begins", begins.size >= 2)
        assertEquals(stop.substringAfter("at=").toLong(), begins.last().substringAfter("offset=").toLong())
        assertTrue(begins.last().substringAfter("offset=").toLong() >= 150_000)
        assertTrue(index("card gate ready") < lines.indexOf(begins.last()))
        c.destroy()
    }

    @Test
    fun aCardStopMidUpdatePausesKeepsThePickAndResumesAtMinor15() = updStop(15)

    @Test
    fun aBusyStopMidUpdatePausesKeepsThePickAndResumesAtMinor14() = updStop(14)

    @Test
    fun anUpdateTappedDuringTheRunIsNotSentUntilReady() {
        val u = upd()
        val s = sim(Library.synthetic(), listOf("card-run=4000+2000@manual"), updDir = u.card)
        val c = link(s)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.battery != null }
        s.device.startCard()
        waitFor { Remote.device.cardDown }
        Firmwares.send(u.image)
        waitFor { Firmwares.view.info == Heart.CARD }
        stored(u)
        assertEquals(emptyList<String>(), between(0, index("card gate ready"), Regex("rx UPD_BEGIN ")))
        c.destroy()
    }

    @Test
    fun anUpdateBeginRefused14InACoalescedRunResumesAtTheFirstReadyState() {
        val u = upd()
        val s = sim(Library.synthetic(), listOf("card-run=4000+2000@manual", "card-coalesce"), updDir = u.card)
        val c = link(s)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.battery != null }
        s.device.startCard()
        waitFor { lines.contains("card gate busy") }
        Firmwares.send(u.image)
        waitFor { Firmwares.view.info == Heart.CARD }
        assertEquals(false, Remote.device.cardDown)
        stored(u)
        val ready = index("card gate ready")
        assertEquals(1, between(0, ready, Regex("rx UPD_BEGIN ")).size)
        assertEquals(1, between(0, ready, Regex("tx UPD_BEGIN flags=0x03 ")).size)
        assertTrue(ready < lines.indexOfFirst { "tx UPD_BEGIN flags=0x01 " in it })
        c.destroy()
    }

    @Test
    fun anUpdateBeginRefusedWithBusyBelowTheCardTagPausesAndResumes() {
        val u = upd()
        val s = sim(Library.synthetic(), listOf("card-run=4000+2000@manual"), minor = 13, updDir = u.card)
        val c = link(s)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.battery != null }
        s.device.startCard()
        waitFor { lines.contains("card gate busy") }
        assertEquals(null, Remote.device.card.value)
        Firmwares.send(u.image)
        waitFor { Firmwares.view.info == "dap busy" }
        assertTrue(Firmwares.view.send.startsWith("paused") || Firmwares.view.send.isEmpty())
        stored(u)
        assertTrue(between(0, index("card gate ready"), Regex("rx UPD_BEGIN ")).isNotEmpty())
        c.destroy()
    }

    @Test
    fun anUpdateBeginRefused14PausesKeepsThePickAndResumes() {
        val u = upd()
        val s = sim(Library.synthetic(), listOf("fail=UPD_BEGIN:14:1"), updDir = u.card)
        val c = link(s)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.battery != null }
        Firmwares.send(u.image)
        waitFor { Firmwares.view.info == Heart.CARD }
        assertTrue(Firmwares.view.send.startsWith("paused"))
        settle(Firmwares.BUSY_RETRY_MS + 1000)
        assertEquals(1, lines.count { "rx UPD_BEGIN" in it })
        s.current!!.push(Msg.STATE, s.device.state())
        stored(u)
        assertEquals(2, lines.count { "rx UPD_BEGIN" in it })
        c.destroy()
    }

    @Test
    fun anUpdateBeginRefused14ThenAReconnectResumesAtTheNewSessionsReady() {
        val u = upd()
        val s = sim(Library.synthetic(), listOf("fail=UPD_BEGIN:14:1"), updDir = u.card)
        val c = link(s)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.battery != null }
        Firmwares.send(u.image)
        waitFor { Firmwares.view.info == Heart.CARD }
        val hellos = lines.count { "rx HELLO" in it }
        s.current!!.close("drop")
        waitFor(15_000) { lines.count { "rx HELLO" in it } > hellos && Remote.phase == Phase.LIVE }
        stored(u)
        val begins = lines.withIndex().filter { "rx UPD_BEGIN" in it.value }.map { it.index }
        val hello = lines.withIndex().filter { "rx HELLO" in it.value }.map { it.index }[1]
        assertEquals(1, begins.count { it < hello })
        assertTrue(begins.any { it > hello })
        c.destroy()
    }

    private fun albumArt(dir: File, lib: Library, track: Long) {
        val i = java.awt.image.BufferedImage(600, 600, java.awt.image.BufferedImage.TYPE_INT_RGB)
        javax.imageio.ImageIO.write(i, "jpg", File(dir, "${lib.albumKey(track)}.jpg"))
    }

    private fun thumbAfterTheRun(faults: List<String>, artFirst: Boolean, coalesce: Boolean = false) {
        Remote.resetArtist()
        Remote.artist(app, http)
        val lib = lib(listOf("Thumb${System.nanoTime()}"))
        val dir = kotlin.io.path.createTempDirectory("art").toFile()
        val track = lib.tracks.first().id
        if (artFirst) albumArt(dir, lib, track)
        val s = sim(lib, faults, artDir = dir)
        val c = link(s)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.track == track }
        s.device.startCard()
        waitFor { if (coalesce) lines.contains("card gate busy") else Remote.device.cardDown }
        Remote.wantArt(Thumbs.MEDIA, track, MediaLink.ART_PX, Remote.device.playback.state.album)
        waitFor { lines.any { "rx ART_GET" in it } }
        settle(500)
        if (!artFirst) albumArt(dir, lib, track)
        assertEquals(!coalesce, Remote.device.cardDown)
        assertEquals(null, Remote.mediaArt(track, MediaLink.ART_PX))
        waitFor(15_000) { Remote.mediaArt(track, MediaLink.ART_PX) != null }
        assertTrue(index("card gate ready") < lines.indexOfLast { "rx ART_GET" in it })
        c.destroy()
    }

    @Test
    fun aThumbNotFoundWhileTheCardIsDownIsAskedAgainAtTheReadyEdge() = thumbAfterTheRun(listOf("card-run=4000+0@manual"), artFirst = false)

    @Test
    fun aThumbRefusedForTheCardIsAskedAgainAtTheReadyEdge() = thumbAfterTheRun(listOf("card-run=4000+0@manual", "fail=ART_GET:14:1"), artFirst = true)

    @Test
    fun aThumbRefusedInACoalescedRunIsAskedAgainAtTheFirstReadyState() = thumbAfterTheRun(listOf("card-run=4000+0@manual", "card-coalesce", "fail=ART_GET:14:1"), artFirst = true, coalesce = true)
}
