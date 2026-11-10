package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.LibRow
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
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
