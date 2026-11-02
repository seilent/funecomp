package net.seilent.funecomp

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Looper
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.ui.Phase
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LinkReceiverTest {
    private val sims = ArrayList<Sim>()
    private val app get() = RuntimeEnvironment.getApplication()
    private val adapter get() = app.getSystemService(BluetoothManager::class.java).adapter
    private val stack = listOf(BluetoothAdapter.ACTION_STATE_CHANGED, BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED, BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)
    private val own = listOf("net.seilent.funecomp.ALARM", Intent.ACTION_SCREEN_ON, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)

    @Before
    fun setUp() {
        scrobblesOff(app)
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(adapter).setEnabled(true)
        MediaLink.controls(app, false)
        Remote.artist(app, net.seilent.funecomp.artist.Http { _, _ -> net.seilent.funecomp.artist.Response(404, ByteArray(0)) })
        Lyrics.init(app, net.seilent.funecomp.artist.Http { _, _ -> net.seilent.funecomp.artist.Response(404, ByteArray(0)) })
    }

    @After
    fun tearDown() {
        Remote.stop()
        Remote.shown(false)
        Lyrics.reset()
        Firmwares.reset()
        sims.forEach { it.stop() }
        scrobblesReset()
    }

    private fun sim(): String {
        val s = Sim(Options(Board.of("v1")), net.seilent.funecomp.sim.Library.synthetic())
        sims += s
        return "tcp:127.0.0.1:${s.listen(0)}"
    }

    private fun waitFor(ms: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun flags(action: String) = shadowOf(app).registeredReceivers.single { it.intentFilter.hasAction(action) }.flags

    private fun channel() = shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(1)?.channelId

    private fun sink(address: String, state: Int) = app.sendBroadcast(
        Intent(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            .putExtra(BluetoothDevice.EXTRA_DEVICE, adapter.getRemoteDevice(address))
            .putExtra(BluetoothProfile.EXTRA_STATE, state),
    )

    @Test
    fun theBluetoothStacksActionsAreExported() {
        val c = Robolectric.buildService(LinkService::class.java).create()
        stack.forEach { assertEquals(it, Context.RECEIVER_EXPORTED, flags(it) and Context.RECEIVER_EXPORTED) }
        c.destroy()
    }

    @Test
    fun theOwnAndSystemActionsStayPrivate() {
        val c = Robolectric.buildService(LinkService::class.java).create()
        own.forEach {
            assertEquals(it, Context.RECEIVER_NOT_EXPORTED, flags(it) and Context.RECEIVER_NOT_EXPORTED)
            assertEquals(it, 0, flags(it) and Context.RECEIVER_EXPORTED)
        }
        c.destroy()
    }

    @Test
    @Config(sdk = [32])
    fun belowTiramisuTheOwnAndSystemActionsNeedThePackagePermission() {
        val permission = "${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        shadowOf(app).grantPermissions(permission)
        val c = Robolectric.buildService(LinkService::class.java).create()
        fun wrapper(action: String) = shadowOf(app).registeredReceivers.single { it.intentFilter.hasAction(action) }
        own.forEach { assertEquals(it, permission, wrapper(it).broadcastPermission) }
        stack.forEach { assertEquals(it, null, wrapper(it).broadcastPermission) }
        c.destroy()
    }

    @Test
    fun destroyDropsBothFilters() {
        Robolectric.buildService(LinkService::class.java).create().destroy()
        assertTrue(shadowOf(app).registeredReceivers.none { w -> (stack + own).any { w.intentFilter.hasAction(it) } })
    }

    @Test
    fun anA2dpSinkToTheLinkHidesThePhoneControlsAndItsDropShowsThem() {
        MediaLink.controls(app, true)
        val c = Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, sim())).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.title != null }
        waitFor { channel() == "media" }
        LinkService::class.java.getDeclaredField("address").apply { isAccessible = true }.set(c.get(), DAP)
        sink(OTHER, BluetoothProfile.STATE_CONNECTED)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("media", channel())
        sink(DAP, BluetoothProfile.STATE_CONNECTED)
        waitFor { channel() == "link" }
        sink(DAP, BluetoothProfile.STATE_DISCONNECTED)
        waitFor { channel() == "media" }
        c.destroy()
    }

    private companion object {
        const val DAP = "00:11:22:33:44:55"
        const val OTHER = "66:77:88:99:AA:BB"
    }
}
