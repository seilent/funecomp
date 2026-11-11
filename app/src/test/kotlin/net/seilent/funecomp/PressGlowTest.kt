package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.artist.ArtPicks
import net.seilent.funecomp.artist.Offer
import net.seilent.funecomp.artist.Sources
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.state.EqSlots
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.PickFor
import net.seilent.funecomp.ui.Preview
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
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class PressGlowTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var c: SimControl
    private lateinit var shell: Shell

    private class Probe(val y: Int, val xs: List<Int>)

    private fun start(pages: List<Page>, prep: (SimControl) -> Unit = {}) {
        c = SimControl("ap80")
        prep(c)
        shell = Shell(Nav(pages))
        rule.mainClock.autoAdvance = false
        rule.setUptimeContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), c, shell) }
        }
        settle(1000)
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun ui(): Ui {
        val size = rule.onRoot().fetchSemanticsNode().size
        return Ui.of(size.width.toFloat(), size.height.toFloat())
    }

    private fun capture(): BufferedImage = rule.onRoot().pixels().also { System.gc() }

    private fun white(img: BufferedImage, x: Int, y: Int) = img.getRGB(x, y).let { minOf(it shr 16 and 0xff, it shr 8 and 0xff, it and 0xff) }

    private fun layout(n: SemanticsNode): TextLayoutResult {
        val out = ArrayList<TextLayoutResult>()
        n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(out)
        return out.single()
    }

    private fun textIn(tag: String, pick: (List<SemanticsNode>) -> SemanticsNode = { it.first() }) =
        pick(rule.onAllNodes(hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes())

    private fun tagged(tag: String) = rule.onAllNodes(hasTestTag(tag) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes().first()

    private fun ink(word: SemanticsNode, img: BufferedImage): Rect {
        val b = word.boundsInRoot
        val x0 = b.left.roundToInt().coerceAtLeast(0)
        val x1 = b.right.roundToInt().coerceAtMost(img.width)
        val y0 = b.top.roundToInt().coerceAtLeast(0)
        val y1 = b.bottom.roundToInt().coerceAtMost(img.height)
        var l = x1
        var t = y1
        var r = x0
        var bottom = y0
        for (y in y0 until y1) for (x in x0 until x1) {
            if (white(img, x, y) < INK) continue
            l = minOf(l, x)
            t = minOf(t, y)
            r = maxOf(r, x + 1)
            bottom = maxOf(bottom, y + 1)
        }
        assertTrue("ink inside the word's box", r > l && bottom > t)
        return Rect(l.toFloat(), t.toFloat(), r.toFloat(), bottom.toFloat())
    }

    private fun probe(word: SemanticsNode, rest: BufferedImage): Probe {
        val ui = ui()
        val above = ui.px(Tok.pressGlowDilate).roundToInt() + ui.px(Tok.pressGlow).roundToInt() / 2f
        val box = ink(word, rest)
        val y = (box.top - above).roundToInt()
        val xs = (box.left.roundToInt() until box.right.roundToInt()).filter { white(rest, it, y) < DARK }
        assertTrue("dark pixels above the ink", xs.isNotEmpty())
        return Probe(y, xs)
    }

    private fun lift(p: Probe, rest: BufferedImage, now: BufferedImage) = p.xs.maxOf { white(now, it, p.y) - white(rest, it, p.y) }

    private fun glows(name: String, hit: String, word: () -> SemanticsNode) {
        val rest = capture()
        val p = probe(word(), rest)
        rule.onNodeWithTag(hit, useUnmergedTree = true).performTouchInput { down(center) }
        settle(Tok.motion_pressGlow.toLong() + FRAME)
        val held = capture()
        assertTrue("$name glows above its ink: ${lift(p, rest, held)}", lift(p, rest, held) > LIFT)
        rule.onNodeWithTag(hit, useUnmergedTree = true).performTouchInput { cancel() }
        settle(1000)
    }

    @Test
    fun aHeldArtistRowGlowsUnderItsWordAndCutsOnTheActFrame() {
        start(listOf(Page.Home, Page.Music))
        rule.pivotTo("music", "artists") { settle(it) }
        val word = textIn("row.0") { ns -> ns.first { it.config.getOrNull(SemanticsProperties.TestTag) == "primary" } }
        assertEquals("the focused row's title at 1.0", 1f, layout(word).layoutInput.style.color.alpha, 0.005f)
        val rest = capture()
        val p = probe(word, rest)
        val box = ink(word, rest)
        val solid = (box.top.roundToInt() until box.bottom.roundToInt()).flatMap { y -> (box.left.roundToInt() until box.right.roundToInt()).map { x -> x to y } }.filter { (x, y) -> white(rest, x, y) >= SOLID }
        assertTrue("solid word pixels", solid.isNotEmpty())
        val empty = (box.top.roundToInt() until box.bottom.roundToInt()).flatMap { y -> (box.left.roundToInt() until box.right.roundToInt()).map { x -> x to y } }.filter { (x, y) -> white(rest, x, y) < EMPTY }
        assertTrue("empty pixels inside the ink box", empty.isNotEmpty())
        rule.onNodeWithTag("row.0").performTouchInput { down(center) }
        settle(Tok.motion_pressGlow.toLong())
        val held = capture()
        assertTrue("lit at +50: ${lift(p, rest, held)}", lift(p, rest, held) > LIFT)
        assertTrue("the word's own pixels", solid.all { (x, y) -> white(held, x, y) >= SOLID })
        val peak = empty.maxOf { (x, y) -> white(held, x, y) - white(rest, x, y) }
        assertTrue("the glow at most pressGlowAlpha white: $peak", peak <= (Tok.pressGlowAlpha * 255).roundToInt() + CAP)
        rule.onNodeWithTag("row.0").performTouchInput { up() }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertTrue("dark on the act frame: ${lift(p, rest, capture())}", lift(p, rest, capture()) <= REST)
        settle(1500)
        assertTrue(shell.nav.top is Page.Artist)
    }

    private fun near(img: BufferedImage, x0: Int, y0: Int, x1: Int, y1: Int, r: Int): Array<BooleanArray> {
        val w = x1 - x0
        val h = y1 - y0
        val across = Array(h) { BooleanArray(w) }
        for (y in 0 until h) {
            var last = -r - 1
            for (x in 0 until w) {
                if (white(img, x0 + x, y0 + y) >= INK) last = x
                across[y][x] = x - last <= r
            }
            last = w + r + 1
            for (x in w - 1 downTo 0) {
                if (white(img, x0 + x, y0 + y) >= INK) last = x
                if (last - x <= r) across[y][x] = true
            }
        }
        val out = Array(h) { BooleanArray(w) }
        for (x in 0 until w) {
            var last = -r - 1
            for (y in 0 until h) {
                if (across[y][x]) last = y
                out[y][x] = y - last <= r
            }
            last = h + r + 1
            for (y in h - 1 downTo 0) {
                if (across[y][x]) last = y
                if (last - y <= r) out[y][x] = true
            }
        }
        return out
    }

    @Test
    fun aHeldHomeWordGlowsAroundItsLettersAndNotOverItsLineBox() {
        start(listOf(Page.Home))
        val word = textIn("home.music") { ns -> ns.first { it.config[SemanticsProperties.Text].joinToString("") == "music" } }
        val ui = ui()
        val reach = ui.px(Tok.pressGlowDilate).roundToInt() + ui.px(Tok.pressGlow).roundToInt() + FAR_PAD
        val rest = capture()
        val p = probe(word, rest)
        val b = word.boundsInRoot
        val x0 = (b.left.roundToInt() - reach).coerceAtLeast(0)
        val x1 = (b.right.roundToInt() + reach).coerceAtMost(rest.width)
        val y0 = (b.top.roundToInt() - reach).coerceAtLeast(0)
        val y1 = (b.bottom.roundToInt() + reach).coerceAtMost(rest.height)
        val close = near(rest, x0, y0, x1, y1, reach)
        val far = (b.top.roundToInt() until b.bottom.roundToInt()).flatMap { y -> (b.left.roundToInt() until b.right.roundToInt()).map { x -> x to y } }
            .filter { (x, y) -> !close[y - y0][x - x0] && white(rest, x, y) < DARK }
        assertTrue("pixels of the line box away from the letters: ${far.size}", far.size > MIN_FAR)
        rule.onNodeWithTag("home.music").performTouchInput { down(center) }
        settle(Tok.motion_pressGlow.toLong() + FRAME)
        val held = capture()
        assertTrue("lit around the letters: ${lift(p, rest, held)}", lift(p, rest, held) > LIFT)
        val peak = far.maxOf { (x, y) -> white(held, x, y) - white(rest, x, y) }
        assertTrue("dark in the line box away from the letters: $peak", peak <= REST)
        rule.onNodeWithTag("home.music").performTouchInput { cancel() }
        settle(1000)
    }

    @Test
    fun aRangeRowTapThatEditsInPlaceDrawsNoGlowOnTheActFrame() {
        start(listOf(Page.Home, Page.Settings))
        val row = "set.balance"
        val word = textIn(row) { ns -> ns.first { it.config.getOrNull(SemanticsProperties.TestTag) == "settings.title" } }
        val rest = capture()
        val p = probe(word, rest)
        rule.onNodeWithTag(row, useUnmergedTree = true).performTouchInput { down(center) }
        settle(Tok.motion_pressGlow.toLong() + FRAME)
        assertTrue("lit while held: ${lift(p, rest, capture())}", lift(p, rest, capture()) > LIFT)
        rule.onNodeWithTag(row, useUnmergedTree = true).performTouchInput { up() }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertTrue("dark on the act frame: ${lift(p, rest, capture())}", lift(p, rest, capture()) <= REST)
        assertEquals("the tap edits in place", Page.Settings, shell.nav.top)
        assertTrue("no sheet", !shell.sheet.shown)
        settle(1000)
    }

    private fun playingRowLift(off: Boolean): Int {
        val album = java.lang.Long.toUnsignedString(Library.synthetic().tracks.first().id)
        start(listOf(Page.Home, Page.Music, Page.Album(album, "First Love", "Utada Hikaru"))) {
            it.transport(Enums.TransportCmd.next)
            if (off) it.set("accentOff", "on")
        }
        val word = textIn("row.1") { ns -> ns.first { it.config.getOrNull(SemanticsProperties.TestTag) == "primary" } }
        val rest = capture()
        val p = probe(word, rest)
        rule.onNodeWithTag("row.1").performTouchInput { down(center) }
        settle(Tok.motion_pressGlow.toLong() + FRAME)
        val l = lift(p, rest, capture())
        rule.onNodeWithTag("row.1").performTouchInput { cancel() }
        settle(1000)
        return l
    }

    @Test
    fun anAccentPlayingTitleHasNoGlow() {
        val l = playingRowLift(false)
        assertTrue("an accent playing title has none: $l", l <= REST)
    }

    @Test
    fun anAccentOffPlayingTitleGlows() {
        val l = playingRowLift(true)
        assertTrue("an ACCENT OFF playing title glows: $l", l > LIFT)
    }

    @Test
    fun aSettingsTitleGlows() {
        start(listOf(Page.Home, Page.Settings))
        val row = rule.onAllNodes(SemanticsMatcher("set row") { n -> n.config.getOrNull(SemanticsProperties.TestTag).let { it != null && it.startsWith("set.") && it != "set.confirm" } }, useUnmergedTree = true).fetchSemanticsNodes().first().config[SemanticsProperties.TestTag]
        glows("settings title", row, { textIn(row) { ns -> ns.first { it.config.getOrNull(SemanticsProperties.TestTag) == "settings.title" } } })
    }

    @Test
    fun aSheetItemGlows() {
        start(listOf(Page.Home, Page.Settings))
        shell.sheet.open("confirm", listOf("apply", "cancel")) {}
        settle(1000)
        glows("sheet item", "sheet.item.1", { textIn("sheet.item.1") })
    }

    @Test
    fun aHomeWordGlows() {
        start(listOf(Page.Home))
        glows("home word", "home.music", { textIn("home.music") { ns -> ns.first { it.config[SemanticsProperties.Text].joinToString("") == "music" } } })
    }

    @Test
    fun theNowPlayingTitleGlows() {
        start(listOf(Page.Home, Page.NowPlaying)) { it.transport(Enums.TransportCmd.next) }
        glows("now playing title", "np.titleBlock", { tagged("np.title") })
    }

    @Test
    fun aJumpLetterGlows() {
        start(listOf(Page.Home, Page.Settings, Page.Eq(Enums.EqOutput.wired), Page.AutoEq(EqSlots.HEADPHONES)))
        rule.onNodeWithTag("aeq.div.#").performClick()
        settle(300)
        glows("jump letter", "jump.19", { textIn("jump.19") })
    }

    @Test
    fun thePickWordGlows() {
        val offer = Offer(ArtPicks.IMAGE, Sources.YOUTUBE, "https://yt3.ggpht.com/a=s900", "https://yt3.ggpht.com/a", null, null, "月-Tsuki-", ByteArray(1))
        start(listOf(Page.Home, Page.Online(), Page.PickPreview(PickFor("Tsuki"), listOf(Preview(offer, ImageBitmap(30, 40))))))
        glows("pick", "pick.label", { tagged("pick.label") })
    }

    private companion object {
        const val FRAME = 16L
        const val DARK = 80
        const val SOLID = 250
        const val LIFT = 8
        const val REST = 3
        const val EMPTY = 10
        const val CAP = 6
        const val INK = 40
        const val FAR_PAD = 3
        const val MIN_FAR = 50
    }
}
