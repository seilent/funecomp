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

    private fun sim(battery: Battery = Battery(87, 87, 0)): String {
        val s = Sim(Options(Board.of("v1"), battery = battery))
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
