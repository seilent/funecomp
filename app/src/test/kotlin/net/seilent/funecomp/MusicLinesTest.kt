package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.ui.App
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
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class MusicLinesTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var c: SimControl
    private val out = listOf("card-seq=out")
    private val album = java.lang.Long.toUnsignedString(Library.synthetic().tracks.first().id)

    private fun settle(ms: Long = 1000) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun start(pages: List<Page>, lib: Library = Library.synthetic(), faults: List<String> = emptyList()) {
        c = SimControl("v1", lib, faults = faults)
        c.queuePage(0)
        val shell = Shell(Nav(pages))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "V1", everLive = true), c, shell) }
        }
        settle()
    }

    private fun count(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun words(tag: String): List<String> =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))), useUnmergedTree = true)
            .fetchSemanticsNodes().flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text.lowercase() } }

    private fun left(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left

    private fun slide(tag: String, to: String): List<Float> {
        val at = left(tag)
        rule.onNodeWithTag("pivot.$to", useUnmergedTree = true).performClick()
        val t0 = rule.mainClock.currentTime
        val xs = ArrayList<Float>()
        while (rule.mainClock.currentTime - t0 < 400) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            assertEquals("$tag at +${rule.mainClock.currentTime - t0} ms", 1, count(tag))
            xs += left(tag) - at
        }
        assertEquals("$tag at +400 ms", at, left(tag), 0.5f)
        return xs
    }

    private fun noCardInPlaceOfTheRows(row: String) {
        assertEquals(listOf("no card"), words("status"))
        assertEquals(0, count(row))
        assertEquals(0, count("count"))
        assertEquals(0, count("line"))
    }

    @Test
    fun cardOutShowsNoCardOnceOnAlbumsAndPlaylists() {
        start(listOf(Page.Home, Page.Music), faults = out)
        noCardInPlaceOfTheRows("row.0")
        rule.pivotTo("music", "playlists") { settle(it) }
        noCardInPlaceOfTheRows("row.nowplaying")
    }

    @Test
    fun cardOutShowsNoCardOnThePlaylistsPage() {
        start(listOf(Page.Home, Page.Playlists), faults = out)
        noCardInPlaceOfTheRows("row.favorites")
    }

    @Test
    fun cardOutShowsNoCardOnTheQueue() {
        start(listOf(Page.Home, Page.NowPlaying, Page.Queue), faults = out)
        noCardInPlaceOfTheRows("queue.0")
    }

    @Test
    fun aCardErrorReadsCardError() {
        start(listOf(Page.Home, Page.Music), faults = listOf("card-seq=error"))
        assertEquals(listOf("card error"), words("status"))
        assertEquals(0, count("row.0"))
    }

    @Test
    fun theCardLineSlidesWithItsPaneThroughAPivotSwitch() {
        start(listOf(Page.Home, Page.Music), lib = Library(emptyList()))
        c.libGet(Enums.View.artists, "", 0)
        settle()
        val line = slide("line", "artists")
        c.push(Msg.STATE, c.device.playback.state.copy(card = Enums.Card.out).encode())
        settle()
        assertEquals(listOf("no card"), words("status"))
        val status = slide("status", "playlists")
        assertTrue("the empty line did not move: $line", line.any { abs(it) > 1f })
        assertEquals("frames", line.size, status.size)
        line.indices.forEach { k -> assertEquals("frame $k", line[k], status[k], 0.5f) }
    }

    @Test
    fun anEmptyLibrarySaysSoOnEachPivot() {
        start(listOf(Page.Home, Page.Music), lib = Library(emptyList()))
        assertEquals(listOf("no albums"), words("line"))
        assertEquals(0, count("status"))
        rule.pivotTo("music", "genres") { settle(it) }
        assertEquals(listOf("no genres"), words("line"))
        rule.pivotTo("music", "folders") { settle(it) }
        assertEquals(listOf("no folders"), words("line"))
    }

    @Test
    fun anEmptyQueueSaysNothingHereWithNoCount() {
        start(listOf(Page.Home, Page.NowPlaying, Page.Queue), lib = Library(emptyList()))
        assertEquals(listOf("nothing here"), words("line"))
        assertEquals(0, count("count"))
    }

    @Test
    fun aLibraryWithSongsShowsRows() {
        start(listOf(Page.Home, Page.Music))
        assertTrue(count("row.0") > 0)
        assertEquals(0, count("line"))
        assertEquals(0, count("status"))
    }

    @Test
    fun theAlbumPageKeepsItsRowsWithTheCardOutUntilItPopsOnTheCardEdge() {
        start(listOf(Page.Home, Page.Music, Page.Album(album, "First Love", "Utada Hikaru")), faults = out)
        assertTrue(count("row.0") > 0)
        assertEquals(0, count("status"))
    }
}
