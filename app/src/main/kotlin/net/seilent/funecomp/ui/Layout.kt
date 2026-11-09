package net.seilent.funecomp.ui

import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.proto.gen.TokBoard
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val PIVOT_TOP = 74f
private const val PIVOT_BOTTOM = 106f
private const val PIVOT_BASELINE_DROP = 21f
private const val CONTENT_TOP = 128f
private const val NP_ART_TOP = 155f
private const val NP_ART_MAX = 168f
private const val NP_SURPLUS_BELOW = 1f / 3f
internal const val NP_TIMES_ROW = 58.77f
private const val NP_LYRIC_ROOM = 298f
private const val NP_RING_MARGIN_LANDSCAPE = 24f
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
private val NP_RING_X = listOf(TokBoard.v1.npRingCx0, TokBoard.v1.npRingCx1, TokBoard.v1.npRingCx2).map { it / Tok.canvasW }
private val NP_NEXT_COUNT = TokBoard.r1.npNextCount.toInt()
private val NP_RING_MARGIN = TokBoard.r1.canvasH - TokBoard.r1.npRingY - TokBoard.r1.npRing / 2
private val NP_R1_ART_BOTTOM = TokBoard.r1.npArtTop + TokBoard.r1.npArt
private val NP_R1_RING_TOP = TokBoard.r1.npRingY - TokBoard.r1.npRing / 2
internal val NP_THREE_NEXT_H = TokBoard.v1.npArtTop + NP_TIMES_ROW + NP_RING_MARGIN + Tok.overlayRing + (NP_R1_RING_TOP - NP_R1_ART_BOTTOM)
internal val NP_WIDE_ROW_H = NP_THREE_NEXT_H - TokBoard.v1.npNextLinePitch * (NP_NEXT_COUNT - 1)

private fun Ui.shortK(anchor: Float): Float = ((Ui.PORTRAIT_H - h) / (NP_ART_TOP - anchor)).coerceIn(0f, 1f)
private fun Ui.byK(tall: Float, short: Float, anchor: Float) = tall + (short - tall) * shortK(anchor)

val Ui.pivotTop: Float get() = when (shape) {
    Shape.P -> PIVOT_TOP
    Shape.SN -> byK(PIVOT_TOP, LayoutTok.pivotTop, Tok.albumArtTop)
    Shape.SW, Shape.L -> LayoutTok.pivotTop
}
val Ui.pivotBottom: Float get() = when (shape) {
    Shape.P -> PIVOT_BOTTOM
    Shape.SN -> byK(PIVOT_BOTTOM, LayoutTok.pivotBottom, Tok.albumArtTop)
    Shape.SW, Shape.L -> LayoutTok.pivotBottom
}
val Ui.pivotBaseline get() = pivotTop + PIVOT_BASELINE_DROP
val LayoutTok = TokBoard.v1

val Ui.contentTop: Float get() = when (shape) {
    Shape.P -> CONTENT_TOP
    Shape.SN -> byK(CONTENT_TOP, Tok.contentTop, Tok.albumArtTop)
    Shape.SW, Shape.L -> Tok.contentTop
}

val Ui.headerScale: Float get() = when (shape) {
    Shape.P -> Ui.PORTRAIT_W / Tok.canvasW
    Shape.SN -> byK(Ui.PORTRAIT_W / Tok.canvasW, 1f, Tok.albumArtTop)
    Shape.SW, Shape.L -> 1f
}
val Ui.headerBaseline get() = Tok.page_headerBaseline * headerScale

val Ui.npTextX: Float get() = when (shape) {
    Shape.P, Shape.SN -> LayoutTok.npTextX
    Shape.SW -> NP_COLUMN_RIGHT - NP_ART_MAX + npArt
    Shape.L -> NP_COLUMN_RIGHT
}
val Ui.npTextRight: Float get() = when (shape) {
    Shape.P, Shape.SN -> w - (Tok.canvasW - LayoutTok.npTextRight)
    Shape.SW, Shape.L -> w - Tok.edge
}
val Ui.npArtX: Float get() = when (shape) {
    Shape.P, Shape.SN -> LayoutTok.npArtX
    Shape.SW, Shape.L -> Tok.edge
}
val Ui.npArtistBaseline: Float get() = when (shape) {
    Shape.P, Shape.SN -> npAlbumBaseline - (TokBoard.r1.npAlbumY - TokBoard.r1.npArtistY)
    Shape.SW, Shape.L -> NP_ARTIST_LANDSCAPE
}
val Ui.npAlbumBaseline: Float get() = when (shape) {
    Shape.P, Shape.SN -> npArtTop - (TokBoard.r1.npArtTop - TokBoard.r1.npAlbumY)
    Shape.SW, Shape.L -> NP_ALBUM_LANDSCAPE
}
val Ui.npArtTop: Float get() = when (shape) {
    Shape.P, Shape.SN -> npArtBottom - npArt
    Shape.SW, Shape.L -> NP_ART_TOP_LANDSCAPE
}
val Ui.npArtBottom: Float get() = when (shape) {
    Shape.P, Shape.SN -> NP_R1_ART_BOTTOM + npStackDrop
    Shape.SW, Shape.L -> NP_ART_TOP_LANDSCAPE + npArt
}
val Ui.npProgressY: Float get() = when (shape) {
    Shape.P, Shape.SN -> npArtBottom
    Shape.SW, Shape.L -> NP_ART_TOP_LANDSCAPE + npArt + NP_PROGRESS_GAP_LANDSCAPE
}
val Ui.npBarY: Float get() = when (shape) {
    Shape.P, Shape.SN -> npArtBottom + Tok.npBarGap
    Shape.SW, Shape.L -> npProgressY
}
val Ui.npTimesBaseline: Float get() = when (shape) {
    Shape.P, Shape.SN -> npArtBottom + LayoutTok.npTimeBaseline
    Shape.SW, Shape.L -> npProgressY + Tok.npTimeDrop
}
val Ui.npSeekBottom: Float get() = when (shape) {
    Shape.P, Shape.SN -> npTitleBaseline - (TokBoard.r1.npTitleY - TokBoard.r1.npSeekBandBottom)
    Shape.SW, Shape.L -> npProgressY + TokBoard.r1.npSeekBandBottom - TokBoard.r1.npProgressY
}
val Ui.npArt: Float get() = when (shape) {
    Shape.P, Shape.SN -> min(TokBoard.r1.npArt, npArtBottom - LayoutTok.npArtTop)
    Shape.SW -> min(NP_ART_MAX, npRingY - npPlayRing / 2 - NP_ART_LANDSCAPE_STACK)
    Shape.L -> min(NP_ART_MAX, h - NP_ART_LANDSCAPE_STACK)
}.coerceAtLeast(0f)
val Ui.npTitleBaseline: Float get() = when (shape) {
    Shape.P, Shape.SN -> TokBoard.r1.npTitleY + npStackDrop
    Shape.SW, Shape.L -> NP_TITLE_LANDSCAPE
}
val Ui.npNextBaseline: Float get() = when (shape) {
    Shape.P, Shape.SN -> npTitleBaseline + LayoutTok.npNextDrop
    Shape.SW, Shape.L -> NP_NEXT_LANDSCAPE
}
val Ui.npNextCount: Int get() = when (shape) {
    Shape.P, Shape.SW, Shape.L -> NP_NEXT_COUNT
    Shape.SN -> if (h >= NP_THREE_NEXT_H) NP_NEXT_COUNT else 1
}
val Ui.npPlayRing: Float get() = when (shape) {
    Shape.P, Shape.SW, Shape.L -> Tok.overlayRing
    Shape.SN -> if (h >= NP_WIDE_ROW_H) Tok.overlayRing else LayoutTok.npRing
}
val Ui.npRowHit: Float get() = when (shape) {
    Shape.P, Shape.SW, Shape.L -> Tok.overlayRing
    Shape.SN -> if (h >= NP_WIDE_ROW_H) Tok.overlayRing else 2 * (npRingY - npNextBaseline - LayoutTok.npNextLinePitch * (npNextCount - 1))
}
val Ui.npRingY: Float get() = when (shape) {
    Shape.P, Shape.SN -> h - NP_RING_MARGIN - npPlayRing / 2 - npLift
    Shape.SW, Shape.L -> h - NP_RING_MARGIN_LANDSCAPE - npPlayRing / 2
}
val Ui.npLyricTop: Float get() = when (shape) {
    Shape.P -> npLyricBottom - NP_LYRIC_ROOM
    Shape.SN -> max(Tok.npTextTop, npLyricBottom - NP_LYRIC_ROOM)
    Shape.SW, Shape.L -> npAlbumBaseline - (Tok.npAlbumBaseline - Tok.npTextTop)
}
val Ui.npLyricBottom: Float get() = when (shape) {
    Shape.P, Shape.SN, Shape.L -> npNextBaseline + LayoutTok.npNextLinePitch * (npNextCount - 1)
    Shape.SW -> npRingY - npPlayRing / 2 - Tok.statusGap
}
private val Ui.npH0: Float get() = Ui.PORTRAIT_H * Tok.canvasW / Ui.PORTRAIT_W + NP_RING_MARGIN + npPlayRing + NP_R1_RING_TOP - TokBoard.r1.canvasH
private val Ui.npLift: Float get() = when (shape) {
    Shape.P, Shape.SN -> (h - npH0).coerceAtLeast(0f) * NP_SURPLUS_BELOW
    Shape.SW, Shape.L -> 0f
}
private val Ui.npStackDrop: Float get() = h - npLift - NP_RING_MARGIN - npPlayRing - NP_R1_RING_TOP + npNextDrop
private val Ui.npNextDrop: Float get() = LayoutTok.npNextLinePitch * (NP_NEXT_COUNT - npNextCount)

fun Ui.npRingCenter(index: Int): Float {
    val f = NP_RING_X[index]
    return when (shape) {
        Shape.P, Shape.SN, Shape.SW -> w * f
        Shape.L -> NP_COLUMN_RIGHT + (w - NP_COLUMN_RIGHT - Tok.edge) * f
    }
}

fun Ui.npTransportX(index: Int) = (npRingCenter(index) + npRingCenter(index + 1)) / 2

val Ui.npStatusGap get() = TokBoard.r1.npStatusGap

fun npHeartFits(art: Float) = art >= LayoutTok.npRing + 2 * Tok.detailRingInset

fun Ui.npHeartYPx(artY: Float) = artY.roundToInt() + px(Tok.detailRingInset).roundToInt() + px(LayoutTok.npRing) / 2
val Ui.npHeartHitPx get() = px(LayoutTok.npRing) + px(Tok.detailRingGap)

val Ui.npMarkCorner: Boolean get() = when (shape) {
    Shape.P, Shape.SN -> false
    Shape.SW, Shape.L -> true
}

fun npMarkFits(art: Float, corner: Boolean) = art >= if (corner) 2 * Tok.detailRingInset + 2 * LayoutTok.npRing + Tok.detailRingGap / 2 else LayoutTok.npRing

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
    Shape.SN, Shape.SW, Shape.L -> ALBUM_ARTIST_BASELINE - (NP_ART_TOP - albumArtTop)
}
val Ui.albumTitleBaseline: Float get() = when (shape) {
    Shape.P -> ALBUM_TITLE_BASELINE
    Shape.SN, Shape.SW, Shape.L -> ALBUM_TITLE_BASELINE - (NP_ART_TOP - albumArtTop)
}
val Ui.albumArtTop: Float get() = when (shape) {
    Shape.P -> NP_ART_TOP
    Shape.SN -> byK(NP_ART_TOP, Tok.albumArtTop, Tok.albumArtTop)
    Shape.SW, Shape.L -> Tok.albumArtTop
}
val Ui.albumRowsTop: Float get() = when (shape) {
    Shape.P, Shape.SN, Shape.SW -> albumArtTop + Tok.tileL + Tok.albumRowsGap
    Shape.L -> albumArtTop
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
val Ui.homeTextX get() = homeMenuX + LayoutTok.homeTextX

fun Ui.homeSlotTop(index: Int) = Held.Rest.homeFirstBaseline + index * homePitch - LayoutTok.homeBarTop
val Ui.homeRingX get() = homeMenuX + LayoutTok.homeRingX
fun Ui.homeRingY(music: Int) = Held.Rest.homeRingY + music * homePitch
val Ui.homeRingHitRight get() = homeMenuX + LayoutTok.homeBarX
fun Ui.homeRingHitBottom(music: Int) = music * homePitch + Held.Rest.homeRingHitBottom

fun homeRingEnter(ms: Float) = Decel.transform((ms / Tok.motion_homeEnterPeek).coerceIn(0f, 1f))
