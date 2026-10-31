package net.seilent.funecomp

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Looper
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.proto.SvcInfo
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.ui.ONLINE_STREAMING
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class SvcKeysTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val app get() = RuntimeEnvironment.getApplication()
    private val sims = ArrayList<Sim>()
    private val logs = CopyOnWriteArrayList<String>()
    private var service: ServiceController<LinkService>? = null
    private val open = ArrayList<ActivityController<Main>>()
    private val tidal = SvcInfo("tidal", "TIDAL", listOf("client_id", "client_secret"), true)
    private val value = "v4lu3s3cret"

    @Before
    fun setUp() {
        Svc.reset()
        scrobblesOff(app)
        debuggable(true)
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
        sims.forEach { it.stop() }
        debuggable(true)
        Svc.reset()
        scrobblesReset()
    }

    private fun debuggable(on: Boolean) {
        val f = app.applicationInfo.flags
        app.applicationInfo.flags = if (on) f or ApplicationInfo.FLAG_DEBUGGABLE else f and ApplicationInfo.FLAG_DEBUGGABLE.inv()
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

    private fun live(vararg faults: String) {
        val s = Sim(Options(Board.of("v1"), Faults(faults.toList()), log = { logs += it }, services = listOf(tidal)))
        sims += s
        val spec = "tcp:127.0.0.1:${s.listen(0)}"
        app.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE).edit().putString(Main.EXTRA_LINK, spec).commit()
        service = Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, spec)).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE }
        debuggable(false)
    }

    private fun main(): ActivityController<Main> {
        val a = Robolectric.buildActivity(Main::class.java, Intent(app, Main::class.java).setAction(Intent.ACTION_MAIN)).create().start().resume().visible()
        open += a
        waitFor { !a.get().shell.gated }
        a.get().shell.nav.push(Page.Online(ONLINE_STREAMING))
        waitFor { rule.onAllNodes(hasTestTag("svc.tidal")).fetchSemanticsNodes().isNotEmpty() }
        return a
    }

    private fun secure(a: ActivityController<Main>) = a.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    private fun keys(a: ActivityController<Main>) {
        rule.onNodeWithTag("svc.tidal").performClick()
        waitFor { a.get().shell.sheet.shown && a.get().shell.sheet.title == "TIDAL" && a.get().shell.sheet.items.firstOrNull() == "custom keys" }
        assertFalse(secure(a))
        rule.onNodeWithTag("sheet.item.0").performClick()
        waitFor { a.get().shell.sheet.shown && a.get().shell.sheet.items.firstOrNull() == "client_id" }
    }

    private fun fill(a: ActivityController<Main>) {
        rule.onNodeWithTag("sheet.item.0").performClick()
        waitFor { a.get().shell.sheet.entry != null }
        secretField().append(value)
        secretField().onEditorAction(EditorInfo.IME_ACTION_DONE)
        waitFor { "send" in a.get().shell.sheet.items }
    }

    @Test
    fun theTypedKeysAndTheSecureWindowOutliveAStopUntilTheSheetCloses() {
        live()
        val a = main()
        assertFalse(secure(a))
        keys(a)
        assertTrue(secure(a))
        fill(a)
        assertTrue(secure(a))
        a.pause().stop()
        assertTrue(secure(a))
        assertTrue(a.get().shell.keys.draft!!.has("client_id"))
        a.restart().resume()
        waitFor { a.get().shell.sheet.shown && "send" in a.get().shell.sheet.items }
        assertTrue(secure(a))
        rule.onNodeWithTag("sheet.item.2").performClick()
        waitFor { a.get().shell.toast.title == Svc.KEYS_SENT }
        assertFalse(secure(a))
        assertNull(a.get().shell.keys.draft)
        assertTrue(logs.any { it.startsWith("svc keys tidal names=[client_id]") })
    }

    @Test
    fun cancelAfterAStopDropsTheKeysAndTheSecureWindow() {
        live()
        val a = main()
        keys(a)
        fill(a)
        a.pause().stop()
        a.restart().resume()
        waitFor { "cancel" in a.get().shell.sheet.items }
        rule.onNodeWithTag("sheet.item.3").performClick()
        waitFor { !a.get().shell.sheet.shown }
        assertFalse(secure(a))
        assertNull(a.get().shell.keys.draft)
        assertTrue(logs.none { it.contains("rx SVC_KEYS") })
    }

    @Test
    fun aSentKeyGetsTheDeviceLineAndLeavesNoTrace() {
        live()
        val a = main()
        keys(a)
        fill(a)
        rule.onNodeWithTag("sheet.item.2").performClick()
        waitFor { a.get().shell.toast.title == Svc.KEYS_SENT }
        assertEquals("TIDAL", a.get().shell.toast.artist)
        assertFalse(secure(a))
        assertNull(a.get().shell.keys.draft)
        assertTrue(logs.any { it.startsWith("svc keys tidal names=[client_id]") })
        assertTrue(logs.none { value in it })
        assertTrue(ShadowLog.getLogs().none { value in it.msg })
        val data = File(app.applicationInfo.dataDir).walkTopDown().filter { it.isFile }.toList()
        assertTrue(data.none { f -> value in String(f.readBytes(), Charsets.ISO_8859_1) })
    }

    @Test
    fun streamingOffOnTheDeviceHasItsLine() {
        live("svc-off")
        val a = main()
        keys(a)
        fill(a)
        rule.onNodeWithTag("sheet.item.2").performClick()
        waitFor { a.get().shell.toast.title == Svc.OFF }
    }
}
