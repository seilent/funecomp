package net.seilent.funecomp

import android.app.Application
import android.os.Handler
import android.view.Choreographer
import androidx.compose.ui.platform.AndroidUiDispatcher
import org.robolectric.TestLifecycleApplication
import java.lang.reflect.Method
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

class TestApp : Application(), TestLifecycleApplication {
    override fun beforeTest(method: Method) = rearmUiDispatcher()

    override fun prepareTest(test: Any) {}

    override fun afterTest(method: Method) {}

    private fun rearmUiDispatcher() {
        val main = field("Main\$delegate").get(null) as Lazy<*>
        if (!main.isInitialized()) return
        val d = (main.value as CoroutineContext)[ContinuationInterceptor] as AndroidUiDispatcher
        val callback = field("dispatchCallback").get(d)
        val handler = field("handler").get(d) as Handler
        synchronized(field("lock").get(d)) {
            if (field("scheduledTrampolineDispatch").getBoolean(d) && !handler.hasCallbacks(callback as Runnable)) handler.post(callback)
            if (field("scheduledFrameDispatch").getBoolean(d)) {
                d.choreographer.removeFrameCallback(callback as Choreographer.FrameCallback)
                d.choreographer.postFrameCallback(callback)
            }
        }
    }

    private fun field(name: String) = AndroidUiDispatcher::class.java.getDeclaredField(name).apply { isAccessible = true }
}
