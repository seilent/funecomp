package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import java.awt.image.BufferedImage
import kotlin.math.max
import net.seilent.funecomp.proto.gen.Enums
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class StatusBatteryTest {
    @get:Rule
    val rule = createComposeRule()

    private var u = 0f
    private lateinit var img: BufferedImage

    private fun start() {
        val c = SimControl("v1")
        c.transport(Enums.TransportCmd.next)
        val shell = Shell(Nav(listOf(Page.Home, Page.NowPlaying)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "V1", everLive = true), c, shell) }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
        val np = rule.onNodeWithTag("np").fetchSemanticsNode().size
        u = Ui.of(np.width.toFloat(), np.height.toFloat()).u
        img = rule.onNodeWithTag("status.battery", useUnmergedTree = true).pixels().also { System.gc() }
    }

    private fun red(x: Int, y: Int) = img.getRGB(x, y) shr 16 and 0xff

    private fun lit(x: Int, y: Int) = red(x, y) > (red(0, 0) + 255) / 2

    private fun column(x: Int) = (0 until img.height).count { lit(x, it) }

    private val nub get() = max(1f, LayoutTok.npStatusBatteryNub * u)

    @Test
    fun theNubIsTheDeviceTokensWideAndTall() {
        start()
        val row = (LayoutTok.npStatusBatteryH / 9 * u).toInt()
        val first = (0 until img.width).first { lit(it, row) }
        assertEquals(nub, first.toFloat(), 1f)
        assertEquals(max(1f, LayoutTok.npStatusBatteryNubH * u), column(0).toFloat(), 1f)
    }

    @Test
    fun theNubStepsOutOnePixelARowOverHalfItsWidth() {
        start()
        val steps = (nub / 2).toInt()
        assertTrue(steps >= 1 && steps + 1 < nub.toInt())
        for (k in 1..steps) assertEquals("column $k", column(0) + 2 * k, column(k))
        assertEquals("past the steps", column(steps), column(steps + 1))
    }
}
