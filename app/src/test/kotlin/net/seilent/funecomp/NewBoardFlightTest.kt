package net.seilent.funecomp

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.ui.Page
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class NewBoardFlightTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val app get() = RuntimeEnvironment.getApplication()
    private val adapter: BluetoothAdapter get() = app.getSystemService(BluetoothManager::class.java).adapter
    private val open = ArrayList<ActivityController<Main>>()
    private val r1 = "00:00:00:00:00:04"
    private val v1 = "00:00:00:00:00:05"

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(adapter).setEnabled(true)
        app.getSharedPreferences(Main.DEVICE_PREFS, Context.MODE_PRIVATE).edit().putBoolean("batteryAsked", true).commit()
        Remote.boards.clear()
        scrobblesOff(app)
        MediaLink.controls(app, false)
        Remote.artist(app, Http { _, _ -> Response(404, ByteArray(0)) })
        Lyrics.init(app, Http { _, _ -> Response(404, ByteArray(0)) })
    }

    @After
    fun tearDown() {
        open.filter { !it.get().isDestroyed }.forEach { it.pause().stop().destroy() }
        Remote.stop()
        Remote.boards.clear()
        scrobblesReset()
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        rule.waitForIdle()
    }

    private fun later(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        rule.waitForIdle()
    }

    private fun main(): ActivityController<Main> {
        val a = Robolectric.buildActivity(Main::class.java, Intent(app, Main::class.java).setAction(Intent.ACTION_MAIN)).create().start().resume().visible()
        open += a
        idle()
        return a
    }

    private fun board(address: String = r1, name: String = "fune r1", bond: Int = BluetoothDevice.BOND_NONE): BluetoothDevice {
        val d = adapter.getRemoteDevice(address)
        shadowOf(d).setName(name)
        shadowOf(d).setBondState(bond)
        shadowOf(d).setUuids(arrayOf(ParcelUuid.fromString(Proto.uuid)))
        shadowOf(d).setCreatedBond(true)
        shadowOf(d).setFetchUuidsWithSdpResult(true)
        return d
    }

    private fun found(d: BluetoothDevice = board()) {
        app.sendBroadcast(Intent(BluetoothDevice.ACTION_FOUND).putExtra(BluetoothDevice.EXTRA_DEVICE, d))
        idle()
    }

    private fun bond(d: BluetoothDevice, state: Int) {
        shadowOf(d).setBondState(state)
        app.sendBroadcast(Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED).putExtra(BluetoothDevice.EXTRA_DEVICE, d).putExtra(BluetoothDevice.EXTRA_BOND_STATE, state))
        idle()
    }

    private fun uuids(d: BluetoothDevice) {
        app.sendBroadcast(Intent(BluetoothDevice.ACTION_UUID).putExtra(BluetoothDevice.EXTRA_DEVICE, d).putExtra(BluetoothDevice.EXTRA_UUID, arrayOf(ParcelUuid.fromString(Proto.uuid))))
        idle()
    }

    private fun prompted(a: ActivityController<Main>) = a.get().shell.sheet.let { it.shown && it.title == "fune r1" }

    private fun picker() = shadowOf(app.getSystemService(CompanionDeviceManager::class.java)).lastAssociationCallback

    private fun device(a: ActivityController<Main>) {
        a.get().shell.nav.push(Page.Connection)
        idle()
        rule.onNodeWithTag("conn.device").performClick()
        idle()
    }

    @Test
    fun thePickerHoldsTheRoundsAndThePromptUntilItsFailure() {
        val a = main()
        assertTrue(adapter.isDiscovering)
        a.get().control.pairDevice()
        idle()
        assertFalse(adapter.isDiscovering)
        found()
        assertFalse(prompted(a))
        later(BoardScan.PERIOD_MS)
        assertFalse(adapter.isDiscovering)
        picker().onFailure("no device")
        idle()
        a.get().control.connectionShown()
        idle()
        assertTrue(adapter.isDiscovering)
        found()
        assertTrue(prompted(a))
    }

    @Test
    fun aBoardFoundUnderTheDeviceSheetIsListedDetectedAndHeldThroughThePicker() {
        shadowOf(adapter).setBondedDevices(setOf(board(v1, "fune v1", BluetoothDevice.BOND_BONDED)))
        val a = main()
        device(a)
        val sheet = a.get().shell.sheet
        assertEquals(listOf("fune v1", "pair a device"), sheet.items)
        found()
        assertFalse(prompted(a))
        assertEquals(listOf("fune v1", "fune r1", "pair a device"), sheet.items)
        assertEquals(listOf("", "detected", ""), sheet.itemValues)
        sheet.accept(2)
        idle()
        assertTrue(Remote.boards.picking)
        assertFalse(prompted(a))
        picker().onFailure("no device")
        idle()
        assertTrue(prompted(a))
    }

    @Test
    fun aDetectedPickPairsItAndItsPromptNeverShows() {
        shadowOf(adapter).setBondedDevices(setOf(board(v1, "fune v1", BluetoothDevice.BOND_BONDED)))
        val a = main()
        device(a)
        found()
        a.get().shell.sheet.accept(1)
        idle()
        assertEquals(r1, Remote.boards.flight?.address)
        assertFalse(prompted(a))
    }

    @Test
    fun aPairInFlightHoldsThePromptForThatBoard() {
        val a = main()
        found()
        a.get().shell.sheet.accept(0)
        idle()
        assertEquals(r1, Remote.boards.flight?.address)
        Remote.boards.found(Find(r1, "fune r1", true, true, false, false), SystemClock.elapsedRealtime(), Long.MAX_VALUE)
        idle()
        assertEquals(r1, Remote.boards.waiting?.address)
        assertFalse(prompted(a))
    }

    @Test
    fun deviceListsTheBoardPairingThenPairedThenAsABondedBoard() {
        val v = board(v1, "fune v1", BluetoothDevice.BOND_BONDED)
        shadowOf(adapter).setBondedDevices(setOf(v))
        val a = main()
        val r = board()
        found(r)
        a.get().shell.sheet.accept(0)
        idle()
        device(a)
        val sheet = a.get().shell.sheet
        assertEquals(listOf("fune v1", "fune r1"), sheet.items)
        assertEquals(listOf("", "pairing"), sheet.itemValues)
        sheet.focus = 1
        bond(r, BluetoothDevice.BOND_BONDED)
        assertTrue(sheet.shown)
        assertEquals(listOf("", "paired"), sheet.itemValues)
        assertEquals(1, sheet.focus)
        shadowOf(adapter).setBondedDevices(setOf(v, r))
        uuids(r)
        assertEquals(listOf("fune r1", "fune v1", "pair a device"), sheet.items)
        assertEquals(listOf("", "", ""), sheet.itemValues)
        assertEquals(0, sheet.focus)
    }
}
