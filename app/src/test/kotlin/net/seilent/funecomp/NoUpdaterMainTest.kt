package net.seilent.funecomp

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
class NoUpdaterMainTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val app get() = RuntimeEnvironment.getApplication()
    private val sims = ArrayList<Sim>()
    private var service: ServiceController<LinkService>? = null
    private val open = ArrayList<ActivityController<Main>>()
    private lateinit var sim: Sim
    private var port = 0

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
        Remote.apps.close()
        Remote.apps = Apps()
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

    private fun main(faults: List<String>, page: String? = null, minor: Int = Proto.minor): ActivityController<Main> {
        val s = Sim(Options(Board.of("v1"), Faults(faults), minor = minor))
        sims += s
        sim = s
        port = s.listen(0)
        val spec = "tcp:127.0.0.1:$port"
        app.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE).edit().putString(Main.EXTRA_LINK, spec).commit()
        service = Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, spec)).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE }
        val intent = Intent(app, Main::class.java).setAction(Intent.ACTION_MAIN)
        if (page != null) intent.putExtra(Main.EXTRA_PAGE, page)
        val a = Robolectric.buildActivity(Main::class.java, intent).create().start().resume().visible()
        open += a
        waitFor { !a.get().shell.gated }
        return a
    }

    private fun swap(faults: List<String>) {
        sim.stop()
        waitFor { Remote.phase != Phase.LIVE }
        sim = Sim(Options(Board.of("v1"), Faults(faults))).also { sims += it }
        sim.listen(port)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.hello?.has(Apps.APP_BIT) == false && Remote.apps.view.info == Apps.NO_INSTALLER }
    }

    private fun launched(a: ActivityController<Main>): Int {
        a.get().control.appFile()
        waitFor { true }
        return shadowOf(a.get()).nextStartedActivityForResult.requestCode
    }

    private fun returned(a: ActivityController<Main>, code: Int) =
        shadowOf(a.get()).callOnActivityResult(code, Activity.RESULT_OK, Intent().setData(Uri.parse("content://net.seilent.funecomp.test/app.fpk")))

    @Test
    fun aPickReturningAfterAHelloWithoutTheInstallerReadsNothing() {
        Remote.apps = Apps().also { it.init(app) }
        val a = main(emptyList())
        val code = launched(a)
        swap(NO_INSTALLER_FAULTS)
        returned(a, code)
        assertEquals("", Remote.apps.view.send)
        waitFor { true }
        assertEquals("", Remote.apps.view.picked)
        assertEquals(Apps.NO_INSTALLER, Remote.apps.view.info)
    }

    @Test
    fun aPickOnADeviceWithTheInstallerIsRead() {
        Remote.apps = Apps().also { it.init(app) }
        val a = main(emptyList())
        returned(a, launched(a))
        assertEquals(Apps.CHECKING_FILE, Remote.apps.view.send)
    }

    @Test
    fun aPickOnALink16PeerIsRead() {
        Remote.apps = Apps().also { it.init(app) }
        val a = main(emptyList(), minor = 16)
        waitFor { Remote.apps.view.info == Apps.NO_INSTALLER }
        returned(a, launched(a))
        assertEquals(Apps.CHECKING_FILE, Remote.apps.view.send)
    }

    @Test
    fun theDebugRouteOnALiveLinkWithoutTheUpdaterEndsAtSettings() {
        val a = main(NO_UPDATER_FAULTS, "firmware")
        waitFor { a.get().shell.nav.stack.toList() == listOf(Page.Home, Page.Settings) }
    }

    @Test
    fun aConfigChangeKeepsTheFirmwarePageOnAnUpdaterDevice() {
        val a = main(emptyList())
        val m = a.get()
        val shell = m.shell
        shell.nav.push(Page.Firmware)
        waitFor { true }
        a.configurationChange(Configuration(m.resources.configuration).apply { fontScale = 1.3f })
        waitFor { true }
        assertSame(m, a.get())
        assertSame(shell, m.shell)
        assertEquals(Page.Firmware, shell.nav.top)
    }
}
