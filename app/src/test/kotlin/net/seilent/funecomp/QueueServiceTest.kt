package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Feature
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.state.LyricsPlan
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
class QueueServiceTest {
    @get:Rule
    val rule = createComposeRule()

    private val lib = Library(
        listOf(Library.track("Band", "Local", "Here", 1, 200_000)),
        listOf(
            Library.track("Band", "Stream", "Song", 1, 200_000, service = "tidal", serviceKey = 167),
            Library.track("Band", "Stream", "Other", 2, 210_000, service = "tidal", serviceKey = 168),
        ),
    )
    private val library = listOf("ART_GET", "LYR_GET", "LYR_OFFSET", "ACTION", "LRC_PUT")

    private fun start(vararg faults: String, pages: List<Page>, features: Long = LINK_FEATURES or (1L shl Feature.svcLogin)): SimControl {
        val c = SimControl("v1", lib, faults = faults.toList(), appFeatures = features)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, everLive = true), c, Shell(Nav(pages))) }
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

    private fun next(c: SimControl) {
        c.transport(Enums.TransportCmd.next)
        settle(1000)
    }

    @Test
    fun aServiceRowFromTheSimPlaysWithTransportAndNoLibraryRequest() {
        val c = start(pages = listOf(Page.Home, Page.NowPlaying))
        val from = c.sent.size
        c.wanted.clear()
        next(c)
        assertEquals(Playing.Service("tidal", 167), c.device.playing)
        assertTrue(exists("np.seek"))
        assertFalse(exists("np.heart"))
        assertTrue(c.wanted.isEmpty())
        next(c)
        assertEquals(Playing.Service("tidal", 168), c.device.playing)
        assertTrue(c.wanted.isEmpty())
        assertTrue(c.sent.drop(from).none { it in library })
    }

    @Test
    fun aBit32LessAppSeesAServiceRowAsNothingPlaying() {
        val c = start(pages = listOf(Page.Home, Page.NowPlaying), features = LINK_FEATURES)
        val from = c.sent.size
        c.wanted.clear()
        next(c)
        assertEquals(Playing.None, c.device.playing)
        assertEquals("Song", c.device.playback.state.title)
        assertFalse(exists("np.seek"))
        assertTrue(c.wanted.isEmpty())
        assertTrue(c.sent.drop(from).none { it in library })
    }

    @Test
    fun theQueueListsServiceRowsAndPlansNoLyricsForThem() {
        for (f in listOf(emptyArray(), arrayOf("svc-row-zero"))) {
            val c = SimControl("v1", lib, faults = f.toList(), appFeatures = LINK_FEATURES or (1L shl Feature.svcLogin))
            c.queuePage(0)
            val items = c.device.queue.items
            assertEquals(3, items.size)
            assertEquals(listOf(null, 167L, 168L), items.values.map { it.serviceKey })
            assertTrue(items.values.drop(1).all { it.library == null })
            assertTrue(LyricsPlan.wants(c.device, emptySet()).none { it.track == 0L || it.title != "Here" })
        }
    }
}
