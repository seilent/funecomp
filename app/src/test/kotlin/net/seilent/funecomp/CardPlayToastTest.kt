package net.seilent.funecomp

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Looper
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.state.CardView
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
class CardPlayToastTest {
    private val sims = ArrayList<Sim>()
    private val logs = CopyOnWriteArrayList<String>()
    private val toasts = CopyOnWriteArrayList<Pair<String, String>>()
    private val app get() = RuntimeEnvironment.getApplication()
    private val lib = Library.synthetic()

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

    private fun waitFor(ms: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out: $toasts ${logs.filter { " rx " in it || " tx " in it || "card" in it }.takeLast(8)}" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun link(faults: List<String>, minor: Int = Proto.minor): org.robolectric.android.controller.ServiceController<LinkService> {
        val s = Sim(Options(Board.of("v1"), Faults(faults), minor = minor, log = { logs += it }), lib)
        sims += s
        val c = Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, "tcp:127.0.0.1:${s.listen(0)}")).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.queueRev != null }
        Remote.queuePage(0)
        waitFor { Remote.device.queue.rev != null }
        return c
    }

    private fun answered(msg: String, n: Int) = waitFor { logs.count { " tx $msg " in it } >= n }

    private fun refused(msg: String) = logs.count { " tx $msg flags=0x03 " in it }

    private fun quiet() {
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun afterReady(edges: Int = 1) = waitFor { Remote.device.card.epoch >= edges && !Remote.device.cardDown }

    private fun plays() {
        Remote.play(Enums.View.songs, "", 0)
        answered("PLAY", 1)
        Remote.queueOp(Enums.QueueCmd.playAt, slot = 1)
        answered("QUEUE_OP", 2)
        Remote.queueOp(Enums.QueueCmd.playNext, tracks = listOf(lib.tracks[2].id))
        answered("QUEUE_OP", 3)
        Remote.action(Enums.ActionCmd.shuffleLibrary)
        answered("ACTION", 1)
        quiet()
    }

    private fun late14(msg: String, send: () -> Unit) {
        val c = link(listOf("card-run=1000+500@hello", "late-state=500", "play-lag=20000"))
        afterReady(2)
        val before = refused(msg)
        assertTrue(!Remote.device.cardDown)
        send()
        waitFor { refused(msg) > before && toasts.isNotEmpty() }
        quiet()
        assertEquals(1, refused(msg) - before)
        val ready = logs.indexOfFirst { it.startsWith("card state ready") }
        val busy = logs.withIndex().firstOrNull { it.index > ready && it.value.startsWith("card state busy") }?.index ?: -1
        val no = logs.indexOfLast { " tx $msg flags=0x03 " in it }
        assertTrue("$ready $busy $no", ready in 0 until busy && busy < no)
        assertEquals(listOf(CardView.REFUSED to ""), toasts.toList())
        c.destroy()
    }

    @Test
    fun aLate14OnAPlayAfterTheReadyEdgeToastsFromThe14() = late14("PLAY") { Remote.play(Enums.View.songs, "", 0) }

    @Test
    fun aLate14OnAPlayAtAfterTheReadyEdgeToastsFromThe14() = late14("QUEUE_OP") { Remote.queueOp(Enums.QueueCmd.playAt, slot = 1) }

    @Test
    fun aLate14OnAPlayNextAfterTheReadyEdgeToastsFromThe14() = late14("QUEUE_OP") { Remote.queueOp(Enums.QueueCmd.playNext, tracks = listOf(lib.tracks[2].id)) }

    @Test
    fun aLate14OnAShuffleAfterTheReadyEdgeToastsFromThe14() = late14("ACTION") { Remote.action(Enums.ActionCmd.shuffleLibrary) }

    @Test
    fun anOldImageBusyAfterTheReadyEdgeStaysSilent() {
        val c = link(listOf("card-run=1000+500@hello", "card-device=old", "play-lag=20000"))
        afterReady()
        val pages = refused("QUEUE_OP")
        plays()
        assertEquals(1, refused("PLAY"))
        assertEquals(2, refused("QUEUE_OP") - pages)
        assertEquals(1, refused("ACTION"))
        assertTrue(toasts.isEmpty())
        c.destroy()
    }

    @Test
    fun aMinor14CardTextOnBusyStaysSilent() {
        val c = link(listOf("card-run=1000+500@hello", "play-lag=20000"), minor = 14)
        afterReady()
        val pages = refused("QUEUE_OP")
        plays()
        assertEquals(1, refused("PLAY"))
        assertEquals(2, refused("QUEUE_OP") - pages)
        assertEquals(1, refused("ACTION"))
        assertTrue(toasts.isEmpty())
        c.destroy()
    }

    @Test
    fun aCardRefusalOfOtherCommandsShowsNoPlayToast() {
        val c = link(listOf("card-run=30000+0@hello"))
        waitFor { Remote.device.cardDown }
        Remote.queueOp(Enums.QueueCmd.remove, slot = 1)
        answered("QUEUE_OP", 2)
        Remote.queueOp(Enums.QueueCmd.move, slot = 1, to = 2)
        answered("QUEUE_OP", 3)
        Remote.queueOp(Enums.QueueCmd.clear)
        answered("QUEUE_OP", 4)
        Remote.action(Enums.ActionCmd.favorite, Remote.device.playback.state.track)
        answered("ACTION", 1)
        Remote.action(Enums.ActionCmd.rescan)
        answered("ACTION", 2)
        quiet()
        assertEquals(3, refused("QUEUE_OP"))
        assertEquals(1, refused("ACTION"))
        assertTrue(toasts.isEmpty())
        c.destroy()
    }
}
