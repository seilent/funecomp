package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.artist.Sources
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.state.LibKey
import net.seilent.funecomp.state.LibList
import net.seilent.funecomp.state.LibMirror
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.homeItems
import net.seilent.funecomp.ui.statusText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class HomeEntryTest {
    @get:Rule
    val rule = createComposeRule()

    private var sim: SimControl? = null

    private fun start(w: Int, h: Int, link: LinkView = LinkView(Phase.LIVE, "AP80", everLive = true)): Shell {
        val c = SimControl("ap80").also { sim = it }
        val shell = Shell(Nav(listOf(Page.Home)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(w.dp, h.dp)) { App(c.device, link, c, shell) }
        }
        settle(1000)
        return shell
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun scrollMax() = rule.onNodeWithTag("home.column").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue()

    @Test
    fun nowPlayingIsTheFirstHomeItemWithItsStateAsSubtitle() {
        val c = SimControl("ap80")
        val live = LinkView(Phase.LIVE, "AP80")
        val items = homeItems(c.device, live, Sources())
        assertEquals(listOf("now playing", "music", "connection", "playlists", "artist art", "settings"), items.map { it.name })
        assertEquals(Page.NowPlaying, items[0].page)
        assertEquals(c.device.playback.state.title, items[0].subtitle)
        val p = c.device.playback
        val empty = c.device.copy(playback = p.copy(state = p.state.copy(track = null, title = null, slots = 0)))
        assertEquals("0 songs", homeItems(empty, live, Sources())[0].subtitle)
        val queued = c.device.copy(playback = p.copy(state = p.state.copy(track = null, title = null, slots = 12)))
        assertEquals("12 songs", homeItems(queued, live, Sources())[0].subtitle)
        val down = LinkView(Phase.DOWN, "AP80", everLive = true)
        assertEquals(c.device.playback.state.title, homeItems(c.device, down, Sources())[0].subtitle)
        assertEquals(statusText(down), homeItems(DeviceState(), down, Sources())[0].subtitle)
        assertEquals("", homeItems(DeviceState(), live, Sources())[3].subtitle)
        val none = DeviceState(lib = LibMirror(pages = mapOf(LibKey(Enums.View.playlists, "") to LibList(total = 0))))
        assertEquals("0 playlists", homeItems(none, live, Sources())[3].subtitle)
    }

    @Test
    fun thePlaylistsTotalIsFetchedAtLiveSoTheHomeShowsItsCount() {
        start(411, 960)
        val c = sim!!
        assertEquals(1, c.sent.count { it == "LIB_GET" })
        assertEquals(0L, c.device.lib.pages[LibKey(Enums.View.playlists, "")]?.total)
        assertEquals("0 playlists", homeItems(c.device, LinkView(Phase.LIVE, "AP80"), Sources())[3].subtitle)
    }

    @Test
    fun beforeTheFirstLiveNoPlaylistsPageIsAsked() {
        start(411, 960, LinkView(Phase.CONNECTING, "AP80"))
        assertEquals(0, sim!!.sent.count { it == "LIB_GET" })
    }

    @Test
    fun theNowPlayingRowOpensNowPlayingAndBackReturnsHome() {
        val shell = start(411, 960)
        rule.onNodeWithTag("home.now playing").performClick()
        settle(1000)
        assertEquals(listOf(Page.Home, Page.NowPlaying), shell.nav.stack.toList())
        shell.nav.pop()
        settle(1000)
        assertEquals(listOf(Page.Home), shell.nav.stack.toList())
    }

    @Test
    fun portraitHomeDoesNotScroll() {
        start(411, 960)
        assertEquals(0f, scrollMax())
    }

    @Test
    fun landscapeHomeScrollsToTheLastItem() {
        val shell = start(960, 600)
        assertTrue(scrollMax() > 0f)
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("home.column").performTouchInput { swipeUp() }
        rule.mainClock.autoAdvance = false
        settle(1000)
        rule.onNodeWithTag("home.settings").performClick()
        settle(1000)
        assertEquals(listOf(Page.Home, Page.Settings), shell.nav.stack.toList())
    }
}
