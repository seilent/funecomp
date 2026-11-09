package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LayoutTok
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.max
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class NowPlayingRolesTest {
    @get:Rule
    val rule = createComposeRule()

    private var size by mutableStateOf(SIZES.first { it.name == "411x960" })

    private class Want(val tag: String, val size: Float, val weight: FontWeight, val alpha: Float)

    private val roles = listOf(
        Want("np.artist", LayoutTok.type_npViewArtist_size, FontWeight.Bold, Tok.type_npViewArtist_alpha),
        Want("np.album", LayoutTok.type_npViewAlbum_size, FontWeight.Normal, Tok.type_npViewAlbum_alpha),
        Want("np.title", LayoutTok.type_npViewTitle_size, FontWeight.Normal, Tok.type_npViewTitle_alpha),
        Want("np.next.0", LayoutTok.type_npViewNext_size, FontWeight.Light, Tok.type_npViewNext_alpha),
        Want("np.elapsed", LayoutTok.type_npViewTime_size, FontWeight.Bold, Tok.type_npViewTime_alpha),
    )

    private fun start(pages: List<Page>, lib: Library = Library.synthetic(), prep: (SimControl) -> Unit = {}) {
        val c = SimControl("v1", lib = lib)
        prep(c)
        val shell = Shell(Nav(pages))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val s = size
            Box(Modifier.requiredSize(s.w.dp, s.h.dp)) { App(c.device, LinkView(Phase.LIVE, "V1", everLive = true), c, shell, insets = s.bars) }
        }
        settle(1500)
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun ui(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().size.let { Ui.of(it.width.toFloat(), it.height.toFloat()) }

    private fun layout(tag: String): TextLayoutResult {
        val n = rule.onAllNodes((hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes().first()
        val out = ArrayList<TextLayoutResult>()
        n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(out)
        return out.single()
    }

    private fun px(t: TextLayoutResult) = with(rule.density) { t.layoutInput.style.fontSize.toPx() }

    private fun check(at: String, want: Want, size: Float) {
        val t = layout(want.tag)
        assertEquals("$at ${want.tag} size", size, px(t), 0.01f)
        assertEquals("$at ${want.tag} weight", want.weight, t.layoutInput.style.fontWeight)
        assertEquals("$at ${want.tag} alpha", want.alpha, t.layoutInput.style.color.alpha, 0.005f)
    }

    @Test
    fun nowPlayingDrawsTheDeviceRoles() {
        start(listOf(Page.Home, Page.NowPlaying)) { it.transport(Enums.TransportCmd.next) }
        for (at in listOf("411x960", "sn411x438")) {
            size = SIZES.first { it.name == at }
            settle(1500)
            val u = ui("np").u
            for (r in roles) check(at, r, max(Ui.MIN_TEXT_PX, r.size * u).roundToInt().toFloat())
        }
    }

    @Test
    fun albumDetailKeepsTheAlbumRoles() {
        val tracks = (1..4).map { Library.track("Band", "Long", "track $it", it, 200_000) }
        val key = java.lang.Long.toUnsignedString(tracks.first().id)
        start(listOf(Page.Home, Page.Music, Page.Album(key, "Long", "Band")), Library(tracks))
        val u = ui("album").u
        val artist = layout("album.artist")
        assertEquals(max(Ui.MIN_TEXT_PX, Tok.type_npArtist_size * u).roundToInt().toFloat(), px(artist), 0.01f)
        assertEquals(FontWeight.Bold, artist.layoutInput.style.fontWeight)
    }
}
