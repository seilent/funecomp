package net.seilent.funecomp

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.ui.Detected
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BoardScanTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val adapter: BluetoothAdapter get() = app.getSystemService(BluetoothManager::class.java).adapter
    private val r1 = "00:00:00:00:00:04"
    private val boards = Boards()
    private val toasts = ArrayList<Pair<String, String>>()
    private val linkedTo = ArrayList<String>()
    private var busy = false
    private var linked: String? = null
    private var now = 0L
    private lateinit var scan: BoardScan

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        shadowOf(adapter).setEnabled(true)
        scan = BoardScan(app, boards, { linked }, { busy }, { n, l -> toasts += n to l }, { linkedTo += it }, { now })
    }

    @After
    fun tearDown() {
        scan.stop()
        Remote.scanCancel = null
        Remote.mainA2dp = null
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun later(ms: Long) {
        now += ms
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    private fun finish() {
        adapter.cancelDiscovery()
        app.sendBroadcast(Intent(BluetoothAdapter.ACTION_DISCOVERY_FINISHED))
        idle()
    }

    private fun board(address: String = r1, name: String? = "fune r1", uuids: Boolean = false, bond: Int = BluetoothDevice.BOND_NONE): BluetoothDevice {
        val d = adapter.getRemoteDevice(address)
        shadowOf(d).setName(name)
        shadowOf(d).setBondState(bond)
        shadowOf(d).setUuids(if (uuids) arrayOf(ParcelUuid.fromString(Proto.uuid)) else null)
        shadowOf(d).setCreatedBond(true)
        shadowOf(d).setFetchUuidsWithSdpResult(true)
        return d
    }

    private fun found(d: BluetoothDevice, cls: Int = 0x04043c) {
        val c = Shadow.newInstanceOf(BluetoothClass::class.java)
        org.robolectric.util.ReflectionHelpers.setField(c, "mClass", cls)
        app.sendBroadcast(Intent(BluetoothDevice.ACTION_FOUND).putExtra(BluetoothDevice.EXTRA_DEVICE, d).putExtra(BluetoothDevice.EXTRA_CLASS, c))
        idle()
    }

    private fun uuids(d: BluetoothDevice, board: Boolean) {
        val list = if (board) arrayOf(ParcelUuid.fromString("0000110b-0000-1000-8000-00805f9b34fb"), ParcelUuid.fromString(Proto.uuid)) else arrayOf(ParcelUuid.fromString("0000110b-0000-1000-8000-00805f9b34fb"))
        app.sendBroadcast(Intent(BluetoothDevice.ACTION_UUID).putExtra(BluetoothDevice.EXTRA_DEVICE, d).putExtra(BluetoothDevice.EXTRA_UUID, list))
        idle()
    }

    private fun bond(d: BluetoothDevice, state: Int) {
        shadowOf(d).setBondState(state)
        app.sendBroadcast(Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED).putExtra(BluetoothDevice.EXTRA_DEVICE, d).putExtra(BluetoothDevice.EXTRA_BOND_STATE, state))
        idle()
    }

    @Test
    fun aRoundAtStartThenEvery30sWhileStarted() {
        scan.start()
        idle()
        assertTrue(adapter.isDiscovering)
        finish()
        later(29_000)
        assertFalse(adapter.isDiscovering)
        later(1_000)
        assertTrue(adapter.isDiscovering)
        finish()
        scan.stop()
        later(60_000)
        assertFalse(adapter.isDiscovering)
    }

    @Test
    fun noRoundBeforeMainsProxyAnswers() {
        var listener: BluetoothProfile.ServiceListener? = null
        scan.stop()
        scan = BoardScan(app, boards, { linked }, { busy }, { n, l -> toasts += n to l }, { linkedTo += it }, { now }) { listener = it; true }
        scan.start()
        idle()
        assertFalse(adapter.isDiscovering)
        later(60_000)
        assertFalse(adapter.isDiscovering)
        val proxy = Shadow.newInstanceOf(BluetoothA2dp::class.java)
        listener!!.onServiceConnected(BluetoothProfile.A2DP, proxy)
        assertTrue(adapter.isDiscovering)
        assertEquals(proxy, Remote.mainA2dp)
    }

    @Test
    fun aFailedProxyRequestLetsRoundsRun() {
        scan.stop()
        scan = BoardScan(app, boards, { linked }, { busy }, { n, l -> toasts += n to l }, { linkedTo += it }, { now }) { false }
        scan.start()
        assertTrue(adapter.isDiscovering)
    }

    @Test
    fun connectionOpenStartsARoundAndRestartsTheCount() {
        scan.start()
        idle()
        finish()
        later(20_000)
        scan.connectionOpened()
        assertTrue(adapter.isDiscovering)
        finish()
        later(29_000)
        assertFalse(adapter.isDiscovering)
        later(1_000)
        assertTrue(adapter.isDiscovering)
    }

    @Test
    fun noRoundWhileBusyOrAnotherDiscoveryRuns() {
        busy = true
        scan.start()
        idle()
        assertFalse(adapter.isDiscovering)
        busy = false
        adapter.startDiscovery()
        later(30_000)
        found(board(uuids = true))
        assertNull(boards.waiting)
        adapter.cancelDiscovery()
        later(30_000)
        assertTrue(adapter.isDiscovering)
        found(board(uuids = true))
        assertEquals(r1, boards.waiting?.address)
    }

    @Test
    fun noRoundWithoutScan() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_SCAN)
        scan.start()
        idle()
        assertFalse(adapter.isDiscovering)
    }

    @Test
    fun aBulkTransferCancelsTheRound() {
        scan.start()
        idle()
        assertTrue(adapter.isDiscovering)
        Remote.scanCancel?.invoke()
        idle()
        assertFalse(adapter.isDiscovering)
    }

    @Test
    fun sdpAfterTheInquiryOneAtATimeAndOnlyForCandidates() {
        scan.start()
        idle()
        val r = board()
        val v = board("00:00:00:00:00:05", "fune v1")
        val tv = board("00:00:00:00:00:06", "KJ-49X9000F")
        found(r)
        found(v)
        found(tv, 0x08043c)
        assertEquals(0, shadowOf(r).fetchUuidsWithSdpCount)
        finish()
        val asked = listOf(r, v).filter { shadowOf(it).fetchUuidsWithSdpCount == 1 }
        assertEquals(1, asked.size)
        assertEquals(0, shadowOf(tv).fetchUuidsWithSdpCount)
        uuids(asked[0], asked[0] == r)
        val other = if (asked[0] == r) v else r
        assertEquals(1, shadowOf(other).fetchUuidsWithSdpCount)
        uuids(other, other == r)
        assertEquals(Detected("fune r1", r1), boards.waiting)
    }

    @Test
    fun anSdpTimeoutIsNotABoard() {
        scan.start()
        idle()
        val r = board()
        found(r)
        finish()
        later(BoardScan.SDP_MS)
        assertNull(boards.waiting)
    }

    @Test
    fun aBondedOrLinkedBoardIsNotOffered() {
        scan.start()
        idle()
        found(board(uuids = true, bond = BluetoothDevice.BOND_BONDED))
        linked = "00:00:00:00:00:05"
        found(board("00:00:00:00:00:05", "fune v1", uuids = true))
        assertNull(boards.waiting)
    }

    @Test
    fun pairCancelsDiscoveryBondsChecksTheUuidAndLinks() {
        scan.start()
        idle()
        val r = board(uuids = true)
        found(r)
        assertEquals(r1, boards.waiting?.address)
        scan.pair(r1, "fune r1")
        assertFalse(adapter.isDiscovering)
        assertNull(boards.waiting)
        bond(r, BluetoothDevice.BOND_BONDED)
        assertEquals(emptyList<String>(), linkedTo)
        uuids(r, true)
        assertEquals(listOf(r1), linkedTo)
    }

    @Test
    fun aBondWithoutTheUuidAsksForPhoneApp() {
        scan.start()
        idle()
        val r = board(uuids = true)
        scan.pair(r1, "fune r1")
        bond(r, BluetoothDevice.BOND_BONDED)
        uuids(r, false)
        assertEquals(emptyList<String>(), linkedTo)
        assertEquals(listOf("fune r1" to BoardScan.TURN_ON), toasts)
    }

    @Test
    fun aFailedBondToastsPairFailed() {
        scan.start()
        idle()
        val r = board(uuids = true)
        scan.pair(r1, "fune r1")
        bond(r, BluetoothDevice.BOND_NONE)
        assertEquals(listOf("fune r1" to BoardScan.PAIR_FAILED), toasts)
        assertEquals(emptyList<String>(), linkedTo)
    }

    @Test
    fun aBulkTransferHoldsTheSdpQueueUntilIdle() {
        scan.start()
        idle()
        val r = board()
        found(r)
        busy = true
        Remote.scanCancel?.invoke()
        idle()
        assertFalse(adapter.isDiscovering)
        later(BoardScan.PERIOD_MS)
        assertEquals(0, shadowOf(r).fetchUuidsWithSdpCount)
        busy = false
        later(BoardScan.PERIOD_MS)
        assertEquals(1, shadowOf(r).fetchUuidsWithSdpCount)
        assertFalse(adapter.isDiscovering)
        uuids(r, true)
        assertEquals(r1, boards.waiting?.address)
    }

    @Test
    fun aPairOutlivesAStopUntilItResolves() {
        scan.start()
        idle()
        val r = board(uuids = true)
        var done = 0
        scan.pair(r1, "fune r1") { done++ }
        scan.stop()
        bond(r, BluetoothDevice.BOND_BONDED)
        uuids(r, true)
        assertEquals(listOf(r1), linkedTo)
        assertEquals(1, done)
        assertTrue(shadowOf(app).registeredReceivers.none { it.intentFilter.hasAction(BluetoothDevice.ACTION_UUID) })
    }

    @Test
    fun aSecondPairIsRefusedWhileOneRuns() {
        scan.start()
        idle()
        val r = board(uuids = true)
        board("00:00:00:00:00:05", "fune v1", uuids = true)
        var refused = 0
        scan.pair(r1, "fune r1")
        scan.pair("00:00:00:00:00:05", "fune v1") { refused++ }
        assertEquals(1, refused)
        assertEquals(listOf("fune r1" to BoardScan.PAIRING), toasts)
        bond(r, BluetoothDevice.BOND_BONDED)
        uuids(r, true)
        assertEquals(listOf(r1), linkedTo)
    }

    @Test
    fun pairCancelsADiscoveryThatIsNotOurs() {
        scan.start()
        idle()
        finish()
        adapter.startDiscovery()
        board(uuids = true)
        scan.pair(r1, "fune r1")
        assertFalse(adapter.isDiscovering)
    }

    @Test
    fun aFastStopAndStartKeepsOneProxy() {
        val listeners = ArrayList<BluetoothProfile.ServiceListener>()
        scan.stop()
        scan = BoardScan(app, boards, { linked }, { busy }, { n, l -> toasts += n to l }, { linkedTo += it }, { now }) { listeners += it; true }
        scan.start()
        scan.stop()
        scan.start()
        val first = Shadow.newInstanceOf(BluetoothA2dp::class.java)
        listeners[0].onServiceConnected(BluetoothProfile.A2DP, first)
        listeners[1].onServiceConnected(BluetoothProfile.A2DP, Shadow.newInstanceOf(BluetoothA2dp::class.java))
        assertEquals(first, Remote.mainA2dp)
    }

    @Test
    fun aBoardThatAnswersSdpAfter10sAsksForPhoneApp() {
        scan.start()
        idle()
        val r = board(uuids = true)
        scan.pair(r1, "fune r1")
        bond(r, BluetoothDevice.BOND_BONDED)
        later(BoardScan.SDP_MS)
        uuids(r, true)
        assertEquals(listOf("fune r1" to BoardScan.TURN_ON), toasts)
        assertEquals(emptyList<String>(), linkedTo)
    }

    @Test
    fun theReceiverTakesTheBluetoothStacksBroadcasts() {
        scan.start()
        val w = shadowOf(app).registeredReceivers.single { it.intentFilter.hasAction(BluetoothDevice.ACTION_FOUND) }
        assertEquals(Context.RECEIVER_EXPORTED, w.flags and Context.RECEIVER_EXPORTED)
    }

    @Test
    fun pairOnABondedBoardChecksItsUuidsWithoutABond() {
        scan.start()
        idle()
        val r = board(uuids = true, bond = BluetoothDevice.BOND_BONDED)
        shadowOf(r).setCreatedBond(false)
        scan.pair(r1, "fune r1")
        assertEquals(true, boards.flight?.paired)
        assertEquals(1, shadowOf(r).fetchUuidsWithSdpCount)
        uuids(r, true)
        assertEquals(listOf(r1), linkedTo)
        assertEquals(emptyList<Pair<String, String>>(), toasts)
        assertNull(boards.flight)
    }

    @Test
    fun pairOnABondingBoardWaitsForItsBond() {
        scan.start()
        idle()
        val r = board(uuids = true, bond = BluetoothDevice.BOND_BONDING)
        shadowOf(r).setCreatedBond(false)
        scan.pair(r1, "fune r1")
        assertEquals(false, boards.flight?.paired)
        assertEquals(emptyList<Pair<String, String>>(), toasts)
        bond(r, BluetoothDevice.BOND_BONDED)
        assertEquals(true, boards.flight?.paired)
        uuids(r, true)
        assertEquals(listOf(r1), linkedTo)
        assertEquals(emptyList<Pair<String, String>>(), toasts)
    }

    @Test
    fun aRoundAndTheSdpQueueWaitWhileThePickerRuns() {
        scan.start()
        idle()
        val r = board()
        found(r)
        boards.picking = true
        finish()
        assertEquals(0, shadowOf(r).fetchUuidsWithSdpCount)
        later(BoardScan.PERIOD_MS)
        assertFalse(adapter.isDiscovering)
        boards.picking = false
        later(BoardScan.PERIOD_MS)
        assertEquals(1, shadowOf(r).fetchUuidsWithSdpCount)
    }

    @Test
    fun aRefusedSecondPairKeepsThatBoardWaiting() {
        scan.start()
        idle()
        found(board(uuids = true))
        found(board("00:00:00:00:00:05", "fune v1", uuids = true))
        scan.pair(r1, "fune r1")
        assertEquals("00:00:00:00:00:05", boards.waiting?.address)
        scan.pair("00:00:00:00:00:05", "fune v1")
        assertEquals("00:00:00:00:00:05", boards.waiting?.address)
    }

    @Test
    fun theReceiverIsOnTheApplicationContext() {
        scan.stop()
        val spy = object : ContextWrapper(app) {
            var calls = 0

            override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, flags: Int): Intent? {
                calls++
                return super.registerReceiver(receiver, filter, flags)
            }

            override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, permission: String?, scheduler: Handler?, flags: Int): Intent? {
                calls++
                return super.registerReceiver(receiver, filter, permission, scheduler, flags)
            }
        }
        scan = BoardScan(spy, boards, { linked }, { busy }, { n, l -> toasts += n to l }, { linkedTo += it }, { now })
        scan.start()
        assertEquals(0, spy.calls)
        assertTrue(shadowOf(app).registeredReceivers.any { it.intentFilter.hasAction(BluetoothDevice.ACTION_FOUND) })
    }

    @Test
    fun aBondPastTheFlightLimitStillLinks() {
        scan.start()
        idle()
        val r = board(uuids = true)
        scan.pair(r1, "fune r1")
        assertEquals(r1, boards.flight?.address)
        later(90_000)
        assertNull(boards.flight)
        assertFalse(adapter.isDiscovering)
        scan.pair("00:00:00:00:00:05", "fune v1")
        assertEquals(listOf("fune r1" to BoardScan.PAIRING), toasts)
        bond(r, BluetoothDevice.BOND_BONDED)
        uuids(r, true)
        assertEquals(listOf(r1), linkedTo)
        scan.stop()
        assertTrue(shadowOf(app).registeredReceivers.none { it.intentFilter.hasAction(BluetoothDevice.ACTION_UUID) })
    }
}
