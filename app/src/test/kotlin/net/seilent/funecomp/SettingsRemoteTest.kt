package net.seilent.funecomp

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.ui.Phase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsRemoteTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val sims = ArrayList<Sim>()
    private var service: ServiceController<LinkService>? = null

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

    private fun live(vararg faults: String) {
        val s = Sim(Options(Board.of("v1"), Faults(faults.toList()), btRole = Enums.BtRole.headphones))
        sims += s
        val spec = "tcp:127.0.0.1:${s.listen(0)}"
        app.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE).edit().putString(Main.EXTRA_LINK, spec).commit()
        service = Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, spec)).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE }
    }

    @Test
    fun aSetReportsItsReplyCode() {
        live("fail=SET:${Err.refused}:1")
        val codes = ArrayList<Int?>()
        Remote.set("scrobble", "card") { codes += it }
        waitFor { codes.size == 1 }
        Remote.set("scrobble", "card") { codes += it }
        waitFor { codes.size == 2 }
        assertEquals(listOf(Err.refused, null), codes)
        waitFor { Remote.device.settings.values["scrobble"] == "card" }
    }

    @Test
    fun aCodecDraftCarriesItsRole() {
        live()
        val codes = ArrayList<Int?>()
        Remote.draft(Enums.DraftKind.btCodec, "ldac", Enums.BtRole.headphones) { code, _ -> codes += code }
        waitFor { codes.size == 1 }
        waitFor { Remote.device.settings.values["btCodecHeadphones"] == "ldac" }
        Remote.draft(Enums.DraftKind.btCodec, "aac", Enums.BtRole.receiver) { code, _ -> codes += code }
        waitFor { codes.size == 2 }
        assertEquals(listOf(null, Err.refused), codes)
        assertEquals("ldac", Remote.device.settings.values["btCodecHeadphones"])
    }
}
