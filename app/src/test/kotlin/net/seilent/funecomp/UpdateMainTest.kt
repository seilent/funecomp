package net.seilent.funecomp

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funekit.artist.Http
import net.seilent.funekit.artist.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class UpdateMainTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val app get() = RuntimeEnvironment.getApplication()
    private val sims = ArrayList<Sim>()
    private var service: ServiceController<LinkService>? = null
    private val open = ArrayList<ActivityController<Main>>()

    @Before
    fun setUp() {
        scrobblesOff(app)
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        MediaLink.controls(app, false)
        Remote.artist(app, Http { _, _ -> Response(404, ByteArray(0)) })
        Lyrics.init(app, Http { _, _ -> Response(404, ByteArray(0)) })
        app.getSharedPreferences(Main.DEVICE_PREFS, Context.MODE_PRIVATE).edit().putBoolean("batteryAsked", true).commit()
    }

    @After
    fun tearDown() {
        open.filter { !it.get().isDestroyed }.forEach { it.pause().stop().destroy() }
        service?.destroy()
        Remote.stop()
        Lyrics.reset()
        Firmwares.reset()
        sims.forEach { it.stop() }
        scrobblesReset()
    }

    private fun waitFor(ms: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            rule.waitForIdle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
        rule.waitForIdle()
    }

    private fun notice() = Intent(app, Main::class.java).setAction(Main.ACTION_UPDATE)

    private fun cold(): ActivityController<Main> {
        val a = Robolectric.buildActivity(Main::class.java, notice()).create().start().resume().visible()
        open += a
        waitFor { true }
        return a
    }

    private fun stack(a: ActivityController<Main>) = a.get().shell.nav.stack.toList()

    @Test
    fun beforeAnyLinkTheNoticeOpensTheUpdatePageOverConnectionColdAndWarm() {
        val a = cold()
        assertEquals(listOf(Page.Connection, Page.Update), stack(a))
        a.get().shell.nav.reset(listOf(Page.Connection, Page.Online()))
        a.newIntent(notice())
        waitFor { true }
        assertEquals(listOf(Page.Connection, Page.Update), stack(a))
    }

    @Test
    fun withADapTheNoticeOpensTheUpdatePageOverHomeColdAndWarm() {
        val s = Sim(Options(Board.of("v1"), Faults(emptyList())))
        sims += s
        val spec = "tcp:127.0.0.1:${listenBelowEphemeral(s)}"
        app.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE).edit().putString(Main.EXTRA_LINK, spec).commit()
        service = Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, spec)).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE }
        val a = cold()
        waitFor { !a.get().shell.gated }
        assertEquals(listOf(Page.Home, Page.Update), stack(a))
        a.get().shell.nav.reset(listOf(Page.Home, Page.Settings, Page.Firmware))
        a.newIntent(notice())
        waitFor { true }
        assertEquals(listOf(Page.Home, Page.Update), stack(a))
    }
}
