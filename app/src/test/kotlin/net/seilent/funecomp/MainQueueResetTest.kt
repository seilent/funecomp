package net.seilent.funecomp

import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class MainQueueResetTest {
    @Test
    fun a_aMainPostLeftQueuedWhenATestEnds() {
        Handler(Looper.getMainLooper()).post { ran.set(true) }
        assertFalse(shadowOf(Looper.getMainLooper()).isIdle)
        posted.set(true)
    }

    @Test
    fun b_theNextTestStartsWithoutIt() {
        assertTrue(posted.get())
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(ran.get())
    }

    companion object {
        private val posted = AtomicBoolean(false)
        private val ran = AtomicBoolean(false)
    }
}
