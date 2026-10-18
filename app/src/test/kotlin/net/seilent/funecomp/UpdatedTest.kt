package net.seilent.funecomp

import android.Manifest
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.ForegroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdatedTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE)
    private val replaced = Intent(Intent.ACTION_MY_PACKAGE_REPLACED)

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        prefs.edit().putString(Main.EXTRA_LINK, SPEC).putBoolean(Main.WANTED, true).commit()
        while (shadowOf(app).nextStartedService != null) Unit
    }

    private fun receive(c: Context = app, intent: Intent = replaced) = Updated().onReceive(c, intent)

    private fun started() = shadowOf(app).nextStartedService

    @Test
    fun neverAskedSetsTheAskAndStartsNothing() {
        receive()
        assertTrue(prefs.getBoolean(Main.RECONNECT_ASK, false))
        assertNull(started())
    }

    @Test
    fun onStartsTheLinkWithTheSpec() {
        prefs.edit().putBoolean(Main.RECONNECT, true).commit()
        receive()
        val s = started()
        assertEquals(LinkService::class.java.name, s?.component?.className)
        assertEquals(SPEC, s?.getStringExtra(Main.EXTRA_LINK))
        assertTrue(s?.getBooleanExtra(LinkService.EXTRA_UPDATED, false) == true)
        assertFalse(prefs.contains(Main.RECONNECT_ASK))
    }

    @Test
    fun offStartsNothingAndAsksNothing() {
        prefs.edit().putBoolean(Main.RECONNECT, false).commit()
        receive()
        assertNull(started())
        assertFalse(prefs.contains(Main.RECONNECT_ASK))
    }

    @Test
    fun aLinkTheUserStoppedIsLeftDown() {
        prefs.edit().putBoolean(Main.RECONNECT, true).remove(Main.WANTED).commit()
        receive()
        prefs.edit().remove(Main.RECONNECT).commit()
        receive()
        assertNull(started())
        assertFalse(prefs.contains(Main.RECONNECT_ASK))
    }

    @Test
    fun noSpecOrNoPermissionDoesNothing() {
        prefs.edit().putBoolean(Main.RECONNECT, true).remove(Main.EXTRA_LINK).commit()
        receive()
        assertNull(started())
        prefs.edit().putString(Main.EXTRA_LINK, SPEC).commit()
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        receive()
        assertNull(started())
    }

    @Test
    fun otherActionsAreIgnored() {
        prefs.edit().putBoolean(Main.RECONNECT, true).commit()
        receive(intent = Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(started())
    }

    @Test
    fun backgroundRestrictedStartsNothing() {
        prefs.edit().putBoolean(Main.RECONNECT, true).commit()
        shadowOf(app.getSystemService(ActivityManager::class.java)).setBackgroundRestricted(true)
        receive()
        assertNull(started())
    }

    @Test
    fun aTaskManagerStopBeforeTheUpdateClearsWanted() {
        prefs.edit().putBoolean(Main.RECONNECT, true).commit()
        shadowOf(app.getSystemService(ActivityManager::class.java)).addApplicationExitInfo(app.packageName, 1, ApplicationExitInfo.REASON_USER_REQUESTED, 0)
        receive()
        assertNull(started())
        assertFalse(prefs.contains(Main.WANTED))
    }

    @Test
    fun aRefusedStartIsCaught() {
        prefs.edit().putBoolean(Main.RECONNECT, true).commit()
        var tried = 0
        val refusing = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent): ComponentName? {
                tried++
                throw ForegroundServiceStartNotAllowedException("background")
            }
        }
        receive(refusing)
        assertEquals(1, tried)
    }

    @Test
    fun aSecurityExceptionIsCaught() {
        prefs.edit().putBoolean(Main.RECONNECT, true).commit()
        var tried = 0
        val refusing = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent): ComponentName? {
                tried++
                throw SecurityException("connectedDevice")
            }
        }
        receive(refusing)
        assertEquals(1, tried)
    }

    @Test
    fun anUpdateKillAfterAnOlderUserStopStillStarts() {
        prefs.edit().putBoolean(Main.RECONNECT, true).commit()
        val am = shadowOf(app.getSystemService(ActivityManager::class.java))
        am.addApplicationExitInfo(app.packageName, 1, ApplicationExitInfo.REASON_USER_REQUESTED, 0)
        am.addApplicationExitInfo(app.packageName, 2, ApplicationExitInfo.REASON_PACKAGE_UPDATED, 0)
        receive()
        assertEquals(LinkService::class.java.name, started()?.component?.className)
        assertTrue(prefs.getBoolean(Main.WANTED, false))
    }

    @Test
    @Config(sdk = [33])
    fun beforeApi34AUserRequestedRecordStillStartsAndAsks() {
        prefs.edit().putBoolean(Main.RECONNECT, true).commit()
        shadowOf(app.getSystemService(ActivityManager::class.java)).addApplicationExitInfo(app.packageName, 1, ApplicationExitInfo.REASON_USER_REQUESTED, 0)
        receive()
        assertEquals(LinkService::class.java.name, started()?.component?.className)
        prefs.edit().remove(Main.RECONNECT).commit()
        receive()
        assertTrue(prefs.getBoolean(Main.RECONNECT_ASK, false))
    }

    private companion object {
        const val SPEC = "tcp:127.0.0.1:1"
    }
}
