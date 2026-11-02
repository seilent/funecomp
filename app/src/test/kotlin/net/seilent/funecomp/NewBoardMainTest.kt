package net.seilent.funecomp

import android.Manifest
import android.app.Activity
import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.ParcelUuid
import android.provider.Settings
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.ui.REMEDY
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.AssociationInfoBuilder

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class NewBoardMainTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val app get() = RuntimeEnvironment.getApplication()
    private val adapter: BluetoothAdapter get() = app.getSystemService(BluetoothManager::class.java).adapter
    private val open = ArrayList<ActivityController<Main>>()
    private val r1 = "00:00:00:00:00:04"

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
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
        Main.linkStart = Main.FOREGROUND
        Main.deferred = false
        open.filter { !it.get().isDestroyed }.forEach { it.pause().stop().destroy() }
        Remote.stop()
        Remote.boards.clear()
        scrobblesReset()
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        rule.waitForIdle()
    }

    private fun main(flags: Int = 0): ActivityController<Main> {
        val a = Robolectric.buildActivity(Main::class.java, Intent(app, Main::class.java).setAction(Intent.ACTION_MAIN).addFlags(flags)).create().start().resume().visible()
        open += a
        idle()
        return a
    }

    private fun found(address: String = r1) {
        val d = adapter.getRemoteDevice(address)
        shadowOf(d).setName("fune r1")
        shadowOf(d).setBondState(BluetoothDevice.BOND_NONE)
        shadowOf(d).setUuids(arrayOf(ParcelUuid.fromString(Proto.uuid)))
        app.sendBroadcast(Intent(BluetoothDevice.ACTION_FOUND).putExtra(BluetoothDevice.EXTRA_DEVICE, d))
        idle()
    }

    private fun requested(a: ActivityController<Main>) = shadowOf(a.get()).lastRequestedPermission?.requestedPermissions?.toList()

    private fun prompted(a: ActivityController<Main>) = a.get().shell.sheet.let { it.shown && it.title == "fune r1" }

    private fun failed(c: CompanionDeviceManager.Callback, code: Int) {
        c.javaClass.getMethod("onFailure", Int::class.javaPrimitiveType, CharSequence::class.java).invoke(c, code, null)
    }

    @Test
    fun theScanAskComesAtTheFirstStartOnce() {
        val a = main()
        assertEquals(listOf(Manifest.permission.BLUETOOTH_SCAN), requested(a))
        assertTrue(app.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE).getBoolean(Main.SCAN_ASKED, false))
        a.pause().stop().destroy()
        val b = main()
        assertNull(requested(b))
    }

    @Test
    fun theScanAskRidesInTheConnectAsk() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        app.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE).edit().putString(Main.EXTRA_LINK, "rfcomm:$r1").commit()
        val a = main()
        val asked = requested(a).orEmpty()
        assertTrue(asked.toString(), Manifest.permission.BLUETOOTH_CONNECT in asked && Manifest.permission.BLUETOOTH_SCAN in asked)
    }

    @Test
    fun noAskWithScanGrantedAndARoundAtStart() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN)
        val a = main()
        assertNull(requested(a))
        assertTrue(adapter.isDiscovering)
        a.pause().stop()
        assertFalse(adapter.isDiscovering)
    }

    @Test
    fun aPlayingStreamSkipsTheRound() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN)
        val was = Remote.phoneA2dp
        Remote.phoneA2dp = { true }
        try {
            main()
            assertFalse(adapter.isDiscovering)
        } finally {
            Remote.phoneA2dp = was
        }
    }

    @Test
    fun aDeniedScanRunsNoRound() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_SCAN)
        app.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE).edit().putBoolean(Main.SCAN_ASKED, true).commit()
        main()
        assertFalse(adapter.isDiscovering)
    }

    @Test
    fun aFoundBoardPromptsAndACancelOutlivesARecreate() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN)
        val a = main()
        found()
        assertTrue(prompted(a))
        a.get().shell.sheet.accept(1)
        idle()
        a.recreate()
        idle()
        found()
        assertFalse(prompted(a))
    }

    @Test
    fun aWaitingPromptOutlivesARecreate() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN)
        val a = main()
        found()
        assertTrue(prompted(a))
        a.recreate()
        idle()
        assertTrue(prompted(a))
    }

    @Test
    fun leavingTheFrontDropsThePrompt() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN)
        val a = main()
        found()
        a.pause().stop()
        assertNull(Remote.boards.waiting)
    }

    @Test
    fun aFailedPickerOpensBluetoothSettingsWithTheToast() {
        val a = main()
        a.get().control.pairDevice()
        idle()
        shadowOf(app.getSystemService(CompanionDeviceManager::class.java)).lastAssociationCallback.onFailure("no device")
        idle()
        assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, shadowOf(app).nextStartedActivity?.action)
        assertEquals(Main.PAIR_A_DEVICE, a.get().shell.toast.artist)
        assertEquals(REMEDY, a.get().shell.toast.title)
    }

    @Test
    fun anOkPickWithNoDeviceForgetsTheAssociation() {
        val a = main()
        val cdm = app.getSystemService(CompanionDeviceManager::class.java)
        val info = AssociationInfoBuilder.newBuilder().setId(7).setPackageName(app.packageName).setDisplayName("fune r1").build()
        shadowOf(cdm).addAssociation(info)
        a.get().control.pairDevice()
        idle()
        shadowOf(cdm).lastAssociationCallback.onDeviceFound(PendingIntent.getActivity(app, 0, Intent(), PendingIntent.FLAG_IMMUTABLE).intentSender)
        idle()
        val sent = shadowOf(a.get()).nextStartedActivityForResult
        shadowOf(a.get()).callOnActivityResult(sent.requestCode, Activity.RESULT_OK, Intent().putExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, info))
        idle()
        assertTrue(cdm.myAssociations.none { it.id == 7 })
    }

    @Test
    fun aPickerCancelOrRejectClosesQuietly() {
        val a = main()
        shadowOf(app).clearNextStartedActivities()
        val cdm = app.getSystemService(CompanionDeviceManager::class.java)
        for (code in listOf(CompanionDeviceManager.RESULT_CANCELED, CompanionDeviceManager.RESULT_USER_REJECTED)) {
            a.get().control.pairDevice()
            idle()
            assertTrue(Remote.boards.picking)
            failed(shadowOf(cdm).lastAssociationCallback, code)
            idle()
            assertFalse(Remote.boards.picking)
            assertNull(shadowOf(app).nextStartedActivity)
            assertFalse(a.get().shell.toast.artist == Main.PAIR_A_DEVICE)
        }
    }

    @Test
    fun aPickerTimeoutOpensBluetoothSettingsWithTheToast() {
        val a = main()
        a.get().control.pairDevice()
        idle()
        failed(shadowOf(app.getSystemService(CompanionDeviceManager::class.java)).lastAssociationCallback, CompanionDeviceManager.RESULT_DISCOVERY_TIMEOUT)
        idle()
        assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, shadowOf(app).nextStartedActivity?.action)
        assertEquals(Main.PAIR_A_DEVICE, a.get().shell.toast.artist)
    }

    @Test
    fun aPickerResultAfterMainIsGoneOnlyClearsPicking() {
        val a = main()
        a.get().control.pairDevice()
        idle()
        assertTrue(Remote.boards.picking)
        a.pause().stop().destroy()
        idle()
        shadowOf(app.getSystemService(CompanionDeviceManager::class.java)).lastAssociationCallback.onDeviceFound(PendingIntent.getActivity(app, 0, Intent(), PendingIntent.FLAG_IMMUTABLE).intentSender)
        idle()
        assertFalse(Remote.boards.picking)
    }

    @Test
    fun aLinkStartRefusedInTheBackgroundWaitsForTheNextStart() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN)
        val a = main()
        Main.linkStart = { _, _ -> throw ForegroundServiceStartNotAllowedException("background") }
        a.get().control.choose(r1)
        idle()
        assertNull(shadowOf(app).nextStartedService)
        Main.linkStart = Main.FOREGROUND
        a.pause().stop().restart().resume()
        idle()
        assertEquals(LinkService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun aLinkStartRefusedThenMainReopenedFromRecentsLinks() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN)
        val a = main()
        Main.linkStart = { _, _ -> throw ForegroundServiceStartNotAllowedException("background") }
        a.get().control.choose(r1)
        idle()
        a.pause().stop().destroy()
        idle()
        Main.linkStart = Main.FOREGROUND
        main(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertEquals(LinkService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
        assertNull(shadowOf(app).nextStartedService)
    }
}
