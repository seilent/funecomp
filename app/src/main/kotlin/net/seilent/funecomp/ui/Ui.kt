package net.seilent.funecomp.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import net.seilent.funecomp.R
import net.seilent.funecomp.proto.gen.Tok
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Immutable
class Ui(val u: Float, val w: Float, val h: Float) {
    fun px(v: Float) = v * u

    fun fontPx(v: Float) = max(MIN_TEXT_PX, v * u).roundToInt().toFloat()

    companion object {
        const val MIN_TEXT_PX = 12f
        const val PORTRAIT_W = 272f
        const val PORTRAIT_H = 480f

        fun of(widthPx: Float, heightPx: Float): Ui {
            val u = min(widthPx / PORTRAIT_W, heightPx / PORTRAIT_H)
            return Ui(u, widthPx / u, heightPx / u)
        }
    }
}

val LocalUi = staticCompositionLocalOf { Ui(1f, Tok.canvasW, Tok.canvasH) }

enum class Case { UPPER, LOWER, MIXED }

@Immutable
data class Role(val size: Float, val weight: FontWeight, val case: Case, val alpha: Float)

private fun weight(style: String) = when (style) {
    "Light" -> FontWeight.Light
    "Bold" -> FontWeight.Bold
    else -> FontWeight.Normal
}

private fun case(c: String) = when (c) {
    "upper" -> Case.UPPER
    "lower" -> Case.LOWER
    else -> Case.MIXED
}

object Roles {
    val npArtist = Role(Tok.type_npArtist_size, weight(Tok.type_npArtist_style), case(Tok.type_npArtist_case), Tok.type_npArtist_alpha)
    val npAlbum = Role(Tok.type_npAlbum_size, weight(Tok.type_npAlbum_style), case(Tok.type_npAlbum_case), Tok.type_npAlbum_alpha)
    val npTitle = Role(Tok.type_npTitle_size, weight(Tok.type_npTitle_style), case(Tok.type_npTitle_case), Tok.type_npTitle_alpha)
    val time = Role(Tok.type_time_size, weight(Tok.type_time_style), case(Tok.type_time_case), Tok.type_time_alpha)
    val overlayNumber = Role(Tok.type_overlayNumber_size, weight(Tok.type_overlayNumber_style), Case.MIXED, Tok.type_overlayNumber_alpha)
    val overlayLabel = Role(Tok.type_overlayLabel_size, weight(Tok.type_overlayLabel_style), case(Tok.type_overlayLabel_case), Tok.type_overlayLabel_alpha)
    val toastArtist = Role(Tok.type_toastArtist_size, weight(Tok.type_toastArtist_style), case(Tok.type_toastArtist_case), Tok.type_toastArtist_alpha)
    val toastTitle = Role(Tok.type_toastTitle_size, weight(Tok.type_toastTitle_style), case(Tok.type_toastTitle_case), Tok.type_toastTitle_alpha)
}

val Selawik = FontFamily(
    Font(R.font.selawkl, FontWeight.Light),
    Font(R.font.selawk, FontWeight.Normal),
    Font(R.font.selawkb, FontWeight.Bold),
)

fun white(alpha: Float) = Color(1f, 1f, 1f, alpha)

val Black = Color(Tok.color_black)

fun Modifier.at(x: Float, y: Float, ax: Float = 0f, ay: Float = 0f) = layout { m, c ->
    val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
    layout(c.maxWidth, c.maxHeight) {
        p.place((x - ax * p.width).roundToInt(), (y - ay * p.height).roundToInt())
    }
}

fun Modifier.atBaseline(x: Float, baseline: Float, maxWidth: Float? = null, ax: Float = 0f) = layout { m, c ->
    val limit = maxWidth?.roundToInt()?.coerceAtLeast(0) ?: Constraints.Infinity
    val p = m.measure(Constraints(maxWidth = limit))
    layout(c.maxWidth, c.maxHeight) {
        p.place((x - ax * p.width).roundToInt(), (baseline - p[FirstBaseline]).roundToInt())
    }
}

fun Modifier.atBottom(x: Float, bottom: Float, maxWidth: Float) = layout { m, c ->
    val p = m.measure(Constraints(maxWidth = maxWidth.roundToInt().coerceAtLeast(0)))
    layout(c.maxWidth, c.maxHeight) {
        p.place(x.roundToInt(), (bottom - p.height).roundToInt())
    }
}

@Composable
fun RoleText(text: String, role: Role, modifier: Modifier, alpha: Float = role.alpha) {
    val ui = LocalUi.current
    val d = LocalDensity.current
    val shown = when (role.case) {
        Case.UPPER -> text.uppercase()
        Case.LOWER -> text.lowercase()
        Case.MIXED -> text
    }
    val style = with(d) {
        TextStyle(
            fontFamily = Selawik,
            fontWeight = role.weight,
            fontSize = ui.fontPx(role.size).toSp(),
            letterSpacing = ui.px(Tok.tracking).toSp(),
            color = white(alpha),
        )
    }
    BasicText(shown, modifier, style = style, overflow = TextOverflow.Clip, softWrap = false, maxLines = 1)
}
