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
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.proto.LyrLine
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.state.LyricsFetch
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.BackdropArt
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.LyricFade
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
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class CalmWindowTest {
    private class Scale(var factor: Float) : MotionDurationScale {
        override val scaleFactor get() = factor
    }

    @get:Rule
    val rule = createComposeRule(Scale(1f))

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val owner = Owner()

    private val entry = ArtCache.Entry("name:むと", "むと", null, "deezer", null, "u", 1, sha = "s")
    private val synced = (0 until 12).map { LyrLine(it * 10_000L, "line $it") }
    private var lyrics by mutableStateOf<LyricsFetch.Show>(LyricsFetch.Show.Off)
    private lateinit var c: SimControl
    private lateinit var view: View

    private val window = 900L
    private val frame = 16L
    private val half = LyricFade.F.toLong()

    private fun start() {
        c = SimControl("ap80", minor = 7)
        val s = c.device.playback.state
        c.device = c.device.withState(s.copy(lyrics = true, lyricsPlain = false, lyricsCrc = 1, playing = false, positionMs = 25_000), SystemClock.elapsedRealtime())
        lyrics = LyricsFetch.Show.Lines(c.device.playback.state.track!!, LyricsFetch.Copy(1, Enums.LyricsKind.synced, synced))
        val shell = Shell(Nav(listOf(Page.Home, Page.NowPlaying)))
        owner.registry.currentState = Lifecycle.State.RESUMED
        rule.mainClock.autoAdvance = false
        rule.setUptimeContent {
            view = LocalView.current
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                Box(Modifier.requiredSize(411.dp, 960.dp)) {
                    App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), c, shell, art = BackdropArt(ImageBitmap(8, 8), null, entry), lyrics = lyrics, liveDevice = { c.device })
                }
            }
        }
        settle(1000)
        rule.onNodeWithTag("np.art").performClick()
        settle(1000)
        assertTrue(exists("np.lyrics"))
        assertFalse(exists("np.title"))
    }

    private fun advance(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(frame, left)
            val target = SystemClock.elapsedRealtime() + step
            rule.mainClock.advanceTimeBy(step)
            val now = SystemClock.elapsedRealtime()
            if (now < target) ShadowSystemClock.advanceBy(Duration.ofMillis(target - now))
            left -= step
        }
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        advance(ms)
        rule.waitForIdle()
    }

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun track(id: Long, on: Boolean?) {
        val s = c.device.playback.state
        lyrics = if (on == false) LyricsFetch.Show.None else LyricsFetch.Show.Blank
        c.device = c.device.withState(s.copy(track = id, title = "track $id", lyrics = on, lyricsPlain = on?.let { false }, lyricsCrc = null, playing = true, positionMs = 0), SystemClock.elapsedRealtime())
    }

    @Test
    fun aTrackChangeIntoNoLyricsLeavesOnlyAfterTheCalmWindow() {
        start()
        track(2, false)
        settle(window / frame * frame - frame)
        assertTrue("the leave fade started inside the $window ms window", view.keepScreenOn)
        settle(3 * frame)
        assertFalse("the leave fade did not start when the $window ms window ended", view.keepScreenOn)
        settle(LyricFade.T.toLong())
        assertTrue(exists("np.title"))
        assertFalse(exists("np.lyrics"))
    }

    @Test
    fun aReadThatEndsAfterTheWindowLeavesWhenItEnds() {
        start()
        track(2, null)
        settle(window / frame * frame + frame)
        assertTrue("the leave fade started before the read ended", view.keepScreenOn)
        val s = c.device.playback.state
        lyrics = LyricsFetch.Show.None
        c.device = c.device.withState(s.copy(lyrics = false, lyricsPlain = false), SystemClock.elapsedRealtime())
        settle(half)
        assertFalse("the leave fade did not start in the fade's first half of $half ms after the read ended", view.keepScreenOn)
        settle(LyricFade.T.toLong())
        assertTrue(exists("np.title"))
        assertFalse(exists("np.lyrics"))
    }
}
