package net.seilent.funecomp

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import net.seilent.funecomp.artist.Sources
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class SourcesLaunchTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val app get() = RuntimeEnvironment.getApplication()
    private var scenario: ActivityScenario<Main>? = null

    @After
    fun tearDown() {
        scenario?.close()
        Remote.stop()
        Remote.resetArtist()
        Lyrics.reset()
        Firmwares.reset()
    }

    @Test
    fun anOldInstallShowsEverySourceOnInTheNewOrderOnTheFirstFrame() {
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        app.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE).edit().putBoolean("batteryAsked", true).commit()
        app.getSharedPreferences(Main.ART_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("theaudiodb", false).putBoolean("discogs", false).putBoolean("fanarttv", false).putString("fanarttvKey", "")
            .putBoolean("deezer", false).putBoolean("youtube", false).putString("order", "commons,theaudiodb,fanarttv,discogs,youtube,deezer").commit()
        scenario = ActivityScenario.launch(Intent(app, Main::class.java).setAction(Intent.ACTION_MAIN).putExtra(Main.EXTRA_PAGE, "art"))
        rule.waitForIdle()
        val tops = Sources.ORDER.map { rule.onNodeWithTag("art.src.$it").fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops)
        for (id in listOf("theaudiodb", "youtube", "discogs", "deezer")) shows("art.src.$id", "ON")
        shows("art.src.fanarttv", "NO KEY")
        shows("art.src.commons", "ALWAYS")
    }

    private fun shows(tag: String, text: String) {
        rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).assertExists()
    }
}
