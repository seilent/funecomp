package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class AlbumHeaderTest {
    @get:Rule
    val rule = createComposeRule()

    private var size by mutableStateOf(SIZES.first { it.name == "411x960" })

    private lateinit var shell: Shell

    private val tracks = (1..12).map { Library.track("Band", "Long", "track $it", it, 200_000) }

    private fun start(at: String, artist: String = "Band") {
        size = SIZES.first { it.name == at }
        val c = SimControl("v1", lib = Library(tracks))
        val key = java.lang.Long.toUnsignedString(tracks.first().id)
        shell = Shell(Nav(listOf(Page.Home, Page.Music, Page.Album(key, "Long", artist))))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val s = size
            Box(Modifier.requiredSize(s.w.dp, s.h.dp)) {
                App(c.device, LinkView(Phase.LIVE, "V1", everLive = true), c, shell, insets = s.bars)
            }
        }
        settle(1500)
    }

    private fun resize(at: String) {
        size = SIZES.first { it.name == at }
        settle(1500)
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun canvas(): Rect = rule.onNodeWithTag("album").fetchSemanticsNode().boundsInRoot

    private fun ui() = canvas().let { Ui.of(it.width, it.height) }

    private fun layout(tag: String): TextLayoutResult {
        val out = ArrayList<TextLayoutResult>()
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(out)
        return out.single()
    }

    private fun baseline(tag: String) = layout(tag).firstBaseline + rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y - canvas().top

    private fun top(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top - canvas().top

    private fun tap(x: Float, y: Float) {
        val c = canvas()
        val u = ui().u
        rule.onRoot().performTouchInput { click(Offset(c.left + x * u, c.top + y * u)) }
        settle(1500)
    }

    @Test
    fun albumDetailDrawsTheAlbumsWord() {
        start("411x960")
        for ((at, top) in listOf("411x960" to 74f, "sn411x438" to 58f, "wm361x343" to 58f)) {
            if (at != "411x960") resize(at)
            val s = if (at == "411x960") Ui.PORTRAIT_W / 240f else 1f
            val ui = ui()
            val c = canvas()
            val t = layout("album.header.word")
            val style = t.layoutInput.style
            assertEquals(at, "ALBUMS", t.layoutInput.text.text)
            with(rule.density) {
                assertEquals("$at size", (143f * s * ui.u).roundToInt().toFloat(), style.fontSize.toPx(), 0.5f)
                assertEquals("$at tracking", -2.895f * s * ui.u, style.letterSpacing.toPx(), 0.01f * ui.u)
            }
            assertEquals("$at weight", FontWeight.Bold, style.fontWeight)
            assertEquals("$at alpha", 1.0f, style.color.alpha, 0.005f)
            val word = rule.onNodeWithTag("album.header.word", useUnmergedTree = true).fetchSemanticsNode().positionInRoot
            assertEquals("$at x", -28f * s * ui.u, word.x - c.left, 1f)
            assertEquals("$at baseline", 52f * s * ui.u, word.y - c.top + t.firstBaseline, 1f)
            assertEquals("$at box", top * ui.u, rule.onNodeWithTag("album.header.clip", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height, 1f)
        }
    }

    @Test
    fun albumDetailHasNoBackRingAndNoStatus() {
        start("411x960")
        val tags = rule.onAllNodes(SemanticsMatcher("tagged") { it.config.getOrNull(SemanticsProperties.TestTag) != null }, useUnmergedTree = true)
            .fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
        assertTrue(tags.contains("album.header"))
        assertTrue(tags.none { it == "back" || it.startsWith("status.") })
    }

    @Test
    fun theAlbumsWordPopsThePage() {
        start("411x960")
        val w = ui().w
        tap(w / 2, 72f)
        assertTrue(shell.nav.top is Page.Album)
        tap(w / 2, 30f)
        assertEquals(Page.Music, shell.nav.top)
    }

    @Test
    fun theShortAlbumsWordPopsUnderTheBand() {
        start("sn411x438")
        tap(ui().w / 2, 65f)
        assertEquals(Page.Music, shell.nav.top)
    }

    @Test
    fun theLandscapeBlockSitsUnderTheWord() {
        start("960x600")
        for (at in listOf("960x600", "flip960x411", "1280x800")) {
            if (at != "960x600") resize(at)
            val u = ui().u
            val artist = baseline("album.artist") / u
            val size = with(rule.density) { layout("album.artist").layoutInput.style.fontSize.toPx() } / u
            assertEquals("$at artist", 86f, artist, 0.3f)
            assertEquals("$at cap top", 20f, artist - Tok.capHeight * size - baseline("album.header.word") / u, 0.5f)
            assertEquals("$at title", 110f, baseline("album.title") / u, 0.3f)
            assertEquals("$at art", 124f, top("album.art") / u, 0.3f)
            assertEquals("$at rows", 124f, top("row.0") / u, 0.3f)
        }
    }

    @Test
    fun theLandscapeTitleWithNoArtistSitsAtTheAlbumLine() {
        start("960x600", artist = "")
        assertEquals(110f, baseline("album.title") / ui().u, 0.3f)
    }
}
