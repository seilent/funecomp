package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.sim.Answer
import net.seilent.funecomp.sim.Out
import net.seilent.funecomp.state.Heart
import net.seilent.funecomp.state.LibKey
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class NowPlayingHeartTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var shell: Shell

    private fun start(minor: Int = 3, vararg faults: String, pages: List<Page> = listOf(Page.Home, Page.NowPlaying)): SimControl {
        val c = SimControl("v1", minor = minor, faults = faults.toList())
        shell = Shell(Nav(pages))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, everLive = true), c, shell) }
        }
        settle(1000)
        return c
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun tap() {
        rule.onNodeWithTag("np.heart").performClick()
        settle(300)
    }

    private fun toast() = shell.toast.artist to shell.toast.title

    private fun lit(on: Boolean) = rule.onNodeWithTag(if (on) "np.heart.on" else "np.heart.off").assertExists()

    private fun favorites(c: SimControl) = c.actions.filter { it.first == Enums.ActionCmd.favorite }

    @Test
    fun aKnownHeartAddsThenRemoves() {
        val c = start()
        val s = c.device.playback.state
        lit(false)
        tap()
        assertEquals(listOf(Enums.ActionCmd.favorite to s.track), c.actions)
        assertEquals(Heart.ADDED to s.title, toast())
        lit(true)
        tap()
        assertEquals(Heart.REMOVED to s.title, toast())
        lit(false)
        assertEquals(2, shell.toast.shows)
    }

    @Test
    fun anUnknownHeartIsShownUnlitAndBusyToastsTheCardLine() {
        val c = start(3, "favs-busy=1")
        val s = c.device.playback.state
        assertNull(s.favorite)
        lit(false)
        tap()
        assertEquals(listOf(Enums.ActionCmd.favorite to s.track), c.actions)
        assertEquals(Heart.CARD to "", toast())
        lit(false)
        tap()
        lit(true)
        assertEquals(Heart.ADDED to s.title, toast())
    }

    @Test
    fun theDeviceBusyCopyIsShown() {
        start(3, "favs-busy=1:favorites not readable")
        tap()
        assertEquals(Heart.UNREADABLE to "", toast())
    }

    @Test
    fun aStaleStateBeforeTheReplyDoesNotPickTheToast() {
        val c = start(3, "stale-state")
        tap()
        assertEquals(Heart.ADDED to c.device.playback.state.title, toast())
        lit(true)
    }

    @Test
    fun aTapWhilePendingSendsNothing() {
        val c = start()
        c.holdReply = true
        tap()
        tap()
        assertEquals(1, favorites(c).size)
        c.holdReply = false
        c.release()
        settle(300)
        assertEquals(Heart.ADDED, toast().first)
        tap()
        assertEquals(2, favorites(c).size)
    }

    @Test
    fun aTrackChangeBeforeTheReplyToastsTheTappedTitle() {
        val c = start()
        val tapped = c.device.playback.state
        c.holdReply = true
        tap()
        c.holdReply = false
        c.transport(Enums.TransportCmd.next)
        settle(300)
        assertTrue(c.device.playback.state.track != tapped.track)
        c.release()
        settle(300)
        assertEquals(tapped.track, favorites(c).single().second)
        assertEquals(Heart.ADDED to tapped.title, toast())
        lit(false)
    }

    @Test
    fun aMinor2PeerHasNoHeart() {
        val c = start(2)
        assertEquals(0, rule.onAllNodesWithTag("np.heart").fetchSemanticsNodes().size)
        rule.onNodeWithTag("np.repeat").assertExists()
        assertNull(c.device.lib.snap)
        assertEquals(c.device.lib.rev, c.device.lib.libRev(Enums.View.albums))
        assertTrue(c.actions.isEmpty())
    }

    @Test
    fun anEmptyQueueHasNoHeart() {
        val c = start()
        c.queueOp(Enums.QueueCmd.clear)
        settle(300)
        assertNull(c.device.playback.state.track)
        assertEquals(0, rule.onAllNodesWithTag("np.heart").fetchSemanticsNodes().size)
    }

    @Test
    fun aHeartTapKeepsTheSnapshotPagesAndARescanDropsThem() {
        val c = start()
        c.libGet(Enums.View.albums, "", 0)
        val album = c.device.lib.pages.getValue(LibKey(Enums.View.albums, "")).rows.getValue(0).key!!
        c.libGet(Enums.View.albumTracks, album, 0)
        val snap = c.device.lib.snapRev(Enums.View.albumTracks)
        val rev = c.device.lib.rev
        tap()
        assertTrue(c.device.lib.rev != rev)
        assertEquals(snap, c.device.lib.snapRev(Enums.View.albumTracks))
        assertNotNull(c.device.lib.pages[LibKey(Enums.View.albumTracks, album)])
        assertNotNull(c.device.lib.pages[LibKey(Enums.View.albums, "")])
        val play = c.sim.request(Msg.PLAY, Node.decode("PLAY", Req.play(Enums.View.albumTracks, album, start = 0, snapRev = snap)), object : Out {
            override fun push(type: Int, payload: ByteArray) {}
        })
        assertTrue(play is Answer.Ok)
        c.action(Enums.ActionCmd.rescan)
        assertEquals(snap!! + 1, c.device.lib.snap)
        assertTrue(c.device.lib.pages.keys.none { it.view == Enums.View.albums || it.view == Enums.View.albumTracks })
    }
}
