package net.seilent.funecomp

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.Role
import net.seilent.funecomp.ui.Roles
import net.seilent.funecomp.ui.Selawik
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.shown
import net.seilent.funecomp.ui.textGlow
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class PressGlowBuildTest {
    @Test
    fun aGlowMaskBuildsInUnderAFrameAt120HzOnEverySite() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dm = context.resources.displayMetrics
        val ui = Ui.of(dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
        val density = Density(context)
        val measurer = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr)
        val d = ui.px(Tok.pressGlowDilate).roundToInt()
        val g = ui.px(Tok.pressGlow).roundToInt()
        fun style(role: Role) = with(density) { TextStyle(fontFamily = Selawik, fontWeight = role.weight, fontSize = ui.fontPx(role.size).toSp(), letterSpacing = ui.px(Tok.tracking).toSp()) }
        val sites = listOf(
            Triple("list row", Roles.listPrimary, "Kendrick Lamar; SZA"),
            Triple("settings title", Roles.pageRowTitle, "media controls"),
            Triple("sheet item", Roles.dialogItem, "add to now playing"),
            Triple("home word", Roles.homeItem, "now playing"),
            Triple("now playing title", Roles.npViewTitle, "Movin' on without you"),
            Triple("jump letter", Roles.letterGlyph, "s"),
            Triple("pick", Roles.dialogItem, "pick"),
        )
        for ((name, role, text) in sites) {
            val layout = measurer.measure(shown(text, role.case), style(role), softWrap = false, maxLines = 1)
            textGlow(layout, d, g)
            val ms = List(RUNS) {
                val t0 = System.nanoTime()
                textGlow(layout, d, g)
                (System.nanoTime() - t0) / 1e6
            }.sorted()[RUNS / 2]
            assertTrue("$name builds in $ms ms", ms < FRAME_120_MS)
        }
    }

    private companion object {
        const val RUNS = 5
        const val FRAME_120_MS = 8.0
    }
}
