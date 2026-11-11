package net.seilent.funecomp

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.link.Session
import net.seilent.funecomp.proto.Lib
import net.seilent.funecomp.proto.PageInfo
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.state.LibKey
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
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibGapLinkTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val sims = ArrayList<Sim>()
    private val faults = ArrayList<Faults>()
    private var service: ServiceController<LinkService>? = null
    private val songs = Enums.View.songs
    private val artists = Enums.View.artists

    @Before
    fun setUp() {
        scrobblesOff(app)
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        MediaLink.controls(app, false)
        app.getSharedPreferences(Main.DEVICE_PREFS, Context.MODE_PRIVATE).edit().putBoolean("batteryAsked", true).commit()
        Remote.artist(app, Http { _, _ -> Response(404, ByteArray(0)) })
        Lyrics.init(app, Http { _, _ -> Response(404, ByteArray(0)) })
        Remote.shown(true)
    }

    @After
    fun tearDown() {
        faults.forEach { it.libOkHold?.countDown() }
        service?.destroy()
        Remote.stop()
        sims.forEach { it.stop() }
        Remote.shown(false)
        scrobblesReset()
    }

    private fun waitFor(ms: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun library(n: Int) = Library((1..n).map { Library.track("Artist", "Album", "Song %03d ".format(it) + "x".repeat(240), 1, 200_000) })

    private fun live(vararg fault: String): Pair<Sim, Faults> {
        val f = Faults(fault.toList())
        faults += f
        val s = Sim(Options(Board.of("v1"), f, btRole = Enums.BtRole.headphones), library(300))
        sims += s
        val spec = "tcp:127.0.0.1:${s.listen(0)}"
        app.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE).edit().putString(Main.EXTRA_LINK, spec).commit()
        service = Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, spec)).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE }
        return s to f
    }

    private fun rows() = Remote.device.lib.pages[LibKey(songs, "")]?.rows.orEmpty()

    private fun block() = (0L until 100L).all { it in rows() }

    private fun songAsks(s: Sim) = s.device.libAsks.filter { it.view == songs }

    private fun asks(s: Sim) = songAsks(s).map { it.offset to it.count }

    private fun barrier() {
        val done = AtomicBoolean(false)
        Remote.set("scrobble", "card") { done.set(true) }
        waitFor { done.get() }
    }

    private fun backoff(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun sweep(rev: Long?): AtomicReference<Session.Result> {
        val session = Remote.session()!!
        val r = AtomicReference<Session.Result>()
        Thread { r.set(Remote.libPage(session, 0, rev)) }.start()
        return r
    }

    @Test
    fun aCutAnswerIsLoadedFromItsFirstMissingRow() {
        val (s, _) = live("lib-cut=8192:1")
        Remote.libGet(songs, "", 0)
        waitFor { block() }
        barrier()
        val a = asks(s)
        assertEquals(2, a.size)
        val k = a[1].first
        assertTrue(k in 1L..99L)
        assertEquals(listOf(0L to 100L, k to 100 - k), a)
    }

    @Test
    fun aLibPageAnswerInFlightHoldsTheSameLibGet() {
        val (s, f) = live("lib-ok-hold")
        val r = sweep(Remote.device.lib.dataRev)
        waitFor { Remote.device.lib.pages[LibKey(artists, "")] != null }
        Remote.libGet(artists, "", 0)
        f.libOkHold!!.countDown()
        waitFor { r.get() != null }
        assertTrue(r.get() is Session.Result.Ok)
        barrier()
        assertEquals("${s.device.libAsks}", 1, s.device.libAsks.count { it.view == artists })
        Remote.libGet(artists, "", 0)
        barrier()
        assertEquals("${s.device.libAsks}", 2, s.device.libAsks.count { it.view == artists })
    }

    @Test
    fun aMidStreamAskOfTheSameBlockSendsNothing() {
        val (s, f) = live("lib-ok-hold", "lib-cut=8192:1")
        Remote.libGet(songs, "", 0)
        waitFor { rows().isNotEmpty() }
        val k = (0L until 100L).first { it !in rows() }
        Remote.libGet(songs, "", k)
        f.libOkHold!!.countDown()
        waitFor { block() }
        barrier()
        assertEquals("${s.device.libAsks}", listOf(0L to 100L, k to 100 - k), asks(s))
    }

    @Test
    fun aRevChangeBeforeTheOkSkipsTheReAsk() {
        val (s, f) = live("lib-ok-hold", "lib-cut=8192:2", "lib-snap-move")
        val snap = Remote.device.lib.snap
        Remote.libGet(songs, "", 0)
        waitFor { Remote.device.lib.snap != snap }
        val l = s.device.lib
        s.current!!.push(Msg.LIB, Lib(l.rev, songs, "", PageInfo(0, 300, true), l.rows(songs, "")!!.take(10), l.snapRev).encode())
        waitFor { rows().size == 10 }
        f.libOkHold!!.countDown()
        barrier()
        assertEquals("${s.device.libAsks}", listOf(0L to 100L), asks(s))
        Remote.libGet(songs, "", 10)
        waitFor { block() }
        barrier()
        val a = asks(s)
        assertEquals(3, a.size)
        assertEquals(10L to 90L, a[1])
        val k = a[2].first
        assertEquals(k to 100 - k, a[2])
    }

    @Test
    fun aBusyReAskIsRetriedOnceAfterABackoff() {
        val (s, _) = live("lib-cut=8192:1", "lib-busy-after=1:1")
        Remote.libGet(songs, "", 0)
        waitFor { songAsks(s).size == 2 }
        barrier()
        assertEquals("${s.device.libAsks}", listOf(null, Err.busy), songAsks(s).map { it.result })
        val k = asks(s)[1].first
        val listing = sweep(Remote.device.lib.dataRev)
        waitFor { listing.get() != null }
        assertEquals("${s.device.libAsks}", 1, s.device.libAsks.count { it.view == artists })
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        val left = Remote.libRetryAt - SystemClock.uptimeMillis()
        assertTrue("$left", left in 1L..Proto.backoffMs.first())
        backoff(left - 1)
        barrier()
        assertEquals("${s.device.libAsks}", 2, songAsks(s).size)
        backoff(1)
        waitFor { block() }
        assertEquals("${s.device.libAsks}", listOf(0L to 100L, k to 100 - k, k to 100 - k), asks(s))
    }

    @Test
    fun aBusyReAskIsRetriedOnceAndThenTheChainEnds() {
        val (s, _) = live("lib-cut=8192:1", "lib-busy-after=1:2")
        Remote.libGet(songs, "", 0)
        waitFor { songAsks(s).size == 2 }
        barrier()
        backoff(1000)
        waitFor { songAsks(s).size == 3 }
        barrier()
        backoff(1000)
        barrier()
        assertEquals("${s.device.libAsks}", listOf(null, Err.busy, Err.busy), songAsks(s).map { it.result })
        val k = (0L until 100L).first { it !in rows() }
        Remote.libGet(songs, "", k)
        waitFor { songAsks(s).size == 4 }
        assertEquals("${s.device.libAsks}", k to 100 - k, asks(s)[3])
    }

    @Test
    fun aBusyRetryIsDroppedWhenTheRevMovesInTheBackoff() {
        val (s, _) = live("lib-cut=8192:1", "lib-busy-after=1:1")
        Remote.libGet(songs, "", 0)
        waitFor { songAsks(s).size == 2 }
        barrier()
        val snap = Remote.device.lib.snap
        s.rescan(library(300))
        waitFor { Remote.device.lib.snap != snap }
        backoff(1000)
        barrier()
        assertEquals("${s.device.libAsks}", 2, songAsks(s).size)
        Remote.libGet(songs, "", 0)
        waitFor { songAsks(s).size == 3 }
        assertEquals("${s.device.libAsks}", 0L to 100L, asks(s)[2])
    }

    @Test
    fun aHelloInTheBackoffDropsTheRetry() {
        val (s, _) = live("lib-cut=8192:1", "lib-busy-after=1:1")
        Remote.libGet(songs, "", 0)
        waitFor { songAsks(s).size == 2 }
        barrier()
        val old = Remote.session()
        s.current!!.close("test")
        waitFor { Remote.phase != Phase.LIVE }
        Remote.kick("test")
        waitFor { Remote.session().let { it != null && it !== old } && Remote.phase == Phase.LIVE }
        barrier()
        backoff(1000)
        barrier()
        assertEquals("${s.device.libAsks}", 2, songAsks(s).size)
    }

    @Test
    fun aKickClearsTheLibRetryTime() {
        val (s, _) = live("lib-cut=8192:1", "lib-busy-after=1:1")
        Remote.libGet(songs, "", 0)
        waitFor { songAsks(s).size == 2 }
        barrier()
        assertTrue("${Remote.libRetryAt}", Remote.libRetryAt != 0L)
        val old = Remote.session()
        s.current!!.close("test")
        waitFor { Remote.phase != Phase.LIVE }
        Remote.kick("test")
        waitFor { Remote.session().let { it != null && it !== old } && Remote.phase == Phase.LIVE }
        assertEquals(0L, Remote.libRetryAt)
    }

    @Test
    fun aStopClearsTheLibRetryTime() {
        val (s, _) = live("lib-cut=8192:1", "lib-busy-after=1:1")
        Remote.libGet(songs, "", 0)
        waitFor { songAsks(s).size == 2 }
        barrier()
        assertTrue("${Remote.libRetryAt}", Remote.libRetryAt != 0L)
        Remote.stop()
        assertEquals(0L, Remote.libRetryAt)
    }

    @Test
    fun aRetryFromAnEndedSessionSendsNothingBesideTheNextChain() {
        val (s, f) = live("lib-cut=8192:1", "lib-busy-after=1:1")
        Remote.libGet(songs, "", 0)
        waitFor { songAsks(s).size == 2 }
        barrier()
        val old = Remote.session()
        s.current!!.close("test")
        waitFor { Remote.phase != Phase.LIVE }
        Remote.kick("test")
        waitFor { Remote.session().let { it != null && it !== old } && Remote.phase == Phase.LIVE }
        barrier()
        f.libOkHold = CountDownLatch(1)
        Remote.libGet(songs, "", 0)
        waitFor { rows().isNotEmpty() }
        backoff(1000)
        f.libOkHold!!.countDown()
        waitFor { block() }
        barrier()
        assertEquals("${s.device.libAsks}", listOf(0L to 100L, asks(s)[1].first to 100 - asks(s)[1].first, 0L to 100L), asks(s))
    }

    @Test
    fun anAskWithNoListEndsAtItsBlock() {
        val (s, _) = live()
        Remote.libGet(songs, "", 150)
        waitFor { songAsks(s).size == 1 }
        barrier()
        assertEquals("${s.device.libAsks}", listOf(150L to 50L), asks(s))
        Remote.libGet(songs, "", 300)
        barrier()
        assertEquals("${s.device.libAsks}", listOf(150L to 50L), asks(s))
    }

    @Test
    fun libPageForgetsItsKeyAtOk() {
        val (s, _) = live()
        val r = sweep(Remote.device.lib.dataRev)
        waitFor { r.get() != null }
        assertTrue(r.get() is Session.Result.Ok)
        assertEquals("${s.device.libAsks}", 1, s.device.libAsks.count { it.view == artists })
        Remote.libGet(artists, "", 0)
        barrier()
        assertEquals("${s.device.libAsks}", 2, s.device.libAsks.count { it.view == artists })
    }

    @Test
    fun aSessionEndClearsTheBlockMarks() {
        val (s, _) = live("lib-cut=8192:1", "lib-busy-after=1:1")
        Remote.libGet(songs, "", 0)
        waitFor { songAsks(s).size == 2 }
        barrier()
        val k = asks(s)[1].first
        val old = Remote.session()
        s.current!!.close("test")
        waitFor { Remote.phase != Phase.LIVE }
        Remote.kick("test")
        waitFor { Remote.session().let { it != null && it !== old } && Remote.phase == Phase.LIVE }
        barrier()
        Remote.libGet(songs, "", k)
        barrier()
        assertEquals("${s.device.libAsks}", 3, songAsks(s).size)
        assertEquals("${s.device.libAsks}", k to 100 - k, asks(s)[2])
        assertEquals("${s.device.libAsks}", null, songAsks(s)[2].result)
    }
}
