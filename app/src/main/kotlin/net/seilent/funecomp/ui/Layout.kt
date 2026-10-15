package net.seilent.funecomp.ui

import net.seilent.funecomp.proto.gen.Tok
import kotlin.math.min

private const val PIVOT_TOP = 74f
private const val PIVOT_BOTTOM = 106f
private const val PIVOT_BASELINE_DROP = 21f
private const val CONTENT_TOP = 128f
private const val NP_TEXT_TOP = 117f
private const val NP_ALBUM_BASELINE = 141f
private const val NP_ART_TOP = 155f
private const val NP_ART_MAX = 168f
private const val NP_ART_STACK = 311f
private const val NP_PROGRESS_FROM_BOTTOM = 156f
private const val NP_TITLE_FROM_BOTTOM = 118f
private const val NP_NEXT_COUNT = 3
private const val NP_RING_FROM_BOTTOM = 40f
private const val NP_COLUMN_RIGHT = 216f
private const val NP_ART_TOP_LANDSCAPE = 72f
private const val NP_ART_LANDSCAPE_STACK = 106f
private const val NP_ARTIST_LANDSCAPE = 60f
private const val NP_ALBUM_LANDSCAPE = 84f
private const val NP_TITLE_LANDSCAPE = 116f
private const val NP_NEXT_LANDSCAPE = 140f
private const val NP_PROGRESS_GAP_LANDSCAPE = 8f
private const val SHORT_PROGRESS_FROM_BOTTOM = 120f
private const val SHORT_TITLE_FROM_BOTTOM = 82f
private const val DETAIL_RING_TOP = 150f
private const val DETAIL_LABEL_BASELINE = 200f
private const val DETAIL_COLUMN = 200f
private const val DETAIL_COLUMN_RIGHT = 216f
private const val OVERLAY_NUMBER_BASELINE = 160f
private const val OVERLAY_RING_Y = 216f
private const val OVERLAY_MINUS_Y = 372f
private const val HOME_PITCH_LANDSCAPE = 48f
private const val HOME_MENU_X_LANDSCAPE = 240f
private val NP_RING_X = listOf(0.13f, 0.5f, 0.87f)

val Ui.pivotTop get() = if (compact) Tok.pivotTop else PIVOT_TOP
val Ui.pivotBottom get() = if (compact) Tok.pivotBottom else PIVOT_BOTTOM
val Ui.pivotBaseline get() = pivotTop + PIVOT_BASELINE_DROP
val Ui.contentTop get() = if (compact) Tok.contentTop else CONTENT_TOP

val Ui.npTextX get() = if (landscape) NP_COLUMN_RIGHT else Tok.edge
val Ui.npArtistBaseline get() = when {
    landscape -> NP_ARTIST_LANDSCAPE
    short -> Tok.npTextTop
    else -> NP_TEXT_TOP
}
val Ui.npAlbumBaseline get() = when {
    landscape -> NP_ALBUM_LANDSCAPE
    short -> Tok.npAlbumBaseline
    else -> NP_ALBUM_BASELINE
}
val Ui.npArtTop get() = when {
    landscape -> NP_ART_TOP_LANDSCAPE
    short -> Tok.npArtTop
    else -> NP_ART_TOP
}
val Ui.npProgressY get() = when {
    landscape -> NP_ART_TOP_LANDSCAPE + npArt + NP_PROGRESS_GAP_LANDSCAPE
    short -> h - SHORT_PROGRESS_FROM_BOTTOM
    else -> h - NP_PROGRESS_FROM_BOTTOM
}
val Ui.npArt get() = when {
    landscape -> min(NP_ART_MAX, h - NP_ART_LANDSCAPE_STACK)
    short -> min(NP_ART_MAX, min(w - 2 * Tok.edge, h - SHORT_PROGRESS_FROM_BOTTOM - Tok.npArtTop))
    else -> min(NP_ART_MAX, min(w - 2 * Tok.edge, h - NP_ART_STACK))
}.coerceAtLeast(0f)
val Ui.npTitleBaseline get() = when {
    landscape -> NP_TITLE_LANDSCAPE
    short -> h - SHORT_TITLE_FROM_BOTTOM
    else -> h - NP_TITLE_FROM_BOTTOM
}
val Ui.npNextBaseline get() = if (landscape) NP_NEXT_LANDSCAPE else npTitleBaseline + Tok.npNextPitch
val Ui.npNextCount get() = if (short) Tok.npNextCount.toInt() else NP_NEXT_COUNT
val Ui.npRingY get() = h - NP_RING_FROM_BOTTOM

fun Ui.npRingCenter(index: Int): Float {
    val f = NP_RING_X[index]
    if (!landscape) return w * f
    return NP_COLUMN_RIGHT + (w - NP_COLUMN_RIGHT - Tok.edge) * f
}

val Ui.detailNameBaseline get() = pivotBaseline + Tok.detailNameDrop
val Ui.detailRingTop get() = if (short) Tok.detailRingTop else DETAIL_RING_TOP
val Ui.detailLabelBaseline get() = when {
    landscape -> contentTop + Tok.capHeight * Roles.sectionLabel.size
    short -> Tok.detailLabelBaseline
    else -> DETAIL_LABEL_BASELINE
}
val Ui.detailRowsTop get() = detailLabelBaseline + Tok.detailLabelDrop

val Ui.detailNameRight get() = if (landscape) DETAIL_COLUMN else w
val Ui.detailListX get() = if (landscape) DETAIL_COLUMN_RIGHT - Tok.hubEdge else 0f

fun detailRingX(index: Int) = Tok.hubEdge + index * (Tok.detailRing + Tok.detailRingGap)

val Ui.albumArtTop get() = when {
    landscape -> contentTop
    short -> Tok.albumArtTop
    else -> NP_ART_TOP
}
val Ui.albumRowsTop get() = if (landscape) contentTop else albumArtTop + Tok.tileL + Tok.albumRowsGap
val Ui.albumRowsX get() = if (landscape) NP_COLUMN_RIGHT - Tok.hubEdge else 0f
val Ui.albumTextX get() = if (landscape) NP_COLUMN_RIGHT else Tok.edge

val Ui.overlayAnchorScale get() = if (landscape || h < Tok.overlayAnchorH) h / Tok.overlayAnchorH else 1f
val Ui.overlayNumberBaseline get() = if (!landscape && h < Tok.overlayAnchorH) OVERLAY_NUMBER_BASELINE * overlayAnchorScale else OVERLAY_NUMBER_BASELINE
val Ui.overlayRingY get() = OVERLAY_RING_Y * overlayAnchorScale
val Ui.overlayMinusY get() = OVERLAY_MINUS_Y * overlayAnchorScale

val Ui.homePitch get() = if (landscape) HOME_PITCH_LANDSCAPE else Tok.homePitch
val Ui.homeMenuX get() = if (landscape) HOME_MENU_X_LANDSCAPE else 0f
val Ui.homeTextX get() = homeMenuX + Tok.homeTextX
