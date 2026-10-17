package net.seilent.funecomp

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Looper
import net.seilent.funecomp.art.Thumbs
import net.seilent.funecomp.sim.Battery
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.state.LyricsPlan
import net.seilent.funecomp.ui.Phase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackgroundTest {
    private val sims = ArrayList<Sim>()
    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        MediaLink.controls(app, false)
    }

    @After
    fun tearDown() {
        Remote.stop()
        sims.forEach { it.stop() }
    }

    private val logs = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun sim(battery: Battery = Battery(87, 87, 0), lrcDir: java.io.File? = null, faults: List<String> = emptyList()): String {
        val s = Sim(Options(Board.of("v1"), net.seilent.funecomp.sim.Faults(faults), battery = battery, lrcDir = lrcDir, log = { logs += it }))
        sims += s
        return "tcp:127.0.0.1:${s.listen(0)}"
    }

    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun text(): String? {
        val n = shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(1) ?: return null
        return n.extras.getCharSequence("android.text")?.toString()
    }

    private fun startIntent(spec: String) = Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, spec)

    @Test
    fun serviceStaysUpAcrossADropAndStopClearsThePhase() {
        val spec = sim()
        val c = Robolectric.buildService(LinkService::class.java, startIntent(spec)).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && text() == "connected, dap 87 %" }
        sims.forEach { it.stop() }
        waitFor { Remote.phase != Phase.LIVE && text() != "connected, dap 87 %" }
        assertFalse(shadowOf(c.get()).isStoppedBySelf)
        c.withIntent(Intent(app, LinkService::class.java).setAction(LinkService.ACTION_STOP)).startCommand(0, 2)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
        assertEquals(Phase.NONE, Remote.phase)
        c.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(Remote.onPhase)

        val again = Robolectric.buildService(LinkService::class.java, startIntent(sim())).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && text() == "connected, dap 87 %" }
        again.destroy()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun fetchedMediaArtHasTheMediaSize() {
        val dir = kotlin.io.path.createTempDirectory("art").toFile()
        val lib = net.seilent.funecomp.sim.Library.synthetic()
        val jpeg = java.awt.image.BufferedImage(1000, 1000, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (t in lib.tracks) javax.imageio.ImageIO.write(jpeg, "jpg", java.io.File(dir, "${lib.albumKey(t.id)}.jpg"))
        val s = Sim(Options(Board.of("v1"), artDir = dir))
        sims += s
        val c = Robolectric.buildService(LinkService::class.java, startIntent("tcp:127.0.0.1:${s.listen(0)}")).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.track != null }
        val track = Remote.device.playback.state.track!!
        Remote.wantArt(Thumbs.MEDIA, track, MediaLink.ART_PX, Remote.device.playback.state.album)
        waitFor { Remote.mediaArt(track, MediaLink.ART_PX) != null }
        assertEquals(MediaLink.ART_PX, Remote.mediaArt(track, MediaLink.ART_PX)!!.width)
        c.destroy()
    }

    @Test
    fun bluetoothOffPausesAndOnResumes() {
        val c = Robolectric.buildService(LinkService::class.java, startIntent(sim())).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE }
        app.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF))
        waitFor { Remote.phase == Phase.DOWN && text() == "bluetooth off" }
        Thread.sleep(1500)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Phase.DOWN, Remote.phase)
        app.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON))
        waitFor { Remote.phase == Phase.LIVE && text() == "connected, dap 87 %" }
        c.destroy()
    }

    @Test
    fun mediaNotificationShowsTheSongWhileThePhoneControlsAreOn() {
        MediaLink.controls(app, true)
        val c = Robolectric.buildService(LinkService::class.java, startIntent(sim())).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.title != null }
        waitFor { title() == Remote.device.playback.state.title }
        val n = shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(1)!!
        assertEquals("media", n.channelId)
        MediaLink.controls(app, false)
        c.withIntent(Intent(app, LinkService::class.java).setAction(LinkService.ACTION_MEDIA)).startCommand(0, 2)
        waitFor { text() == "connected, dap 87 %" }
        assertEquals("link", shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(1)!!.channelId)
        c.destroy()
    }

    @Test
    fun lowDapBatteryNotifiesOnceAndTheLinkTextCarriesIt() {
        val c = Robolectric.buildService(LinkService::class.java, startIntent(sim(Battery.parse("17:14@300")))).create().startCommand(0, 1)
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        waitFor { Remote.phase == Phase.LIVE && text()?.startsWith("connected, dap ") == true }
        waitFor { nm.getNotification(2) != null }
        assertEquals("v1 battery 15 %", nm.getNotification(2)!!.extras.getCharSequence("android.title")?.toString())
        assertEquals("dap", nm.getNotification(2)!!.channelId)
        waitFor { text() == "connected, dap 14 %" }
        assertEquals("v1 battery 15 %", nm.getNotification(2)!!.extras.getCharSequence("android.title")?.toString())
        c.destroy()
    }

    @Test
    fun lyricsArePutOncePerTrackAcrossADrop() {
        val dir = kotlin.io.path.createTempDirectory("lrc").toFile()
        val calls = java.util.Collections.synchronizedList(ArrayList<String>())
        Lyrics.init(app, net.seilent.funecomp.artist.Http { url, _ ->
            calls += url
            net.seilent.funecomp.artist.Response(200, "{\"syncedLyrics\":\"[00:01.00]line\\n\",\"instrumental\":false}".toByteArray())
        })
        Lyrics.set(true)
        val c = Robolectric.buildService(LinkService::class.java, startIntent(sim(lrcDir = dir))).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE }
        Remote.queuePage(0)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        waitFor { logs.count { it.startsWith("stored lrc") } >= 3 }
        Thread.sleep(1500)
        val first = logs.filter { it.startsWith("stored lrc") }.toSet()
        assertTrue(first.size in 3..1 + LyricsPlan.AHEAD)
        assertEquals(first.size, calls.size)
        assertEquals(first.size, logs.count { it.startsWith("stored lrc") })
        sims.forEach { it.stop() }
        waitFor { Remote.phase != Phase.LIVE }
        logs.clear()
        c.withIntent(Intent(app, LinkService::class.java).setAction(LinkService.ACTION_STOP)).startCommand(0, 2)
        c.destroy()
        val again = Robolectric.buildService(LinkService::class.java, startIntent(sim(lrcDir = dir))).create().startCommand(0, 3)
        waitFor { Remote.phase == Phase.LIVE }
        Remote.queuePage(0)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        Thread.sleep(1500)
        shadowOf(Looper.getMainLooper()).idle()
        val second = logs.filter { it.startsWith("stored lrc") }
        assertEquals(second.toSet().size, second.size)
        assertTrue(second.none { it in first })
        Lyrics.set(false)
        again.destroy()
    }

    @Test
    fun aRefusedLyricsPutIsNotSentAgain() {
        Lyrics.init(app, net.seilent.funecomp.artist.Http { _, _ ->
            net.seilent.funecomp.artist.Response(200, "{\"syncedLyrics\":\"[00:01.00]line\\n\",\"instrumental\":false}".toByteArray())
        })
        Lyrics.set(true)
        val c = Robolectric.buildService(LinkService::class.java, startIntent(sim(faults = listOf("refuse=LRC_PUT")))).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE }
        Remote.queuePage(0)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        val end = System.currentTimeMillis() + 30_000
        while (logs.count { it.contains("rx LRC_PUT") } < 1 + LyricsPlan.AHEAD) {
            check(System.currentTimeMillis() < end) { "puts: ${logs.count { it.contains("rx LRC_PUT") }}" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        Thread.sleep(500)
        val first = logs.count { it.contains("rx LRC_PUT") }
        assertEquals(1 + LyricsPlan.AHEAD, first)
        repeat(3) {
            Lyrics.poke()
            Remote.queuePage(0)
            Thread.sleep(300)
        }
        assertEquals(first, logs.count { it.contains("rx LRC_PUT") })
        Lyrics.set(false)
        c.destroy()
    }

    private fun title(): String? =
        shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(1)?.extras?.getCharSequence("android.title")?.toString()

    @Test
    fun startGate() {
        assertTrue(Main.opens(Intent(Intent.ACTION_MAIN), debuggable = false))
        assertTrue(Main.opens(Intent(Main.ACTION_OPEN), debuggable = false))
        assertTrue(Main.opens(Intent().putExtra(Main.EXTRA_LINK, "tcp:127.0.0.1:1"), debuggable = true))
        assertFalse(Main.opens(Intent().putExtra(Main.EXTRA_LINK, "tcp:127.0.0.1:1"), debuggable = false))
        assertFalse(Main.opens(Intent(), debuggable = true))
        assertFalse(Main.opens(Intent(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY), debuggable = true))
        assertFalse(Main.opens(null, debuggable = true))
    }

    @Test
    fun aLauncherOpenStartsTheLinkAndAReturnDoesNot() {
        app.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE).edit().putString(Main.EXTRA_LINK, "tcp:127.0.0.1:1").commit()
        val shadow = shadowOf(app)
        while (shadow.nextStartedService != null) Unit
        val a = Robolectric.buildActivity(Main::class.java, Intent(app, Main::class.java).setAction(Intent.ACTION_MAIN)).create().start().resume()
        assertEquals(LinkService::class.java.name, shadow.nextStartedService?.component?.className)
        a.pause().stop()
        assertNull(shadow.nextStartedService)
        a.restart().resume()
        assertNull(shadow.nextStartedService)
        a.pause().stop()
        a.newIntent(Intent(app, Main::class.java).setAction(Intent.ACTION_MAIN))
        a.restart().resume()
        assertEquals(LinkService::class.java.name, shadow.nextStartedService?.component?.className)
        a.pause().stop().destroy()
    }
}
