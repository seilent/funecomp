package net.seilent.funecomp

import net.seilent.funecomp.link.Session
import net.seilent.funecomp.link.TcpTransport
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.state.LyricsFetch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

class DapLyricsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val lib = Library.synthetic()
    private val track = lib.tracks.first().id
    private val id = java.lang.Long.toUnsignedString(track)
    private val logs: MutableList<Pair<Long, String>> = java.util.concurrent.CopyOnWriteArrayList()
    private var onLog: (String) -> Unit = {}
    private val sims = ArrayList<Sim>()
    private val sessions = ArrayList<Session>()

    @After
    fun tearDown() {
        sessions.forEach { it.close("done") }
        sims.forEach { it.stop() }
    }

    private fun now() = System.nanoTime() / 1_000_000

    private fun lrc(text: String, t: Long = track) = File(tmp.root.resolve("lrc").apply { mkdirs() }, "${java.lang.Long.toUnsignedString(t)}.lrc").writeText(text)

    private fun lines(n: Int, tag: String = "x", len: Int = 64) = (0 until n).joinToString("") { "[%02d:%02d.00]%s\n".format(it / 60, it % 60, (tag + it).padEnd(len, '.').take(len)) }

    private fun stamped(n: Int, tag: String) = (0 until n).joinToString("") { i ->
        (0 until 3).joinToString("") { k -> "[%02d:%02d.00]".format((i * 3 + k) / 60, (i * 3 + k) % 60) } + (tag + i).padEnd(40, '.') + "\n"
    }

    private class Rig(val sim: Sim) {
        lateinit var session: Session
        lateinit var lyrics: DapLyrics
        @Volatile var state = DeviceState()
        @Volatile var override: DeviceState? = null
    }

    private fun rig(minor: Int = Proto.minor, faults: List<String> = emptyList(), features: Long? = null): Rig {
        val f = Faults(faults)
        val sim = Sim(Options(Board.of("v1"), f, minor = minor, lrcDir = tmp.root.resolve("lrc"), log = { s -> logs += now() to s; onLog(s) }), lib)
        if (features != null) f.features = features
        sims += sim
        val ready = CountDownLatch(1)
        val r = Rig(sim)
        val s = Session(TcpTransport("127.0.0.1", sim.listen(0)), object : Session.Listener {
            override fun onHello(hello: HelloReply) {
                r.state = r.state.withHello(hello)
                r.lyrics.session()
                ready.countDown()
            }

            override fun onPush(type: Int, body: Node) {
                r.state = r.state.reduce(type, body, now())
                if (type == Msg.STATE) r.lyrics.poke()
            }
        }, Session.Config())
        r.session = s
        r.lyrics = DapLyrics({ s }, { r.override ?: r.state }, ::now)
        sessions += s
        Thread { s.run() }.apply { isDaemon = true }.start()
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        return r
    }

    private fun waitFor(what: String, ms: Long = 10_000, cond: () -> Boolean) {
        val end = now() + ms
        while (!cond()) {
            check(now() < end) { "timed out: $what ${synchronized(logs) { logs.map { it.second }.filter { "LYR" in it || "lyr" in it } }}" }
            Thread.sleep(20)
        }
    }

    private fun asks() = synchronized(logs) { logs.filter { " rx LYR_GET " in it.second } }

    private fun shown(r: Rig) = r.lyrics.show as? LyricsFetch.Show.Lines

    private fun quiet() = Thread.sleep(400)

    private fun neverTwoPending() = synchronized(logs) {
        var pending = 0
        for ((_, l) in logs) {
            if (" rx LYR_GET " in l) pending++
            if (" tx LYR_GET " in l) pending--
            assertTrue(l, pending <= 1)
        }
    }

    private fun seek(r: Rig) = r.session.call(Msg.TRANSPORT, Req.transport(Enums.TransportCmd.seek, seekMs = 1000))

    @Test
    fun thePlayingTrackIsFetchedOnceAndShown() {
        lrc(lines(3))
        val r = rig()
        quiet()
        assertEquals(0, asks().size)
        r.lyrics.shown(true)
        waitFor("lines") { shown(r) != null }
        assertEquals(3, shown(r)!!.copy.lines.size)
        assertEquals(track, shown(r)!!.track)
        seek(r)
        r.lyrics.poke()
        quiet()
        assertEquals(1, asks().size)
    }

    @Test
    fun anLrcPutBetweenPagesShowsOnlyTheNewText() {
        lrc(stamped(160, "old"))
        val r = rig()
        val text = stamped(160, "new").toByteArray()
        var fired = false
        onLog = { s ->
            if (!fired && s.startsWith("lyr get track=$id offset=0 ")) {
                fired = true
                r.sim.device.request(Msg.LRC_PUT, Node.decode("LRC_PUT", Req.lrcPut(track, lib.rev, text, CRC32().apply { update(text) }.value)), object : net.seilent.funecomp.sim.Out {
                    override fun push(type: Int, payload: ByteArray) {}
                })
            }
        }
        r.lyrics.shown(true)
        waitFor("restart") { synchronized(logs) { logs.any { it.second == "lyr restart track=$id" } } }
        waitFor("pages done") { asks().size >= 4 }
        quiet()
        assertEquals(null, shown(r))
        seek(r)
        waitFor("new lines") { shown(r) != null }
        assertTrue(shown(r)!!.copy.lines.all { it.text.startsWith("new") })
        neverTwoPending()
    }

    @Test
    fun busyIsRetriedOnTheBackoff() {
        lrc(lines(2))
        val r = rig(faults = listOf("busy=LYR_GET:2"))
        r.lyrics.shown(true)
        waitFor("lines") { shown(r) != null }
        val at = asks().map { it.first }
        assertEquals(3, at.size)
        assertTrue("${at[1] - at[0]}", at[1] - at[0] in 1000L..1800L)
        assertTrue("${at[2] - at[1]}", at[2] - at[1] in 2000L..2800L)
    }

    @Test
    fun notFoundShowsNoneAndAsksNoMore() {
        lrc(lines(2))
        val r = rig()
        r.sim.device.request(Msg.LRC_DEL, Node.decode("LRC_DEL", Req.lrcDel(track)), object : net.seilent.funecomp.sim.Out {
            override fun push(type: Int, payload: ByteArray) {}
        })
        r.lyrics.shown(true)
        waitFor("none") { r.lyrics.show == LyricsFetch.Show.None }
        r.lyrics.poke()
        r.lyrics.shown(false)
        r.lyrics.shown(true)
        quiet()
        assertEquals(1, asks().size)
    }

    @Test
    fun aStaleIdAsksOnceUntilSnapRevMoves() {
        lrc(lines(2))
        val r = rig()
        r.lyrics.shown(true)
        waitFor("lines") { shown(r) != null }
        val s = r.state.playback.state.copy(track = 424242, lyricsCrc = 1)
        r.override = r.state.withState(s, now())
        r.lyrics.poke()
        waitFor("stale ask") { asks().size == 2 }
        r.lyrics.poke()
        r.lyrics.poke()
        quiet()
        assertEquals(2, asks().size)
        assertEquals(LyricsFetch.Show.Blank, r.lyrics.show)
        r.override = r.state.withState(s.copy(snapRev = (s.snapRev ?: 0) + 1), now())
        r.lyrics.poke()
        waitFor("after snap") { asks().size == 3 }
    }

    @Test
    fun theGateIsBit11NotTheMinor() {
        lrc(lines(2))
        val old = rig(minor = 6)
        old.lyrics.shown(true)
        quiet()
        assertEquals(LyricsFetch.Show.Off, old.lyrics.show)
        assertEquals(0, asks().size)
        val masked = rig(features = Groups.all and Groups.mask("lyricsGet").inv())
        masked.lyrics.shown(true)
        quiet()
        assertEquals(LyricsFetch.Show.Off, masked.lyrics.show)
        assertEquals(0, asks().size)
        val forced = rig(minor = 6, features = Groups.all)
        forced.lyrics.shown(true)
        waitFor("lines") { shown(forced) != null }
    }

    @Test
    fun a1024LineFileTakesSevenPages() {
        lrc(lines(1024))
        val r = rig()
        r.lyrics.shown(true)
        waitFor("lines") { shown(r) != null }
        assertEquals(1024, shown(r)!!.copy.lines.size)
        assertEquals(7, asks().size)
        neverTwoPending()
    }

    @Test
    fun sixteenLinesOf4096BytesTakeEightPages() {
        lrc((0 until 16).joinToString("") { "[00:%02d.00]%s\n".format(it, "a".repeat(5000)) })
        val r = rig()
        r.lyrics.shown(true)
        waitFor("lines") { shown(r) != null }
        assertEquals(16, shown(r)!!.copy.lines.size)
        assertTrue(shown(r)!!.copy.lines.all { it.text.length == Proto.lyrLineMax })
        assertEquals(8, asks().size)
    }
}
