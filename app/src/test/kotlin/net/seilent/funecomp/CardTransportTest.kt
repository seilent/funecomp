package net.seilent.funecomp

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Looper
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.PhoneLine
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.state.CardView
import net.seilent.funecomp.ui.Phase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CardTransportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val sims = ArrayList<Sim>()
    private val logs = CopyOnWriteArrayList<String>()
    private val toasts = CopyOnWriteArrayList<Pair<String, String>>()
    private val app get() = RuntimeEnvironment.getApplication()
    private val lib = Library.synthetic()
    private val card = CardView.REFUSED to ""

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

    private fun phone(playing: Boolean): List<PhoneLine> {
        val f = File(tmp.root, "phone.tsv")
        f.writeText("Phone Song\tPhone Artist\tPhone Album\t200000\t0\t${if (playing) 1 else 0}\tSePhone\t1\t3\n")
        return PhoneLine.load(f)
    }

    private fun link(faults: List<String>, receiver: Boolean = false, phone: List<PhoneLine> = emptyList()): Sim {
        val s = Sim(Options(Board.of("v1"), Faults(faults), minor = Proto.minor, receiver = receiver, phone = phone, log = { logs += it }), lib)
        sims += s
        Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, "tcp:127.0.0.1:${s.listen(0)}")).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.queueRev != null }
        return s
    }

    private fun sent(msg: String) = logs.count { " rx $msg " in it }

    private fun answered(msg: String, n: Int) = waitFor { logs.count { " tx $msg " in it } >= n }

    private fun refused(msg: String) = logs.count { " tx $msg flags=0x03 " in it }

    private fun busyBefore14(msg: String) {
        val all = logs.toList()
        val no = all.indexOfLast { " tx $msg flags=0x03 " in it }
        val ready = all.subList(0, no.coerceAtLeast(0)).indexOfLast { it.startsWith("card state ready") }
        val busy = all.subList(0, no.coerceAtLeast(0)).indexOfLast { it.startsWith("card state busy") }
        assertTrue("$ready $busy $no", ready in 0 until busy)
    }

    private fun quiet() {
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun refusable() {
        Remote.transport(Enums.TransportCmd.toggle)
        Remote.transport(Enums.TransportCmd.play)
        Remote.transport(Enums.TransportCmd.next)
        Remote.transport(Enums.TransportCmd.prev)
        Remote.transport(Enums.TransportCmd.seek, seekMs = 1000)
        Remote.transport(Enums.TransportCmd.shuffle, on = true)
    }

    @Test
    fun everyCardTransportCommandToastsAndSendsNothingWhileTheCardIsDown() {
        link(listOf("card-run=30000+0@hello"))
        waitFor { Remote.device.cardDown }
        refusable()
        quiet()
        assertEquals(0, sent("TRANSPORT"))
        assertEquals(List(6) { card }, toasts.toList())
        Remote.transport(Enums.TransportCmd.pause)
        Remote.transport(Enums.TransportCmd.repeat, repeat = Enums.Repeat.all)
        answered("TRANSPORT", 2)
        quiet()
        assertEquals(0, refused("TRANSPORT"))
        assertEquals(6, toasts.size)
    }

    @Test
    fun aToggleWhilePlayingIsSentAndPausesThenTheNextToggleIsRefused() {
        link(listOf("card-run=30000+0@play"))
        assertTrue(!Remote.device.cardDown)
        Remote.transport(Enums.TransportCmd.play)
        answered("TRANSPORT", 1)
        waitFor { Remote.device.playback.state.playing == true }
        waitFor { Remote.device.cardDown }
        Remote.transport(Enums.TransportCmd.toggle)
        answered("TRANSPORT", 2)
        waitFor { Remote.device.playback.state.playing == false }
        assertEquals(0, refused("TRANSPORT"))
        assertTrue(toasts.isEmpty())
        Remote.transport(Enums.TransportCmd.toggle)
        quiet()
        assertEquals(2, sent("TRANSPORT"))
        assertEquals(listOf(card), toasts.toList())
    }

    @Test
    fun aPhoneStreamTakesEveryTransportCommandWithNoCardToast() {
        link(listOf("card-run=30000+0@hello"), receiver = true, phone = phone(true))
        waitFor { Remote.device.cardDown && Remote.device.playback.state.source == Enums.Source.phone }
        refusable()
        answered("TRANSPORT", 6)
        quiet()
        assertEquals(6, sent("TRANSPORT"))
        assertTrue("$toasts", toasts.none { it == card })
    }

    @Test
    fun theReceiverPageWithAnIdlePhonePlayerSendsNextAndPlayToThePhoneWithNoToast() {
        link(listOf("card-run=30000+0@hello", "phone-suspend=300"), receiver = true, phone = phone(false))
        waitFor { Remote.device.cardDown && Remote.device.playback.state.receiver == true && Remote.device.playback.state.source != Enums.Source.phone }
        Remote.transport(Enums.TransportCmd.next)
        answered("TRANSPORT", 1)
        Remote.transport(Enums.TransportCmd.play)
        answered("TRANSPORT", 2)
        waitFor { Remote.device.playback.state.source == Enums.Source.phone }
        quiet()
        assertEquals(0, refused("TRANSPORT"))
        assertTrue("$toasts", toasts.isEmpty())
    }

    @Test
    fun theReceiverPageWithNoPhonePlayerGetsTheDevice14OnNextAndTheReceiverRefusalOnPlay() {
        link(listOf("card-run=30000+0@hello"), receiver = true)
        waitFor { Remote.device.cardDown && Remote.device.playback.state.receiver == true }
        Remote.transport(Enums.TransportCmd.next)
        answered("TRANSPORT", 1)
        waitFor { toasts.isNotEmpty() }
        Remote.transport(Enums.TransportCmd.play)
        answered("TRANSPORT", 2)
        waitFor { toasts.size == 2 }
        quiet()
        assertEquals(2, refused("TRANSPORT"))
        assertEquals(listOf(card, Remote.RECEIVER to ""), toasts.toList())
    }

    @Test
    fun aLate14OnANextAfterTheReadyEdgeToastsOnce() {
        link(listOf("card-run=1000+500@hello", "late-state=500", "play-lag=20000"))
        waitFor { Remote.device.card.epoch >= 2 && !Remote.device.cardDown }
        Remote.transport(Enums.TransportCmd.next)
        answered("TRANSPORT", 1)
        waitFor { toasts.isNotEmpty() }
        quiet()
        assertEquals(1, refused("TRANSPORT"))
        busyBefore14("TRANSPORT")
        assertEquals(listOf(card), toasts.toList())
    }

    @Test
    fun withNoSinkARefusedCommandSendsNothingAndShowsNothing() {
        link(listOf("card-run=30000+0@hello"))
        waitFor { Remote.device.cardDown }
        Remote.refusals(null)
        Remote.transport(Enums.TransportCmd.play)
        Remote.transport(Enums.TransportCmd.next)
        quiet()
        assertEquals(0, sent("TRANSPORT"))
        assertTrue(toasts.isEmpty())
    }

    @Test
    fun aRescanToastsAndSendsNothingWhileDownAndALate14ToastsAfterReady() {
        val s = link(listOf("card-run=2000+500@manual", "late-state=500", "play-lag=20000"))
        s.device.startCard()
        waitFor { Remote.device.cardDown }
        var got: Int? = -1
        Remote.action(Enums.ActionCmd.rescan) { code, _ -> got = code }
        quiet()
        assertEquals(Err.noCard, got)
        assertEquals(0, sent("ACTION"))
        assertEquals(listOf(card), toasts.toList())
        waitFor { Remote.device.card.epoch >= 1 && !Remote.device.cardDown }
        Remote.action(Enums.ActionCmd.rescan)
        answered("ACTION", 1)
        waitFor { toasts.size == 2 }
        assertEquals(1, refused("ACTION"))
        busyBefore14("ACTION")
    }
}
