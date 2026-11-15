package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.accentFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt

internal val QUEUE_LIB = Library(
    listOf(
        Library.track("Kaze Ensemble, Sora", "Coastlines", "Signal Fire", 1, 200_000, albumArtist = "Kaze Ensemble"),
        Library.track("", "Coastlines", "Low Tide", 2, 200_000, albumArtist = "Kaze Ensemble"),
        Library.track("Kaze Ensemble", "Coastlines", "Harbor Lights", 3, 200_000),
    ),
)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class QueueRowsTest {
    @get:Rule
    val rule = createComposeRule()

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun start() {
        val c = SimControl("ap80", QUEUE_LIB)
        val shell = Shell(Nav(listOf(Page.Home, Page.NowPlaying, Page.Queue)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), c, shell) }
        }
        settle(1000)
    }

    private fun texts(tag: String): List<String> =
        rule.onAllNodes(hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes().flatMap { n -> n.config[SemanticsProperties.Text].map { it.text } }

    private fun lit(i: Int): Boolean {
        settle(Tok.motion_focusAlpha.toLong() + 16)
        val a = accentFor(Tok.accent_hueDefault.toInt())
        val file = File.createTempFile("row", ".png").apply { deleteOnExit() }
        rule.onNode(hasTestTag("primary") and hasAnyAncestor(hasTestTag("queue.$i")), useUnmergedTree = true).captureRoboImage(file.path)
        val img = ImageIO.read(file)
        fun near(v: Int, f: Float) = abs(v - (f * 255).roundToInt()) <= 16
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val p = img.getRGB(x, y)
            if (near(p shr 16 and 0xFF, a.red) && near(p shr 8 and 0xFF, a.green) && near(p and 0xFF, a.blue)) return true
        }
        return false
    }

    @Test
    fun twoNamesJoinOnTheLineUnderTheTitle() {
        start()
        assertTrue(texts("queue.0").toString(), "Kaze Ensemble, Sora" in texts("queue.0"))
        val page = rule.onNodeWithTag("hub.queue").fetchSemanticsNode().boundsInRoot
        val ui = Ui.of(page.width, page.height)
        val row = rule.onNodeWithTag("queue.0").fetchSemanticsNode().boundsInRoot
        val line = rule.onNode(hasText("Kaze Ensemble, Sora") and hasAnyAncestor(hasTestTag("queue.0")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val title = rule.onNode(hasTestTag("primary") and hasAnyAncestor(hasTestTag("queue.0")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(line.top > title.top)
        assertEquals(row.top + ui.px(Tok.rowHeight - Tok.rowTextInset), line.bottom, 1f)
    }

    @Test
    fun oneNameDrawsTheName() {
        start()
        assertTrue(texts("queue.2").toString(), "Kaze Ensemble" in texts("queue.2"))
    }

    @Test
    fun aRowWithNoNamesDrawsNoSecondLine() {
        start()
        assertEquals(setOf("Low Tide", "2"), texts("queue.1").toSet())
    }

    @Test
    fun thePlayingRowKeepsItsAccentTitle() {
        start()
        assertTrue(lit(0))
        assertFalse(lit(1))
    }
}
