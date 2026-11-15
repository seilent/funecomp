package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Tags
import net.seilent.funecomp.state.CardView
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.Control
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
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
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class SongSheetAddTest {
    @get:Rule
    val rule = createComposeRule()

    private val shell = Shell(Nav(listOf(Page.Home, Page.Music)))
    private val logs = ArrayList<String>()
    private var dropped = false

    private fun start(faults: List<String> = emptyList()): SimControl {
        val c = SimControl("ap80", faults = faults)
        val control = object : Control by c {
            override fun queueAdd(cmd: Int, view: Int?, parent: String?, tracks: List<Long>, done: (Long?, Int?) -> Unit) {
                if (dropped) done(null, -1) else c.queueAdd(cmd, view, parent, tracks, done)
            }

            override fun log(message: String) {
                logs += message
            }
        }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), control, shell) }
        }
        settle()
        rule.pivotTo("music", "songs") { settle() }
        return c
    }

    private fun settle() {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
    }

    private fun playNext(): String {
        rule.onNodeWithTag("row.0").performTouchInput { longClick() }
        settle()
        assertEquals("play next", shell.sheet.items[1])
        val name = shell.sheet.title
        rule.runOnIdle { shell.sheet.accept(1) }
        settle()
        return name
    }

    @Test
    fun aPlayNextToastsTheDevicesWordsAfterItsReply() {
        val c = start()
        val shows = shell.toast.shows
        val name = playNext()
        val b = Node.decode("QUEUE_OP", c.adds.single())
        assertEquals(Enums.QueueCmd.playNext, b.int(Tags.QueueOp.cmd))
        assertEquals(1, b.longs(Tags.QueueOp.track).size)
        assertEquals(shows + 1, shell.toast.shows)
        assertEquals("song added to now playing", shell.toast.artist)
        assertEquals(name, shell.toast.title)
    }

    @Test
    fun aPlayNextWithNoSessionShowsNoToast() {
        start()
        dropped = true
        val shows = shell.toast.shows
        playNext()
        assertEquals(shows, shell.toast.shows)
        assertTrue(logs.any { it == "queue add error -1" })
    }

    @Test
    fun aStaleRevShowsNoToast() {
        val c = start(listOf("fail=QUEUE_OP:${Err.staleRev}:1"))
        val shows = shell.toast.shows
        playNext()
        assertTrue(Err.staleRev in c.failed)
        assertEquals(shows, shell.toast.shows)
    }

    @Test
    fun noCardToastsCardNotAvailable() {
        start(listOf("fail=QUEUE_OP:${Err.noCard}:1"))
        val shows = shell.toast.shows
        playNext()
        assertEquals(shows + 1, shell.toast.shows)
        assertEquals(CardView.REFUSED, shell.toast.artist)
        assertEquals("", shell.toast.title)
    }

    @Test
    fun anOkWithNoCountToastsTheSongForm() {
        start(listOf("features=${Groups.all and Groups.mask("queueAdd").inv()}"))
        val shows = shell.toast.shows
        val name = playNext()
        assertEquals(shows + 1, shell.toast.shows)
        assertEquals("song added to now playing", shell.toast.artist)
        assertEquals(name, shell.toast.title)
    }
}
