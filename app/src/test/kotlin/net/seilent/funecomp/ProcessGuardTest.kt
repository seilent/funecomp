package net.seilent.funecomp

import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import net.seilent.funecomp.ui.Phase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProcessGuardTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        ShadowApplication.setProcessName(app.packageName)
        Remote.stop()
        app.applicationInfo.flags = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
    }

    @Test
    fun theLoginProcessRunsNoInitAndStartsNoLink() {
        assertTrue(Main.mainProcess(app))
        ShadowApplication.setProcessName("${app.packageName}:login")
        assertFalse(Main.mainProcess(app))
        Main.init(app)
        assertEquals(0, shadowOf(app.getSystemService(ConnectivityManager::class.java)).networkCallbacks.size)
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        assertFalse(Remote.start(app, "tcp:127.0.0.1:1"))
        assertNull(Remote.session())
        assertEquals(Phase.NONE, Remote.phase)
    }
}
