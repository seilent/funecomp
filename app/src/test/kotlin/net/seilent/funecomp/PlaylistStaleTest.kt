package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.LibRow
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.state.LibKey
import net.seilent.funecomp.state.LibList
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.Control
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
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
class PlaylistStaleTest {
    @get:Rule
    val rule = createComposeRule()

    private val shell = Shell(Nav(listOf(Page.Home, Page.Playlists)))
    private lateinit var c: SimControl
    private var listed = true
    private val rootReads = ArrayList<Long?>()
    private val tops = ArrayList<Page>()

    private fun start() {
        c = SimControl("v1", faults = listOf("fail=PLAY:7:1"))
        val control = object : Control by c {
            override fun libGet(view: Int, parent: String, offset: Long) {
                if (view != Enums.View.playlists || parent.isNotEmpty()) return c.libGet(view, parent, offset)
                rootReads += c.device.lib.rev
                val root = if (listed) LibList(1, mapOf(0L to LibRow("road", 2, null, "road"))) else LibList(0)
                c.device = c.device.copy(lib = c.device.lib.copy(pages = c.device.lib.pages + (LibKey(view, parent) to root)))
            }

            override fun play(view: Int?, parent: String?, start: Long, shuffle: Boolean, tracks: List<Long>) {
                c.play(view, parent, start, shuffle, tracks)
                listed = false
                c.sim.lib.rev++
                c.push(Msg.STATE, c.sim.state())
            }
        }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "V1", everLive = true), control, shell) }
        }
        settle(1500)
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun log(at: String) {
        System.err.println("playlistStale $at top ${shell.nav.top} stack ${shell.nav.stack.toList()} plays ${c.sent.count { it == "PLAY" }} failed ${c.failed} toasts ${shell.toast.shows} '${shell.toast.artist}' '${shell.toast.title}' root reads $rootReads lib rev ${c.device.lib.rev}")
    }

    @Test
    fun aTapOnAListGoneFromTheDapReturnsToThePlaylistsOnceDecided() {
        start()
        val before = c.device.lib.rev
        rule.onNodeWithTag("row.playlist.0").performClick()
        tops += shell.nav.top
        log("tap")
        settle(2000)
        tops += shell.nav.top
        log("decided")
        settle(1500)
        val rows = rule.onAllNodesWithTag("row.playlist.0").fetchSemanticsNodes().size
        log("settled rows $rows")
        assertEquals(1, c.sent.count { it == "PLAY" })
        assertEquals(listOf<Page>(Page.NowPlaying, Page.Playlists), tops)
        assertEquals(listOf(Page.Home, Page.Playlists), shell.nav.stack.toList())
        assertEquals(before?.plus(1), rootReads.last())
        assertEquals(0, rows)
    }
}
