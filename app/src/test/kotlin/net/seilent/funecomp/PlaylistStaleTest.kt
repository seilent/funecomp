package net.seilent.funecomp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import net.seilent.funecomp.proto.LibRow
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
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
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val base = Library.synthetic()
    private val road = base.tracks.take(12).map { it.id }
    private val roadKey = "/Playlists/road.m3u8"
    private val under = listOf(Page.Home, Page.Playlists)
    private val roadPage = Page.List(Enums.View.playlists, roadKey, "road")
    private var shell = Shell(Nav(under))
    private lateinit var c: SimControl
    private var link by mutableStateOf(LIVE)
    private var listed = true
    private var hold = false
    private val rootReads = ArrayList<Long?>()
    private val tops = ArrayList<Page>()
    private val plays = ArrayList<Pair<Long, Long?>>()
    private val replies = ArrayList<(Int?) -> Unit>()

    private fun startWrapped() {
        c = SimControl("v1", faults = listOf("fail=PLAY:7:1"))
        val control = object : Control by c {
            override fun libGet(view: Int, parent: String, offset: Long) {
                if (view != Enums.View.playlists || parent.isNotEmpty()) return c.libGet(view, parent, offset)
                rootReads += c.device.lib.rev
                val root = if (listed) LibList(1, mapOf(0L to LibRow("road", 2, null, "road"))) else LibList(0)
                c.device = c.device.copy(lib = c.device.lib.copy(pages = c.device.lib.pages + (LibKey(view, parent) to root)))
            }

            override fun play(view: Int?, parent: String?, start: Long, shuffle: Boolean, tracks: List<Long>, libRev: Long?, done: (Int?) -> Unit) {
                c.play(view, parent, start, shuffle, tracks, libRev, done)
                listed = false
                c.sim.lib.rev++
                c.push(Msg.STATE, c.sim.state())
            }
        }
        show(control)
    }

    private fun start(pages: List<Page> = under, faults: List<String> = emptyList()) {
        c = SimControl("v1", Library(base.tracks, playlists = listOf(Playlist("road", roadKey, road))), faults = faults)
        val control = object : Control by c {
            override fun play(view: Int?, parent: String?, start: Long, shuffle: Boolean, tracks: List<Long>, libRev: Long?, done: (Int?) -> Unit) {
                plays += start to libRev
                if (hold) replies += done else c.play(view, parent, start, shuffle, tracks, libRev, done)
            }
        }
        shell = Shell(Nav(pages))
        show(control)
    }

    private fun show(control: Control) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, link, control, shell) }
        }
        settle(1500)
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun stack() = shell.nav.stack.toList()

    private fun log(at: String) {
        System.err.println("playlistStale $at top ${shell.nav.top} stack ${stack()} plays ${c.sent.count { it == "PLAY" }} $plays failed ${c.failed} toasts ${shell.toast.shows} '${shell.toast.artist}' '${shell.toast.title}' root reads $rootReads lib rev ${c.device.lib.rev}")
    }

    private fun tapRoad(tag: String = "row.playlist.0") {
        rule.onNodeWithTag(tag).performClick()
        tops += shell.nav.top
    }

    private fun reply(code: Int?) {
        rule.runOnIdle { replies.single()(code) }
        settle(1000)
    }

    @Test
    fun aTapOnAListGoneFromTheDapReturnsToThePlaylistsOnceDecided() {
        startWrapped()
        val before = c.device.lib.rev
        tapRoad()
        log("tap")
        settle(2000)
        tops += shell.nav.top
        log("decided")
        settle(1500)
        val rows = rule.onAllNodesWithTag("row.playlist.0").fetchSemanticsNodes().size
        log("settled rows $rows")
        assertEquals(1, c.sent.count { it == "PLAY" })
        assertEquals(listOf<Page>(Page.NowPlaying, Page.Playlists), tops)
        assertEquals(under, stack())
        assertEquals(before?.plus(1), rootReads.last())
        assertEquals(0, rows)
        assertEquals(1, shell.toast.shows)
        assertEquals("playlist not readable", shell.toast.artist)
        assertEquals("road", shell.toast.title)
    }

    @Test
    fun aRowReadBeforeAnUnrelatedMoveIsSentAgainAtItsNewIndex() {
        start(under + roadPage)
        val rev = c.device.lib.rev
        rule.runOnIdle { c.sim.lib.edit(roadKey, road - road[2]) }
        tapRoad("row.5")
        settle(500)
        log("tap")
        assertEquals(Page.NowPlaying, shell.nav.top)
        rule.runOnIdle { c.push(Msg.STATE, c.sim.state()) }
        settle(1000)
        log("resent")
        assertEquals(listOf(5L to rev, 4L to null), plays)
        assertEquals(base.byId.getValue(road[5]).title, c.device.playback.state.title)
        assertEquals(under + roadPage + Page.NowPlaying, stack())
        assertEquals(0, shell.toast.shows)
        settle(2500)
        assertEquals(under + roadPage + Page.NowPlaying, stack())
    }

    @Test
    fun theLinkLeavingLiveDuringTheDecisionReturns() {
        start(faults = listOf("fail=PLAY:7:1"))
        tapRoad()
        settle(500)
        assertEquals(Page.NowPlaying, shell.nav.top)
        rule.runOnIdle { link = LinkView(Phase.DOWN, "V1", everLive = true) }
        settle(1000)
        log("down")
        assertEquals(under, stack())
        assertEquals(0, shell.toast.shows)
    }

    @Test
    fun theBoundWithTheListPresentReturnsSilently() {
        start(faults = listOf("fail=PLAY:7:1"))
        tapRoad()
        settle(1500)
        assertEquals(Page.NowPlaying, shell.nav.top)
        settle(1000)
        log("bound")
        assertEquals(under, stack())
        assertEquals(1, plays.size)
        assertEquals(0, shell.toast.shows)
    }

    @Test
    fun aBackDuringTheWaitThenALateRefusalMovesNothing() {
        hold = true
        start()
        tapRoad()
        settle(500)
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        settle(1000)
        assertEquals(under, stack())
        reply(Err.refused)
        assertEquals(under, stack())
    }

    @Test
    fun aDownOnNowPlayingDuringTheWaitThenALateRefusalMovesNothing() {
        hold = true
        start()
        tapRoad()
        settle(1000)
        rule.onRoot().performTouchInput {
            down(center)
            cancel()
        }
        settle(500)
        reply(Err.refused)
        assertEquals(under + Page.NowPlaying, stack())
    }

    @Test
    fun aLostReplyReturns() {
        hold = true
        start()
        tapRoad()
        settle(1000)
        reply(-1)
        assertEquals(under, stack())
    }

    @Test
    fun aStopThenALateRefusalNeitherReturnsNorResends() {
        hold = true
        start()
        tapRoad()
        settle(1000)
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        settle(500)
        reply(Err.refused)
        assertEquals(under + Page.NowPlaying, stack())
        assertEquals(1, plays.size)
    }

    @Test
    fun aStopThenALateStaleReplyIsNotSentAgain() {
        hold = true
        start()
        tapRoad()
        settle(1000)
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        settle(500)
        reply(Err.staleRev)
        rule.runOnIdle { c.lists { edit(roadKey, road) } }
        settle(2500)
        assertEquals(1, plays.size)
        assertEquals(under + Page.NowPlaying, stack())
    }

    private companion object {
        val LIVE = LinkView(Phase.LIVE, "V1", everLive = true)
    }
}
