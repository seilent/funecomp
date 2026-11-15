package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.LibRow
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.sim.Playlist
import net.seilent.funecomp.state.LibKey
import net.seilent.funecomp.state.LibList
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.Control
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class PlaylistRowsTest {
    @get:Rule
    val rule = createComposeRule()

    private val shell = Shell(Nav(listOf(Page.Home, Page.Playlists)))

    private fun start() {
        val c = SimControl("v1")
        val road = LibList(1, mapOf(0L to LibRow("road", 2, null, "road")))
        val control = object : Control by c {
            override fun libGet(view: Int, parent: String, offset: Long) {
                if (view != Enums.View.playlists) return c.libGet(view, parent, offset)
                c.device = c.device.copy(lib = c.device.lib.copy(pages = c.device.lib.pages + (LibKey(view, parent) to road)))
            }
        }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE), control, shell) }
        }
        settle(1500)
    }

    private fun startSim(): SimControl {
        val base = Library.synthetic()
        val lists = listOf(Playlist("road", ROAD, base.tracks.take(12).map { it.id }), Playlist("empty", EMPTY, emptyList()), Playlist("night", NIGHT, base.tracks.takeLast(3).map { it.id }))
        val c = SimControl("v1", Library(base.tracks, playlists = lists))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE), c, shell) }
        }
        settle(1500)
        return c
    }

    private fun hold(tag: String) {
        rule.onNodeWithTag(tag).performTouchInput { longClick() }
        settle(600)
    }

    private fun name(i: Int) = rule.onNode(hasTestTag("primary") and hasAnyAncestor(hasTestTag("row.playlist.$i")), useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString().lowercase()

    private fun count(i: Int) = rule.onAllNodes(hasText("songs", substring = true, ignoreCase = true) and hasAnyAncestor(hasTestTag("row.playlist.$i")), useUnmergedTree = true).fetchSemanticsNodes().single().config[SemanticsProperties.Text].joinToString().lowercase()

    private val listRow = SemanticsMatcher("a playlist row") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("row.playlist.") == true }

    private fun rows() = rule.onAllNodes(listRow, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun assertNamesAtTheEdge(page: String) {
        val frame = rule.onNodeWithTag("hub.$page").fetchSemanticsNode().boundsInRoot
        val u = Ui.of(frame.width, frame.height).u
        for (row in listOf("row.nowplaying", "row.favorites", "row.playlist.0")) {
            val name = rule.onNode(hasTestTag("primary") and hasAnyAncestor(hasTestTag(row)), useUnmergedTree = true).fetchSemanticsNode()
            assertEquals("$page $row", Tok.hubEdge * u, name.positionInRoot.x - frame.left, 1f)
        }
    }

    private companion object {
        const val ROAD = "/Playlists/road.m3u8"
        const val EMPTY = "/Playlists/empty.m3u8"
        const val NIGHT = "/Playlists/night.m3u8"
    }

    @Test
    fun aListWithSongsOffersThePlaysThenOpenPlaylist() {
        startSim()
        hold("row.playlist.0")
        assertEquals(listOf("play", "play next", "add to now playing", "shuffle", "open playlist"), shell.sheet.items)
    }

    @Test
    fun anEmptyListOffersOpenPlaylistAlone() {
        startSim()
        hold("row.playlist.1")
        assertEquals(listOf("open playlist"), shell.sheet.items)
    }

    @Test
    fun anEmptyListTapSendsNoPlayAndStays() {
        val c = startSim()
        rule.onNodeWithTag("row.playlist.1").performClick()
        settle(1000)
        assertEquals(0, c.sent.count { it == "PLAY" })
        assertEquals(Page.Playlists, shell.nav.top)
    }

    @Test
    fun aRenameAHideAndADeleteOnTheDapReloadTheRows() {
        val c = startSim()
        assertEquals(3, rows())
        rule.runOnIdle { c.lists { rename(ROAD, "/Playlists/trip.m3u8", "trip") } }
        settle(1500)
        assertEquals("trip", name(0))
        assertEquals("12 songs", count(0))
        assertEquals(3, rows())
        rule.runOnIdle { c.lists { hide(NIGHT) } }
        settle(1500)
        assertEquals(2, rows())
        rule.runOnIdle { c.lists { delete(EMPTY) } }
        settle(1500)
        assertEquals(1, rows())
        assertEquals("trip", name(0))
        val gets = c.sent.count { it == "LIB_GET" }
        settle(3000)
        assertEquals(gets, c.sent.count { it == "LIB_GET" })
    }

    @Test
    fun playlistRowsDrawNoRing() {
        start()
        assertNamesAtTheEdge("playlists")
        rule.runOnIdle { shell.nav.reset(listOf(Page.Home, Page.Music)) }
        settle(1500)
        rule.pivotTo("music", "playlists") { settle(it) }
        assertNamesAtTheEdge("music")
    }
}
