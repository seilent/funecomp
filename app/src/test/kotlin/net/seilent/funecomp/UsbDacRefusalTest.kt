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
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.ui.Phase
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UsbDacRefusalTest {
    private val sims = ArrayList<Sim>()
    private val logs = CopyOnWriteArrayList<String>()
    private val toasts = CopyOnWriteArrayList<Pair<String, String>>()
    private val app get() = RuntimeEnvironment.getApplication()

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

    private fun link(faults: List<String>): org.robolectric.android.controller.ServiceController<LinkService> {
        val s = Sim(Options(Board.of("v1"), Faults(faults), log = { logs += it }))
        sims += s
        val c = Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, "tcp:127.0.0.1:${s.listen(0)}")).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.queueRev != null }
        Remote.queuePage(0)
        waitFor { Remote.device.queue.rev != null }
        return c
    }

    private fun sent(msg: String) = logs.count { " tx $msg " in it }

    private fun answer(msg: String, send: () -> Unit) {
        val n = sent(msg)
        send()
        waitFor { sent(msg) > n }
    }

    private fun quiet() {
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun aLibraryPlayAQueueRowTheHomeRingAndNowPlayingPlayToastTheUsbDac() {
        val c = link(listOf("usbdac"))
        Remote.play(Enums.View.songs, "", 0)
        waitFor { toasts.size == 1 }
        Remote.queueOp(Enums.QueueCmd.playAt, slot = 1)
        waitFor { toasts.size == 2 }
        Remote.action(Enums.ActionCmd.shuffleLibrary)
        waitFor { toasts.size == 3 }
        Remote.transport(Enums.TransportCmd.toggle)
        waitFor { toasts.size == 4 }
        assertEquals(List(4) { Remote.USB_DAC to "" }, toasts.toList())
        c.destroy()
    }

    @Test
    fun anAddToNowPlayingShowsNoToast() {
        val c = link(listOf("usbdac"))
        var done = false
        var code: Int? = -1
        Remote.queueAdd(Enums.QueueCmd.add, null, null, listOf(Library.synthetic().tracks[2].id)) { _, r ->
            code = r
            done = true
        }
        waitFor { done }
        quiet()
        assertNull(code)
        assertTrue(toasts.isEmpty())
        c.destroy()
    }

    @Test
    fun withoutTheUsbDacEveryPlayIsTaken() {
        val c = link(emptyList())
        val track = Remote.device.playback.state.track
        Remote.play(null, null, 0, tracks = listOf(Library.synthetic().tracks[2].id))
        waitFor { Remote.device.playback.state.playing == true && Remote.device.playback.state.track != track }
        answer("QUEUE_OP") { Remote.queueOp(Enums.QueueCmd.playAt, slot = 0) }
        answer("ACTION") { Remote.action(Enums.ActionCmd.shuffleLibrary) }
        answer("TRANSPORT") { Remote.transport(Enums.TransportCmd.toggle) }
        quiet()
        assertTrue(toasts.isEmpty())
        assertTrue(logs.none { l -> listOf("QUEUE_OP", "ACTION", "TRANSPORT").any { " tx $it flags=0x03 " in l } })
        c.destroy()
    }
}
