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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.state.DeviceState
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val CONTROL_MAX = 255f
private const val EXIT = "exit"
private const val VOLUME = "volume"

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
    val cx = ui.px(ui.w) / 2
    val ringY = ui.px(ui.volRingY)
    val minusY = ui.px(ui.volMinusY)
    val skip = ui.px(Tok.vol_skipX)
    val lit = white(Tok.overlayLit)
    val number = s.volumePct ?: ((s.volume ?: 0) * 100f / CONTROL_MAX).roundToInt()
    val hit = with(d) { ui.px(Tok.vol_hit).toDp() }
    val ringHit = with(d) { ui.px(Tok.vol_ring).toDp() }
    val exitHit = with(d) { ui.px(Tok.adjustExit).toDp() }
    val plusY = ui.px(ui.volPlusY)
    val resolver = LocalFontFamilyResolver.current
    val exitCaps = Offset(ui.px(Tok.vol_exitX), ui.px(ui.volExitY))
    val exitLeft = exitCaps.x - remember(ui, resolver) { ink(shown(EXIT, Roles.volExit.case), Roles.volExit, ui, resolver) }.centerX()
    val exitBase = exitCaps.y + Tok.capHeight * ui.fontPx(Roles.volExit.size) / 2
    val numText = (if (number < 10) "0" else "") + number
    val numRole = remember(numText, ui, resolver) { numberRole(numText, ui.px(ui.w / 2 - Tok.vol_plus * 3 / 8 - Tok.vol_numberX), ui, resolver) }
    val numLeft = ui.px(Tok.vol_numberX) - remember(numText, numRole, ui, resolver) { ink(numText, numRole, ui, resolver) }.left
    val numBase = ui.px(ui.volNumberY) + Tok.capHeight * ui.fontPx(numRole.size) / 2
    val labelPx = ui.fontPx(Roles.volLabel.size)
    val labelBase = ui.px(ui.volLabelY) + Tok.capHeight * labelPx
    val phone = s.source == Enums.Source.phone
    val artist = if (phone) s.phoneName?.let { "from $it" } ?: "" else s.artist ?: ""
    val title = s.title ?: ""
    val lines = lineBases(artist, title, labelBase - Tok.capHeight * labelPx / 2, labelBase + remember(ui, resolver) { ink(shown(VOLUME, Roles.volLabel.case), Roles.volLabel, ui, resolver) }.bottom)

    val prevPress = rememberPress()
    val ringPress = rememberPress()
    val nextPress = rememberPress()
    val plusPress = rememberPress()
    val minusPress = rememberPress()
    val exitPress = rememberPress()
    fun pressLit(p: PressState) = if (p.held) 1f else p.lit
    val skipSide = ui.px(Tok.vol_skip).roundToInt()
    val plusSide = ui.px(Tok.vol_plus).roundToInt()
    val ringSide = ui.px(Tok.vol_ring).roundToInt()
    val stroke = ui.px(Tok.vol_ringStroke)
    val skipReach = ui.px(Tok.vol_glowSkip)
    val ringReach = ui.px(Tok.vol_glowRing)
    val masks = remember(skipSide, plusSide, ringSide, stroke, skipReach, ringReach, d.density) { arrayOfNulls<ImageBitmap>(5) }
    if (prevPress.held && masks[0] == null) masks[0] = glyphGlow(GlyphName.SKIP_PREVIOUS, skipSide, skipReach)
    if (ringPress.held && masks[1] == null) masks[1] = ringGlow(ringSide, stroke, ringReach)
    if (nextPress.held && masks[2] == null) masks[2] = glyphGlow(GlyphName.SKIP_NEXT, skipSide, skipReach)
    if (plusPress.held && masks[3] == null) masks[3] = glyphGlow(GlyphName.PLUS, plusSide, skipReach)
    if (minusPress.held && masks[4] == null) masks[4] = glyphGlow(GlyphName.MINUS, plusSide, skipReach)

    val tilt = remember { VolumeTilt() }
    LaunchedEffect(tilt.downs) {
        if (tilt.downs == 0) return@LaunchedEffect
        var moving = true
        while (moving) {
            withFrameMillis { t ->
                tilt.drawn = tilt.k(t)
                moving = tilt.moving(t)
            }
        }
    }
    val eye = ui.px(ui.w) * Tok.motion_volumeTiltEye
    val plate = Modifier.drawWithContent {
        val k = tilt.drawn
        if (k <= 0f) {
            drawContent()
        } else {
            val h = tiltMatrix(tilt.pose, k, size.width / 2, size.height / 2, eye)
            val m = Matrix()
            m[0, 0] = h[0]
            m[1, 0] = h[1]
            m[3, 0] = h[2]
            m[0, 1] = h[3]
            m[1, 1] = h[4]
            m[3, 1] = h[5]
            m[0, 3] = h[6]
            m[1, 3] = h[7]
            m[3, 3] = h[8]
            withTransform({ transform(m) }) { this@drawWithContent.drawContent() }
        }
    }

    Box(Modifier.fillMaxSize().alpha(alpha.value).testTag("overlay")) {
        Box(Modifier.fillMaxSize().bleed(LocalBleed.current).background(Black.copy(alpha = Tok.scrim_overlay_alpha)))
        Box(Modifier.fillMaxSize().then(plate).testTag("overlay.plate.parts")) {
            Canvas(Modifier.fillMaxSize().testTag("overlay.press")) {
                fun glow(m: ImageBitmap?, x: Float, a: Float, y: Float = ringY) {
                    if (m != null && a > 0f) drawImage(m, Offset(x - m.width / 2f, y - m.height / 2f), alpha = a)
                }
                val ringAlpha = pressLit(ringPress)
                glow(masks[0], cx - skip, pressLit(prevPress))
                glow(masks[2], cx + skip, pressLit(nextPress))
                glow(masks[3], cx, pressLit(plusPress), plusY)
                glow(masks[4], cx, pressLit(minusPress), minusY)
                glow(masks[1], cx, ringAlpha)
                if (ringAlpha > 0f) drawCircle(lit, (ringSide - 2 * stroke) / 2, Offset(cx, ringY), alpha = Tok.vol_discAlpha * ringAlpha)
            }
            Glyph(GlyphName.PLUS, Tok.vol_plus, lit, Modifier.at(cx, plusY, 0.5f, 0.5f).testTag("overlay.plus.glyph"))
            RoleText(numText, numRole, Modifier.atBaseline(numLeft, numBase).testTag("overlay.number"), color = lit)
            Glyph(GlyphName.SKIP_PREVIOUS, Tok.vol_skip, white(Tok.alpha_full), Modifier.at(cx - skip, ringY, 0.5f, 0.5f).testTag("overlay.prev.glyph"))
            Ring(Tok.vol_ring, if (s.playing == true) GlyphName.PAUSE_WIDE else GlyphName.PLAY, Look.Touch, Modifier.at(cx, ringY, 0.5f, 0.5f), tag = "overlay.ring", stroke = Tok.vol_ringStroke, glyphSize = Tok.vol_pause)
            Glyph(GlyphName.SKIP_NEXT, Tok.vol_skip, white(Tok.alpha_full), Modifier.at(cx + skip, ringY, 0.5f, 0.5f).testTag("overlay.next.glyph"))
            RoleText(VOLUME, Roles.volLabel, Modifier.atBaseline(cx, labelBase, ax = 0.5f).testTag("overlay.volume"), color = lit)
            Glyph(GlyphName.MINUS, Tok.vol_plus, lit, Modifier.at(cx, minusY, 0.5f, 0.5f).testTag("overlay.minus.glyph"))
        }
        Lines(artist, title, lines)

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
        Box(Modifier.at(exitCaps.x, exitCaps.y, 0.5f, 0.5f).size(exitHit).testTag("overlay.exit.hit").press({ close() }, press = exitPress))
        RoleText(EXIT, Roles.volExit, Modifier.atBaseline(exitLeft, exitBase).testTag("overlay.exit"), color = lit, glow = exitPress.word)
        val touch = remember { MutableInteractionSource() }
        fun target(x: Float, y: Float, tag: String, box: Dp = hit, p: PressState? = null, act: () -> Unit): Modifier {
            val side = with(d) { box.toPx() }
            val at = Modifier.at(x, y, 0.5f, 0.5f).size(box).testTag(tag).tiltOn(tilt, Offset(x - side / 2, y - side / 2), ui.px(ui.w), ui.px(ui.h))
            val go = {
                act()
                overlay.show()
            }
            return if (p == null) at.clickable(touch, null, onClick = go) else at.press({ go() }, press = p)
        }
        Box(target(cx, plusY, "overlay.plus", p = plusPress) { actions.volume(1) })
        Box(target(cx, minusY, "overlay.minus", p = minusPress) { actions.volume(-1) })
        Box(target(cx - skip, ringY, "overlay.prev", p = prevPress) { actions.transport(Enums.TransportCmd.prev) })
        Box(target(cx, ringY, "overlay.ring", ringHit, ringPress) { actions.transport(Enums.TransportCmd.toggle) })
        Box(target(cx + skip, ringY, "overlay.next", p = nextPress) { actions.transport(Enums.TransportCmd.next) })
    }
}

internal class TiltPose(val rx: Float, val ry: Float)

private fun Modifier.tiltOn(tilt: VolumeTilt, at: Offset, w: Float, h: Float) = pointerInput(tilt, at, w, h) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        tilt.down((at.x + down.position.x) / w, (at.y + down.position.y) / h)
        var last = down.uptimeMillis
        while (true) {
            val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            last = c.uptimeMillis
            if (!c.pressed) break
            tilt.move((at.x + c.position.x) / w, (at.y + c.position.y) / h)
        }
        tilt.up(last)
    }
}

internal fun platePose(nx: Float, ny: Float, rateDeg: Float = Tok.motion_volumeTiltRate): TiltPose {
    val r = Math.toRadians(rateDeg.toDouble()).toFloat()
    return TiltPose(r * (ny.coerceIn(0f, 1f) - 0.5f), -r * (nx.coerceIn(0f, 1f) - 0.5f))
}

private fun mul3(a: FloatArray, b: FloatArray) = FloatArray(9) { i -> (0 until 3).sumOf { j -> (a[i / 3 * 3 + j] * b[j * 3 + i % 3]).toDouble() }.toFloat() }

internal fun tiltMatrix(p: TiltPose, q: Float, cx: Float, cy: Float, d: Float): FloatArray {
    val ax = p.rx * q
    val ay = p.ry * q
    val ca = cos(ax)
    val sa = sin(ax)
    val cb = cos(ay)
    val sb = sin(ay)
    val to = floatArrayOf(1f, 0f, -cx, 0f, 1f, -cy, 0f, 0f, 1f)
    val back = floatArrayOf(1f, 0f, cx, 0f, 1f, cy, 0f, 0f, 1f)
    val turn = floatArrayOf(d * cb, d * sa * sb, 0f, 0f, d * ca, 0f, -sb, sa * cb, d)
    return mul3(back, mul3(turn, to))
}

@Stable
internal class VolumeTilt {
    var pose by mutableStateOf(TiltPose(0f, 0f))
        private set

    var downs by mutableIntStateOf(0)
        private set

    var drawn by mutableFloatStateOf(0f)

    private var on = false
    private var upAt = -1L

    fun down(nx: Float, ny: Float) {
        on = true
        upAt = -1L
        pose = platePose(nx, ny)
        drawn = 1f
        downs++
    }

    fun move(nx: Float, ny: Float) {
        if (on && upAt < 0) pose = platePose(nx, ny)
    }

    fun up(now: Long) {
        if (on && upAt < 0) upAt = now
    }

    fun k(now: Long): Float {
        if (!on) return 0f
        if (upAt < 0) return 1f
        return 1f - ((now - upAt - Tok.motion_volumeTiltHold) / Tok.motion_volumeTiltReturn).coerceIn(0f, 1f)
    }

    fun moving(now: Long): Boolean {
        if (on && upAt >= 0 && now >= upAt + Tok.motion_volumeTiltHold + Tok.motion_volumeTiltReturn) on = false
        return on
    }
}

private class LineBases(val artist: Float, val title: Float)

private fun numberRole(text: String, room: Float, ui: Ui, resolver: FontFamily.Resolver): Role {
    val full = Roles.volNumber
    fun inkW(px: Float) = ink(text, full.copy(size = px / ui.u), ui, resolver).width()
    val whole = ui.fontPx(full.size)
    val w = inkW(whole)
    if (w <= room) return full
    var px = (whole * room / w).toInt().toFloat()
    while (px > 1f && inkW(px) > room) px -= 1f
    return full.copy(size = px / ui.u)
}

@Composable
private fun lineBases(artist: String, title: String, labelCaps: Float, labelInk: Float): LineBases {
    val ui = LocalUi.current
    val resolver = LocalFontFamilyResolver.current
    val a = remember(artist, ui, resolver) { ink(shown(artist, Roles.volArtist.case), Roles.volArtist, ui, resolver) }
    val t = remember(title, ui, resolver) { ink(shown(title, Roles.volTitle.case), Roles.volTitle, ui, resolver) }
    val top = max(ui.px(ui.volTextY), labelCaps + ui.px(LayoutTok.vol_textGap))
    val artistBase = top + Tok.capHeight * ui.fontPx(Roles.volArtist.size) / 2
    val titleBase = artistBase + ui.px(LayoutTok.vol_textPitch)
    val need = if (title.isEmpty()) 0f else max(0f, titleBase + t.bottom - ui.px(ui.h))
    val room = max(0f, artistBase + (if (artist.isEmpty()) 0f else a.top) - labelInk)
    val lift = min(need, room)
    return LineBases(artistBase - lift, titleBase - lift)
}

@Composable
private fun Lines(artist: String, title: String, at: LineBases) {
    val ui = LocalUi.current
    val x = ui.px(Tok.vol_textX)
    val right = ui.px(ui.w - Tok.edge)
    Box(Modifier.at(x, 0f).size((right - x).coerceAtLeast(0f).dp(), ui.px(ui.h).dp()).clipToBounds()) {
        RoleText(artist, Roles.volArtist, Modifier.atBaseline(0f, at.artist).testTag("overlay.artist"), color = white(Tok.overlayLit))
        RoleText(title, Roles.volTitle, Modifier.atBaseline(0f, at.title).testTag("overlay.title"), color = white(Tok.overlayLit))
    }
}
