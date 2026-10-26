package net.seilent.funecomp

import android.app.Application
import android.os.Handler
import android.view.Choreographer
import androidx.compose.ui.platform.AndroidUiDispatcher
import net.seilent.funecomp.artist.UrlHttp
import org.robolectric.TestLifecycleApplication
import java.io.IOException
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

class TestApp : Application(), TestLifecycleApplication {
    override fun beforeTest(method: Method) {
        blocked.clear()
        UrlHttp.open = {
            blocked += "$it on ${Thread.currentThread().name}"
            throw IOException("tests never reach the network: $it")
        }
        Remote.resetArtist()
        rearmUiDispatcher()
    }

    override fun prepareTest(test: Any) {}

    override fun afterTest(method: Method) {
        if (blocked.isNotEmpty()) throw AssertionError("${method.name} opened the network: ${blocked.distinct()}")
    }

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

    companion object {
        val blocked = CopyOnWriteArrayList<String>()
    }
}
