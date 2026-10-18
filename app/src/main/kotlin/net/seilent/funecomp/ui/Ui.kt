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
class Ui(val u: Float, val w: Float, val h: Float, val landscape: Boolean) {
    fun px(v: Float) = v * u

    fun fontPx(v: Float) = max(MIN_TEXT_PX, v * u).roundToInt().toFloat()

    companion object {
        const val MIN_TEXT_PX = 12f
        const val PORTRAIT_W = 272f
        const val PORTRAIT_H = 480f
        const val LANDSCAPE_W = 480f
        const val LANDSCAPE_H = 272f

        fun of(widthPx: Float, heightPx: Float): Ui {
            val landscape = widthPx > heightPx
            val u = if (landscape) {
                min(widthPx / LANDSCAPE_W, heightPx / LANDSCAPE_H)
            } else {
                min(widthPx / PORTRAIT_W, heightPx / PORTRAIT_H)
            }
            return Ui(u, widthPx / u, heightPx / u, landscape)
        }
    }
}

val LocalUi = staticCompositionLocalOf { Ui(1f, Tok.canvasW, Tok.canvasH, false) }

@Immutable
data class Bleed(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)

val LocalBleed = staticCompositionLocalOf { Bleed() }

fun Modifier.bleed(b: Bleed) = layout { m, c ->
    require(c.hasBoundedWidth && c.hasBoundedHeight)
    val p = m.measure(Constraints.fixed(c.maxWidth + b.left + b.right, c.maxHeight + b.top + b.bottom))
    layout(c.maxWidth, c.maxHeight) { p.place(-b.left, -b.top) }
}

enum class Case { UPPER, LOWER, MIXED }

@Immutable
data class Role(val size: Float, val weight: FontWeight, val case: Case, val alpha: Float, val dim: Float = alpha)

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
    val hubHeader = Role(Tok.type_hubHeader_size, weight(Tok.type_hubHeader_style), case(Tok.type_hubHeader_case), Tok.type_hubHeader_alpha)
    val homeItem = Role(Tok.type_homeItem_size, weight(Tok.type_homeItem_style), case(Tok.type_homeItem_case), Tok.type_homeItem_alpha, Tok.type_homeItem_alphaDim)
    val homeSubtitle = Role(Tok.type_homeSubtitle_size, weight(Tok.type_homeSubtitle_style), case(Tok.type_homeSubtitle_case), Tok.type_homeSubtitle_alpha)
    val pivotActive = Role(Tok.type_pivotActive_size, weight(Tok.type_pivotActive_style), case(Tok.type_pivotActive_case), Tok.type_pivotActive_alpha)
    val pivotInactive = Role(Tok.type_pivotInactive_size, weight(Tok.type_pivotInactive_style), case(Tok.type_pivotInactive_case), Tok.type_pivotInactive_alpha)
    val sectionLabel = Role(Tok.type_sectionLabel_size, weight(Tok.type_sectionLabel_style), case(Tok.type_sectionLabel_case), Tok.type_sectionLabel_alpha)
    val listPrimary = Role(Tok.type_listPrimary_size, weight(Tok.type_listPrimary_style), case(Tok.type_listPrimary_case), Tok.type_listPrimary_alpha, Tok.type_listPrimary_alphaDim)
    val listPlaylist = Role(Tok.type_listPlaylist_size, weight(Tok.type_listPlaylist_style), case(Tok.type_listPlaylist_case), Tok.type_listPlaylist_alpha, Tok.type_listPlaylist_alphaDim)
    val listSecondary = Role(Tok.type_listSecondary_size, weight(Tok.type_listSecondary_style), case(Tok.type_listSecondary_case), Tok.type_listSecondary_alpha)
    val listCount = Role(Tok.type_listCount_size, weight(Tok.type_listCount_style), case(Tok.type_listCount_case), Tok.type_listCount_alpha)
    val detailName = Role(Tok.type_detailName_size, weight(Tok.type_detailName_style), case(Tok.type_detailName_case), Tok.type_detailName_alpha)
    val settingsItem = Role(Tok.type_settingsItem_size, weight(Tok.type_settingsItem_style), case(Tok.type_settingsItem_case), Tok.type_settingsItem_alpha, Tok.type_settingsItem_alphaDim)
    val settingsValue = Role(Tok.type_settingsValue_size, weight(Tok.type_settingsValue_style), case(Tok.type_settingsValue_case), Tok.type_settingsValue_alpha)
    val settingsSub = Role(Tok.type_settingsSub_size, weight(Tok.type_settingsSub_style), case(Tok.type_settingsSub_case), Tok.type_settingsSub_alpha, Tok.type_settingsSub_alphaDim)
    val npArtist = Role(Tok.type_npArtist_size, weight(Tok.type_npArtist_style), case(Tok.type_npArtist_case), Tok.type_npArtist_alpha)
    val npAlbum = Role(Tok.type_npAlbum_size, weight(Tok.type_npAlbum_style), case(Tok.type_npAlbum_case), Tok.type_npAlbum_alpha)
    val npTitle = Role(Tok.type_npTitle_size, weight(Tok.type_npTitle_style), case(Tok.type_npTitle_case), Tok.type_npTitle_alpha)
    val npNext = Role(Tok.type_npNext_size, weight(Tok.type_npNext_style), case(Tok.type_npNext_case), Tok.type_npNext_alpha)
    val time = Role(Tok.type_time_size, weight(Tok.type_time_style), case(Tok.type_time_case), Tok.type_time_alpha)
    val overlayNumber = Role(Tok.type_overlayNumber_size, weight(Tok.type_overlayNumber_style), Case.MIXED, Tok.type_overlayNumber_alpha)
    val overlayLabel = Role(Tok.type_overlayLabel_size, weight(Tok.type_overlayLabel_style), case(Tok.type_overlayLabel_case), Tok.type_overlayLabel_alpha)
    val toastArtist = Role(Tok.type_toastArtist_size, weight(Tok.type_toastArtist_style), case(Tok.type_toastArtist_case), Tok.type_toastArtist_alpha)
    val toastTitle = Role(Tok.type_toastTitle_size, weight(Tok.type_toastTitle_style), case(Tok.type_toastTitle_case), Tok.type_toastTitle_alpha)
    val dialogTitle = Role(Tok.type_dialogTitle_size, weight(Tok.type_dialogTitle_style), case(Tok.type_dialogTitle_case), Tok.type_dialogTitle_alpha)
    val dialogItem = Role(Tok.type_dialogItem_size, weight(Tok.type_dialogItem_style), case(Tok.type_dialogItem_case), Tok.type_dialogItem_alpha, Tok.type_dialogItem_alphaDim)
    val watermark = Role(Tok.type_watermark_size, weight(Tok.type_watermark_style), case(Tok.type_watermark_case), Tok.type_watermark_alpha)
}

val Selawik = FontFamily(
    Font(R.font.selawkl, FontWeight.Light),
    Font(R.font.selawk, FontWeight.Normal),
    Font(R.font.selawkb, FontWeight.Bold),
)

fun white(alpha: Float) = Color(1f, 1f, 1f, alpha)

val Black = Color(Tok.color_black)

private val ACCENTS = listOf(
    Tok.accent_h0, Tok.accent_h10, Tok.accent_h20, Tok.accent_h30, Tok.accent_h40, Tok.accent_h50,
    Tok.accent_h60, Tok.accent_h70, Tok.accent_h80, Tok.accent_h90, Tok.accent_h100, Tok.accent_h110,
    Tok.accent_h120, Tok.accent_h130, Tok.accent_h140, Tok.accent_h150, Tok.accent_h160, Tok.accent_h170,
    Tok.accent_h180, Tok.accent_h190, Tok.accent_h200, Tok.accent_h210, Tok.accent_h220, Tok.accent_h230,
    Tok.accent_h240, Tok.accent_h250, Tok.accent_h260, Tok.accent_h270, Tok.accent_h280, Tok.accent_h290,
    Tok.accent_h300, Tok.accent_h310, Tok.accent_h320, Tok.accent_h330, Tok.accent_h340, Tok.accent_h350,
)

fun accentFor(hue: Int?): Color {
    val step = Tok.accent_hueStep.toInt()
    val n = Tok.accent_hueCount.toInt()
    val h = hue ?: return Color(Tok.accent_default)
    val i = ((Math.round(h.toFloat() / step) % n) + n) % n
    return Color(ACCENTS[i])
}

val LocalAccent = staticCompositionLocalOf { Color(Tok.accent_default) }

val Accent: Color
    @Composable get() = LocalAccent.current

fun shown(text: String, case: Case) = when (case) {
    Case.UPPER -> text.uppercase()
    Case.LOWER -> text.lowercase()
    Case.MIXED -> text
}

fun Modifier.at(x: Float, y: Float, ax: Float = 0f, ay: Float = 0f, unbounded: Boolean = false) = layout { m, c ->
    val p = m.measure(if (unbounded) Constraints() else c.copy(minWidth = 0, minHeight = 0))
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
fun textStyle(role: Role, color: Color, tracking: Float = Tok.tracking): TextStyle {
    val ui = LocalUi.current
    return with(LocalDensity.current) {
        TextStyle(
            fontFamily = Selawik,
            fontWeight = role.weight,
            fontSize = ui.fontPx(role.size).toSp(),
            letterSpacing = ui.px(tracking).toSp(),
            color = color,
        )
    }
}

@Composable
fun RoleText(text: String, role: Role, modifier: Modifier, alpha: Float = role.alpha, color: Color = white(alpha), tracking: Float = Tok.tracking) {
    BasicText(shown(text, role.case), modifier, style = textStyle(role, color, tracking), overflow = TextOverflow.Clip, softWrap = false, maxLines = 1)
}
