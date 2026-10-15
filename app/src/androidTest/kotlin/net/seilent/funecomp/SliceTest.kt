package net.seilent.funecomp

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SliceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun text(tag: String): String {
        val nodes = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
        return nodes.firstOrNull()?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: ""
    }

    @After
    fun clearLink() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun volume() = Remote.device.playback.state.volume

    @Test
    fun nextAndVolumeStep() {
        val link = InstrumentationRegistry.getArguments().getString("link") ?: "tcp:127.0.0.1:7700"
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, Main::class.java).putExtra(Main.EXTRA_LINK, link)
        ActivityScenario.launch<Main>(intent).use {
            compose.waitUntil(15000) { text("np.title").isNotEmpty() }
            val first = text("np.title")
            val v0 = volume() ?: error("no volume")
            Log.i(TAG, "connected board=${Remote.device.hello?.board} title=$first volume=$v0")

            val max = Remote.device.playback.state.maxVolume ?: error("no max")
            val dir = if (v0 + 2 <= max) 1 else -1
            val key = if (dir > 0) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key)
            compose.waitUntil(5000) { volume() == v0 + dir }
            Log.i(TAG, "volume key ${volume()}")

            val tag = if (dir > 0) "overlay.plus" else "overlay.minus"
            compose.waitUntil(5000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(tag).performClick()
            compose.waitUntil(5000) { volume() == v0 + 2 * dir }
            Log.i(TAG, "volume tap ${volume()}")

            compose.onNodeWithTag("overlay.next").performClick()
            compose.waitUntil(5000) { text("np.title") != first }
            Log.i(TAG, "next title=${text("np.title")}")

            assertEquals(v0 + 2 * dir, volume())
        }
    }

    private companion object {
        const val TAG = "FunecompTest"
    }
}
