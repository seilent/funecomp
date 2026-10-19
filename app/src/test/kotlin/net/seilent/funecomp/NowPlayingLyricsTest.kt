package net.seilent.funecomp

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.proto.LyrLine
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.BackdropArt
import net.seilent.funecomp.ui.Control
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.state.LyricsFetch
import org.junit.Assert.assertEquals
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
class NowPlayingLyricsTest {
    @get:Rule
    val rule = createComposeRule()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val entry = ArtCache.Entry("name:むと", "むと", null, "deezer", null, "u", 1, sha = "s")
    private val shown = ArrayList<Boolean>()
    private var lyrics by mutableStateOf<LyricsFetch.Show>(LyricsFetch.Show.Off)
    private lateinit var c: SimControl
    private lateinit var view: View
    private val owner = Owner()

    private val synced = (0 until 12).map { LyrLine(it * 10_000L, "line $it") }
    private val plain = (0 until 200).map { LyrLine(null, "plain $it") }

    private fun flags(on: Boolean?, playing: Boolean = false) {
        val s = c.device.playback.state
        c.device = c.device.withState(s.copy(lyrics = on, lyricsPlain = on?.let { false }, lyricsCrc = if (on == true) 1 else null, playing = playing, positionMs = 25_000), 0)
    }

    private fun start(minor: Int = 7, on: Boolean? = true, copy: List<LyrLine>? = synced, kind: Int = Enums.LyricsKind.synced, art: Boolean = true): Shell {
        c = SimControl("ap80", minor = minor)
        flags(on)
        val track = c.device.playback.state.track!!
        lyrics = if (copy == null) LyricsFetch.Show.None else LyricsFetch.Show.Lines(track, LyricsFetch.Copy(1, kind, copy))
        val control = object : Control by c {
            override fun nowPlayingShown(on: Boolean) {
                shown += on
            }
        }
        val shell = Shell(Nav(listOf(Page.Home, Page.NowPlaying)))
        owner.registry.currentState = Lifecycle.State.RESUMED
        rule.mainClock.autoAdvance = false
        rule.setContent {
            view = LocalView.current
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                Box(Modifier.requiredSize(411.dp, 960.dp)) {
                    App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), control, shell, art = if (art) BackdropArt(ImageBitmap(8, 8), null, entry) else null, lyrics = lyrics)
                }
            }
        }
        settle(1000)
        return shell
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun enterBy(tag: String) {
        rule.onNodeWithTag(tag).performClick()
        settle(1000)
    }

    @Test
    fun theMarkAndTheArtEntryNeedBit11AndAKind() {
        start(on = null)
        assertFalse(exists("np.mark"))
        enterBy("np.art")
        assertFalse(exists("np.lyrics"))
        flags(false)
        settle(100)
        assertFalse(exists("np.mark"))
        flags(true)
        settle(100)
        assertTrue(exists("np.mark"))
        rule.onNodeWithTag("np.mark").assertIsDisplayed()
    }

    @Test
    fun aMinor6PeerWithoutBit11HasNoMarkOrEntry() {
        start(minor = 6)
        assertFalse(exists("np.mark"))
        enterBy("np.art")
        assertFalse(exists("np.lyrics"))
    }

    @Test
    fun theArtTapAndTheMarkTapEnterAndAColumnTapLeaves() {
        start()
        enterBy("np.art")
        assertTrue(exists("np.lyrics"))
        assertFalse(exists("np.title"))
        assertFalse(exists("np.mark"))
        assertFalse(exists("np.titleBlock"))
        rule.onNodeWithText("line 2").assertIsDisplayed()
        enterBy("np.lyrics")
        assertTrue(exists("np.title"))
        assertFalse(exists("np.lyrics"))
        enterBy("np.mark.hit")
        assertTrue(exists("np.lyrics"))
    }

    @Test
    fun aTapDuringTheFadeDoesNothing() {
        start()
        rule.onNodeWithTag("np.art").performClick()
        settle(200)
        rule.onNodeWithTag("np.art").performClick()
        settle(1000)
        assertTrue(exists("np.lyrics"))
        rule.onNodeWithTag("np.lyrics").performClick()
        settle(200)
        rule.onNodeWithTag("np.lyrics").performClick()
        settle(1000)
        assertTrue(exists("np.title"))
        assertFalse(exists("np.lyrics"))
    }

    private fun sheetFrom(shell: Shell, tag: String) {
        rule.onNodeWithTag(tag).performTouchInput { longClick() }
        settle(500)
        assertEquals(tag, "むと", shell.sheet.title)
        shell.sheet.close()
        settle(500)
    }

    @Test
    fun theLongPressOpensTheBlockSheetOnEverySurface() {
        val shell = start()
        sheetFrom(shell, "np.backdrop")
        sheetFrom(shell, "np.art")
        sheetFrom(shell, "np.mark.hit")
        enterBy("np.art")
        sheetFrom(shell, "np.lyrics")
        assertTrue(exists("np.lyrics"))
        lyrics = LyricsFetch.Show.Lines(c.device.playback.state.track!!, LyricsFetch.Copy(1, Enums.LyricsKind.plain, plain))
        settle(500)
        assertTrue(exists("np.lyric.plain.0"))
        sheetFrom(shell, "np.lyrics")
        assertTrue(exists("np.lyrics"))
    }

    @Test
    fun aPlainDragScrollsAndDoesNotLeave() {
        start(copy = plain, kind = Enums.LyricsKind.plain)
        enterBy("np.art")
        assertTrue(exists("np.lyric.plain.0"))
        rule.onNodeWithTag("np.lyrics").performTouchInput { swipeUp() }
        settle(2000)
        assertTrue(exists("np.lyrics"))
        assertFalse(exists("np.lyric.plain.0"))
    }

    @Test
    fun theTitleBlockOpensTheQueueOutsideTheMarkHitBox() {
        val shell = start()
        enterBy("np.mark.hit")
        assertEquals(Page.NowPlaying, shell.nav.top)
        enterBy("np.lyrics")
        rule.onNodeWithTag("np.titleBlock").performTouchInput { click(centerLeft) }
        settle(1000)
        assertEquals(Page.Queue, shell.nav.top)
    }

    private fun at(ms: Long) {
        val s = c.device.playback.state
        c.device = c.device.withState(s.copy(positionMs = ms), 0)
    }

    @Test
    fun aNewCopyInsideTheLineDipStartsFresh() {
        start()
        at(105_000)
        enterBy("np.art")
        rule.onNodeWithText("line 10").assertIsDisplayed()
        at(5_000)
        lyrics = LyricsFetch.Show.Lines(c.device.playback.state.track!!, LyricsFetch.Copy(1, Enums.LyricsKind.synced, synced.take(3)))
        settle(50)
        settle(1000)
        rule.onNodeWithText("line 0").assertIsDisplayed()
    }

    @Test
    fun aCopyForAnotherCrcIsNotShown() {
        start()
        lyrics = LyricsFetch.Show.Lines(c.device.playback.state.track!!, LyricsFetch.Copy(9, Enums.LyricsKind.synced, synced))
        enterBy("np.art")
        assertTrue(exists("np.lyrics"))
        assertTrue(rule.onAllNodesWithText("line 2").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun noneShowsTheTitle() {
        start(copy = null)
        enterBy("np.art")
        rule.onNodeWithTag("np.lyric.current.0").assertIsDisplayed()
        assertTrue(exists("np.lyrics"))
    }

    @Test
    fun theModeEndsWhenNowPlayingClosesAndTheScreenHoldFollowsIt() {
        val shell = start()
        assertEquals(listOf(true), shown)
        flags(true, playing = true)
        settle(100)
        assertFalse(view.keepScreenOn)
        enterBy("np.art")
        assertTrue(view.keepScreenOn)
        flags(true, playing = false)
        settle(100)
        assertFalse(view.keepScreenOn)
        flags(true, playing = true)
        settle(100)
        assertTrue(view.keepScreenOn)
        enterBy("np.lyrics")
        assertFalse(view.keepScreenOn)
        enterBy("np.art")
        assertTrue(view.keepScreenOn)
        owner.registry.currentState = Lifecycle.State.CREATED
        settle(100)
        assertFalse(view.keepScreenOn)
        assertEquals(listOf(true, false), shown)
        owner.registry.currentState = Lifecycle.State.RESUMED
        settle(100)
        assertTrue(view.keepScreenOn)
        shell.nav.pop()
        settle(1500)
        assertFalse(view.keepScreenOn)
        assertEquals(listOf(true, false, true, false), shown)
        shell.nav.push(Page.NowPlaying)
        settle(1500)
        assertTrue(exists("np.title"))
        assertFalse(exists("np.lyrics"))
        assertFalse(view.keepScreenOn)
    }
}
