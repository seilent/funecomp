package net.seilent.funecomp

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.SystemClock
import android.view.View
import android.view.Window
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.espresso.Espresso
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
import net.seilent.funecomp.ui.PIN_LEAD_MS
import net.seilent.funecomp.ui.RefreshPin
import net.seilent.funecomp.ui.RefreshPin.ModeInfo
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.state.LyricsFetch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class NowPlayingLyricsTest {
    private class Scale(var factor: Float) : MotionDurationScale {
        override val scaleFactor get() = factor
    }

    private val scale = Scale(1f)

    @get:Rule
    val rule = createComposeRule(scale)

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val entry = ArtCache.Entry("name:むと", "むと", null, "deezer", null, "u", 1, sha = "s")
    private val shown = ArrayList<Boolean>()
    private var lyrics by mutableStateOf<LyricsFetch.Show>(LyricsFetch.Show.Off)
    private lateinit var c: SimControl
    private lateinit var view: View
    private lateinit var window: Window
    private val flip7 = listOf(1 to 120.00001f, 2 to 80f, 3 to 60f, 4 to 48f, 5 to 30f, 6 to 24f, 7 to 10f).map { (id, hz) -> ModeInfo(id, 1080, 2520, hz) }
    private var modes = flip7 to flip7[2]
    private var config by mutableStateOf<Configuration?>(null)
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
        rule.setUptimeContent {
            view = LocalView.current
            remember(view) {
                window = activity(view.context).window
                RefreshPin(window) { modes }.also { view.rootView.setTag(R.id.refresh_pin, it) }
            }
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalConfiguration provides (config ?: LocalConfiguration.current)) {
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
    fun theArtTapAndTheMarkTapEnterAndTheRingLeaves() {
        start()
        enterBy("np.art")
        assertTrue(exists("np.lyrics"))
        assertFalse(exists("np.title"))
        assertFalse(exists("np.mark"))
        assertFalse(exists("np.titleBlock"))
        rule.onNodeWithText("line 2").assertIsDisplayed()
        enterBy("np.lyrics")
        assertTrue(exists("np.lyrics"))
        enterBy("back")
        assertTrue(exists("np.title"))
        assertFalse(exists("np.lyrics"))
        enterBy("np.mark.hit")
        assertTrue(exists("np.lyrics"))
    }

    @Test
    fun anArtTapDuringTheEnterFadeReverses() {
        val shell = start()
        for (tag in listOf("np.art", "np.mark.hit")) {
            rule.onNodeWithTag(tag).performClick()
            settle(200)
            rule.onNodeWithTag(tag).performClick()
            settle(100)
            assertTrue(tag, exists("np.shade"))
            settle(120)
            normalOn(shell)
        }
    }

    @Test
    fun withAnimationsOffTheFadeSnapsBothWays() {
        scale.factor = 0f
        val shell = start()
        rule.onNodeWithTag("np.art").performClick()
        settle(16)
        assertTrue(exists("np.lyrics"))
        assertFalse(exists("np.title"))
        ring()
        settle(16)
        normalOn(shell)
    }

    @Test
    fun aDoubleScaleStretchesTheFadeAndTheReverse() {
        scale.factor = 2f
        val shell = start()
        rule.onNodeWithTag("np.art").performClick()
        settle(600)
        assertTrue(exists("np.title"))
        assertTrue(exists("np.shade"))
        settle(300)
        assertTrue(exists("np.lyrics"))
        assertFalse(exists("np.title"))
        settle(700)
        ring()
        settle(1600)
        normalOn(shell)
        rule.onNodeWithTag("np.art").performClick()
        settle(400)
        rule.onNodeWithTag("np.art").performClick()
        settle(300)
        assertTrue(exists("np.shade"))
        assertTrue(exists("np.title"))
        settle(120)
        normalOn(shell)
    }

    private fun normalOn(shell: Shell) {
        assertTrue(exists("np.title"))
        assertFalse(exists("np.lyrics"))
        assertFalse(exists("np.shade"))
        assertEquals(listOf(Page.Home, Page.NowPlaying), shell.nav.stack.toList())
    }

    private fun back() {
        Espresso.pressBack()
    }

    private fun ring() {
        rule.onNodeWithTag("back").performClick()
    }

    @Test
    fun theBackRingClosesTheViewAndStays() {
        val shell = start()
        enterBy("np.art")
        ring()
        settle(1000)
        normalOn(shell)
    }

    @Test
    fun androidBackClosesTheViewAndStays() {
        val shell = start()
        enterBy("np.art")
        back()
        settle(1000)
        normalOn(shell)
    }

    @Test
    fun backOutsideTheViewPops() {
        val shell = start()
        back()
        settle(1500)
        assertEquals(listOf(Page.Home), shell.nav.stack.toList())
        shell.nav.push(Page.NowPlaying)
        settle(1500)
        ring()
        settle(1500)
        assertEquals(listOf(Page.Home), shell.nav.stack.toList())
    }

    @Test
    fun aSheetOverTheViewClosesFirst() {
        val shell = start()
        enterBy("np.art")
        rule.onNodeWithTag("np.lyrics").performTouchInput { longClick() }
        settle(500)
        assertTrue(shell.sheet.shown)
        back()
        settle(500)
        assertFalse(shell.sheet.shown)
        assertTrue(exists("np.lyrics"))
        back()
        settle(1000)
        normalOn(shell)
        back()
        settle(1500)
        assertEquals(listOf(Page.Home), shell.nav.stack.toList())
    }

    @Test
    fun aScrimTapKeepsTheView() {
        val shell = start()
        enterBy("np.art")
        rule.onNodeWithTag("np.lyrics").performTouchInput { longClick() }
        settle(500)
        assertTrue(shell.sheet.shown)
        rule.onRoot().performTouchInput { click(topCenter.copy(y = top + 10f)) }
        settle(500)
        assertFalse(shell.sheet.shown)
        assertTrue(exists("np.lyrics"))
        back()
        settle(1000)
        normalOn(shell)
    }

    @Test
    fun anArtTapInTheCloseFadeDoesNothing() {
        val shell = start()
        enterBy("np.art")
        back()
        settle(500)
        assertTrue(exists("np.title"))
        rule.onNodeWithTag("np.art").performClick()
        settle(1000)
        normalOn(shell)
    }

    @Test
    fun backDuringTheEnterFadeReverses() {
        val shell = start()
        for (at in listOf(200L, 500L)) {
            rule.onNodeWithTag("np.art").performClick()
            settle(at)
            if (at > 400) assertTrue(exists("np.lyrics"))
            back()
            settle(at - 32)
            assertTrue("$at", exists("np.shade"))
            settle(40)
            normalOn(shell)
        }
    }

    @Test
    fun theScreenHoldEndsAtTheBack() {
        start()
        flags(true, playing = true)
        settle(100)
        rule.onNodeWithTag("np.art").performClick()
        settle(200)
        assertTrue(view.keepScreenOn)
        back()
        settle(20)
        assertTrue(exists("np.shade"))
        assertFalse(view.keepScreenOn)
    }

    @Test
    fun anEnterThenABackInTheSameFrame() {
        val shell = start()
        rule.onNodeWithTag("np.art").performClick()
        back()
        settle(50)
        normalOn(shell)
    }

    @Test
    fun duringAReverseBackRingAndArtDoNothing() {
        val shell = start()
        rule.onNodeWithTag("np.art").performClick()
        settle(600)
        back()
        settle(400)
        assertTrue(exists("np.title"))
        assertTrue(exists("np.shade"))
        back()
        ring()
        rule.onNodeWithTag("np.art").performClick()
        settle(1000)
        normalOn(shell)
    }

    @Test
    fun ringDuringTheEnterFadeReverses() {
        val shell = start()
        rule.onNodeWithTag("np.art").performClick()
        settle(200)
        ring()
        settle(250)
        normalOn(shell)
    }

    @Test
    fun backDuringTheCloseFadeDoesNothing() {
        val shell = start()
        for (second in listOf(::back, ::ring)) {
            if (shell.nav.top != Page.NowPlaying) shell.nav.push(Page.NowPlaying)
            settle(1500)
            enterBy("np.art")
            back()
            settle(200)
            second()
            settle(1000)
            normalOn(shell)
            back()
            settle(1500)
            assertEquals(listOf(Page.Home), shell.nav.stack.toList())
        }
    }

    private fun item(shell: Shell, text: String) {
        rule.onNodeWithTag("sheet.item.${shell.sheet.items.indexOf(text)}").performClick()
        settle(500)
    }

    @Test
    fun aPushedPageTakesBackFirst() {
        val shell = start()
        enterBy("np.art")
        rule.onNodeWithTag("np.lyrics").performTouchInput { longClick() }
        settle(500)
        item(shell, "pick image")
        rule.onNodeWithTag("sheet.item.${shell.sheet.items.indexOf("browse")}").performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertTrue(shell.nav.top is Page.Browse)
        assertTrue(exists("np.lyrics"))
        back()
        settle(1500)
        assertEquals(listOf(Page.Home, Page.NowPlaying), shell.nav.stack.toList())
        assertTrue(exists("np.lyrics"))
        rule.onNodeWithTag("np.lyrics").performTouchInput { longClick() }
        settle(500)
        item(shell, "pick image")
        item(shell, "browse")
        settle(1500)
        assertTrue(shell.nav.top is Page.Browse)
        assertFalse(exists("np.lyrics"))
        back()
        settle(1500)
        normalOn(shell)
    }

    @Test
    fun backAfterStopAndStart() {
        val shell = start()
        enterBy("np.art")
        owner.registry.currentState = Lifecycle.State.CREATED
        settle(100)
        owner.registry.currentState = Lifecycle.State.RESUMED
        settle(100)
        back()
        settle(1000)
        normalOn(shell)
    }

    @Test
    fun theFlagsDropWhileShown() {
        val shell = start()
        enterBy("np.art")
        flags(false)
        settle(100)
        assertTrue(exists("np.lyrics"))
        back()
        settle(1000)
        assertFalse(exists("np.lyrics"))
        assertEquals(listOf(Page.Home, Page.NowPlaying), shell.nav.stack.toList())
    }

    @Test
    fun aColumnTapStaysInTheView() {
        start()
        enterBy("np.art")
        enterBy("np.lyrics")
        rule.onNodeWithText("line 2").assertIsDisplayed()
        assertFalse(exists("np.title"))
        lyrics = LyricsFetch.Show.Lines(c.device.playback.state.track!!, LyricsFetch.Copy(1, Enums.LyricsKind.plain, plain))
        settle(500)
        assertTrue(exists("np.lyric.plain.0"))
        enterBy("np.lyrics")
        assertTrue(exists("np.lyric.plain.0"))
        assertFalse(exists("np.title"))
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
    fun aHeartTapDoesNotEnterLyrics() {
        start()
        enterBy("np.heart")
        assertEquals(1, c.actions.count { it.first == Enums.ActionCmd.favorite })
        assertFalse(exists("np.lyrics"))
    }

    @Test
    fun aHeartLongPressOpensNoSheet() {
        val shell = start()
        rule.onNodeWithTag("np.heart").performTouchInput { longClick() }
        settle(500)
        assertFalse(shell.sheet.shown)
        assertFalse(exists("np.lyrics"))
    }

    @Test
    fun theHeartHidesInLyricsMode() {
        start()
        assertTrue(exists("np.heart"))
        enterBy("np.art")
        assertTrue(exists("np.lyrics"))
        assertFalse(exists("np.heart"))
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
        back()
        settle(1000)
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

    private val four = "We kept on running through the rain until the city lights went out"

    private fun grow(x: String) = listOf("Light on the water", "Lights out", "Say my name again", x, "Nothing left to lose", "Under the last light", "Hold on", "Paper moon", "Quiet field")
        .zip(listOf(0L, 2000L, 4000L, 6000L, 7000L, 8000L, 9000L, 10000L, 11000L)) { t, ms -> LyrLine(ms, t) }

    private fun tagged(prefix: String) = rule.onAllNodes(SemanticsMatcher(prefix) { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true }).fetchSemanticsNodes()

    private fun below() = rule.onAllNodesWithText("Nothing left to lose").fetchSemanticsNodes().map { it.positionInRoot.y }

    @Test
    fun theRowsBelowAGrowingLineHoldThenShowLower() {
        start()
        enterBy("np.art")
        val track = c.device.playback.state.track!!
        val x = listOf("Every road leads back home", "We were young and the night was ours", "Light on the water, we were young and the night was ours", four).firstOrNull { t ->
            lyrics = LyricsFetch.Show.Lines(track, LyricsFetch.Copy(1, Enums.LyricsKind.synced, grow(t)))
            playAt(6_500)
            advance(100)
            tagged("np.lyric.current.").size == 2
        }
        assertNotNull(x)
        playAt(5_000)
        advance(100)
        val a = below().single()
        playAt(6_000)
        advance(16)
        val frames = ArrayList<Pair<Float?, Map<String, Float>>>()
        repeat(30) {
            frames += below().singleOrNull() to tagged("np.lyric.line.").associate { it.config[SemanticsProperties.TestTag] to it.boundsInRoot.bottom }
            advance(16)
        }
        val b = below().single()
        assertTrue("$a to $b", b > a + 1f)
        val sides = frames.map { (y, _) ->
            when {
                y == null -> null
                abs(y - a) <= 1f -> 1
                abs(y - b) <= 1f -> 2
                else -> throw AssertionError("line 4 at $y, not $a or $b")
            }
        }
        assertTrue(sides.contains(1))
        assertEquals(sides.filterNotNull().sorted(), sides.filterNotNull())
        assertTrue(sides.contains(2))
        for (k in 1 until frames.size) {
            if (sides[k] != sides[k - 1]) continue
            for ((tag, y1) in frames[k].second) {
                val before = frames[k - 1].second[tag] ?: continue
                assertTrue("$tag bottom $before to $y1 at frame $k", y1 <= before + 0.01f)
            }
        }
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
        back()
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
        enterBy("back")
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

    private tailrec fun activity(c: Context): Activity = if (c is Activity) c else activity((c as ContextWrapper).baseContext)

    private val f = 32L
    private val end = Tok.motion_lyricSlide.toLong()

    private fun pin() = window.attributes.preferredDisplayModeId

    private fun pos() = c.device.positionMs(SystemClock.elapsedRealtime())

    private fun to(p: Long) = advance(p - pos())

    private fun at(p: Long, id: Int) {
        to(p)
        assertEquals("pin at ${pos()}", id, pin())
    }

    private fun stays(id: Int, until: Long) {
        while (pos() < until) {
            assertEquals("pin at ${pos()}", id, pin())
            advance(16)
        }
    }

    private fun current() = tagged("np.lyric.current.").mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text }

    private fun every(gap: Long, n: Int = 12) = (0 until n).map { LyrLine(it * gap, "lyric $it") }

    private fun begin(copy: List<LyrLine>, from: Long) {
        start(copy = copy)
        enterBy("np.art")
        playAt(from)
        advance(16)
    }

    @Test
    fun aDueLinePinsFromTheLeadAndClearsAtTheSlideEnd() {
        begin(every(1000), 1300)
        at(2000 - PIN_LEAD_MS - f, 0)
        at(2000 - PIN_LEAD_MS + f, 1)
        at(2200, 1)
        at(2000 + end + f, 0)
    }

    @Test
    fun closeLinesKeepThePinAcross() {
        begin(every(400, 8), 1300)
        at(1400 - f, 0)
        to(1400 + f)
        stays(1, 2800 + end - f)
        at(2800 + end + f, 0)
    }

    @Test
    fun linesFiveHundredTwentyApartKeepThePin() {
        begin(every(520, 6), 300)
        to(320 + f)
        stays(1, 2600 + end - f)
        at(2600 + end + f, 0)
    }

    @Test
    fun aLineShorterThanTheLeadKeepsTheNextLead() {
        begin(listOf(0L, 1000L, 1150L, 2150L).mapIndexed { i, ms -> LyrLine(ms, "lyric $i") }, 500)
        to(800 + f)
        stays(1, 1150 + end - f)
        at(1150 + end + f, 0)
    }

    @Test
    fun aShortLineSnapKeepsTheNextLead() {
        begin(listOf(0L, 1000L, 1080L, 2080L).mapIndexed { i, ms -> LyrLine(ms, "lyric $i") }, 500)
        at(800 - f, 0)
        to(800 + f)
        stays(1, 1080 + end - f)
        at(1080 + end + f, 0)
    }

    @Test
    fun aLateChangeSnapsAndClears() {
        begin(every(1000), 1300)
        at(1850, 1)
        playAt(2260)
        advance(f)
        assertEquals(listOf("lyric 2"), current())
        assertEquals(0, pin())
        at(2800 - f, 0)
        at(2800 + f, 1)
    }

    @Test
    fun aPauseBeforeTheDueLineClears() {
        begin(every(1000), 1300)
        at(1850, 1)
        playAt(1850, playing = false)
        advance(f)
        assertEquals(0, pin())
        advance(1000)
        assertEquals(0, pin())
    }

    @Test
    fun aSeekForwardAwayClears() {
        begin(every(1000), 1300)
        at(1850, 1)
        playAt(5300)
        advance(f)
        assertEquals(listOf("lyric 5"), current())
        assertEquals(0, pin())
        at(5800 - f, 0)
        at(5800 + f, 1)
    }

    @Test
    fun aSeekBackAwayClears() {
        begin(every(1000), 4300)
        at(4850, 1)
        playAt(1300)
        advance(f)
        assertEquals(listOf("lyric 1"), current())
        assertEquals(0, pin())
        at(1800 - f, 0)
        at(1800 + f, 1)
    }

    @Test
    fun aSeekBackInsideTheLineDropsTheLead() {
        begin(every(1000), 2300)
        at(2850, 1)
        playAt(2250)
        advance(f)
        assertEquals(0, pin())
        at(2800 - f, 0)
        at(2800 + f, 1)
    }

    @Test
    fun aNewCopyStartsUnpinned() {
        begin(listOf(1000L, 2000L, 3000L).mapIndexed { i, ms -> LyrLine(ms, "lyric $i") }, 500)
        at(850, 1)
        val next = c.device.playback.state.track!! + 1
        lyrics = LyricsFetch.Show.Lines(next, LyricsFetch.Copy(1, Enums.LyricsKind.synced, listOf(5000L, 6000L).mapIndexed { i, ms -> LyrLine(ms, "new $i") }))
        c.device = c.device.withState(c.device.playback.state.copy(track = next, positionMs = 0), SystemClock.elapsedRealtime())
        anchor = SystemClock.elapsedRealtime() to 0L
        advance(f)
        assertEquals(0, pin())
        stays(0, 4800 - f)
        at(4800 + f, 1)
    }

    @Test
    fun aSeekForwardOneLineSlidesWithThePin() {
        begin(every(1000), 1300)
        at(1500, 0)
        playAt(2020)
        advance(f)
        assertEquals(1, pin())
        at(2000 + end + f, 0)
        at(2800 - f, 0)
    }

    @Test
    fun aResumeAcrossAStampSlidesWithThePin() {
        begin(every(1000), 1300)
        to(1950)
        playAt(1950, playing = false)
        advance(f)
        assertEquals(0, pin())
        advance(500)
        assertEquals(0, pin())
        playAt(2030)
        advance(f)
        assertEquals(1, pin())
        at(2000 + end + f, 0)
        at(2800 - f, 0)
    }

    @Test
    fun aHeldPositionNeverArms() {
        start(copy = every(1000))
        enterBy("np.art")
        val s = c.device.playback.state
        c.device = c.device.copy(rttMs = null).withState(s.copy(positionMs = 1900, playing = true, positionHeld = true, sentMs = null, sampleMs = null), SystemClock.elapsedRealtime())
        repeat(125) {
            advance(16)
            assertEquals(0, pin())
        }
    }

    @Test
    fun anRttDropMidLineStillSlidesOnTime() {
        start(copy = every(1000))
        enterBy("np.art")
        playAt(1100)
        c.device = c.device.withRtt(200)
        advance(16)
        to(2500)
        c.device = c.device.withRtt(40)
        at(2800 + f, 1)
        to(3000 - f)
        assertEquals(listOf("lyric 2"), current())
        to(3000 + 2 * f)
        assertEquals(listOf("lyric 3"), current())
        at(3000 + end + f, 0)
    }

    @Test
    fun theLastLinePinsNothing() {
        begin(every(1000, 3), 2100)
        stays(0, 4100)
    }

    @Test
    fun plainLyricsNeverPin() {
        start(copy = plain, kind = Enums.LyricsKind.plain)
        enterBy("np.art")
        playAt(1000)
        rule.onNodeWithTag("np.lyrics").performTouchInput { swipeUp() }
        repeat(125) {
            advance(16)
            assertEquals(0, pin())
        }
    }

    @Test
    fun leavingNowPlayingMidSlideClears() {
        val shell = start(copy = every(1000))
        enterBy("np.art")
        playAt(1300)
        at(2100, 1)
        shell.nav.pop()
        while (exists("np.lyrics")) advance(16)
        advance(f)
        assertEquals(0, pin())
        advance(1500)
        assertEquals(0, pin())
    }

    @Test
    fun closingTheViewMidSlideClears() {
        begin(every(1000), 1300)
        at(2100, 1)
        ring()
        advance(f)
        assertEquals(0, pin())
        while (exists("np.lyrics")) {
            assertEquals(0, pin())
            advance(16)
        }
        advance(1500)
        assertEquals(0, pin())
    }

    @Test
    fun closingTheViewBeforeALeadNeverArms() {
        begin(every(1000), 1300)
        at(1700, 0)
        ring()
        while (exists("np.lyrics")) {
            assertEquals(0, pin())
            advance(16)
        }
        assertEquals(0, pin())
    }

    private fun frozenAt(positionMs: Long?, durationMs: Long?) {
        start(copy = every(1000))
        enterBy("np.art")
        val s = c.device.playback.state
        c.device = c.device.copy(rttMs = null).withState(s.copy(positionMs = positionMs, durationMs = durationMs, playing = true, positionHeld = null, sentMs = null, sampleMs = null), SystemClock.elapsedRealtime())
        repeat(125) {
            advance(16)
            assertEquals(0, pin())
        }
    }

    @Test
    fun anEstimateClampedAtTheDurationNeverArms() = frozenAt(2100, 2100)

    @Test
    fun aMissingPositionNeverArms() = frozenAt(null, 60_000)

    @Test
    fun aNewCopyOfTheSameTrackStartsUnpinned() {
        begin(every(1000), 1300)
        at(1850, 1)
        val track = c.device.playback.state.track!!
        lyrics = LyricsFetch.Show.Lines(track, LyricsFetch.Copy(1, Enums.LyricsKind.synced, listOf(0L, 500L, 1500L, 5000L, 6000L).mapIndexed { i, ms -> LyrLine(ms, "new $i") }))
        advance(f)
        assertEquals(0, pin())
        stays(0, 4800 - f)
        at(4800 + f, 1)
    }

    @Test
    fun stopStopsArmingAndStartRearms() {
        begin(every(1000), 1300)
        at(1850, 1)
        owner.registry.currentState = Lifecycle.State.CREATED
        advance(f)
        assertEquals(0, pin())
        stays(0, 2300)
        assertEquals(listOf("lyric 1"), current())
        owner.registry.currentState = Lifecycle.State.RESUMED
        advance(f)
        assertEquals(listOf("lyric 2"), current())
        assertEquals(0, pin())
        at(2800 - f, 0)
        at(2800 + f, 1)
    }

    @Test
    fun aStopMidSlideClearsAtTheSlideEnd() {
        begin(every(1000), 1300)
        at(2100, 1)
        owner.registry.currentState = Lifecycle.State.CREATED
        at(2000 + end - f, 1)
        at(2000 + end + f, 0)
        stays(0, 3200)
    }

    @Test
    fun aDisplayChangeRepicksFromASyntheticCoverList() {
        begin(every(400, 30), 1300)
        at(1500, 1)
        val cover = listOf(ModeInfo(8, 948, 1048, 120.00001f), ModeInfo(9, 948, 1048, 60f))
        modes = cover to cover[1]
        ShadowDisplayManager.changeDisplay(0, "w361dp-h399dp-420dpi")
        advance(f)
        assertEquals(8, pin())
        modes = flip7 to flip7[2]
        ShadowDisplayManager.changeDisplay(0, "w411dp-h960dp-420dpi")
        advance(f)
        assertEquals(1, pin())
        modes = cover.take(1) to cover[0]
        ShadowDisplayManager.changeDisplay(0, "w361dp-h399dp-420dpi")
        advance(f)
        assertEquals(0, pin())
    }

    @Test
    fun aConfigurationChangeRepicksFromASyntheticCoverList() {
        begin(every(400, 30), 1300)
        at(1500, 1)
        val cover = listOf(ModeInfo(8, 948, 1048, 120.00001f), ModeInfo(9, 948, 1048, 60f))
        modes = cover to cover[1]
        advance(f)
        assertEquals(1, pin())
        config = Configuration(view.resources.configuration).apply { keyboard = if (keyboard == Configuration.KEYBOARD_QWERTY) Configuration.KEYBOARD_12KEY else Configuration.KEYBOARD_QWERTY }
        advance(f)
        assertEquals(8, pin())
    }
}
