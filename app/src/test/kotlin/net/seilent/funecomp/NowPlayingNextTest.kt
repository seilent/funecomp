package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.Control
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.upcomingSlots
import org.junit.Assert.assertEquals
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
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class NowPlayingNextTest {
    @get:Rule
    val rule = createComposeRule()

    private val pages = ArrayList<Long>()
    private lateinit var c: SimControl

    private fun start(tracks: Int, slot: Long, repeat: Int) {
        c = SimControl("v1", lib = Library((1..tracks).map { Library.track("Band", "Album", "song $it", it, 200_000) }))
        c.transport(Enums.TransportCmd.next)
        c.queueOp(Enums.QueueCmd.playAt, slot = slot)
        c.transport(Enums.TransportCmd.repeat, repeat = repeat)
        val control = object : Control by c {
            override fun queuePage(offset: Long) {
                pages += offset
                c.queuePage(offset)
            }
        }
        val shell = Shell(Nav(listOf(Page.Home, Page.NowPlaying)))
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

    private fun nexts() = (0..2).mapNotNull { i -> rule.onAllNodesWithTag("np.next.$i").fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") }

    @Test
    fun theUpcomingSlotsWrapUnderRepeatAllAndStopAtTheEndOtherwise() {
        assertEquals(listOf(0L, 1L), upcomingSlots(2, 3, 3, true))
        assertEquals(emptyList<Long>(), upcomingSlots(2, 3, 3, false))
        assertEquals(listOf(0L), upcomingSlots(1, 3, 2, true))
        assertEquals(listOf(4L, 5L, 6L), upcomingSlots(3, 3, 10, false))
        assertEquals(listOf(9L, 0L, 1L), upcomingSlots(8, 3, 10, true))
        assertEquals(emptyList<Long>(), upcomingSlots(0, 3, 1, true))
    }

    @Test
    fun aThreeTrackQueueAtItsLastSlotShowsTheFirstTwoUnderRepeatAll() {
        start(3, 2, Enums.Repeat.all)
        assertEquals(listOf("song 1", "song 2"), nexts())
    }

    @Test
    fun aThreeTrackQueueAtItsLastSlotShowsNoneUnderRepeatOff() {
        start(3, 2, Enums.Repeat.off)
        assertEquals(emptyList<String>(), nexts())
    }

    @Test
    fun aTwoTrackQueueAtSlotOneShowsOneLineUnderRepeatAll() {
        start(2, 1, Enums.Repeat.all)
        assertEquals(listOf("song 1"), nexts())
    }

    @Test
    fun turningRepeatAllOnAtTheLastSlotPagesSlotZero() {
        start(120, 119, Enums.Repeat.off)
        assertNull(c.device.queue.items[0L])
        assertEquals(emptyList<String>(), nexts())
        pages.clear()
        c.transport(Enums.TransportCmd.repeat, repeat = Enums.Repeat.all)
        settle(1500)
        assertTrue("$pages", 0L in pages)
        assertEquals(listOf("song 1", "song 2", "song 3"), nexts())
    }
}
