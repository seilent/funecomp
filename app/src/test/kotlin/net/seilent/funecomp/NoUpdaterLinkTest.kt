package net.seilent.funecomp

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.BatteryManager
import android.os.Looper
import net.seilent.funecomp.artist.Firmware
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.UNSIGNED_RELEASE
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
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoUpdaterLinkTest {
    private val sims = ArrayList<Sim>()
    private val app get() = RuntimeEnvironment.getApplication()
    private var service: ServiceController<LinkService>? = null
    private lateinit var sim: Sim
    private var port = 0
    private val states = AtomicInteger()
    private val calls = CopyOnWriteArrayList<String>()
    @Volatile private var list = "[]"
    private val net = Http { url, _ ->
        calls += url
        Response(200, list.toByteArray())
    }
    private val phoneLow = "phone battery under 20%"
    private val unsignedList = "[{\"tag_name\":\"v0.4.0\",\"draft\":false,\"prerelease\":false,\"published_at\":\"2026-10-27T00:00:00Z\",\"assets\":[{\"name\":\"v1.upt\",\"size\":50000,\"browser_download_url\":\"https://github.com/seilent/funeos/releases/download/v0.4.0/v1.upt\",\"digest\":\"sha256:${"0".repeat(64)}\"}]}]"
    private val noUpdater = "device has no updater"

    @Before
    fun setUp() {
        scrobblesOff(app)
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        MediaLink.controls(app, false)
        Remote.artist(app, Http { _, _ -> Response(404, ByteArray(0)) })
        Lyrics.init(app, Http { _, _ -> Response(404, ByteArray(0)) })
        shadowOf(app.getSystemService(BatteryManager::class.java)).setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 10)
        stale()
    }

    @After
    fun tearDown() {
        service?.destroy()
        Remote.stop()
        Remote.shown(false)
        Lyrics.reset()
        Firmwares.reset()
        sims.forEach { it.stop() }
        scrobblesReset()
    }

    private fun stale() {
        app.getSharedPreferences("firmware", Context.MODE_PRIVATE).edit().putBoolean("checkDaily", true).putLong("lastCheck", 0).commit()
    }

    private fun lists() = calls.count { it == Firmware.URL }

    private fun waitFor(ms: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out: ${Firmwares.view} ${Remote.phase}" }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun linked(update: Boolean) = waitFor {
        Remote.phase == Phase.LIVE && Remote.device.hello?.has("update") == update && Remote.device.playback.state.battery != null
    }

    private fun fresh(faults: List<String>) = Sim(Options(Board.of("v1"), Faults(faults))).also {
        sims += it
        sim = it
    }

    private fun live(faults: List<String>) {
        Firmwares.init(app, net)
        port = listenBelowEphemeral(fresh(faults))
        service = Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, "tcp:127.0.0.1:$port")).create().startCommand(0, 1)
        val before = Remote.onState
        Remote.onState = {
            before?.invoke()
            states.incrementAndGet()
        }
        linked(faults.isEmpty())
    }

    private fun swap(faults: List<String>) {
        sim.stop()
        waitFor { Remote.phase != Phase.LIVE }
        stale()
        listenAgain(fresh(faults), port)
        linked(faults.isEmpty())
    }

    private fun stated() {
        val n = states.get()
        sim.current!!.push(Msg.STATE, sim.device.state())
        waitFor { states.get() > n }
    }

    private fun drained() = Firmwares.idle()

    private fun queued() {
        val f = File(kotlin.io.path.createTempDirectory("upd").toFile(), "image.upt").apply { writeBytes(ByteArray(50_000) { (it * 17 + 3).toByte() }) }
        Firmwares.send(f, null)
    }

    @Test
    fun aDeviceWithoutTheUpdaterMakesNoCheckAndResumesNoSend() {
        live(emptyList())
        stated()
        drained()
        val lists = lists()
        queued()
        waitFor { Firmwares.view.info == phoneLow }
        swap(NO_UPDATER_FAULTS)
        stated()
        assertEquals(phoneLow, Firmwares.view.info)
        drained()
        assertEquals(lists, lists())
    }

    @Test
    fun aPageStartWithoutTheUpdaterAfterAnUnsignedCheckMakesNoCheck() {
        list = unsignedList
        live(emptyList())
        waitFor { Firmwares.view.info == UNSIGNED_RELEASE }
        stated()
        drained()
        val lists = lists()
        swap(NO_UPDATER_FAULTS)
        Firmwares.opened()
        drained()
        assertEquals(lists, lists())
    }

    @Test
    fun aNewSpecAfterADeviceWithoutTheUpdaterChecksAtThePageStartAsOnMain() {
        list = unsignedList
        live(emptyList())
        waitFor { Firmwares.view.info == UNSIGNED_RELEASE }
        swap(NO_UPDATER_FAULTS)
        stated()
        drained()
        val lists = lists()
        val idle = java.net.ServerSocket(0).use { it.localPort }
        Remote.start(app, "tcp:127.0.0.1:$idle")
        Firmwares.opened()
        drained()
        assertEquals(lists + 1, lists())
    }

    @Test
    fun aDeviceWithTheUpdaterChecksAndResumesAsToday() {
        live(NO_UPDATER_FAULTS)
        stated()
        drained()
        val lists = lists()
        queued()
        waitFor { Firmwares.view.info == noUpdater }
        swap(emptyList())
        drained()
        assertTrue(lists() > lists)
        stated()
        assertEquals(phoneLow, Firmwares.view.info)
    }
}
