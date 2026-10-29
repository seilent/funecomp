package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import net.seilent.funecomp.artist.Sources
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.state.LibKey
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.accentFor
import org.junit.Assert.assertEquals
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class PlayingRowTest {
    @get:Rule
    val rule = createComposeRule()

    private val album = java.lang.Long.toUnsignedString(net.seilent.funecomp.sim.Library.synthetic().tracks.first().id)
    private lateinit var c: SimControl
    private lateinit var shell: Shell

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun start(pages: List<Page>, prep: (SimControl) -> Unit = {}) {
        c = SimControl("ap80")
        prep(c)
        shell = Shell(Nav(pages))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) {
                App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), c, shell, Sources.COMMONS, {})
            }
        }
        settle(1000)
    }

    private fun playing(c: SimControl) = c.transport(Enums.TransportCmd.next)

    private fun lit(i: Int): Boolean {
        settle(Tok.motion_focusAlpha.toLong() + 16)
        val a = accentFor(Tok.accent_hueDefault.toInt())
        val file = File.createTempFile("row", ".png").apply { deleteOnExit() }
        rule.onNode(hasTestTag("primary") and hasAnyAncestor(hasTestTag("row.$i")), useUnmergedTree = true).captureRoboImage(file.path)
        val img = ImageIO.read(file)
        fun near(v: Int, f: Float) = abs(v - (f * 255).roundToInt()) <= 16
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val p = img.getRGB(x, y)
            if (near(p shr 16 and 0xFF, a.red) && near(p shr 8 and 0xFF, a.green) && near(p and 0xFF, a.blue)) return true
        }
        return false
    }

    private fun row(folder: String, i: Long) = c.device.lib.pages.getValue(LibKey(Enums.View.folders, folder)).rows.getValue(i)

    private fun focus(i: Int) {
        rule.onNodeWithTag("row.$i").performTouchInput { longClick() }
        settle(500)
        shell.sheet.close()
        settle(1000)
    }

    private fun assertLit(vararg rows: Pair<Int, Boolean>) {
        assertEquals(rows.toList(), rows.map { it.first to lit(it.first) })
    }

    @Test
    fun albumDetailLightsThePlayingTitle() {
        start(listOf(Page.Home, Page.Music, Page.Album(album, "First Love", "Utada Hikaru")), ::playing)
        assertLit(0 to false, 1 to true, 2 to false)
        focus(1)
        assertLit(0 to false, 1 to true)
        focus(2)
        assertLit(1 to true, 2 to false)
        c.transport(Enums.TransportCmd.next)
        assertLit(1 to false, 2 to true)
    }

    @Test
    fun aServiceRowLightsNoLibraryRow() {
        start(listOf(Page.Home, Page.Music, Page.Album(album, "First Love", "Utada Hikaru")), ::playing)
        assertLit(1 to true)
        c.push(Msg.STATE, c.device.playback.state.copy(service = "tidal", serviceKey = 167).encode())
        assertLit(0 to false, 1 to false, 2 to false)
    }

    @Test
    fun songsLightsThePlayingTitle() {
        start(listOf(Page.Home, Page.Music), ::playing)
        rule.onNodeWithTag("pivot.songs").performClick()
        settle(1000)
        assertLit(0 to false, 1 to true, 2 to false)
        focus(0)
        assertLit(0 to false, 1 to true)
        c.transport(Enums.TransportCmd.next)
        assertLit(1 to false, 2 to true)
    }

    @Test
    fun folderRowsStayUnlitAndFolderTracksLight() {
        start(listOf(Page.Home, Page.Music))
        for (p in listOf("albums", "songs", "playlists", "folders")) {
            rule.onNodeWithTag("pivot.$p").performClick()
            settle(1000)
        }
        val root = row("", 2)
        assertEquals("Utada Hikaru", root.name)
        assertEquals(c.device.playback.state.track, root.first)
        assertLit(0 to false, 1 to false, 2 to false)
        rule.onNodeWithTag("row.2").performClick()
        settle(1000)
        assertEquals("First Love", row("Utada Hikaru", 1).name)
        rule.onNodeWithTag("row.1").performClick()
        settle(1000)
        assertEquals("Automatic", row("Utada Hikaru/First Love", 0).name)
        assertLit(0 to true, 1 to false)
        focus(1)
        assertLit(0 to true, 1 to false)
        c.transport(Enums.TransportCmd.next)
        assertLit(0 to false, 1 to true)
    }
}
