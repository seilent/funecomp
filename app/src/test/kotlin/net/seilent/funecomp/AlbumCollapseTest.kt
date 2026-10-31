package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.ThumbMath
import net.seilent.funecomp.ui.ThumbSpan
import net.seilent.funecomp.ui.Ui
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class AlbumCollapseTest {
    @get:Rule
    val rule = createComposeRule()

    private var size by mutableStateOf(SIZES.first { it.name == "cover474x419" })

    private val tracks = (1..12).map { Library.track("Band", "Long", "track $it", it, 200_000) } + (1..3).map { Library.track("Band", "Mid", "mid $it", it, 200_000) }

    private fun key(album: String) = java.lang.Long.toUnsignedString(tracks.first { it.album == album }.id)

    private fun start(album: String = "Long"): SimControl {
        val c = SimControl("ap80", lib = Library(tracks))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val s = size
            Box(Modifier.requiredSize(s.w.dp, s.h.dp)) {
                App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), c, Shell(Nav(listOf(Page.Home, Page.Music, Page.Album(key(album), album, "Band")))), insets = s.bars)
            }
        }
        settle(1000)
        return c
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun firstRowBelow(y: Float) = (0 until 12).first { i -> rule.onAllNodesWithTag("row.$i").fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.let { it.bottom > y + 1f } == true }

    private fun ui(): Ui {
        val f = bounds("album")
        return Ui.of(f.width, f.height)
    }

    private fun scrollBy(px: Float) {
        rule.onNodeWithTag("rows").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, px) }
        settle(SCROLL_MS)
    }

    private fun thumb(): IntRange? {
        val ui = ui()
        val img = rule.onNodeWithTag("album").pixels()
        System.gc()
        return thumbRun(img, img.width - ui.px(Tok.scrollbar_width).roundToInt() / 2, ui.px(3f).roundToInt())
    }

    private fun titleDescent(): Float {
        val out = ArrayList<TextLayoutResult>()
        rule.onNodeWithTag("album.title").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(out)
        return out.single().let { it.size.height - it.firstBaseline }
    }

    private fun rest() {
        rule.onNodeWithTag("rows").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -1e6f) }
        settle(1000)
    }

    @Test
    fun theArtCollapsesThenDocksAndTheFirstRowStaysOnTheRowsTop() {
        start()
        for (name in listOf("cover474x419", "sn411x438")) {
            size = SIZES.first { it.name == name }
            settle(1000)
            rest()
            val ui = ui()
            val top = bounds("album").top
            var at = 0
            for (o in listOf(0f, 31.5f, 63f, 72.3f, 89f, 115f, 140f)) {
                val px = (o * ui.u).roundToInt()
                scrollBy((px - at).toFloat())
                at = px
                val n = "$name $o"
                val held = px / ui.u
                val y = min(held, 115f)
                val side = 116f - min(y, 63f)
                val dock = max(0f, y - 63f)
                val rowsTop = 252f - y
                val art = bounds("album.art")
                assertEquals(n, (124f - dock) * ui.u, art.top - top, 1.5f)
                assertEquals(n, side * ui.u, art.height, 1.5f)
                val rise = 124f - 110f - titleDescent() / ui.u
                assertEquals(n, 16f + 61f * min(dock, rise) / rise, bounds("album.title").left / ui.u, 0.5f)
                val first = if (o <= 115f) 0 else 1
                val rowTop = rowsTop + first * Tok.rowHeight - (held - y)
                assertEquals(n, rowTop * ui.u, bounds("row.$first").top - top, 1.5f)
                if (o in listOf(31.5f, 115f, 140f)) {
                    val run = thumb()
                    assertTrue(n, run != null)
                    val view = ((ui.h - rowsTop) * ui.u).roundToInt()
                    val content = (12 * Tok.rowHeight * ui.u).roundToInt().toLong()
                    val bar = ThumbMath.of(ThumbSpan(0, view, view.toLong(), content, ((held - y) * ui.u).roundToLong()), ui.px(Tok.scrollbar_min).roundToInt(), ui.u.roundToInt())!!
                    assertEquals(n, rowsTop * ui.u + bar.y, run!!.first.toFloat(), 2f)
                    assertEquals(n, bar.len.toFloat(), (run.last - run.first + 1).toFloat(), 2f)
                }
            }
        }
    }

    @Test
    fun aListThatFitsAfterTheCollapseShowsNoThumb() {
        start("Mid")
        val ui = ui()
        scrollBy(5f * ui.u)
        assertEquals(111f * ui.u, bounds("album.art").height, 1.5f)
        assertNull(thumb())
    }

    @Test
    fun theFocusBarSitsOnTheTappedRowAtRestAndDocked() {
        start()
        for (o in listOf(0f, 140f)) {
            rest()
            val ui = ui()
            scrollBy(o * ui.u)
            rule.onNodeWithTag("row.1").performClick()
            settle(1000)
            assertEquals("$o", bounds("row.1").top, bounds("bar").top, 1.5f)
            assertEquals("$o", 1, rule.onAllNodesWithTag("bar").fetchSemanticsNodes().size)
        }
    }

    @Test
    fun aResizeKeepsTheFirstVisibleRowAndRestStaysAtRest() {
        start()
        val cover = size
        val ui = ui()
        assertEquals(0f, bounds("row.0").top - bounds("album").top - 252f * ui.u, 1.5f)
        size = SIZES.first { it.name == "411x960" }
        settle(1000)
        size = cover
        settle(1000)
        assertEquals(0f, bounds("row.0").top - bounds("album").top - 252f * ui.u, 1.5f)
        scrollBy((115f + 2.5f * Tok.rowHeight) * ui.u)
        val first = firstRowBelow(bounds("album").top + 137f * ui.u)
        assertEquals(2, first)
        size = SIZES.first { it.name == "411x960" }
        settle(1000)
        assertEquals(first, firstRowBelow(bounds("rows").top))
        size = cover
        settle(1000)
        assertEquals(first, firstRowBelow(bounds("album").top + 137f * ui.u))
    }

    @Test
    fun aMidCollapseComesBackAfterPortrait() {
        start()
        val cover = size
        val ui = ui()
        scrollBy(31.5f * ui.u)
        assertEquals((116f - 31.5f) * ui.u, bounds("album.art").height, 1.5f)
        size = SIZES.first { it.name == "411x960" }
        settle(1000)
        assertEquals(0, firstRowBelow(bounds("rows").top))
        size = cover
        settle(1000)
        assertEquals((116f - 31.5f) * ui.u, bounds("album.art").height, 1.5f)
        assertEquals(220.5f * ui.u, bounds("row.0").top - bounds("album").top, 1.5f)
    }

    @Test
    fun portraitAndLandscapeAlbumPagesDoNotCollapse() {
        start()
        for (name in listOf("411x960", "960x600")) {
            size = SIZES.first { it.name == name }
            settle(1000)
            rest()
            val art = bounds("album.art")
            val row = bounds("row.2").top
            val ui = ui()
            scrollBy(60f * ui.u)
            assertEquals(name, art, bounds("album.art"))
            assertEquals(name, row - 60f * ui.u, bounds("row.2").top, 1.5f)
            assertEquals(name, Tok.tileL * ui.u, art.height, 1.5f)
        }
    }

    private companion object {
        const val SCROLL_MS = 600L
    }
}
