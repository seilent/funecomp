package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.state.Playing
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
class NowPlayingServiceTest {
    @get:Rule
    val rule = createComposeRule()

    private val library = listOf("ART_GET", "LYR_GET", "LYR_OFFSET", "ACTION", "LRC_PUT")

    private fun start(): SimControl {
        val c = SimControl("v1")
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, everLive = true), c, Shell(Nav(listOf(Page.Home, Page.NowPlaying)))) }
        }
        settle(1000)
        return c
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun row(track: Long? = null, service: String? = "tidal") = State(
        source = Enums.Source.local, output = Enums.Output.wired, track = track, title = "Song", artist = "Band", artists = listOf("Band"),
        durationMs = 200_000, positionMs = 5_000, playing = true, slot = 0, slots = 2, lyrics = false, lyricsPlain = false,
        service = service, serviceKey = 167L.takeIf { service != null },
    )

    private fun play(c: SimControl, s: State): Int {
        val from = c.sent.size
        c.wanted.clear()
        c.push(Msg.STATE, s.encode())
        settle(1000)
        return from
    }

    @Test
    fun aServiceRowShowsTheSeekBandAndTransportAndNoLibraryParts() {
        val c = start()
        val from = play(c, row())
        assertEquals(Playing.Service("tidal", 167), c.device.playing)
        assertTrue(exists("np.seek"))
        assertTrue(exists("np.progress"))
        assertTrue(exists("np.toggle"))
        assertFalse(exists("np.heart"))
        assertFalse(exists("np.mark"))
        assertFalse(exists("np.art.image"))
        assertTrue(c.wanted.isEmpty())
        assertTrue(c.sent.drop(from).none { it in library })
        rule.onNodeWithTag("np.toggle").performClick()
        settle(300)
        assertEquals(listOf(Enums.TransportCmd.toggle), c.transports)
    }

    @Test
    fun aServiceRowBesideATrackAsksNothingForTheTrack() {
        val c = start()
        val from = play(c, row(track = 9))
        assertTrue(exists("np.seek"))
        assertFalse(exists("np.heart"))
        assertTrue(c.wanted.isEmpty())
        assertTrue(c.sent.drop(from).none { it in library })
    }

    @Test
    fun aTrackZeroIsNoTrack() {
        val c = start()
        val from = play(c, row(track = 0, service = null))
        assertEquals(Playing.None, c.device.playing)
        assertFalse(exists("np.seek"))
        assertFalse(exists("np.heart"))
        assertTrue(c.wanted.isEmpty())
        assertTrue(c.sent.drop(from).none { it in library })
    }
}
