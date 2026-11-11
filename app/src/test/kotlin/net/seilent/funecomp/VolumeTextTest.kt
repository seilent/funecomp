package net.seilent.funecomp

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LayoutTok
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
import kotlin.math.max

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class VolumeTextTest {
    @get:Rule
    val rule = createComposeRule()

    private var size by mutableStateOf(SIZES.first { it.name == "411x960" })
    private lateinit var c: SimControl

    private fun start(at: String, state: (State) -> State = { it }) {
        c = SimControl("v1")
        c.device = c.device.withState(state(c.device.playback.state), 0)
        size = SIZES.first { it.name == at }
        val shell = Shell(Nav(listOf(Page.Home)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val s = size
            Box(Modifier.requiredSize(s.w.dp, s.h.dp)) { App(c.device, LinkView(Phase.LIVE, "V1", everLive = true), c, shell, insets = s.bars) }
        }
        settle(1000)
        shell.overlay.show()
        settle(400)
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun root() = rule.onNodeWithTag("overlay").fetchSemanticsNode().boundsInRoot

    private fun ui() = root().let { Ui.of(it.width, it.height) }

    private fun node(tag: String): SemanticsNode = rule.onAllNodes(hasTestTag(tag) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes().single()

    private fun layout(n: SemanticsNode): TextLayoutResult {
        val out = ArrayList<TextLayoutResult>()
        n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(out)
        return out.single()
    }

    private fun text(tag: String) = node(tag).config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: ""

    private fun px(t: TextLayoutResult) = with(rule.density) { t.layoutInput.style.fontSize.toPx() }

    private fun baseline(tag: String): Float {
        val n = node(tag)
        return n.positionInRoot.y + layout(n).firstBaseline
    }

    private fun caps(tag: String): Float {
        val n = node(tag)
        val t = layout(n)
        return n.positionInRoot.y + t.firstBaseline - Tok.capHeight * px(t) / 2
    }

    private fun ink(tag: String): Rect {
        val n = node(tag)
        val t = layout(n)
        val s = t.layoutInput.style
        val paint = Paint().apply {
            typeface = t.layoutInput.fontFamilyResolver.resolve(s.fontFamily, s.fontWeight ?: FontWeight.Normal).value as Typeface
            textSize = with(rule.density) { s.fontSize.toPx() }
            letterSpacing = with(rule.density) { s.letterSpacing.toPx() } / textSize
        }
        val path = Path()
        paint.getTextPath(t.layoutInput.text.text, 0, t.layoutInput.text.length, 0f, 0f, path)
        val r = RectF().also { path.computeBounds(it, true) }
        val base = n.positionInRoot.y + t.firstBaseline
        return Rect(n.positionInRoot.x + r.left, base + r.top, n.positionInRoot.x + r.right, base + r.bottom)
    }

    private fun textY() = root().top + LayoutTok.vol_textY * ui().h / LayoutTok.canvasH * ui().u

    private fun capsRule(): Float {
        val u = ui().u
        return max(textY(), caps("overlay.volume") + LayoutTok.vol_textGap * u)
    }

    @Test
    fun theArtistCapsCentreOnTextYAndTheTitleSitsItsPitchUnder() {
        start("411x960") { it.copy(artist = "Utada Hikaru", title = "First Love") }
        val ui = ui()
        val u = ui.u
        val o = root()
        assertEquals(textY(), caps("overlay.artist"), 1f)
        assertEquals(baseline("overlay.artist") + LayoutTok.vol_textPitch * u, baseline("overlay.title"), 1f)
        for (tag in listOf("overlay.artist", "overlay.title")) assertEquals(tag, o.left + Tok.vol_textX * u, node(tag).positionInRoot.x, 1f)
        val a = layout(node("overlay.artist"))
        val t = layout(node("overlay.title"))
        assertEquals(ui.fontPx(Tok.type_volArtist_size), px(a), 0.01f)
        assertEquals(FontWeight.Bold, a.layoutInput.style.fontWeight)
        assertEquals("UTADA HIKARU", text("overlay.artist"))
        assertEquals(ui.fontPx(Tok.type_volTitle_size), px(t), 0.01f)
        assertEquals(FontWeight.Normal, t.layoutInput.style.fontWeight)
        assertEquals("First Love", text("overlay.title"))
        assertEquals(Tok.type_volArtist_alpha, a.layoutInput.style.color.alpha, 0.005f)
        assertEquals(Tok.type_volTitle_alpha, t.layoutInput.style.color.alpha, 0.005f)
    }

    @Test
    fun withNoTitleTheArtistCapsTakeTheLargerOfTextYAndVolumesGap() {
        start("flip960x411") { it.copy(artist = "Utada Hikaru", title = "") }
        val u = ui().u
        val gap = caps("overlay.volume") + LayoutTok.vol_textGap * u
        assertTrue("the VOLUME gap wins here", gap > textY() + 2f)
        assertEquals(gap, caps("overlay.artist"), 1f)
    }

    @Test
    fun aTitleThatWouldLeaveTheScreenLiftsBothLines() {
        start("flip960x411") { it.copy(artist = "Utada Hikaru", title = "Gypsy Jaq") }
        val u = ui().u
        val lift = capsRule() - caps("overlay.artist")
        assertTrue("lift $lift", lift > 1f)
        assertEquals(baseline("overlay.artist") + LayoutTok.vol_textPitch * u, baseline("overlay.title"), 1f)
        val bottom = root().bottom
        val title = ink("overlay.title")
        val room = ink("overlay.artist").top - ink("overlay.volume").bottom
        assertTrue("$title $bottom $room", title.bottom <= bottom + 1f || room <= 1f)
        assertTrue("no line crosses VOLUME: $room", room >= -0.5f)
    }

    @Test
    fun onThePhoneSourceTheArtistLineIsFromThePhoneName() {
        start("411x960") { it.copy(source = Enums.Source.phone, phoneName = "Pixel 9", artist = "Utada Hikaru", title = "Phone song") }
        assertEquals("FROM PIXEL 9", text("overlay.artist"))
        assertEquals("Phone song", text("overlay.title"))
        c.device = c.device.withState(c.device.playback.state.copy(phoneName = null), 0)
        settle(100)
        assertEquals("", text("overlay.artist"))
        assertEquals("Phone song", text("overlay.title"))
    }

    @Test
    fun aLongTitleClipsAtTheEdge() {
        start("411x960") { it.copy(artist = "Utada Hikaru", title = "First Love ".repeat(8)) }
        val u = ui().u
        val right = root().left + (ui().w - Tok.edge) * u
        val img = rule.onRoot().pixels().also { System.gc() }
        val n = node("overlay.title")
        val t = layout(n)
        val top = (n.positionInRoot.y + t.firstBaseline - Tok.capHeight * px(t)).toInt()
        val bottom = (n.positionInRoot.y + t.firstBaseline).toInt()
        fun red(x: Int, y: Int) = img.getRGB(x, y) shr 16 and 0xff
        val peak = (root().left.toInt() until right.toInt()).maxOf { x -> (top until bottom).maxOf { y -> red(x, y) } }
        fun lit(x: Int) = (top until bottom).any { y -> red(x, y) > peak / 2 }
        assertTrue("lit before the edge", (right.toInt() - (3 * u).toInt() until right.toInt()).any { lit(it) })
        for (x in right.toInt() + 1 until minOf(img.width, root().right.toInt())) assertTrue("$x past the edge", !lit(x))
    }
}
