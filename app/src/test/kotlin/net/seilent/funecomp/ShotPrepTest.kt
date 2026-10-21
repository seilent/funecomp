package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.LyrLine
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.state.LyricsFetch
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class ShotPrepTest {
    @get:Rule
    val rule = createComposeRule()

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun entersOnAnArtTap(plain: Boolean) {
        val c = SimControl("ap80")
        lyricsOn(plain)(c)
        val track = c.device.playback.state.track!!
        val kind = if (plain) Enums.LyricsKind.plain else Enums.LyricsKind.synced
        val lyrics = LyricsFetch.Show.Lines(track, LyricsFetch.Copy(1, kind, listOf(LyrLine(if (plain) null else 0L, "line 0"))))
        val shell = Shell(Nav(listOf(Page.Home, Page.NowPlaying)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), c, shell, lyrics = lyrics) }
        }
        settle(1000)
        assertFalse(exists("np.lyrics"))
        rule.onNodeWithTag("np.art").performClick()
        settle(1000)
        assertTrue(exists("np.lyrics"))
    }

    @Test
    fun theSyncedShotPrepEntersTheViewOnAnArtTap() = entersOnAnArtTap(false)

    @Test
    fun thePlainShotPrepEntersTheViewOnAnArtTap() = entersOnAnArtTap(true)
}
