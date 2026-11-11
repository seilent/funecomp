package net.seilent.funecomp.ui

import android.os.SystemClock
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
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
private const val MEASURES = 16

class Overlay {
    var shows by mutableIntStateOf(0)
        private set

    var hides by mutableIntStateOf(0)
        private set

    var until by mutableLongStateOf(0L)
        private set

    var drawn by mutableStateOf(false)
        internal set

    internal var open by mutableStateOf(false)
        private set

    val visible get() = open && SystemClock.uptimeMillis() < until

    fun show() {
        open = true
        if (until != Long.MAX_VALUE) until = max(until, SystemClock.uptimeMillis() + VolumeLegs.SPAN)
        shows++
    }

    fun hide() {
        open = false
        until = SystemClock.uptimeMillis()
        hides++
    }

    internal fun left(ms: Long) {
        if (open) until = if (ms == Long.MAX_VALUE) ms else SystemClock.uptimeMillis() + ms
    }

    internal fun opened(ms: Long) {
        open = true
        left(ms)
    }

    internal fun ended() {
        if (!open) return
        open = false
        until = SystemClock.uptimeMillis()
    }
}

@Composable
fun VolumeOverlay(overlay: Overlay, device: DeviceState, actions: Control) {
    val ui = LocalUi.current
    val d = LocalDensity.current
    val legs = remember { VolumeLegs() }
    var now by remember { mutableLongStateOf(-1L) }
    var kick by remember { mutableIntStateOf(0) }
    val s = device.playback.state
    val number = s.volumePct ?: ((s.volume ?: 0) * 100f / CONTROL_MAX).roundToInt()
    val phone = s.source == Enums.Source.phone
    val artist = if (phone) s.phoneName?.let { "from $it" } ?: "" else s.artist ?: ""
    val title = s.title ?: ""
    var inArtist by remember { mutableStateOf(artist) }
    var inTitle by remember { mutableStateOf(title) }
    var oldArtist by remember { mutableStateOf("") }
    var oldTitle by remember { mutableStateOf("") }
    val seen = remember { intArrayOf(number) }
    LaunchedEffect(overlay.shows) {
        if (overlay.shows == 0) return@LaunchedEffect
        withFrameMillis { t ->
            if (!legs.visible(t)) {
                inArtist = artist
                inTitle = title
            }
            legs.open(t)
            overlay.opened(legs.left(t))
            now = t
            kick++
        }
    }
    LaunchedEffect(overlay.hides) {
        if (overlay.hides == 0) return@LaunchedEffect
        withFrameMillis { t ->
            legs.hide(t)
            now = t
            kick++
        }
    }
    LaunchedEffect(number) {
        if (number == seen[0]) return@LaunchedEffect
        seen[0] = number
        withFrameMillis { t -> if (legs.change(t)) overlay.left(legs.left(t)) }
    }
    LaunchedEffect(artist, title) {
        if (artist == inArtist && title == inTitle) return@LaunchedEffect
        withFrameMillis { t ->
            if (legs.track(t)) {
                oldArtist = inArtist
                oldTitle = inTitle
            }
            inArtist = artist
            inTitle = title
        }
    }
    LaunchedEffect(kick) {
        if (kick == 0) return@LaunchedEffect
        var shown = true
        while (shown) {
            withFrameMillis { t ->
                now = t
                shown = legs.visible(t)
                overlay.drawn = shown
                if (!shown) overlay.ended()
            }
        }
    }
    fun close(t: Long) {
        legs.hide(t)
        overlay.hide()
    }
    if (now < 0 || !legs.visible(now)) return
    val l = legs.look(now)

    val cx = ui.px(ui.w) / 2
    val ringY = ui.px(ui.volRingY)
    val minusY = ui.px(ui.volMinusY)
    val skip = ui.px(Tok.vol_skipX)
    val lit = white(Tok.overlayLit)
    val hit = with(d) { ui.px(Tok.vol_hit).toDp() }
    val ringHit = with(d) { ui.px(Tok.vol_ring).toDp() }
    val exitHit = with(d) { ui.px(Tok.adjustExit).toDp() }
    val plusY = ui.px(ui.volPlusY)
    val ring = Offset(cx, ringY)
    val measurer = rememberTextMeasurer(cacheSize = MEASURES)
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
    val labelLine = measurer.measure(shown(VOLUME, Roles.volLabel.case), textStyle(Roles.volLabel, lit))
    val labelInk = remember(ui, resolver) { ink(shown(VOLUME, Roles.volLabel.case), Roles.volLabel, ui, resolver) }
    val labelCaps = labelBase - Tok.capHeight * labelPx / 2
    val labelBottom = labelBase + labelInk.bottom
    val lines = lineBases(inArtist, inTitle, labelCaps, labelBottom)
    val oldLines = if (l.oldArtist != null) lineBases(oldArtist, oldTitle, labelCaps, labelBottom) else null

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
    val owned = remember(legs, overlay, ui) { OwnedTouch(legs, overlay, ui.px(ui.w), ui.px(ui.h)) }

    Box(Modifier.fillMaxSize().testTag("overlay")) {
        Box(Modifier.fillMaxSize().bleed(LocalBleed.current).background(Black.copy(alpha = Tok.scrim_overlay_alpha * l.scrim)).testTag("overlay.scrim"))
        Box(Modifier.fillMaxSize().then(plate).testTag("overlay.plate.parts")) {
            Canvas(Modifier.fillMaxSize().testTag("overlay.press")) {
                fun glow(m: ImageBitmap?, x: Float, a: Float, y: Float = ringY) {
                    if (m == null || a <= 0f) return
                    withTransform({
                        translate((cx - x) * (1f - l.k), (ringY - y) * (1f - l.k))
                        scale(l.size, l.size, Offset(x, y))
                    }) { drawImage(m, Offset(x - m.width / 2f, y - m.height / 2f), alpha = a * l.group) }
                }
                val ringAlpha = pressLit(ringPress)
                glow(masks[0], cx - skip, pressLit(prevPress))
                glow(masks[2], cx + skip, pressLit(nextPress))
                glow(masks[3], cx, pressLit(plusPress), plusY)
                glow(masks[4], cx, pressLit(minusPress), minusY)
                glow(masks[1], cx, ringAlpha)
                if (ringAlpha > 0f) drawCircle(lit, (ringSide - 2 * stroke) / 2 * l.size, ring, alpha = Tok.vol_discAlpha * ringAlpha * l.group)
            }
            Glyph(GlyphName.PLUS, Tok.vol_plus, lit, Modifier.at(cx, plusY, 0.5f, 0.5f).part(l, Offset(cx, plusY), ring).testTag("overlay.plus.glyph"))
            RoleText(numText, numRole, Modifier.atBaseline(numLeft, numBase).testTag("overlay.number"), color = white(Tok.overlayLit * l.number))
            Glyph(GlyphName.SKIP_PREVIOUS, Tok.vol_skip, white(Tok.alpha_full), Modifier.at(cx - skip, ringY, 0.5f, 0.5f).part(l, Offset(cx - skip, ringY), ring).testTag("overlay.prev.glyph"))
            Ring(Tok.vol_ring, if (s.playing == true) GlyphName.PAUSE_WIDE else GlyphName.PLAY, Look.Touch, Modifier.at(cx, ringY, 0.5f, 0.5f).part(l, ring, ring), tag = "overlay.ring", stroke = Tok.vol_ringStroke, glyphSize = Tok.vol_pause)
            Glyph(GlyphName.SKIP_NEXT, Tok.vol_skip, white(Tok.alpha_full), Modifier.at(cx + skip, ringY, 0.5f, 0.5f).part(l, Offset(cx + skip, ringY), ring).testTag("overlay.next.glyph"))
            val labelAt = Offset(labelInk.centerX() / labelLine.size.width, (labelLine.firstBaseline + labelInk.centerY()) / labelLine.size.height)
            val labelMid = Offset(cx - labelLine.size.width / 2f + labelInk.centerX(), labelBase + labelInk.centerY())
            RoleText(VOLUME, Roles.volLabel, Modifier.atBaseline(cx, labelBase, ax = 0.5f).part(l, labelMid, ring, labelAt).testTag("overlay.volume"), color = white(Tok.overlayLit * l.group))
            Glyph(GlyphName.MINUS, Tok.vol_plus, lit, Modifier.at(cx, minusY, 0.5f, 0.5f).part(l, Offset(cx, minusY), ring).testTag("overlay.minus.glyph"))
        }
        Lines(inArtist, inTitle, lines, l.artist, l.title, oldArtist, oldTitle, oldLines, l.oldArtist, l.oldTitle)

        if (overlay.open && !legs.closing(now)) Box(
            Modifier.fillMaxSize().testTag("overlay.plate").pointerInput(legs) {
                awaitEachGesture {
                    val first = awaitFirstDown()
                    if (!owned.live(first.uptimeMillis)) return@awaitEachGesture
                    first.consume()
                    owned.down(first.uptimeMillis)
                    val slop = viewConfiguration.touchSlop
                    var moved = false
                    var closed = false
                    var last = first.uptimeMillis
                    try {
                        while (true) {
                            val c = awaitPointerEvent().changes.firstOrNull { it.id == first.id } ?: break
                            c.consume()
                            last = c.uptimeMillis
                            if ((c.position - first.position).getDistance() > slop) moved = true
                            if (!c.pressed) {
                                if (!moved) {
                                    closed = true
                                    close(last)
                                }
                                break
                            }
                        }
                    } finally {
                        if (!closed) owned.up(last)
                    }
                }
            },
        )
        Box(Modifier.at(exitCaps.x, exitCaps.y, 0.5f, 0.5f).size(exitHit).graphicsLayer { alpha = l.exit }.testTag("overlay.exit.hit").owns(owned, null, Offset.Zero).press({ close(legs.lastUp) }, press = exitPress))
        RoleText(EXIT, Roles.volExit, Modifier.atBaseline(exitLeft, exitBase).testTag("overlay.exit"), color = white(Tok.overlayLit * l.exit), glow = exitPress.word)
        val touch = remember { MutableInteractionSource() }
        fun target(x: Float, y: Float, tag: String, box: Dp = hit, p: PressState? = null, act: () -> Unit): Modifier {
            val side = with(d) { box.toPx() }
            val at = Modifier.at(x, y, 0.5f, 0.5f).size(box).testTag(tag).owns(owned, tilt, Offset(x - side / 2, y - side / 2))
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

private class OwnedTouch(val legs: VolumeLegs, val overlay: Overlay, val w: Float, val h: Float) {
    fun live(t: Long) = overlay.open && legs.visible(t) && !legs.closing(t)

    fun down(t: Long) {
        legs.down(t)
        overlay.left(Long.MAX_VALUE)
    }

    fun up(t: Long) {
        legs.up(t)
        overlay.left(legs.left(t))
    }
}

private fun Modifier.part(l: VolumeLook, anchor: Offset, ring: Offset, origin: Offset? = null) = graphicsLayer {
    if (origin == null) alpha = l.group else transformOrigin = TransformOrigin(origin.x, origin.y)
    scaleX = l.size
    scaleY = l.size
    translationX = (ring.x - anchor.x) * (1f - l.k)
    translationY = (ring.y - anchor.y) * (1f - l.k)
}

private fun Modifier.owns(owned: OwnedTouch, tilt: VolumeTilt?, at: Offset) = pointerInput(owned, tilt, at) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!owned.live(down.uptimeMillis)) return@awaitEachGesture
        owned.down(down.uptimeMillis)
        tilt?.down((at.x + down.position.x) / owned.w, (at.y + down.position.y) / owned.h)
        var last = down.uptimeMillis
        try {
            while (true) {
                val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                last = c.uptimeMillis
                if (!c.pressed) break
                tilt?.move((at.x + c.position.x) / owned.w, (at.y + c.position.y) / owned.h)
            }
        } finally {
            tilt?.up(last)
            owned.up(last)
        }
    }
}

internal class TiltPose(val rx: Float, val ry: Float)

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
private fun Lines(artist: String, title: String, at: LineBases, a: VolumeLine, t: VolumeLine, oldArtist: String, oldTitle: String, oldAt: LineBases?, oa: VolumeLine?, ot: VolumeLine?) {
    val ui = LocalUi.current
    val left = ui.px(if (oa != null) Tok.vol_textClip else Tok.vol_textX)
    val x = ui.px(Tok.vol_textX) - left
    val right = ui.px(ui.w - Tok.edge)
    Box(Modifier.at(left, 0f).size((right - left).coerceAtLeast(0f).dp(), ui.px(ui.h).dp()).clipToBounds()) {
        if (oa != null && ot != null && oldAt != null) {
            RoleText(oldTitle, Roles.volTitle, Modifier.atBaseline(x + ui.px(ot.dx), oldAt.title).testTag("overlay.title.old"), color = white(Tok.overlayLit * ot.a * Roles.volTitle.alpha))
            RoleText(oldArtist, Roles.volArtist, Modifier.atBaseline(x + ui.px(oa.dx), oldAt.artist).testTag("overlay.artist.old"), color = white(Tok.overlayLit * oa.a * Roles.volArtist.alpha))
        }
        RoleText(title, Roles.volTitle, Modifier.atBaseline(x + ui.px(t.dx), at.title).testTag("overlay.title"), color = white(Tok.overlayLit * t.a * Roles.volTitle.alpha))
        RoleText(artist, Roles.volArtist, Modifier.atBaseline(x + ui.px(a.dx), at.artist).testTag("overlay.artist"), color = white(Tok.overlayLit * a.a * Roles.volArtist.alpha))
    }
}
