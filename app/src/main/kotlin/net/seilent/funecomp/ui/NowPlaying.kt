package net.seilent.funecomp.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlin.math.roundToInt
import net.seilent.funecomp.art.Thumbs
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.artist.Crop
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.runtime.key
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.state.DeviceState

private const val BACKDROP_FADE_MS = 2000

fun formatTime(ms: Long): String {
    val total = maxOf(0L, ms / 1000)
    val s = total % 60
    return "${total / 60}:${if (s < 10) "0" else ""}$s"
}

class BackdropArt(val image: ImageBitmap, val face: Pair<Double, Double>? = null, val entry: ArtCache.Entry? = null, val wrongArtist: Boolean = false)

@Composable
fun Backdrop(art: BackdropArt?) {
    var shown by remember { mutableStateOf(art) }
    var old by remember { mutableStateOf<BackdropArt?>(null) }
    var oldFrom by remember { mutableFloatStateOf(1f) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(art) {
        if (art === shown) return@LaunchedEffect
        val f = fade.value
        if (old != null && f < 0.5f) {
            oldFrom *= 1f - f
        } else {
            oldFrom = if (old == null) 1f else f
            old = shown
        }
        shown = art
        fade.snapTo(0f)
        fade.animateTo(1f, tween(BACKDROP_FADE_MS, easing = LinearEasing))
        old = null
    }
    if (shown == null && old == null) return
    Box(Modifier.fillMaxSize().clipToBounds().testTag("backdrop")) {
        old?.let { o -> key(o) { BackdropLayer(o, oldFrom * (1f - fade.value)) } }
        shown?.let { n -> key(n) { BackdropLayer(n, fade.value) } }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    Tok.backdrop_stop0_pos to Black.copy(alpha = Tok.backdrop_stop0_alpha),
                    Tok.backdrop_stop1_pos to Black.copy(alpha = Tok.backdrop_stop1_alpha),
                    Tok.backdrop_stop2_pos to Black.copy(alpha = Tok.backdrop_stop2_alpha),
                ),
            ),
        )
    }
}

@Composable
private fun BackdropLayer(art: BackdropArt, alpha: Float) {
    val ui = LocalUi.current
    val w = ui.px(ui.w)
    val h = ui.px(ui.h)
    val img = art.image
    val painter = remember(art, w.roundToInt(), h.roundToInt()) {
        val r = Crop.rect(img.width, img.height, w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1), art.face?.let { it.first * img.width }, art.face?.let { it.second * img.height })
        BitmapPainter(img, IntOffset(r.x, r.y), IntSize(r.w, r.h))
    }
    Image(
        painter,
        null,
        Modifier.requiredSize(w.dp(), h.dp()).graphicsLayer { this.alpha = alpha },
        contentScale = ContentScale.FillBounds,
    )
}

@Composable
fun BackRing(onBack: () -> Unit) {
    val ui = LocalUi.current
    Box(
        Modifier.size(ui.px(Tok.backRingX + Tok.backRing).dp(), ui.px(Tok.backRingY + Tok.backRing).dp()).clipToBounds()
            .press({ onBack() }).testTag("back"),
    ) {
        Box(Modifier.at(ui.px(Tok.backRingX), ui.px(Tok.backRingY), unbounded = true)) { Ring(Tok.backRing, GlyphName.BACK, true) }
    }
}

@Composable
fun NowPlaying(device: DeviceState, control: Control, onBack: () -> Unit, onQueue: () -> Unit, art: BackdropArt? = null, onBackdrop: ((BackdropArt) -> Unit)? = null) {
    val ui = LocalUi.current
    val s = device.playback.state
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val playing = s.playing == true
    LaunchedEffect(playing, device.playback.receivedAt) {
        now = SystemClock.elapsedRealtime()
        if (!playing) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = SystemClock.elapsedRealtime()
                delay(Tok.motion_progress.toLong())
            }
        }
    }
    var touches by remember { mutableIntStateOf(0) }
    var idle by remember { mutableStateOf(false) }
    LaunchedEffect(touches) {
        idle = false
        delay(Tok.motion_idle.toLong())
        idle = true
    }
    val idleAlpha by animateFloatAsState(if (idle) Tok.motion_idleAlpha else 1f, tween(Tok.motion_idleFade.toInt(), easing = Decel), label = "idle")
    var ringFocus by remember { mutableIntStateOf(0) }
    var seekFraction by remember { mutableStateOf<Float?>(null) }

    val textX = ui.px(ui.npTextX)
    val textW = ui.px(ui.w - Tok.edge) - textX
    val artX = ui.px(Tok.edge)
    val artY = ui.px(ui.npArtTop)
    val artSide = ui.px(ui.npArt)
    val progressY = ui.px(ui.npProgressY)
    val timeBaseline = progressY + ui.px(Tok.npTimeDrop)
    val titleBaseline = ui.px(ui.npTitleBaseline)
    val titleTop = titleBaseline - ui.px(Tok.capHeight * Roles.npTitle.size)
    val nextBaseline = ui.px(ui.npNextBaseline)
    val titleBottom = nextBaseline + ui.px(Tok.npNextPitch) * (NP_NEXT_COUNT - 1)
    val ringY = ui.px(ui.npRingY)
    val duration = s.durationMs ?: 0L
    val position = seekFraction?.let { (it * duration).toLong() } ?: device.positionMs(now)
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val slot = s.slot
    val upcoming = if (slot == null) emptyList() else (1..NP_NEXT_COUNT).mapNotNull { device.queue.items[slot + it]?.title }
    LaunchedEffect(slot, device.queue.rev, s.queueRev) {
        if (slot == null || !device.has("queue")) return@LaunchedEffect
        val total = s.slots ?: return@LaunchedEffect
        if (slot + 1 < total && device.queue.items[slot + 1] == null) control.queuePage(slot + 1)
    }

    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
                touches++
            }
        }.testTag("np"),
    ) {
        Backdrop(art)
        val held = art?.takeIf { it.entry != null }
        if (held != null && onBackdrop != null) Box(Modifier.fillMaxSize().press({}, { onBackdrop(held) }).testTag("np.backdrop"))
        BackRing(onBack)
        Box(Modifier.fillMaxSize().alpha(idleAlpha)) { Status(device) }
        RoleText(s.artist ?: "", Roles.npArtist, Modifier.atBaseline(textX, ui.px(ui.npArtistBaseline), textW).testTag("np.artist"))
        RoleText(s.album ?: "", Roles.npAlbum, Modifier.atBaseline(textX, ui.px(ui.npAlbumBaseline), textW).testTag("np.album"))
        if (artSide > 0f) {
            val track = s.track
            val px = artSide.roundToInt()
            LaunchedEffect(track, px, device.hello) { if (track != null) control.wantArt(Thumbs.NOW_PLAYING, track, px, s.album) }
            Box(Modifier.at(artX, artY).size(artSide.dp()).background(Color(Tok.color_tile)).testTag("np.art")) {
                track?.let { control.art(it, px) }?.let { Image(it, null, Modifier.fillMaxSize().testTag("np.art.image"), contentScale = ContentScale.Crop) }
            }
        }
        if (s.track != null) {
            val thick = ui.px(Tok.npProgressThick).dp()
            Box(Modifier.at(artX, progressY).size(artSide.dp(), thick).background(white(Tok.alpha_low)))
            Box(Modifier.at(artX, progressY).size((artSide * fraction).dp(), thick).background(Accent).testTag("np.progress"))
            RoleText(formatTime(position), Roles.time, Modifier.atBaseline(artX, timeBaseline).testTag("np.elapsed"))
            val remaining = if (duration > 0) "-" + formatTime(duration - position) else ""
            RoleText(remaining, Roles.time, Modifier.atBaseline(artX + artSide, timeBaseline, ax = 1f).testTag("np.remaining"))
            val margin = ui.px(Tok.edge - Tok.npSeekBandX0)
            val bandW = artSide + 2 * margin
            Box(
                Modifier.at(artX - margin, progressY).size(bandW.dp(), (titleTop - progressY).coerceAtLeast(0f).dp()).testTag("np.seek")
                    .pointerInput(duration, artSide) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            fun f(x: Float) = ((x - margin) / artSide).coerceIn(0f, 1f)
                            seekFraction = f(down.position.x)
                            var last = down.position.x
                            while (true) {
                                val e = awaitPointerEvent()
                                val c = e.changes.firstOrNull() ?: break
                                if (!c.pressed) break
                                last = c.position.x
                                seekFraction = f(last)
                                c.consume()
                            }
                            val target = (f(last) * duration).toLong()
                            if (duration > 0) control.transport(Enums.TransportCmd.seek, seekMs = target)
                            seekFraction = null
                        }
                    },
            )
        }
        Box(Modifier.at(textX, titleTop).size(textW.dp(), (titleBottom - titleTop).coerceAtLeast(0f).dp()).press({ onQueue() }).testTag("np.titleBlock"))
        RoleText(s.title ?: "", Roles.npTitle, Modifier.atBaseline(textX, titleBaseline, textW).testTag("np.title"))
        upcoming.forEachIndexed { i, t ->
            val x = textX + ui.px(Tok.npNextIndent)
            RoleText(t, Roles.npNext, Modifier.atBaseline(x, nextBaseline + ui.px(Tok.npNextPitch) * i, ui.px(ui.w - Tok.edge) - x).testTag("np.next.$i"))
        }
        val repeat = s.repeat ?: Enums.Repeat.off
        val glyphs = listOf(GlyphName.SHUFFLE, if (repeat == Enums.Repeat.one) GlyphName.REPEAT_ONE else GlyphName.REPEAT)
        val ons = listOf(s.shuffle == true, repeat != Enums.Repeat.off)
        val hitW = ui.px(ui.npRingCenter(1) - ui.npRingCenter(0))
        val hitH = minOf(hitW, 2 * (ringY - titleBottom)).coerceAtLeast(0f)
        glyphs.forEachIndexed { i, g ->
            val cx = ui.px(ui.npRingCenter(i))
            val focused = ringFocus == i
            var swaps by remember { mutableIntStateOf(0) }
            var down by remember { mutableStateOf(false) }
            Box(Modifier.at(cx, ringY, 0.5f, 0.5f).alpha(if (focused) 1f else idleAlpha)) {
                Ring(Tok.npRing, g, focused, toggle = true, on = ons[i], swaps = swaps, pressed = down)
            }
            Box(
                Modifier.at(cx, ringY, 0.5f, 0.5f).size(hitW.dp(), hitH.dp()).testTag(if (i == 0) "np.shuffle" else "np.repeat").press({
                    ringFocus = i
                    swaps++
                    if (i == 0) {
                        control.transport(Enums.TransportCmd.shuffle, on = s.shuffle != true)
                    } else {
                        val next = when (repeat) {
                            Enums.Repeat.off -> Enums.Repeat.all
                            Enums.Repeat.all -> Enums.Repeat.one
                            else -> Enums.Repeat.off
                        }
                        control.transport(Enums.TransportCmd.repeat, repeat = next)
                    }
                }) { down = it },
            )
        }
    }
}

@Composable
fun Status(device: DeviceState) {
    val ui = LocalUi.current
    val s = device.playback.state
    val top = ui.px(Tok.statusTop)
    var right = ui.px(ui.w - Tok.edge)
    val step = ui.px(Tok.statusHeight + Tok.statusGap)
    val battery = s.battery
    if (battery != null) {
        val low = battery < Tok.batteryLowPercent
        Glyph(
            GlyphName.BATTERY,
            Tok.statusHeight,
            if (low) Accent else white(Tok.alpha_full),
            Modifier.at(right, top, ax = 1f),
            level = battery / 100f,
        )
        right -= step
    }
    val playing = s.playing == true
    val paused = !playing && (s.slots ?: 0L) > 0L
    if (playing || paused) {
        Glyph(
            if (playing) GlyphName.PLAY else GlyphName.PAUSE,
            Tok.statusHeight,
            white(Tok.alpha_full),
            Modifier.at(right, top, ax = 1f).testTag(if (playing) "status.play" else "status.pause"),
        )
    }
}
