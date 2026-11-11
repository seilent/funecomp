package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.artist.ArtPicks
import net.seilent.funecomp.artist.Offer
import net.seilent.funecomp.artist.Sources
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.proto.gen.TokBoard
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LayoutTok
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.PickFor
import net.seilent.funecomp.ui.Preview
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
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
class NowPlayingChromeTest {
    @get:Rule
    val rule = createComposeRule()

    private fun start(pages: List<Page>, prep: (SimControl) -> Unit = {}) {
        val c = SimControl("v1")
        prep(c)
        val shell = Shell(Nav(pages))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "V1", everLive = true), c, shell) }
        }
        settle(1500)
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun node(tag: String, under: String): SemanticsNode = rule.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag(under)), useUnmergedTree = true).fetchSemanticsNodes().single()

    private fun u(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().size.let { Ui.of(it.width.toFloat(), it.height.toFloat()).u }

    @Test
    fun nowPlayingTakesTheDeviceBackRing() {
        start(listOf(Page.Home, Page.NowPlaying))
        val u = u("np")
        val back = node("back", "np")
        assertEquals((LayoutTok.npBackRingX + LayoutTok.npBackRing) * u, back.size.width.toFloat(), 1f)
        assertEquals((LayoutTok.npBackRingY + LayoutTok.npBackRing) * u, back.size.height.toFloat(), 1f)
        assertEquals(68.7f * u, node("back.ring", "np").size.width.toFloat(), 1f)
        assertEquals(42.5f * u, node("back.glyph", "np").size.width.toFloat(), 1f)
    }

    @Test
    fun theBatteryIsTheFramesOutlineWithTheNubLeftAndTheLevelFromTheRight() {
        start(listOf(Page.Home, Page.NowPlaying)) { it.transport(Enums.TransportCmd.next) }
        val u = u("np")
        val node = rule.onNodeWithTag("status.battery", useUnmergedTree = true)
        val b = node.fetchSemanticsNode()
        val w = LayoutTok.npStatusBatteryW
        val h = LayoutTok.npStatusBatteryH
        assertEquals(w * u, b.size.width.toFloat(), 1f)
        assertEquals(h * u, b.size.height.toFloat(), 1f)
        val img = node.pixels().also { System.gc() }
        fun lit(x: Float, y: Float) = (img.getRGB((x * u).toInt().coerceIn(0, img.width - 1), (y * u).toInt().coerceIn(0, img.height - 1)) shr 16 and 0xff) > 80
        val nub = LayoutTok.npStatusBatteryNub
        val sk = h * 2 / 9
        val ix = (w - nub - 2 * sk) / 18
        assertTrue("nub", lit(nub / 2, h / 2))
        assertFalse("over the nub", lit(nub / 2, sk / 2))
        assertTrue("right end", lit(w - sk / 2, h / 2))
        assertTrue("level at the right", lit(w - sk - ix - 0.5f, h / 2))
        assertFalse("level not at the left", lit(nub + sk + ix + 0.5f, h / 2))
    }

    @Test
    fun theStatusRowSitsAtTheDeviceSizes() {
        start(listOf(Page.Home, Page.NowPlaying)) { it.transport(Enums.TransportCmd.next) }
        val np = rule.onNodeWithTag("np").fetchSemanticsNode()
        val ui = Ui.of(np.size.width.toFloat(), np.size.height.toFloat())
        val u = ui.u
        val o = np.positionInRoot
        val play = rule.onAllNodes(hasTestTag("status.play") or hasTestTag("status.pause"), useUnmergedTree = true).fetchSemanticsNodes().single()
        val battery = rule.onNodeWithTag("status.battery", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(11.5f * u, play.size.width.toFloat(), 1f)
        assertEquals(11.5f * u, play.size.height.toFloat(), 1f)
        assertEquals(5.9f * u, play.positionInRoot.y - o.y, 1f)
        assertEquals((ui.w - 13.7f) * u, battery.positionInRoot.x - o.x + battery.size.width, 1f)
        assertEquals(TokBoard.r1.npStatusGap * u, battery.positionInRoot.x - (play.positionInRoot.x + play.size.width), 1f)
    }

    @Test
    fun theBarAndTheTimesSitUnderTheArt() {
        start(listOf(Page.Home, Page.NowPlaying)) {
            it.set("accentOff", "on")
            it.transport(Enums.TransportCmd.next)
            it.transport(Enums.TransportCmd.seek, seekMs = 1_000)
        }
        val np = rule.onNodeWithTag("np").fetchSemanticsNode()
        val u = Ui.of(np.size.width.toFloat(), np.size.height.toFloat()).u
        val art = rule.onNodeWithTag("np.art").fetchSemanticsNode().boundsInRoot
        val track = rule.onNodeWithTag("np.track").fetchSemanticsNode().boundsInRoot
        val fill = rule.onNodeWithTag("np.progress").fetchSemanticsNode().boundsInRoot
        assertEquals(LayoutTok.npBarHeight * u, fill.height, 1f)
        assertEquals(LayoutTok.npBarHeight * u, track.height, 1f)
        assertEquals(Tok.npBarGap * u, track.top - art.bottom, 1f)
        assertEquals(art.width, track.width, 1f)
        val img = rule.onRoot().pixels().also { System.gc() }
        val p = img.getRGB((track.right - 2f).toInt(), track.center.y.toInt())
        assertEquals(Tok.npBarTrack * 255f, (p shr 16 and 0xff).toFloat(), 3f)
        val elapsed = rule.onNodeWithTag("np.elapsed", useUnmergedTree = true).fetchSemanticsNode()
        val layout = ArrayList<TextLayoutResult>().also { elapsed.config[SemanticsActions.GetTextLayoutResult].action?.invoke(it) }.single()
        assertEquals(LayoutTok.npTimeBaseline * u, elapsed.positionInRoot.y + layout.firstBaseline - art.bottom, 1f)
    }

    @Test
    fun theArtPickKeepsTheBackRing() {
        val offer = Offer(ArtPicks.IMAGE, Sources.LINK, "https://example.org/0.jpg", "https://example.org/0.jpg", null, null, "TSUKI", ByteArray(1))
        start(listOf(Page.Home, Page.NowPlaying, Page.PickPreview(PickFor("TSUKI"), listOf(Preview(offer, null)))))
        val u = u("pick.page")
        val back = node("back", "pick.page")
        assertEquals((Tok.backRingX + Tok.backRing) * u, back.size.width.toFloat(), 1f)
        assertEquals((Tok.backRingY + Tok.backRing) * u, back.size.height.toFloat(), 1f)
        assertEquals(64f * u, node("back.ring", "pick.page").size.width.toFloat(), 1f)
        assertEquals(32f * u, node("back.glyph", "pick.page").size.width.toFloat(), 1f)
    }
}
