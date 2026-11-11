package net.seilent.funecomp

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LayoutTok
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.LocalUi
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Overlay
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shape
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.VolumeOverlay
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
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class VolumeOverlayLayoutTest {
    @get:Rule
    val rule = createComposeRule()

    private var size by mutableStateOf(SIZES.first { it.name == "411x960" })
    private lateinit var c: SimControl
    private lateinit var shell: Shell

    private fun start() {
        c = SimControl("v1")
        c.transport(Enums.TransportCmd.next)
        shell = Shell(Nav(listOf(Page.Home, Page.NowPlaying)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val s = size
            Box(Modifier.requiredSize(s.w.dp, s.h.dp)) { App(c.device, LinkView(Phase.LIVE, "V1", everLive = true), c, shell, insets = s.bars) }
        }
        settle(1000)
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun open(at: String) {
        size = SIZES.first { it.name == at }
        settle(1000)
        shell.overlay.show()
        settle(400)
    }

    private fun ui() = rule.onNodeWithTag("np").fetchSemanticsNode().size.let { Ui.of(it.width.toFloat(), it.height.toFloat()) }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun root() = rule.onNodeWithTag("overlay").fetchSemanticsNode().boundsInRoot

    private fun layout(tag: String): Pair<Rect, TextLayoutResult> {
        val n = rule.onAllNodes((hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes().first()
        val out = ArrayList<TextLayoutResult>()
        n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(out)
        return n.boundsInRoot to out.single()
    }

    private fun caps(tag: String): Rect {
        val (b, t) = layout(tag)
        val px = with(rule.density) { t.layoutInput.style.fontSize.toPx() }
        return Rect(b.left + t.getLineLeft(0), b.top + t.firstBaseline - Tok.capHeight * px, b.left + t.getLineRight(0), b.top + t.firstBaseline)
    }

    private fun outlineInk(tag: String): Rect {
        val (b, t) = layout(tag)
        val s = t.layoutInput.style
        val paint = Paint().apply {
            typeface = t.layoutInput.fontFamilyResolver.resolve(s.fontFamily, s.fontWeight ?: FontWeight.Normal).value as Typeface
            textSize = with(rule.density) { s.fontSize.toPx() }
            letterSpacing = with(rule.density) { s.letterSpacing.toPx() } / textSize
        }
        val path = Path()
        paint.getTextPath(t.layoutInput.text.text, 0, t.layoutInput.text.length, 0f, 0f, path)
        val r = RectF().also { path.computeBounds(it, true) }
        return Rect(b.left + r.left, b.top + t.firstBaseline + r.top, b.left + r.right, b.top + t.firstBaseline + r.bottom)
    }

    private fun capsCentre(tag: String): Float {
        val (b, t) = layout(tag)
        val px = with(rule.density) { t.layoutInput.style.fontSize.toPx() }
        return b.top + t.firstBaseline - Tok.capHeight * px / 2
    }

    private fun ink(tag: String, l: Float, t: Float, r: Float, b: Float): Rect {
        val box = bounds(tag)
        val k = box.width / Tok.glyphBox
        return Rect(box.left + l * k, box.top + t * k, box.left + r * k, box.top + b * k)
    }

    @Test
    fun theOverlayDrawsTheDeviceSizes() {
        start()
        for (at in listOf("411x960", "360x640", "sn411x438", "flip960x411")) {
            open(at)
            val ui = ui()
            val u = ui.u
            val o = root()
            val ring = bounds("overlay.ring.ring")
            assertEquals(at, Tok.vol_ring * u, ring.width, 1f)
            val img = rule.onNodeWithTag("overlay.ring.ring", useUnmergedTree = true).pixels().also { System.gc() }
            val row = (0 until img.width).map { img.getRGB(it, img.height / 2) shr 16 and 0xff }
            val half = row.max() / 2
            val first = row.indexOfFirst { it > half }
            assertEquals(at, Tok.vol_ringStroke * u, row.drop(first).takeWhile { it > half }.size.toFloat(), 1.5f)
            assertEquals(at, Tok.vol_pause * u, bounds("overlay.ring.glyph").width, 1f)
            val cx = o.left + ui.px(ui.w) / 2
            for ((tag, sign) in listOf("overlay.prev.glyph" to -1, "overlay.next.glyph" to 1)) {
                val g = bounds(tag)
                assertEquals("$at $tag", Tok.vol_skip * u, g.width, 1f)
            }
            for (tag in listOf("overlay.plus.glyph", "overlay.minus.glyph")) assertEquals("$at $tag", Tok.vol_plus * u, bounds(tag).width, 1f)
            for ((tag, want) in listOf("overlay.volume" to Tok.vol_label, "overlay.exit" to Tok.vol_exit)) {
                val t = layout(tag).second
                assertEquals("$at $tag", ui.fontPx(want), with(rule.density) { t.layoutInput.style.fontSize.toPx() }, 0.01f)
                assertEquals("$at $tag", FontWeight.Bold, t.layoutInput.style.fontWeight)
            }
            shell.overlay.hide()
            settle(1000)
        }
    }

    @Test
    fun everyPartSitsAtItsDevicePlace() {
        start()
        for (at in listOf("411x960", "360x640", "sn411x438", "flip960x411", "cover474x419")) {
            open(at)
            val ui = ui()
            val u = ui.u
            val o = root()
            val cx = o.left + ui.px(ui.w) / 2
            fun y(v: Float) = o.top + v * ui.h / LayoutTok.canvasH * u
            val plus = bounds("overlay.plus.glyph")
            assertEquals("$at plus x", cx, plus.center.x, 1f)
            assertEquals("$at plus y", y(LayoutTok.vol_plusY), plus.center.y, 1f)
            assertEquals("$at minus", y(LayoutTok.vol_minusY), bounds("overlay.minus.glyph").center.y, 1f)
            assertEquals("$at ring", y(LayoutTok.vol_ringY), bounds("overlay.ring.ring").center.y, 1f)
            for ((tag, sign) in listOf("overlay.prev.glyph" to -1, "overlay.next.glyph" to 1)) {
                val g = bounds(tag)
                assertEquals("$at $tag x", cx + sign * Tok.vol_skipX * u, g.center.x, 1f)
                assertEquals("$at $tag y", y(LayoutTok.vol_ringY), g.center.y, 1f)
            }
            val num = outlineInk("overlay.number")
            assertEquals("$at number left ink", o.left + Tok.vol_numberX * u, num.left, 1f)
            assertEquals("$at number caps", y(LayoutTok.vol_numberY), capsCentre("overlay.number"), 1f)
            val n = layout("overlay.number").second
            assertEquals("$at number size", ui.fontPx(Tok.vol_number), with(rule.density) { n.layoutInput.style.fontSize.toPx() }, 0.01f)
            assertEquals("$at number weight", FontWeight.Light, n.layoutInput.style.fontWeight)
            val (vb, vt) = layout("overlay.volume")
            val vpx = with(rule.density) { vt.layoutInput.style.fontSize.toPx() }
            assertEquals("$at volume caps top", y(LayoutTok.vol_labelY), vb.top + vt.firstBaseline - Tok.capHeight * vpx, 1f)
            assertEquals("$at volume centre", cx, vb.left + (vt.getLineLeft(0) + vt.getLineRight(0)) / 2, 1f)
            val exit = outlineInk("overlay.exit")
            assertEquals("$at exit x", o.left + Tok.vol_exitX * u, exit.center.x, 1f)
            assertEquals("$at exit caps", y(LayoutTok.vol_exitY), capsCentre("overlay.exit"), 1f)
            val box = bounds("overlay.exit.hit")
            assertEquals("$at exit box x", o.left + Tok.vol_exitX * u, box.center.x, 1f)
            assertEquals("$at exit box y", y(LayoutTok.vol_exitY), box.center.y, 1f)
            for (tag in listOf("overlay.number", "overlay.volume", "overlay.exit")) {
                assertEquals("$at $tag alpha", Tok.overlayLit, layout(tag).second.layoutInput.style.color.alpha, 0.005f)
            }
            shell.overlay.hide()
            settle(1000)
        }
    }

    private fun numberPx() = with(rule.density) { layout("overlay.number").second.layoutInput.style.fontSize.toPx() }

    @Test
    fun aHundredOnThePhoneKeepsItsSizeBeforeThePlusInk() {
        start()
        c.device = c.device.withState(c.device.playback.state.copy(volumePct = 100), 0)
        open("411x960")
        val ui = ui()
        val u = ui.u
        val plusInk = bounds("overlay.plus.glyph").center.x - Tok.vol_plus * 3 / 8 * u
        val num = outlineInk("overlay.number")
        assertTrue("$num $plusInk", num.right <= plusInk + 0.5f)
        assertEquals(root().left + Tok.vol_numberX * u, num.left, 1f)
        assertEquals(ui.fontPx(Tok.vol_number), numberPx(), 0.01f)
    }

    @Test
    fun aHundredOnTheDeviceCanvasShrinksToEndAtThePlusInk() {
        c = SimControl("v1")
        c.transport(Enums.TransportCmd.next)
        c.device = c.device.withState(c.device.playback.state.copy(volumePct = 100), 0)
        val overlay = Overlay()
        val ui = Ui(1f, Tok.canvasW, LayoutTok.canvasH, Shape.SN)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val d = LocalDensity.current
            Box(Modifier.requiredSize(with(d) { ui.px(ui.w).toDp() }, with(d) { ui.px(ui.h).toDp() })) {
                CompositionLocalProvider(LocalUi provides ui) { VolumeOverlay(overlay, c.device, c) }
            }
        }
        settle(1000)
        overlay.show()
        settle(400)
        val u = ui.u
        val plusInk = bounds("overlay.plus.glyph").center.x - Tok.vol_plus * 3 / 8 * u
        val num = outlineInk("overlay.number")
        assertTrue("$num $plusInk", num.right <= plusInk + 0.5f)
        assertEquals(plusInk, num.right, 2f)
        assertEquals(root().left + Tok.vol_numberX * u, num.left, 1f)
        assertTrue(numberPx() < ui.fontPx(Tok.vol_number))
    }

    @Test
    fun thePlayAndTheWidePauseTakeTheDevicePauseSize() {
        start()
        if (c.device.playback.state.playing != true) c.transport(Enums.TransportCmd.toggle)
        open("411x960")
        val u = ui().u
        assertEquals(Tok.vol_pause * u, bounds("overlay.ring.glyph").width, 1f)
        val img = rule.onNodeWithTag("overlay.ring.glyph", useUnmergedTree = true).pixels().also { System.gc() }
        val row = (0 until img.width).map { img.getRGB(it, img.height / 2) shr 16 and 0xff > 128 }
        val left = row.indexOfFirst { it }
        val gapStart = left + row.drop(left).indexOfFirst { !it }
        val gap = row.drop(gapStart).indexOfFirst { it }
        assertEquals(Tok.vol_pauseGap * u, gap.toFloat(), 1.5f)
        c.transport(Enums.TransportCmd.toggle)
        shell.overlay.show()
        settle(400)
        assertEquals(Tok.vol_pause * u, bounds("overlay.ring.glyph").width, 1f)
    }

    @Test
    fun theNumberDoesNotPopOnAShowOrAChange() {
        start()
        size = SIZES.first { it.name == "411x960" }
        settle(1000)
        shell.overlay.show()
        rule.waitForIdle()
        repeat(10) {
            if (rule.onAllNodesWithTag("overlay.number", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
                rule.mainClock.advanceTimeByFrame()
                rule.waitForIdle()
            }
        }
        val first = bounds("overlay.number").height
        settle(1000)
        assertEquals(bounds("overlay.number").height, first, 0.5f)
        c.volume(1)
        shell.overlay.show()
        rule.waitForIdle()
        repeat(2) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
        }
        assertEquals(first, bounds("overlay.number").height, 0.5f)
    }

    @Test
    fun noTwoInksCrossOnEveryWindow() {
        start()
        for (s in SIZES) {
            open(s.name)
            val inks = listOf(
                "ring" to bounds("overlay.ring.ring"),
                "prev" to ink("overlay.prev.glyph", 1.7f, 8f, 30.3f, 24f),
                "next" to ink("overlay.next.glyph", 1.7f, 8f, 30.3f, 24f),
                "plus" to ink("overlay.plus.glyph", 4f, 4f, 28f, 28f),
                "minus" to ink("overlay.minus.glyph", 4f, 14f, 28f, 18f),
                "number" to caps("overlay.number"),
                "volume" to caps("overlay.volume"),
                "exit" to caps("overlay.exit"),
                "artist" to caps("overlay.artist"),
                "title" to caps("overlay.title"),
            )
            for (i in inks.indices) for (j in i + 1 until inks.size) {
                assertFalse("${s.name} ${inks[i].first} ${inks[j].first} ${inks[i].second} ${inks[j].second}", inks[i].second.deflate(0.5f).overlaps(inks[j].second.deflate(0.5f)))
            }
            shell.overlay.hide()
            settle(1000)
        }
    }
}
