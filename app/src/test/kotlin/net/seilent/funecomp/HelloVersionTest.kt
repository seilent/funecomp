package net.seilent.funecomp

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Looper
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.LyricSources
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.ui.Phase
import org.junit.After
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
class HelloVersionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val sims = ArrayList<Sim>()
    private val lines = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        scrobblesOff(app)
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        MediaLink.controls(app, false)
        Remote.resetArtist()
        Lyrics.init(app, Http { _, _ -> Response(404, ByteArray(0)) })
        Lyrics.set(LyricSources.LRCLIB, false)
        Lyrics.set(LyricSources.NETEASE, false)
    }

    @After
    fun tearDown() {
        Remote.stop()
        Remote.shown(false)
        sims.forEach { it.stop() }
        Remote.resetArtist()
        Lyrics.reset()
        scrobblesReset()
    }

    private fun waitFor(ms: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
            Thread.sleep(20)
        }
    }

    @Test
    fun theHelloCarriesTheInstalledVersionName() {
        shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName).versionName = "9.8.7-test"
        Remote.artist(app, Http { _, _ -> Response(404, ByteArray(0)) })
        val s = Sim(Options(Board.of("v1"), log = { lines += it }))
        sims += s
        Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, "tcp:127.0.0.1:${s.listen(0)}")).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE }
        assertTrue(lines.toString(), lines.any { " hello app " in it && " version=9.8.7-test " in it })
    }
}
