package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.artist.ArtPicks
import net.seilent.funecomp.artist.Offer
import net.seilent.funecomp.artist.Sources
import net.seilent.funecomp.proto.gen.Tok
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
