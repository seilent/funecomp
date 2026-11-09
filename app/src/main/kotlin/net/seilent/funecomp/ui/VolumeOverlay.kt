package net.seilent.funecomp.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.state.DeviceState
import kotlin.math.roundToInt

private const val CONTROL_MAX = 255f

class Overlay {
    var shows by mutableIntStateOf(0)
        private set

    var hides by mutableIntStateOf(0)
        private set

    var until by mutableLongStateOf(0L)
        private set

    val visible get() = SystemClock.uptimeMillis() < until

    fun show() {
        until = SystemClock.uptimeMillis() + Tok.motion_volumeHide.toLong() + Tok.motion_volumeFade.toLong()
        shows++
    }

    fun hide() {
        until = SystemClock.uptimeMillis()
        hides++
    }
}

@Composable
fun VolumeOverlay(overlay: Overlay, device: DeviceState, actions: Control) {
    val ui = LocalUi.current
    val d = LocalDensity.current
    val alpha = remember { Animatable(0f) }
    var live by remember { mutableStateOf(false) }
    var touchClose by remember { mutableStateOf(false) }
    LaunchedEffect(overlay.shows, overlay.hides) {
        if (overlay.shows == 0) return@LaunchedEffect
        if (!overlay.visible) {
            live = false
            val touched = touchClose
            touchClose = false
            alpha.animateTo(0f, if (touched) tween(Held.Vol.motion_volumeTouchClose.toInt(), easing = LinearEasing) else tween(Tok.motion_volumeFade.toInt(), easing = Decel))
            return@LaunchedEffect
        }
        live = true
        touchClose = false
        launch { alpha.animateTo(1f, tween(Tok.motion_volumeShow.toInt(), easing = LinearEasing)) }
        delay(Tok.motion_volumeHide.toLong())
        live = false
        alpha.animateTo(0f, tween(Tok.motion_volumeFade.toInt(), easing = Decel))
    }
    fun close() {
        touchClose = true
        overlay.hide()
    }
    if (alpha.value <= 0f) return

    val s = device.playback.state
    val edge = ui.px(Tok.edge)
    val cx = ui.px(ui.w) / 2
    val ringY = ui.px(ui.overlayRingY)
    val minusY = ui.px(ui.overlayMinusY)
    val skip = ui.px(Tok.vol_skipX)
    val lit = white(Tok.overlayLit)
    val textW = ui.px(ui.w) - 2 * edge
    val number = s.volumePct ?: ((s.volume ?: 0) * 100f / CONTROL_MAX).roundToInt()
    val hit = with(d) { ui.px(Tok.vol_hit).toDp() }
    val ringHit = with(d) { ui.px(Tok.vol_ring).toDp() }
    val exitHit = with(d) { ui.px(Tok.adjustExit).toDp() }
    val plusY = ui.px(ui.volPlusY)
    val exitX = ui.px(ui.volExitX)
    val exitY = ui.px(ui.volExitY)
    val exitStyle = textStyle(Roles.volExit, lit)
    val exitLine = rememberTextMeasurer().measure(shown("exit", Roles.volExit.case), exitStyle)
    val exitCaps = with(d) { Offset(exitX + (exitLine.getLineLeft(0) + exitLine.getLineRight(0)) / 2, exitY + exitLine.firstBaseline - Tok.capHeight * exitStyle.fontSize.toPx() / 2) }

    val prevTouch = remember { MutableInteractionSource() }
    val ringTouch = remember { MutableInteractionSource() }
    val nextTouch = remember { MutableInteractionSource() }
    val prevHeld by prevTouch.collectIsPressedAsState()
    val ringHeld by ringTouch.collectIsPressedAsState()
    val nextHeld by nextTouch.collectIsPressedAsState()
    val pressOff = tween<Float>(Held.Vol.motion_volumePressOff.toInt(), easing = LinearEasing)
    val prevOff by animateFloatAsState(if (prevHeld) 1f else 0f, if (prevHeld) snap() else pressOff, label = "prev")
    val prevAlpha = if (prevHeld) 1f else prevOff
    val ringAlpha = if (ringHeld) 1f else 0f
    val nextOff by animateFloatAsState(if (nextHeld) 1f else 0f, if (nextHeld) snap() else pressOff, label = "next")
    val nextAlpha = if (nextHeld) 1f else nextOff
    val skipSide = ui.px(Held.Vol.vol_skip).roundToInt()
    val ringSide = ui.px(Tok.vol_ring).roundToInt()
    val stroke = ui.px(Tok.vol_ringStroke)
    val skipReach = ui.px(Tok.vol_glowSkip)
    val ringReach = ui.px(Tok.vol_glowRing)
    val masks = remember(skipSide, ringSide, stroke, skipReach, ringReach, d.density) { arrayOfNulls<ImageBitmap>(3) }
    if (prevHeld && masks[0] == null) masks[0] = glyphGlow(GlyphName.SKIP_PREVIOUS, skipSide, skipReach)
    if (ringHeld && masks[1] == null) masks[1] = ringGlow(ringSide, stroke, ringReach)
    if (nextHeld && masks[2] == null) masks[2] = glyphGlow(GlyphName.SKIP_NEXT, skipSide, skipReach)

    Box(Modifier.fillMaxSize().alpha(alpha.value).testTag("overlay")) {
        Box(Modifier.fillMaxSize().bleed(LocalBleed.current).background(Black.copy(alpha = Tok.scrim_overlay_alpha)))
        Canvas(Modifier.fillMaxSize().testTag("overlay.press")) {
            fun glow(m: ImageBitmap?, x: Float, a: Float) {
                if (m != null && a > 0f) drawImage(m, Offset(x - m.width / 2f, ringY - m.height / 2f), alpha = a)
            }
            glow(masks[0], cx - skip, prevAlpha)
            glow(masks[2], cx + skip, nextAlpha)
            glow(masks[1], cx, ringAlpha)
            if (ringAlpha > 0f) drawCircle(lit, (ringSide - 2 * stroke) / 2, Offset(cx, ringY), alpha = Tok.vol_discAlpha * ringAlpha)
        }
        Glyph(GlyphName.PLUS, Tok.vol_plus, lit, Modifier.at(cx, plusY, 0.5f, 0.5f).testTag("overlay.plus.glyph"))
        RoleText(
            number.toString(),
            Roles.overlayNumber,
            Modifier.atBaseline(edge, ui.px(ui.overlayNumberBaseline)).testTag("overlay.number"),
            alpha = Tok.overlayLit,
        )
        Glyph(GlyphName.SKIP_PREVIOUS, Held.Vol.vol_skip, white(Tok.alpha_full), Modifier.at(cx - skip, ringY, 0.5f, 0.5f).testTag("overlay.prev.glyph"))
        Ring(Tok.vol_ring, if (s.playing == true) GlyphName.PAUSE_WIDE else GlyphName.PLAY, Look.Touch, Modifier.at(cx, ringY, 0.5f, 0.5f), tag = "overlay.ring", stroke = Tok.vol_ringStroke, glyphSize = Tok.vol_pause)
        Glyph(GlyphName.SKIP_NEXT, Held.Vol.vol_skip, white(Tok.alpha_full), Modifier.at(cx + skip, ringY, 0.5f, 0.5f).testTag("overlay.next.glyph"))
        Box(Modifier.fillMaxSize().alpha(Tok.overlayDim)) {
            RoleText("exit", Roles.volExit, Modifier.at(exitX, exitY).testTag("overlay.exit"), alpha = Tok.alpha_full)
            RoleText("volume", Roles.volLabel, Modifier.at(cx, minusY + ui.px(Tok.overlayGlyph) / 2, ax = 0.5f).testTag("overlay.volume"), alpha = Tok.alpha_full)
            Lines(s.artist ?: "", s.title ?: "", textW)
        }
        Glyph(GlyphName.MINUS, Tok.vol_plus, lit, Modifier.at(cx, minusY, 0.5f, 0.5f).testTag("overlay.minus.glyph"))

        if (live && overlay.until - Tok.motion_volumeFade.toLong() > SystemClock.uptimeMillis()) Box(
            Modifier.fillMaxSize().testTag("overlay.plate").pointerInput(overlay) {
                awaitEachGesture {
                    val first = awaitFirstDown()
                    if (SystemClock.uptimeMillis() >= overlay.until - Tok.motion_volumeFade.toLong()) return@awaitEachGesture
                    first.consume()
                    val slop = viewConfiguration.touchSlop
                    var moved = false
                    while (true) {
                        val c = awaitPointerEvent().changes.firstOrNull { it.id == first.id } ?: break
                        c.consume()
                        if ((c.position - first.position).getDistance() > slop) moved = true
                        if (!c.pressed) {
                            if (!moved) close()
                            break
                        }
                    }
                }
            },
        )
        val touch = remember { MutableInteractionSource() }
        fun target(x: Float, y: Float, tag: String, box: Dp = hit, source: MutableInteractionSource = touch, act: () -> Unit) = Modifier.at(x, y, 0.5f, 0.5f).size(box).testTag(tag)
            .clickable(source, null) {
                act()
                overlay.show()
            }
        Box(target(cx, plusY, "overlay.plus") { actions.volume(1) })
        Box(target(cx, minusY, "overlay.minus") { actions.volume(-1) })
        Box(target(cx - skip, ringY, "overlay.prev", source = prevTouch) { actions.transport(Enums.TransportCmd.prev) })
        Box(target(cx, ringY, "overlay.ring", ringHit, ringTouch) { actions.transport(Enums.TransportCmd.toggle) })
        Box(target(cx + skip, ringY, "overlay.next", source = nextTouch) { actions.transport(Enums.TransportCmd.next) })
        Box(Modifier.at(exitCaps.x, exitCaps.y, 0.5f, 0.5f).size(exitHit).testTag("overlay.exit.hit").clickable(touch, null) { close() })
    }
}

internal var glowsBuilt = 0
    private set

internal fun glyphGlow(name: GlyphName, side: Int, reach: Float): ImageBitmap {
    val paint = Paint().apply {
        color = android.graphics.Color.WHITE
        isAntiAlias = true
    }
    return glow(side, (reach / 3).roundToInt().coerceAtLeast(1), 0f) { it.drawPath(path(name, side / Tok.glyphBox).asAndroidPath(), paint) }
}

internal fun ringGlow(side: Int, stroke: Float, reach: Float): ImageBitmap {
    val paint = Paint().apply {
        color = android.graphics.Color.WHITE
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = stroke
    }
    return glow(side, (reach / 3).roundToInt().coerceAtLeast(1), side / 2f) { it.drawCircle(side / 2f, side / 2f, (side - stroke) / 2, paint) }
}

private fun glow(side: Int, r: Int, inside: Float, cover: (android.graphics.Canvas) -> Unit): ImageBitmap {
    val pad = 3 * r
    val b = Bitmap.createBitmap(side + 2 * pad, side + 2 * pad, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(b)
    canvas.drawColor(android.graphics.Color.BLACK)
    canvas.translate(pad.toFloat(), pad.toFloat())
    cover(canvas)
    val px = IntArray(b.width * b.height)
    b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
    CoverBlur.blur(px, b.width, b.height, r)
    val c = b.width / 2f
    for (y in 0 until b.height) for (x in 0 until b.width) {
        val dx = x + 0.5f - c
        val dy = y + 0.5f - c
        val a = if (dx * dx + dy * dy < inside * inside) 0 else px[y * b.width + x] shr 16 and 0xFF
        px[y * b.width + x] = a shl 24 or 0xFFFFFF
    }
    b.setPixels(px, 0, b.width, 0, 0, b.width, b.height)
    glowsBuilt++
    return b.asImageBitmap()
}

@Composable
private fun Lines(artist: String, title: String, width: Float) {
    val ui = LocalUi.current
    Layout(
        content = {
            RoleText(artist, Roles.toastArtist, Modifier.testTag("overlay.artist"), alpha = Tok.type_toastArtist_alpha)
            RoleText(title, Roles.toastTitle, Modifier.testTag("overlay.title"), alpha = Tok.type_toastTitle_alpha)
        },
        modifier = Modifier.fillMaxSize(),
    ) { m, c ->
        val limit = Constraints(maxWidth = width.roundToInt().coerceAtLeast(0))
        val a = m[0].measure(limit)
        val t = m[1].measure(limit)
        layout(c.maxWidth, c.maxHeight) {
            val edge = ui.px(Tok.edge).roundToInt()
            val titleY = c.maxHeight - ui.px(Tok.overlayBottomMargin).roundToInt() - t.height
            val titleBaseline = titleY + t[FirstBaseline]
            t.place(edge, titleY)
            a.place(edge, titleBaseline - ui.px(Tok.overlayTextPitch).roundToInt() - a[FirstBaseline])
        }
    }
}
