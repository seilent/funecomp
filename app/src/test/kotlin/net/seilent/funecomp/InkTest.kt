package net.seilent.funecomp

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextPainter
import androidx.compose.ui.text.font.FontFamily
import net.seilent.funecomp.ui.LocalUi
import net.seilent.funecomp.ui.Role
import net.seilent.funecomp.ui.Roles
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.ink
import net.seilent.funecomp.ui.shown
import net.seilent.funecomp.ui.textStyle
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
class InkTest {
    @get:Rule
    val rule = createComposeRule()

    private class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private fun drawnInk(t: TextLayoutResult, pad: Int): Box {
        val img = ImageBitmap(t.size.width + 2 * pad, t.size.height + 2 * pad)
        val canvas = Canvas(img)
        canvas.translate(pad.toFloat(), pad.toFloat())
        TextPainter.paint(canvas, t)
        val b = img.asAndroidBitmap()
        val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        var left = b.width
        var top = b.height
        var right = -1
        var bottom = -1
        for (y in 0 until b.height) for (x in 0 until b.width) if (px[y * b.width + x] ushr 24 >= 128) {
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
        return Box(left.toFloat(), top.toFloat(), right + 1f, bottom + 1f)
    }

    private val texts = listOf<Pair<String, Role>>(
        "music" to Roles.pivotActive,
        "artists" to Roles.pivotInactive,
        "EXIT" to Roles.volExit,
        "VOLUME" to Roles.volLabel,
        "07" to Roles.volNumber,
        "Utada Hikaru" to Roles.volArtist,
        "First Love" to Roles.volTitle,
    )

    @Test
    fun inkBoundsAreTheDrawnGlyphBoundsFromTheLineStart() {
        val ui = Ui.of(4000f, 9332f)
        val cases = texts.map { (text, role) -> shown(text, role.case) to role }
        val drawn = HashMap<Pair<String, Role>, TextLayoutResult>()
        var resolver: FontFamily.Resolver? = null
        rule.setContent {
            CompositionLocalProvider(LocalUi provides ui) {
                resolver = LocalFontFamilyResolver.current
                for (c in cases) BasicText(c.first, style = textStyle(c.second, Color.White), onTextLayout = { drawn[c] = it })
            }
        }
        rule.waitForIdle()
        for (c in cases) {
            val (text, role) = c
            val t = drawn.getValue(c)
            val pad = t.size.height / 2
            val want = ink(text, role, ui, resolver!!)
            val got = drawnInk(t, pad)
            val y = pad + t.firstBaseline
            assertEquals("$text left", pad + want.left, got.left, 1f)
            assertEquals("$text right", pad + want.right, got.right, 1f)
            assertEquals("$text top", y + want.top, got.top, 1f)
            assertEquals("$text bottom", y + want.bottom, got.bottom, 1f)
        }
    }
}
