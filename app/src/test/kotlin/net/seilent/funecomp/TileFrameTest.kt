package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.artist.Sources
import net.seilent.funecomp.artist.Tile
import net.seilent.funecomp.artist.YouTube
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.BrowseTile
import net.seilent.funecomp.ui.Control
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.PickFor
import net.seilent.funecomp.ui.Previewed
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
class TileFrameTest {
    @get:Rule
    val rule = createComposeRule()

    private fun start() {
        val c = SimControl("ap80")
        val control = object : Control by c {
            override fun browse(name: String, mbid: String?, px: Int, sections: (List<String>) -> Unit, tile: (String, BrowseTile) -> Unit, sectionDone: (String) -> Unit, search: (YouTube.Search) -> Unit, done: (Boolean) -> Unit): () -> Unit {
                deliver = {
                    sections(listOf(Sources.DEEZER))
                    (0 until TILES).forEach { tile(Sources.DEEZER, BrowseTile(Tile(Sources.DEEZER, "${Sources.DEEZER} $it", ByteArray(1), "https://e/${Sources.DEEZER}/$it.jpg", "${Sources.DEEZER}$it", null), ImageBitmap(8, 8))) }
                }
                return {}
            }

            override fun takeTile(t: Tile, done: (Previewed) -> Unit): () -> Unit = {}
        }
        val shell = Shell(Nav(listOf(Page.Home, Page.NowPlaying, Page.Browse(PickFor("TSUKI")))))
        rule.mainClock.autoAdvance = false
        rule.setUptimeContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), control, shell, Sources.COMMONS.copy(youtube = true)) }
        }
        settle(1000)
        deliver()
        settle(500)
    }

    private var deliver: () -> Unit = {}

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun frame() {
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    private fun u(): Float {
        val size = rule.onRoot().fetchSemanticsNode().size
        return Ui.of(size.width.toFloat(), size.height.toFloat()).u
    }

    private fun capture(): BufferedImage = rule.onRoot().pixels().also { System.gc() }

    private fun red(img: BufferedImage, at: Offset) = img.getRGB(at.x.roundToInt(), at.y.roundToInt()) shr 16 and 0xff

    private fun bounds() = rule.onNodeWithTag(TILE).fetchSemanticsNode().boundsInRoot

    private class Probes(val band: Offset, val inner: Offset)

    private fun probes(): Probes {
        val u = u()
        val b = bounds()
        val y = b.top + Tok.gridTile * u / 2
        return Probes(Offset(b.left + Tok.boxInset * u, y), Offset(b.left + (Tok.boxInset + Tok.pressFrame / 2) * u + INNER_PX, y))
    }

    @Test
    fun aHeldTileDrawsTheFrameAndItsInnerGlowAtRestSize() {
        start()
        val p = probes()
        val tile = bounds()
        val rest = capture()
        assertTrue("no frame at rest", red(rest, p.band) < BRIGHT)
        rule.onNodeWithTag(TILE).performTouchInput { down(center) }
        frame()
        val held = capture()
        assertEquals("the frame at 1.0 the frame after the down", 255f, red(held, p.band).toFloat(), 3f)
        assertEquals("the tile at rest size", tile, bounds())
        settle(Tok.motion_pressGlow.toLong())
        assertTrue("the glow inside the frame", red(capture(), p.inner) > red(rest, p.inner) + GLOW_LIFT)
        rule.onNodeWithTag(TILE).performTouchInput { up() }
        frame()
        assertEquals("cut on the act frame", red(rest, p.band).toFloat(), red(capture(), p.band).toFloat(), 3f)
    }

    @Test
    fun aDragPastTheSlopReturnsTheFrameLinearOver100() {
        start()
        val p = probes()
        val rest = capture()
        rule.onNodeWithTag(TILE).performTouchInput { down(center) }
        settle(Tok.motion_pressGlow.toLong() + FRAME)
        rule.onNodeWithTag(TILE).performTouchInput { moveBy(Offset(Tok.touchSlop * u() + 10f, 0f)) }
        settle(Tok.motion_press.toLong() / 2)
        val mid = red(capture(), p.band)
        assertTrue("returning: $mid", mid > red(rest, p.band) + 20 && mid < 250)
        settle(Tok.motion_press.toLong() / 2 + FRAME)
        assertEquals("back", red(rest, p.band).toFloat(), red(capture(), p.band).toFloat(), 3f)
        rule.onNodeWithTag(TILE).performTouchInput { up() }
        settle(500)
    }

    private companion object {
        const val TILE = "pick.tile.deezer.0"
        const val TILES = 6
        const val FRAME = 16L
        const val INNER_PX = 2f
        const val BRIGHT = 128
        const val GLOW_LIFT = 20
    }
}
