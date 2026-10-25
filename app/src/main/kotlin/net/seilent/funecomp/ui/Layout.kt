package net.seilent.funecomp.ui

import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.proto.gen.TokBoard
import kotlin.math.abs
import kotlin.math.min

private const val PIVOT_TOP = 74f
private const val PIVOT_BOTTOM = 106f
private const val PIVOT_BASELINE_DROP = 21f
private const val CONTENT_TOP = 128f
private const val NP_ART_TOP = 155f
private const val NP_ART_MAX = 168f
private const val NP_ART_STACK = 311f
private const val NP_ALBUM_RISE = 14f
private const val NP_ARTIST_RISE = 24f
private const val NP_PROGRESS_FROM_BOTTOM = 156f
private const val NP_TITLE_FROM_BOTTOM = 118f
const val NP_NEXT_COUNT = 3
private const val NP_RING_FROM_BOTTOM = 40f
private const val NP_SURPLUS_BELOW = 1f / 3f
private const val NP_COLUMN_RIGHT = 216f
private const val NP_ART_TOP_LANDSCAPE = 72f
private const val NP_ART_LANDSCAPE_STACK = 106f
private const val NP_ARTIST_LANDSCAPE = 60f
private const val NP_ALBUM_LANDSCAPE = 84f
private const val NP_TITLE_LANDSCAPE = 116f
private const val NP_NEXT_LANDSCAPE = 140f
private const val NP_PROGRESS_GAP_LANDSCAPE = 8f
const val DETAIL_RING_TOP = 150f
private const val ALBUM_ARTIST_BASELINE = 117f
private const val ALBUM_TITLE_BASELINE = 141f
private const val DETAIL_LABEL_BASELINE = 200f
private const val DETAIL_COLUMN = 200f
private const val DETAIL_COLUMN_RIGHT = 216f
const val OVERLAY_NUMBER_BASELINE = 160f
private const val OVERLAY_RING_Y = 216f
private const val OVERLAY_MINUS_Y = 372f
private const val HOME_PITCH_LANDSCAPE = 48f
private const val HOME_MENU_X_LANDSCAPE = 240f
private val NP_RING_X = listOf(0.13f, 0.5f, 0.87f)

val Ui.pivotTop get() = if (landscape) Tok.pivotTop else PIVOT_TOP
val Ui.pivotBottom get() = if (landscape) Tok.pivotBottom else PIVOT_BOTTOM
val Ui.pivotBaseline get() = pivotTop + PIVOT_BASELINE_DROP
val LayoutTok = TokBoard.v1

val Ui.contentTop get() = if (landscape) Tok.contentTop else CONTENT_TOP

val Ui.npTextX get() = if (landscape) NP_COLUMN_RIGHT else Tok.edge
val Ui.npArtistBaseline get() = if (landscape) NP_ARTIST_LANDSCAPE else npAlbumBaseline - NP_ARTIST_RISE
val Ui.npAlbumBaseline get() = if (landscape) NP_ALBUM_LANDSCAPE else npArtTop - NP_ALBUM_RISE
val Ui.npArtTop get() = if (landscape) NP_ART_TOP_LANDSCAPE else npProgressY - npArt
val Ui.npProgressY get() = if (landscape) NP_ART_TOP_LANDSCAPE + npArt + NP_PROGRESS_GAP_LANDSCAPE else h - NP_PROGRESS_FROM_BOTTOM - npRowExtra - npLift
val Ui.npSeekBottom get() = npProgressY + LayoutTok.npSeekBandBottom - LayoutTok.npProgressY
val Ui.npArt get() = if (landscape) {
    min(NP_ART_MAX, h - NP_ART_LANDSCAPE_STACK)
} else {
    min(NP_ART_MAX, min(w - 2 * Tok.edge, h - NP_ART_STACK - npRowExtra))
}.coerceAtLeast(0f)
val Ui.npTitleBaseline get() = if (landscape) NP_TITLE_LANDSCAPE else h - NP_TITLE_FROM_BOTTOM - npRowExtra - npLift
val Ui.npNextBaseline get() = if (landscape) NP_NEXT_LANDSCAPE else npTitleBaseline + Tok.npNextPitch
val Ui.npRingY get() = h - (NP_RING_FROM_BOTTOM - Tok.npRing / 2) - Tok.overlayRing / 2 - npLift
val Ui.npLyricTop get() = if (landscape) npAlbumBaseline - (Tok.npAlbumBaseline - Tok.npTextTop) else npLyricBottom - NP_LYRIC_ROOM
val Ui.npLyricBottom get() = npNextBaseline + Tok.npNextPitch * (NP_NEXT_COUNT - 1)
private val Ui.npLift get() = if (landscape) 0f else (h - Ui.PORTRAIT_H - npRowExtra).coerceAtLeast(0f) * NP_SURPLUS_BELOW
private val Ui.npRowExtra get() = if (landscape) 0f else Tok.overlayRing - Tok.npRing
private val NP_LYRIC_ROOM = NP_ART_MAX + NP_ALBUM_RISE + (Tok.npAlbumBaseline - Tok.npTextTop) + (NP_PROGRESS_FROM_BOTTOM - NP_TITLE_FROM_BOTTOM) + Tok.npNextPitch * NP_NEXT_COUNT

fun Ui.npRingCenter(index: Int): Float {
    val f = NP_RING_X[index]
    if (!landscape) return w * f
    return NP_COLUMN_RIGHT + (w - NP_COLUMN_RIGHT - Tok.edge) * f
}

fun Ui.npTransportX(index: Int) = (npRingCenter(index) + npRingCenter(index + 1)) / 2

fun npHeartFits(art: Float) = art >= Tok.npRing + 2 * Tok.detailRingInset

fun npMarkFits(art: Float, landscape: Boolean) = art >= if (landscape) 2 * Tok.detailRingInset + 2 * Tok.npRing + Tok.detailRingGap / 2 else Tok.npRing

val Ui.detailNameBaseline get() = pivotBaseline + Tok.detailNameDrop
val Ui.detailLabelBaseline get() = if (landscape) contentTop + Tok.capHeight * Roles.sectionLabel.size else DETAIL_LABEL_BASELINE
val Ui.detailRowsTop get() = detailLabelBaseline + Tok.detailLabelDrop

val Ui.detailNameRight get() = if (landscape) DETAIL_COLUMN else w
val Ui.detailListX get() = if (landscape) DETAIL_COLUMN_RIGHT - Tok.hubEdge else 0f

fun detailRingX(index: Int) = Tok.hubEdge + index * (Tok.detailRing + Tok.detailRingGap)

val Ui.albumArtistBaseline get() = if (landscape) NP_ARTIST_LANDSCAPE else ALBUM_ARTIST_BASELINE
val Ui.albumTitleBaseline get() = if (landscape) NP_ALBUM_LANDSCAPE else ALBUM_TITLE_BASELINE
val Ui.albumArtTop get() = if (landscape) contentTop else NP_ART_TOP
val Ui.albumRowsTop get() = if (landscape) contentTop else albumArtTop + Tok.tileL + Tok.albumRowsGap
val Ui.albumRowsX get() = if (landscape) NP_COLUMN_RIGHT - Tok.hubEdge else 0f
val Ui.albumTextX get() = if (landscape) NP_COLUMN_RIGHT else Tok.edge

val Ui.overlayAnchorScale get() = if (landscape) h / Tok.overlayAnchorH else 1f
val Ui.overlayRingY get() = OVERLAY_RING_Y * overlayAnchorScale
val Ui.overlayMinusY get() = OVERLAY_MINUS_Y * overlayAnchorScale

val Ui.homePitch get() = if (landscape) HOME_PITCH_LANDSCAPE else Tok.homePitch
val Ui.homeMenuX get() = if (landscape) HOME_MENU_X_LANDSCAPE else 0f
val Ui.homeTextX get() = homeMenuX + Tok.homeTextX

fun Ui.homeSlotTop(index: Int) = Tok.homeFirstBaseline + index * homePitch - Tok.homeBarTop
val Ui.homeRingX get() = homeMenuX + Tok.homeRingX
fun Ui.homeRingY(music: Int) = Tok.homeRingY + music * homePitch
val Ui.homeRingHitRight get() = homeMenuX + min(Tok.homeBarX, Tok.homeRingX + Tok.homeRingSize / 2)
fun Ui.homeRingHitBottom(music: Int) = music * homePitch + Tok.homeRingHitBottom

fun homeRingEnter(ms: Float) = Decel.transform((ms / Tok.motion_homeEnterPeek).coerceIn(0f, 1f))

fun homeBarAlpha(focus: Float, music: Int) = abs(focus - music).coerceIn(0f, 1f)
