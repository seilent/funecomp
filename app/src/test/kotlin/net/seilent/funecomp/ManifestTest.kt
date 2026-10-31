package net.seilent.funecomp

import android.content.ComponentName
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManifestTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun theLoginHostIsNotExportedAndRunsInTheMainProcess() {
        val s = app.packageManager.getServiceInfo(ComponentName(app, SvcLoginHost::class.java), PackageManager.GET_META_DATA)
        assertFalse(s.exported)
        assertEquals(app.packageName, s.processName)
    }
}
