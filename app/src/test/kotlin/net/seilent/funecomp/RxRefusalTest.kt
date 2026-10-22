package net.seilent.funecomp

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Looper
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.PhoneLine
import net.seilent.funecomp.sim.Sim
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
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RxRefusalTest {
    private val sims = ArrayList<Sim>()
    private val logs = CopyOnWriteArrayList<String>()
    private val toasts = CopyOnWriteArrayList<Pair<String, String>>()
    private val app get() = RuntimeEnvironment.getApplication()
    private val idol = PhoneLine("Idol", "YOASOBI", "THE BOOK 3", 213_000, 0, true)

    @Before
    fun setUp() {
        scrobblesOff(app)
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        MediaLink.controls(app, false)
        Remote.artist(app, Http { _, _ -> Response(404, ByteArray(0)) })
        Lyrics.init(app, Http { _, _ -> Response(404, ByteArray(0)) })
        Remote.refusals({ line, title -> toasts += line to title })
    }

    @After
    fun tearDown() {
        Remote.refusals(null)
        Remote.stop()
        Lyrics.reset()
        sims.forEach { it.stop() }
        scrobblesReset()
    }

    private fun waitFor(ms: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out: $toasts ${logs.filter { " rx " in it || " tx " in it }.takeLast(6)}" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun link(lines: List<PhoneLine>, receiver: Boolean = true, faults: List<String> = emptyList()): org.robolectric.android.controller.ServiceController<LinkService> {
        val s = Sim(Options(Board.of("v1"), Faults(faults), log = { logs += it }, phone = lines, receiver = receiver))
        sims += s
        val c = Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, "tcp:127.0.0.1:${s.listen(0)}")).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.queueRev != null }
        Remote.queuePage(0)
        waitFor { Remote.device.queue.rev != null }
        return c
    }

    private fun answered(msg: String, n: Int = 1) = waitFor { logs.count { " tx $msg " in it } >= n }

    private fun quiet() {
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun libraryPlayPlayAtAndTheShuffleRingShowTheReceiverToast() {
        val c = link(listOf(idol))
        Remote.play(Enums.View.songs, "", 0)
        waitFor { toasts.size == 1 }
        Remote.queueOp(Enums.QueueCmd.playAt, slot = 1)
        waitFor { toasts.size == 2 }
        Remote.action(Enums.ActionCmd.shuffleLibrary)
        waitFor { toasts.size == 3 }
        assertEquals(List(3) { Remote.RECEIVER to "" }, toasts.toList())
        assertEquals(Enums.Source.phone, Remote.device.playback.state.source)
        c.destroy()
    }

    @Test
    fun aPlayWithNothingPlayingShowsTheToast() {
        val c = link(emptyList())
        Remote.transport(Enums.TransportCmd.toggle)
        waitFor { toasts.size == 1 }
        Remote.transport(Enums.TransportCmd.play)
        waitFor { toasts.size == 2 }
        assertEquals(List(2) { Remote.RECEIVER to "" }, toasts.toList())
        c.destroy()
    }

    @Test
    fun aRefusalWithNoTextShowsNothing() {
        val c = link(listOf(idol))
        Remote.transport(Enums.TransportCmd.seek, seekMs = 10_000)
        answered("TRANSPORT")
        Remote.transport(Enums.TransportCmd.shuffle, on = true)
        answered("TRANSPORT", 2)
        quiet()
        assertTrue(toasts.isEmpty())
        assertTrue(logs.any { " tx TRANSPORT flags=0x03 " in it })
        c.destroy()
    }

    @Test
    fun withoutTheReceiverPlayPlaysAsBefore() {
        val c = link(emptyList(), receiver = false)
        val track = Remote.device.playback.state.track
        Remote.play(null, null, 0, tracks = listOf(Library.synthetic().tracks[2].id))
        waitFor { Remote.device.playback.state.playing == true && Remote.device.playback.state.track != track }
        Remote.action(Enums.ActionCmd.shuffleLibrary)
        answered("ACTION")
        quiet()
        assertTrue(toasts.isEmpty())
        c.destroy()
    }

    @Test
    fun noSinkNoToast() {
        Remote.refusals(null)
        val c = link(listOf(idol))
        Remote.play(Enums.View.songs, "", 0)
        answered("PLAY")
        quiet()
        assertTrue(toasts.isEmpty())
        c.destroy()
    }

    @Test
    fun aRefusalWithAnotherTextShowsNothing() {
        val c = link(emptyList(), receiver = false, faults = listOf("refuse=PLAY"))
        Remote.play(Enums.View.songs, "", 0)
        answered("PLAY")
        quiet()
        assertTrue(toasts.isEmpty())
        assertTrue(logs.any { " tx PLAY flags=0x03 " in it })
        c.destroy()
    }

    @Test
    fun anOlderSinkLettingGoKeepsTheCurrentOne() {
        val old: (String, String) -> Unit = { line, _ -> toasts += "old" to line }
        val now: (String, String) -> Unit = { line, title -> toasts += line to title }
        Remote.refusals(old)
        Remote.refusals(now)
        Remote.refusals(null, old)
        val c = link(listOf(idol))
        Remote.play(Enums.View.songs, "", 0)
        waitFor { toasts.size == 1 }
        assertEquals(listOf(Remote.RECEIVER to ""), toasts.toList())
        Remote.refusals(null, now)
        Remote.play(Enums.View.songs, "", 0)
        answered("PLAY", 2)
        quiet()
        assertEquals(1, toasts.size)
        c.destroy()
    }
}
