package net.seilent.funecomp

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.LyrLine
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.state.Playback
import net.seilent.funecomp.state.LyricsFetch
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
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
class NowPlayingSeenTest {
    @get:Rule
    val rule = createComposeRule()

    private val lines = (0 until 12).map { LyrLine(it * 10_000L, "line $it") }
    private var lyrics by mutableStateOf<LyricsFetch.Show>(LyricsFetch.Show.Off)
    private val shell = Shell(Nav(listOf(Page.Home, Page.NowPlaying)))

    private fun start(c: SimControl) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), c, shell, lyrics = lyrics, liveDevice = { c.device }) }
        }
        settle(1000)
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun shows(line: String) = rule.onAllNodes(hasText(line) and hasAnyAncestor(hasTestTag("np.lyrics"))).fetchSemanticsNodes().isNotEmpty()

    private fun timed(c: SimControl, now: Long) {
        val s = c.device.playback.state
        c.device = c.device.withState(s.copy(lyrics = true, lyricsPlain = false, lyricsCrc = 1, playing = false, positionMs = 25_000), now)
        lyrics = LyricsFetch.Show.Lines(s.track!!, LyricsFetch.Copy(1, Enums.LyricsKind.synced, lines))
    }

    @Test
    fun aFirstStateAtTimeZeroShowsTheLyrics() {
        val c = SimControl("ap80")
        c.mode = true
        timed(c, 0)
        start(c)
        assertTrue(exists("np.lyrics"))
        assertTrue(shows("line 2"))
    }

    @Test
    fun aLaterStateAtTimeZeroKeepsTheLyrics() {
        val c = SimControl("ap80")
        c.mode = true
        timed(c, SystemClock.elapsedRealtime())
        start(c)
        assertTrue(shows("line 2"))
        c.push(Msg.STATE, c.device.playback.state.encode())
        settle(1000)
        assertEquals(0L, c.device.playback.receivedAt)
        assertTrue(shows("line 2"))
    }

    @Test
    fun anUnseenPlaybackWithATrackShowsNoLyricsAndTakesNoArtTap() {
        val c = SimControl("ap80")
        val track = c.device.playback.state.track!!
        c.device = DeviceState(c.device.hello, playback = Playback(State(source = Enums.Source.local, track = track, lyrics = true, lyricsPlain = false, lyricsCrc = 1)))
        lyrics = LyricsFetch.Show.Lines(track, LyricsFetch.Copy(1, Enums.LyricsKind.synced, lines))
        c.mode = true
        start(c)
        assertFalse(exists("np.lyrics"))
        c.mode = false
        settle(100)
        rule.onNodeWithTag("np.art").performClick()
        settle(600)
        assertFalse(c.mode)
        assertEquals(0, shell.toast.shows)
    }

    @Test
    fun theFirstStateAfterNoneShowsThePhoneWithNoSwap() {
        val c = SimControl("ap80")
        val hello = c.device.hello
        c.device = DeviceState(hello)
        start(c)
        val phone = State(
            source = Enums.Source.phone, output = Enums.Output.phoneReceiver, track = 0, title = "Signal Fire", artist = "Kaze Ensemble", durationMs = 213_000,
            positionMs = 61_000, playing = true, receiver = true, phoneName = "Phone 7",
        )
        c.device = c.device.withState(phone, SystemClock.elapsedRealtime())
        settle(32)
        assertTrue(exists("np.from"))
    }
}
