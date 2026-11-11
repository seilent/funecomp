package net.seilent.funecomp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.ui.Black
import net.seilent.funecomp.ui.HubPage
import net.seilent.funecomp.ui.LocalUi
import net.seilent.funecomp.ui.SettingsRow
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
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class ValueCutTest {
    @get:Rule
    val rule = createComposeRule()

    private fun alone(content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) {
                BoxWithConstraints(Modifier.fillMaxSize().background(Black)) {
                    CompositionLocalProvider(LocalUi provides Ui.of(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())) { content() }
                }
            }
        }
        settle(1000)
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun frame() {
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    private fun capture(): BufferedImage = rule.onRoot().pixels().also { System.gc() }

    private fun shows(text: String) = rule.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun most(a: BufferedImage, b: BufferedImage): Int {
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        var most = 0
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                val p = a.getRGB(x, y)
                val q = b.getRGB(x, y)
                for (s in intArrayOf(0, 8, 16)) most = maxOf(most, abs((p shr s and 0xff) - (q shr s and 0xff)))
            }
        }
        return most
    }

    private fun cut(from: String, to: String, content: @Composable (String) -> Unit) {
        var value by mutableStateOf(from)
        alone { content(value) }
        assertTrue("$from at rest", shows(from))
        val before = capture()
        rule.runOnIdle { value = to }
        var n = 0
        var next: BufferedImage
        do {
            check(n++ < 10) { "no frame changed after the change" }
            frame()
            next = capture()
        } while (most(before, next) == 0)
        assertTrue("$to on the first changed frame", shows(to))
        assertTrue("$from gone on the first changed frame", !shows(from))
        settle(1000)
        assertEquals("$to: the first changed frame against the settled frame", 0, most(next, capture()))
    }

    @Test
    fun aSettingsValueChangeShowsTheNewValueAtFullAlphaOnTheNextFrame() = cut("OFF", "SHUFFLE ALL") { v ->
        SettingsRow("shuffle", v, focused = true)
    }

    @Test
    fun aCountChangeShowsTheNewCountAtFullAlphaOnTheNextFrame() = cut("1 OF 3", "2 OF 3") { v ->
        HubPage("queue", {}, count = v) {}
    }
}
