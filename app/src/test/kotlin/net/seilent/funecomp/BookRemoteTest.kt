package net.seilent.funecomp

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.state.CardView
import net.seilent.funecomp.ui.Phase
import net.seilent.funekit.artist.Http
import net.seilent.funekit.artist.Response
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BookRemoteTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val sims = ArrayList<Sim>()
    private var service: ServiceController<LinkService>? = null
    private val lines = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        scrobblesOff(app)
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        MediaLink.controls(app, false)
        app.getSharedPreferences(Main.DEVICE_PREFS, Context.MODE_PRIVATE).edit().putBoolean("batteryAsked", true).commit()
        Remote.artist(app, Http { _, _ -> Response(404, ByteArray(0)) })
        Lyrics.init(app, Http { _, _ -> Response(404, ByteArray(0)) })
    }

    @After
    fun tearDown() {
        Remote.refusals(null)
        service?.destroy()
        Remote.stop()
        sims.forEach { it.stop() }
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

    private fun quiet(ms: Long = 500) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    private fun live(vararg faults: String) {
        lines.clear()
        val s = Sim(Options(Board.of("v1"), Faults(faults.toList()), log = { lines += it }))
        sims += s
        val spec = "tcp:127.0.0.1:${s.listen(0)}"
        app.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE).edit().putString(Main.EXTRA_LINK, spec).commit()
        service = Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, spec)).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.seen && sent("DRAFT") == 1 }
    }

    private fun sent(type: String) = lines.count { " rx $type " in it }

    @Test
    fun oneSkipFliesTheTapsBehindASilentOneWaitAndANewSessionSendsAtOnce() {
        live("bookplay", "mute=TRANSPORT:1")
        assertTrue(Remote.device.books)
        Remote.skip(-30_000)
        waitFor { sent("TRANSPORT") == 1 }
        Remote.skip(-30_000)
        Remote.skip(-30_000)
        quiet()
        assertEquals(1, sent("TRANSPORT"))
        service?.destroy()
        Remote.stop()
        waitFor { Remote.phase != Phase.LIVE }
        live("bookplay")
        Remote.skip(30_000)
        waitFor { sent("TRANSPORT") == 1 }
        quiet()
        assertEquals(1, sent("TRANSPORT"))
    }

    @Test
    fun anAnsweredSkipSendsTheTapsSummedBehindIt() {
        live("bookplay")
        Remote.skip(30_000)
        Remote.skip(30_000)
        Remote.skip(30_000)
        repeat(2) {
            val answered = AtomicBoolean()
            Remote.volumePercent(50) { answered.set(true) }
            waitFor { answered.get() }
        }
        assertEquals(2, sent("TRANSPORT"))
    }

    @Test
    fun aRefusedSkipDropsTheTapsBehindItWithOneUsbDacToast() {
        live("bookplay", "usbdac")
        val toasts = CopyOnWriteArrayList<String>()
        Remote.refusals({ line, _ -> toasts += line })
        Remote.skip(30_000)
        Remote.skip(30_000)
        Remote.skip(30_000)
        repeat(2) {
            val answered = AtomicBoolean()
            Remote.volumePercent(50) { answered.set(true) }
            waitFor { answered.get() }
        }
        assertEquals(1, sent("TRANSPORT"))
        assertEquals(listOf(Remote.USB_DAC), toasts.toList())
    }

    @Test
    fun theLatestSpeedPickWinsBeforeItsStateComesBack() {
        live("bookplay", "mute=TRANSPORT:1")
        val codes = ArrayList<Int?>()
        Remote.speed(125) { code, _ -> codes += code }
        waitFor { sent("TRANSPORT") == 1 }
        Remote.speed(150) { code, _ -> codes += code }
        waitFor { sent("TRANSPORT") == 2 }
        waitFor { codes.size == 1 }
        assertEquals(listOf<Int?>(null), codes)
    }

    @Test
    fun aPickOfTheAppliedSpeedSendsNothing() {
        live("bookplay")
        val done = ArrayList<Pair<Int?, String?>>()
        Remote.speed(150) { code, text -> done += code to text }
        quiet()
        assertEquals(listOf<Pair<Int?, String?>>(null to null), done)
        assertEquals(0, sent("TRANSPORT"))
    }

    @Test
    fun aCardDownSpeedIsRefusedBeforeTheSend() {
        live("bookplay", "card-seq=busy:60000,ready")
        waitFor { Remote.device.cardDown }
        val toasts = CopyOnWriteArrayList<String>()
        Remote.refusals({ line, _ -> toasts += line })
        val done = ArrayList<Int?>()
        Remote.speed(125) { code, _ -> done += code }
        quiet()
        assertEquals(listOf<Int?>(-1), done)
        assertEquals(listOf(CardView.REFUSED), toasts.toList())
        assertEquals(0, sent("TRANSPORT"))
    }

    @Test
    fun endOfTrackSendsOnceAndAfterAnUnansweredOffSendsAgain() {
        live("bookplay")
        val done = ArrayList<Int?>()
        Remote.endOfTrack { code, _ -> done += code }
        waitFor { Remote.device.playback.state.sleepEnd == true }
        assertEquals(2, sent("DRAFT"))
        Remote.endOfTrack { code, _ -> done += code }
        quiet()
        assertEquals(2, sent("DRAFT"))
        assertEquals(listOf<Int?>(null, null), done)
    }

    @Test
    fun anOffWithNoReplyThenEndOfTrackSendsEndOfTrack() {
        live("bookplay", "mute=DRAFT:3")
        Remote.endOfTrack()
        Remote.draft(Enums.DraftKind.sleep, minutes = 0)
        Remote.endOfTrack()
        waitFor { sent("DRAFT") == 4 }
    }

    @Test
    fun withoutBooksSkipSpeedAndEndOfTrackSendNothing() {
        live("bookplay", "nobooks")
        val done = ArrayList<Int?>()
        Remote.skip(30_000)
        Remote.speed(125) { code, _ -> done += code }
        Remote.endOfTrack { code, _ -> done += code }
        quiet()
        assertEquals(listOf<Int?>(-1, -1), done)
        assertEquals(0, sent("TRANSPORT"))
        assertEquals(1, sent("DRAFT"))
    }
}
