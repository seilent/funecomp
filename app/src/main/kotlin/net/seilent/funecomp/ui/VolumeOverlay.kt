package net.seilent.funecomp.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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

    var drawn by mutableStateOf(false)
        internal set

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
            alpha.animateTo(0f, if (touched) tween(Tok.motion_volumeTouchClose.toInt(), easing = LinearEasing) else tween(Tok.motion_volumeFade.toInt(), easing = Decel))
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
    SideEffect { overlay.drawn = alpha.value > 0f }
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

    val prevPress = rememberPress()
    val ringPress = rememberPress()
    val nextPress = rememberPress()
    fun pressLit(p: PressState) = if (p.held) 1f else p.lit
    val skipSide = ui.px(Tok.vol_skip).roundToInt()
    val ringSide = ui.px(Tok.vol_ring).roundToInt()
    val stroke = ui.px(Tok.vol_ringStroke)
    val skipReach = ui.px(Tok.vol_glowSkip)
    val ringReach = ui.px(Tok.vol_glowRing)
    val masks = remember(skipSide, ringSide, stroke, skipReach, ringReach, d.density) { arrayOfNulls<ImageBitmap>(3) }
    if (prevPress.held && masks[0] == null) masks[0] = glyphGlow(GlyphName.SKIP_PREVIOUS, skipSide, skipReach)
    if (ringPress.held && masks[1] == null) masks[1] = ringGlow(ringSide, stroke, ringReach)
    if (nextPress.held && masks[2] == null) masks[2] = glyphGlow(GlyphName.SKIP_NEXT, skipSide, skipReach)

    Box(Modifier.fillMaxSize().alpha(alpha.value).testTag("overlay")) {
        Box(Modifier.fillMaxSize().bleed(LocalBleed.current).background(Black.copy(alpha = Tok.scrim_overlay_alpha)))
        Canvas(Modifier.fillMaxSize().testTag("overlay.press")) {
            fun glow(m: ImageBitmap?, x: Float, a: Float) {
                if (m != null && a > 0f) drawImage(m, Offset(x - m.width / 2f, ringY - m.height / 2f), alpha = a)
            }
            val ringAlpha = pressLit(ringPress)
            glow(masks[0], cx - skip, pressLit(prevPress))
            glow(masks[2], cx + skip, pressLit(nextPress))
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
        Glyph(GlyphName.SKIP_PREVIOUS, Tok.vol_skip, white(Tok.alpha_full), Modifier.at(cx - skip, ringY, 0.5f, 0.5f).testTag("overlay.prev.glyph"))
        Ring(Tok.vol_ring, if (s.playing == true) GlyphName.PAUSE_WIDE else GlyphName.PLAY, Look.Touch, Modifier.at(cx, ringY, 0.5f, 0.5f), tag = "overlay.ring", stroke = Tok.vol_ringStroke, glyphSize = Tok.vol_pause)
        Glyph(GlyphName.SKIP_NEXT, Tok.vol_skip, white(Tok.alpha_full), Modifier.at(cx + skip, ringY, 0.5f, 0.5f).testTag("overlay.next.glyph"))
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
        fun target(x: Float, y: Float, tag: String, box: Dp = hit, p: PressState? = null, act: () -> Unit): Modifier {
            val at = Modifier.at(x, y, 0.5f, 0.5f).size(box).testTag(tag)
            val go = {
                act()
                overlay.show()
            }
            return if (p == null) at.clickable(touch, null, onClick = go) else at.press({ go() }, press = p)
        }
        Box(target(cx, plusY, "overlay.plus") { actions.volume(1) })
        Box(target(cx, minusY, "overlay.minus") { actions.volume(-1) })
        Box(target(cx - skip, ringY, "overlay.prev", p = prevPress) { actions.transport(Enums.TransportCmd.prev) })
        Box(target(cx, ringY, "overlay.ring", ringHit, ringPress) { actions.transport(Enums.TransportCmd.toggle) })
        Box(target(cx + skip, ringY, "overlay.next", p = nextPress) { actions.transport(Enums.TransportCmd.next) })
        Box(Modifier.at(exitCaps.x, exitCaps.y, 0.5f, 0.5f).size(exitHit).testTag("overlay.exit.hit").clickable(touch, null) { close() })
    }
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
