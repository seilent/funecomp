package net.seilent.funecomp

import android.os.Looper
import androidx.compose.ui.platform.AndroidUiDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
class UiDispatcherRearmTest {
    @Test
    fun a_aDispatchLeftQueuedWhenATestEnds() {
        val main = AndroidUiDispatcher.Main
        val t = Thread { CoroutineScope(main).launch { } }
        t.start()
        t.join()
        assertFalse(shadowOf(Looper.getMainLooper()).isIdle)
    }

    @Test
    fun b_theNextTestStillRunsMainDispatcherWork() {
        val ran = AtomicBoolean(false)
        CoroutineScope(AndroidUiDispatcher.Main).launch { ran.set(true) }
        val end = System.currentTimeMillis() + 5000
        while (!ran.get() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertTrue(ran.get())
    }
}
