package net.seilent.funecomp

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.Looper
import android.view.KeyEvent
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.state.LyricsFetch
import net.seilent.funecomp.ui.OffsetEdit
import net.seilent.funecomp.ui.Phase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class OffsetKeysTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val rule = createEmptyComposeRule()

    private val app get() = RuntimeEnvironment.getApplication()
    private val sims = ArrayList<Sim>()
    private val logs = CopyOnWriteArrayList<String>()
    private var service: ServiceController<LinkService>? = null
    private val open = ArrayList<ActivityController<Main>>()
    private val lib = Library.synthetic()
    private val track = lib.tracks.first().id
    private val id = java.lang.Long.toUnsignedString(track)
    private val offMainWrite = AtomicLong(0)
    private var writes: ObserverHandle? = null

    @Before
    fun setUp() {
        writes = Snapshot.registerGlobalWriteObserver { if (Looper.myLooper() != Looper.getMainLooper()) offMainWrite.set(System.nanoTime()) }
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
        try {
            waitFor { System.nanoTime() - offMainWrite.get() > QUIET_NS }
        } finally {
            writes?.dispose()
        }
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

    private fun live() {
        val dir = tmp.newFolder("lrc")
        File(dir, "$id.lrc").writeText("[00:01.00]one\n[00:03.00]two\n")
        val s = Sim(Options(Board.of("v1"), Faults(emptyList()), log = { logs += it }, lrcDir = dir), lib)
        sims += s
        val spec = "tcp:127.0.0.1:${s.listen(0)}"
        app.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE).edit().putString(Main.EXTRA_LINK, spec).commit()
        service = Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, spec)).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.lyrics == true }
        debuggable(false)
    }

    private fun main(): ActivityController<Main> {
        val a = Robolectric.buildActivity(Main::class.java, Intent(app, Main::class.java).setAction(Intent.ACTION_MAIN)).create().start().resume().visible()
        open += a
        waitFor { !a.get().shell.gated }
        return a
    }

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun edit(a: ActivityController<Main>): OffsetEdit {
        val shell = a.get().shell
        waitFor { Remote.dapLyrics.show is LyricsFetch.Show.Lines && exists("np.art") }
        rule.onNodeWithTag("np.art").performClick()
        waitFor { exists("np.lyrics") }
        rule.mainClock.advanceTimeBy(1000)
        rule.onNodeWithTag("np.lyrics").performClick()
        waitFor { shell.offset?.stored == 0 }
        assertEquals("lyrics", shell.sheet.title)
        return shell.offset!!
    }

    private fun key(a: ActivityController<Main>, code: Int, repeat: Int = 0) =
        a.get().onKeyDown(code, KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, code, repeat))

    private fun offset() = Remote.device.playback.state.lyricsOffsetMs ?: 0

    private fun volumes() = logs.count { " rx VOLUME " in it }

    private fun sets() = logs.filter { it.startsWith("lyr offset set ") }.map { it.substringAfter("ms=").toInt() }

    @Test
    fun volumeKeysInAnEditStepTheOffsetAndOutsideActAsBefore() {
        live()
        val a = main()
        val shell = a.get().shell
        val e = edit(a)
        e.begin()
        val shows = shell.overlay.shows
        assertTrue(key(a, KeyEvent.KEYCODE_VOLUME_UP))
        waitFor { offset() == 1000 && e.stored == 1000 }
        assertTrue(key(a, KeyEvent.KEYCODE_VOLUME_UP, repeat = 1))
        waitFor { offset() == 2000 && e.stored == 2000 }
        assertTrue(key(a, KeyEvent.KEYCODE_VOLUME_DOWN))
        waitFor { offset() == 1000 && e.stored == 1000 }
        assertEquals(shows, shell.overlay.shows)
        assertEquals(0, volumes())
        e.keep()
        assertTrue(key(a, KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(shows + 1, shell.overlay.shows)
        assertTrue(shell.overlay.visible)
        Thread.sleep(300)
        assertEquals(0, volumes())
        assertTrue(key(a, KeyEvent.KEYCODE_VOLUME_UP))
        waitFor { volumes() == 1 }
        assertEquals(1000, offset())
        assertEquals(listOf(1000, 2000, 1000), sets())
    }

    @Test
    fun stopRestoresAndClosesWhileAConfigChangeKeepsTheEdit() {
        live()
        val a = main()
        val shell = a.get().shell
        val e = edit(a)
        e.begin()
        e.step(1)
        waitFor { offset() == 100 && e.stored == 100 }
        a.configurationChange(Configuration(a.get().resources.configuration).apply { orientation = Configuration.ORIENTATION_LANDSCAPE })
        waitFor { true }
        assertSame(shell, a.get().shell)
        assertSame(e, shell.offset)
        assertTrue(e.editing)
        assertTrue(shell.sheet.shown)
        a.pause().stop()
        waitFor { offset() == 0 && sets() == listOf(100, 0) }
        assertNull(shell.offset)
        assertFalse(shell.sheet.shown)
        a.destroy()
    }

    companion object {
        private const val QUIET_NS = 300_000_000L
    }
}
