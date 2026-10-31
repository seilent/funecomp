package net.seilent.funecomp.ui

import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.proto.gen.TokBoard
import kotlin.math.abs
import kotlin.math.max
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
private const val NP_NEXT_COUNT = 3
private const val NP_RING_FROM_BOTTOM = 40f
private const val NP_SURPLUS_BELOW = 1f / 3f
private const val NP_TIMES_ROW = 52.6f
private const val NP_COLUMN_RIGHT = 216f
private const val NP_ART_TOP_LANDSCAPE = 72f
private const val NP_ART_LANDSCAPE_STACK = 106f
private const val NP_ARTIST_LANDSCAPE = 60f
private const val NP_ALBUM_LANDSCAPE = 84f
private const val NP_TITLE_LANDSCAPE = 116f
private const val NP_NEXT_LANDSCAPE = 140f
private const val NP_PROGRESS_GAP_LANDSCAPE = 8f
private const val DETAIL_RING_TOP = 150f
private const val ALBUM_ARTIST_BASELINE = 117f
private const val ALBUM_TITLE_BASELINE = 141f
private const val DETAIL_LABEL_BASELINE = 200f
private const val DETAIL_COLUMN = 200f
private const val DETAIL_COLUMN_RIGHT = 216f
private const val OVERLAY_NUMBER_BASELINE = 160f
private const val OVERLAY_RING_Y = 216f
private const val OVERLAY_MINUS_Y = 372f
private const val HOME_PITCH_LANDSCAPE = 48f
private const val HOME_MENU_X_LANDSCAPE = 240f
private val NP_RING_X = listOf(0.13f, 0.5f, 0.87f)
internal val NP_THREE_NEXT_H = Tok.npArtTop + NP_TIMES_ROW + NP_PROGRESS_FROM_BOTTOM + (Tok.overlayRing - Tok.npRing)
internal val NP_WIDE_ROW_H = NP_THREE_NEXT_H - Tok.npNextPitch * (NP_NEXT_COUNT - 1)

private val Ui.shortK: Float get() = ((Ui.PORTRAIT_H - h) / (NP_ART_TOP - Tok.npArtTop)).coerceIn(0f, 1f)
private fun Ui.byK(tall: Float, short: Float) = tall + (short - tall) * shortK

val Ui.pivotTop: Float get() = when (shape) {
    Shape.P -> PIVOT_TOP
    Shape.SN -> byK(PIVOT_TOP, Tok.pivotTop)
    Shape.SW, Shape.L -> Tok.pivotTop
}
val Ui.pivotBottom: Float get() = when (shape) {
    Shape.P -> PIVOT_BOTTOM
    Shape.SN -> byK(PIVOT_BOTTOM, Tok.pivotBottom)
    Shape.SW, Shape.L -> Tok.pivotBottom
}
val Ui.pivotBaseline get() = pivotTop + PIVOT_BASELINE_DROP
val LayoutTok = TokBoard.v1

val Ui.contentTop: Float get() = when (shape) {
    Shape.P -> CONTENT_TOP
    Shape.SN -> byK(CONTENT_TOP, Tok.contentTop)
    Shape.SW, Shape.L -> Tok.contentTop
}

val Ui.npTextX: Float get() = when (shape) {
    Shape.P, Shape.SN -> Tok.edge
    Shape.SW -> NP_COLUMN_RIGHT - NP_ART_MAX + npArt
    Shape.L -> NP_COLUMN_RIGHT
}
val Ui.npArtistBaseline: Float get() = when (shape) {
    Shape.P, Shape.SN -> npAlbumBaseline - NP_ARTIST_RISE
    Shape.SW, Shape.L -> NP_ARTIST_LANDSCAPE
}
val Ui.npAlbumBaseline: Float get() = when (shape) {
    Shape.P, Shape.SN -> npArtTop - NP_ALBUM_RISE
    Shape.SW, Shape.L -> NP_ALBUM_LANDSCAPE
}
val Ui.npArtTop: Float get() = when (shape) {
    Shape.P -> npProgressY - npArt
    Shape.SN -> byK(NP_ART_TOP, Tok.npArtTop)
    Shape.SW, Shape.L -> NP_ART_TOP_LANDSCAPE
}
val Ui.npProgressY: Float get() = when (shape) {
    Shape.P, Shape.SN -> h - NP_PROGRESS_FROM_BOTTOM - npRowExtra - npLift + npNextDrop
    Shape.SW, Shape.L -> NP_ART_TOP_LANDSCAPE + npArt + NP_PROGRESS_GAP_LANDSCAPE
}
val Ui.npSeekBottom get() = npProgressY + LayoutTok.npSeekBandBottom - LayoutTok.npProgressY
val Ui.npArt: Float get() = when (shape) {
    Shape.P -> min(NP_ART_MAX, min(w - 2 * Tok.edge, h - NP_ART_STACK - npRowExtra))
    Shape.SN -> min(NP_ART_MAX, min(w - 2 * Tok.edge, npProgressY - npArtTop))
    Shape.SW -> min(NP_ART_MAX, npRingY - npPlayRing / 2 - NP_ART_LANDSCAPE_STACK)
    Shape.L -> min(NP_ART_MAX, h - NP_ART_LANDSCAPE_STACK)
}.coerceAtLeast(0f)
val Ui.npTitleBaseline: Float get() = when (shape) {
    Shape.P, Shape.SN -> h - NP_TITLE_FROM_BOTTOM - npRowExtra - npLift + npNextDrop
    Shape.SW, Shape.L -> NP_TITLE_LANDSCAPE
}
val Ui.npNextBaseline: Float get() = when (shape) {
    Shape.P, Shape.SN -> npTitleBaseline + Tok.npNextPitch
    Shape.SW, Shape.L -> NP_NEXT_LANDSCAPE
}
val Ui.npNextCount: Int get() = when (shape) {
    Shape.P, Shape.SW, Shape.L -> NP_NEXT_COUNT
    Shape.SN -> if (h >= NP_THREE_NEXT_H) NP_NEXT_COUNT else 1
}
val Ui.npPlayRing: Float get() = when (shape) {
    Shape.P, Shape.SW, Shape.L -> Tok.overlayRing
    Shape.SN -> if (h >= NP_WIDE_ROW_H) Tok.overlayRing else Tok.npRing
}
val Ui.npRowHit: Float get() = when (shape) {
    Shape.P, Shape.SW, Shape.L -> Tok.overlayRing
    Shape.SN -> if (h >= NP_WIDE_ROW_H) Tok.overlayRing else 2 * (npRingY - npNextBaseline - Tok.npNextPitch * (npNextCount - 1))
}
val Ui.npRingY: Float get() = h - (NP_RING_FROM_BOTTOM - Tok.npRing / 2) - npPlayRing / 2 - npLift
val Ui.npLyricTop: Float get() = when (shape) {
    Shape.P -> npLyricBottom - NP_LYRIC_ROOM
    Shape.SN -> max(Tok.npTextTop, npLyricBottom - NP_LYRIC_ROOM)
    Shape.SW, Shape.L -> npAlbumBaseline - (Tok.npAlbumBaseline - Tok.npTextTop)
}
val Ui.npLyricBottom: Float get() = when (shape) {
    Shape.P, Shape.SN, Shape.L -> npNextBaseline + Tok.npNextPitch * (npNextCount - 1)
    Shape.SW -> npRingY - npPlayRing / 2 - Tok.statusGap
}
private val Ui.npLift: Float get() = when (shape) {
    Shape.P -> (h - Ui.PORTRAIT_H - npRowExtra).coerceAtLeast(0f) * NP_SURPLUS_BELOW
    Shape.SN, Shape.SW, Shape.L -> 0f
}
private val Ui.npRowExtra: Float get() = when (shape) {
    Shape.P, Shape.SN, Shape.SW -> npPlayRing - Tok.npRing
    Shape.L -> 0f
}
private val Ui.npNextDrop: Float get() = Tok.npNextPitch * (NP_NEXT_COUNT - npNextCount)
private val NP_LYRIC_ROOM = NP_ART_MAX + NP_ALBUM_RISE + (Tok.npAlbumBaseline - Tok.npTextTop) + (NP_PROGRESS_FROM_BOTTOM - NP_TITLE_FROM_BOTTOM) + Tok.npNextPitch * NP_NEXT_COUNT

fun Ui.npRingCenter(index: Int): Float {
    val f = NP_RING_X[index]
    return when (shape) {
        Shape.P, Shape.SN, Shape.SW -> w * f
        Shape.L -> NP_COLUMN_RIGHT + (w - NP_COLUMN_RIGHT - Tok.edge) * f
    }
}

fun Ui.npTransportX(index: Int) = (npRingCenter(index) + npRingCenter(index + 1)) / 2

fun npHeartFits(art: Float) = art >= Tok.npRing + 2 * Tok.detailRingInset

fun Ui.npHeartYPx(artY: Float) = artY + px(Tok.detailRingInset) + px(Tok.npRing) / 2
val Ui.npHeartHitPx get() = px(Tok.npRing) + px(Tok.detailRingGap)

val Ui.npMarkCorner: Boolean get() = when (shape) {
    Shape.P, Shape.SN -> false
    Shape.SW, Shape.L -> true
}

fun npMarkFits(art: Float, corner: Boolean) = art >= if (corner) 2 * Tok.detailRingInset + 2 * Tok.npRing + Tok.detailRingGap / 2 else Tok.npRing

val Ui.detailNameBaseline get() = pivotBaseline + Tok.detailNameDrop
val Ui.detailRingTop: Float get() = when (shape) {
    Shape.P, Shape.SN, Shape.SW -> DETAIL_RING_TOP - (PIVOT_TOP - pivotTop)
    Shape.L -> DETAIL_RING_TOP
}
val Ui.detailLabelBaseline: Float get() = when (shape) {
    Shape.P, Shape.SN, Shape.SW -> DETAIL_LABEL_BASELINE - (PIVOT_TOP - pivotTop)
    Shape.L -> contentTop + Tok.capHeight * Roles.sectionLabel.size
}
val Ui.detailRowsTop get() = detailLabelBaseline + Tok.detailLabelDrop

val Ui.detailNameRight: Float get() = when (shape) {
    Shape.P, Shape.SN, Shape.SW -> w
    Shape.L -> DETAIL_COLUMN
}
val Ui.detailListX: Float get() = when (shape) {
    Shape.P, Shape.SN, Shape.SW -> 0f
    Shape.L -> DETAIL_COLUMN_RIGHT - Tok.hubEdge
}

fun detailRingX(index: Int) = Tok.hubEdge + index * (Tok.detailRing + Tok.detailRingGap)

val Ui.albumArtistBaseline: Float get() = when (shape) {
    Shape.P -> ALBUM_ARTIST_BASELINE
    Shape.SN, Shape.SW -> ALBUM_ARTIST_BASELINE - (NP_ART_TOP - albumArtTop)
    Shape.L -> NP_ARTIST_LANDSCAPE
}
val Ui.albumTitleBaseline: Float get() = when (shape) {
    Shape.P -> ALBUM_TITLE_BASELINE
    Shape.SN, Shape.SW -> ALBUM_TITLE_BASELINE - (NP_ART_TOP - albumArtTop)
    Shape.L -> NP_ALBUM_LANDSCAPE
}
val Ui.albumArtTop: Float get() = when (shape) {
    Shape.P -> NP_ART_TOP
    Shape.SN -> byK(NP_ART_TOP, Tok.npArtTop)
    Shape.SW -> Tok.npArtTop
    Shape.L -> contentTop
}
val Ui.albumRowsTop: Float get() = when (shape) {
    Shape.P, Shape.SN, Shape.SW -> albumArtTop + Tok.tileL + Tok.albumRowsGap
    Shape.L -> contentTop
}
val Ui.albumLead: Float get() = when (shape) {
    Shape.P, Shape.L -> 0f
    Shape.SN, Shape.SW -> Tok.tileL - Tok.tileS + albumArtTop - albumArtistBaseline + Tok.capHeight * Roles.npArtist.size
}
fun albumSide(y: Float) = Tok.tileL - min(y, Tok.tileL - Tok.tileS)
fun albumDock(y: Float) = max(0f, y - (Tok.tileL - Tok.tileS))
fun Ui.albumLineX(dock: Float, rise: Float): Float = when (shape) {
    Shape.P, Shape.L -> albumTextX
    Shape.SN, Shape.SW -> albumTextX + (Tok.albumArtX + Tok.tileS + Tok.tileLGutter - albumTextX) * min(dock, rise) / rise
}
val Ui.albumRowsX: Float get() = when (shape) {
    Shape.P, Shape.SN, Shape.SW -> 0f
    Shape.L -> NP_COLUMN_RIGHT - Tok.hubEdge
}
val Ui.albumTextX: Float get() = when (shape) {
    Shape.P, Shape.SN, Shape.SW -> Tok.edge
    Shape.L -> NP_COLUMN_RIGHT
}

val Ui.overlayAnchorScale: Float get() = when (shape) {
    Shape.P -> 1f
    Shape.SN, Shape.SW, Shape.L -> h / Tok.overlayAnchorH
}
val Ui.overlayRingY get() = OVERLAY_RING_Y * overlayAnchorScale
val Ui.overlayMinusY get() = OVERLAY_MINUS_Y * overlayAnchorScale
val Ui.overlayNumberBaseline: Float get() = when (shape) {
    Shape.P, Shape.L -> OVERLAY_NUMBER_BASELINE
    Shape.SN, Shape.SW -> OVERLAY_NUMBER_BASELINE * overlayAnchorScale
}

val Ui.homePitch: Float get() = when (shape) {
    Shape.P, Shape.SN, Shape.SW -> Tok.homePitch
    Shape.L -> HOME_PITCH_LANDSCAPE
}
val Ui.homeMenuX: Float get() = when (shape) {
    Shape.P, Shape.SN, Shape.SW -> 0f
    Shape.L -> HOME_MENU_X_LANDSCAPE
}
val Ui.homeTextX get() = homeMenuX + Tok.homeTextX

fun Ui.homeSlotTop(index: Int) = Tok.homeFirstBaseline + index * homePitch - Tok.homeBarTop
val Ui.homeRingX get() = homeMenuX + Tok.homeRingX
fun Ui.homeRingY(music: Int) = Tok.homeRingY + music * homePitch
val Ui.homeRingHitRight get() = homeMenuX + min(Tok.homeBarX, Tok.homeRingX + Tok.homeRingSize / 2)
fun Ui.homeRingHitBottom(music: Int) = music * homePitch + Tok.homeRingHitBottom

fun homeRingEnter(ms: Float) = Decel.transform((ms / Tok.motion_homeEnterPeek).coerceIn(0f, 1f))

fun homeBarAlpha(focus: Float, music: Int) = abs(focus - music).coerceIn(0f, 1f)
