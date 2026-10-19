package net.seilent.funecomp

import android.os.SystemClock
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
import androidx.compose.ui.test.assertTextEquals
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
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.ExpOut6
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
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

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

    private var anchor = 0L to 0L

    private fun playAt(ms: Long, playing: Boolean = true) {
        val s = c.device.playback.state
        val now = SystemClock.elapsedRealtime()
        c.device = c.device.copy(rttMs = null).withState(s.copy(positionMs = ms, playing = playing, sentMs = null, sampleMs = null), now)
        if (playing) anchor = now to ms
    }

    private fun advance(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(16L, left)
            val target = SystemClock.elapsedRealtime() + step
            rule.mainClock.advanceTimeBy(step)
            val now = SystemClock.elapsedRealtime()
            if (now < target) ShadowSystemClock.advanceBy(Duration.ofMillis(target - now))
            left -= step
        }
    }

    private fun sinceStamp(stamp: Long) = SystemClock.elapsedRealtime() - anchor.first + anchor.second - stamp

    private fun top(text: String) = rule.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

    private val slideMs = Tok.motion_lyricSlide

    private fun rise(): Float = (top("line 9") - top("line 3")) / (6 * Tok.npNextPitch) * Tok.npLyricPitch

    private fun between(at: String, got: Float, end: Float, rise: Float, e: Long) {
        val lo = end + rise * (1f - ExpOut6.transform(((e + 16) / slideMs).coerceIn(0f, 1f))) - 1.5f
        val hi = end + rise * (1f - ExpOut6.transform(((e - 16) / slideMs).coerceIn(0f, 1f))) + 1.5f
        assertTrue("$at: $got not in $lo..$hi at $e ms", got in lo..hi)
    }

    private fun intoLine1() {
        start()
        enterBy("np.art")
        playAt(19_000)
        advance(100)
        rule.onNodeWithTag("np.lyric.current.1").assertTextEquals("line 1")
    }

    private fun midAndEnd(at: String, stamp: Long) {
        val e = sinceStamp(stamp)
        val mid = top("line 2")
        advance(400)
        val end = top("line 2")
        between(at, mid, end, rise(), e)
        assertTrue("$at: $mid moves to $end", mid > end + 1f)
    }

    @Test
    fun aLineReachedByTheEstimateSlidesOnTheCurve() {
        intoLine1()
        advance(1075)
        midAndEnd("line 2 near d / 2", 20_000)
    }

    @Test
    fun aChangeCaughtLateRunsTheRestOfTheSlide() {
        intoLine1()
        playAt(20_100)
        advance(75)
        midAndEnd("line 2 caught 100 ms late", 20_000)
    }

    @Test
    fun aPausedStatePastTheNextStampSnaps() {
        intoLine1()
        playAt(20_500, playing = false)
        advance(32)
        val snapped = top("line 2")
        advance(400)
        assertEquals(top("line 2"), snapped, 0.5f)
    }

    @Test
    fun aSeekTwoLinesOnOrBackShowsBAtOnce() {
        intoLine1()
        playAt(39_000)
        advance(32)
        val on = top("line 3")
        advance(400)
        assertEquals(top("line 3"), on, 0.5f)
        playAt(9_000)
        advance(32)
        val back = top("line 0")
        advance(400)
        assertEquals(top("line 0"), back, 0.5f)
    }

    @Test
    fun aPauseMidSlideFinishesIt() {
        intoLine1()
        advance(950)
        playAt(sinceStamp(0), playing = false)
        advance(125)
        midAndEnd("paused line 2", 20_000)
    }

    @Test
    fun aNewCopyMidSlideStartsFresh() {
        intoLine1()
        advance(1000)
        val two = (0 until 12).map { LyrLine(it * 10_000L, "two $it") }
        lyrics = LyricsFetch.Show.Lines(c.device.playback.state.track!!, LyricsFetch.Copy(1, Enums.LyricsKind.synced, two))
        advance(32)
        val fresh = top("two 2")
        advance(400)
        assertEquals(top("two 2"), fresh, 0.5f)
    }

    @Test
    fun aTrackChangeDoesNotSlide() {
        intoLine1()
        val next = c.device.playback.state.track!! + 1
        lyrics = LyricsFetch.Show.Lines(next, LyricsFetch.Copy(1, Enums.LyricsKind.synced, synced))
        c.device = c.device.withState(c.device.playback.state.copy(track = next, positionMs = 20_000), SystemClock.elapsedRealtime())
        advance(32)
        val fresh = top("line 2")
        advance(400)
        assertEquals(top("line 2"), fresh, 0.5f)
    }

    @Test
    fun leavingTheModeMidSlideLeavesNoFrameLoop() {
        intoLine1()
        advance(950)
        rule.onNodeWithTag("np.lyrics").performClick()
        advance(1000)
        assertFalse(exists("np.lyrics"))
        assertFalse(exists("np.lyric.line.2"))
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertTrue(exists("np.title"))
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
