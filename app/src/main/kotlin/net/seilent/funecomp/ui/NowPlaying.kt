package net.seilent.funecomp.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import kotlin.math.ceil
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.state.Heart
import net.seilent.funecomp.state.LyricsFetch

internal const val PIN_LEAD_MS = 200L
private const val MIC_INK_LEFT = 8f
private const val MIC_INK_RIGHT = 24f
private const val MIC_INK_TOP = 3f
private const val MIC_INK_BOTTOM = 30f
private val PLAIN = Roles.npAlbum.copy(case = Case.MIXED)
private const val SHUFFLE = 0
private const val PREV = 1
private const val TOGGLE = 2
private const val NEXT = 3
private const val REPEAT = 4
private const val HEART = 5
private val ITEM_TAGS = mapOf(SHUFFLE to "np.shuffle", PREV to "np.prev", TOGGLE to "np.toggle", NEXT to "np.nextTrack", REPEAT to "np.repeat")

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
        fade.animateTo(1f, tween(Tok.motion_backdropFade.toInt(), easing = LinearEasing))
        old = null
    }
    if (shown == null && old == null) return
    val b = LocalBleed.current
    val ui = LocalUi.current
    Box(Modifier.fillMaxSize().bleed(b).clipToBounds().testTag("backdrop")) {
        old?.let { o -> key(o) { BackdropLayer(o, oldFrom * (1f - fade.value)) } }
        shown?.let { n -> key(n) { BackdropLayer(n, fade.value) } }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    Tok.backdrop_stop0_pos to Black.copy(alpha = Tok.backdrop_stop0_alpha),
                    Tok.backdrop_stop1_pos to Black.copy(alpha = Tok.backdrop_stop1_alpha),
                    Tok.backdrop_stop2_pos to Black.copy(alpha = Tok.backdrop_stop2_alpha),
                    startY = b.top.toFloat(),
                    endY = b.top + ui.px(ui.h),
                ),
            ),
        )
    }
}

@Composable
private fun BackdropLayer(art: BackdropArt, alpha: Float) {
    val ui = LocalUi.current
    val b = LocalBleed.current
    val w = ui.px(ui.w) + b.left + b.right
    val h = ui.px(ui.h) + b.top + b.bottom
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
fun NowPlaying(device: DeviceState, control: Control, toast: ToastState, onBack: () -> Unit, onQueue: () -> Unit, art: BackdropArt? = null, lyrics: LyricsFetch.Show = LyricsFetch.Show.Off, onBackdrop: ((BackdropArt?) -> Unit)? = null) {
    val ui = LocalUi.current
    val s = device.playback.state
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val playing = s.playing == true
    val scope = rememberCoroutineScope()
    var lyricMode by remember { mutableStateOf(false) }
    var lyricTarget by remember { mutableStateOf(false) }
    var turning by remember { mutableStateOf(false) }
    val layer = remember { Animatable(1f) }
    val shade = remember { Animatable(0f) }
    val canLyrics = LyricsFetch.has(device)
    fun toggleLyrics() {
        if (turning) return
        turning = true
        lyricTarget = !lyricMode
        scope.launch {
            val spec = tween<Float>(Tok.motion_variantFade.toInt(), easing = Decel)
            launch { shade.animateTo(if (lyricTarget) 1f else 0f, spec) }
            layer.animateTo(0f, spec)
            lyricMode = lyricTarget
            layer.animateTo(1f, spec)
            turning = false
        }
    }
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            control.nowPlayingShown(true)
            try {
                awaitCancellation()
            } finally {
                control.nowPlayingShown(false)
            }
        }
    }
    val view = LocalView.current
    val hold = lyricTarget && playing && lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.STARTED)
    DisposableEffect(view, hold) {
        view.keepScreenOn = hold
        onDispose { view.keepScreenOn = false }
    }
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
        if (art != null && shade.value > 0f) Box(Modifier.fillMaxSize().bleed(LocalBleed.current).background(Color(Tok.color_bg).copy(alpha = Tok.scrim_sheet_alpha * shade.value)).testTag("np.shade"))
        val held = art?.takeIf { it.entry != null }
        val forward: () -> Unit = { if (held != null) onBackdrop?.invoke(held) }
        if (onBackdrop != null && (held != null || s.artists.isNotEmpty())) Box(Modifier.fillMaxSize().press({}, { onBackdrop(held) }).testTag("np.backdrop"))
        BackRing(onBack)
        Box(Modifier.fillMaxSize().alpha(idleAlpha)) { Status(device) }
        if (lyricMode) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = layer.value }) {
                LyricsColumn(device, lyrics, upcoming.firstOrNull(), lifecycle, lyricTarget, ::toggleLyrics, forward)
            }
        } else {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = layer.value }) {
                RoleText(s.artist ?: "", Roles.npArtist, Modifier.atBaseline(textX, ui.px(ui.npArtistBaseline), textW).testTag("np.artist"))
                RoleText(s.album ?: "", Roles.npAlbum, Modifier.atBaseline(textX, ui.px(ui.npAlbumBaseline), textW).testTag("np.album"))
                if (artSide > 0f) {
                    val track = s.track
                    val px = artSide.roundToInt()
                    LaunchedEffect(track, px, device.hello) { if (track != null) control.wantArt(Thumbs.NOW_PLAYING, track, px, s.album) }
                    val enter = if (canLyrics) Modifier.press({ toggleLyrics() }, forward) else Modifier
                    Box(Modifier.at(artX, artY).size(artSide.dp()).background(Color(Tok.color_tile)).then(enter).testTag("np.art")) {
                        track?.let { control.art(it, px) }?.let { Image(it, null, Modifier.fillMaxSize().testTag("np.art.image"), contentScale = ContentScale.Crop) }
                    }
                    if (Heart.shown(device.hello, s) && npHeartFits(ui.npArt)) {
                        key(HEART) {
                            val inset = ui.px(Tok.detailRingInset)
                            val ring = ui.px(Tok.npRing)
                            val hx = artX + artSide - inset - ring / 2
                            val hy = artY + inset + ring / 2
                            val on = Heart.lit(s)
                            val focused = ringFocus == HEART
                            var swaps by remember { mutableIntStateOf(0) }
                            var down by remember { mutableStateOf(false) }
                            Box(Modifier.at(hx, hy, 0.5f, 0.5f).alpha(if (focused) 1f else idleAlpha).testTag(if (on) "np.heart.on" else "np.heart.off")) {
                                Ring(Tok.npRing, GlyphName.HEART, focused, toggle = true, on = on, swaps = swaps, pressed = down)
                            }
                            Box(
                                Modifier.at(hx, hy, 0.5f, 0.5f).size((ring + ui.px(Tok.detailRingGap)).dp()).testTag("np.heart").press({
                                    ringFocus = HEART
                                    swaps++
                                    control.favorite(s) { line, title -> toast.show(line, title) }
                                }, {}) { down = it },
                            )
                        }
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
                val markSide = Roles.npNext.size * Tok.capHeight * Tok.glyphBox / (MIC_INK_BOTTOM - MIC_INK_TOP)
                val markX = ui.w - Tok.edge - MIC_INK_RIGHT * markSide / Tok.glyphBox
                val markY = ui.npNextBaseline - MIC_INK_BOTTOM * markSide / Tok.glyphBox
                val nextRight = ui.px(if (canLyrics) markX + MIC_INK_LEFT * markSide / Tok.glyphBox - Tok.statusGap else ui.w - Tok.edge)
                upcoming.forEachIndexed { i, t ->
                    val x = textX + ui.px(Tok.npNextIndent)
                    RoleText(t, Roles.npNext, Modifier.atBaseline(x, nextBaseline + ui.px(Tok.npNextPitch) * i, (if (i == 0) nextRight else ui.px(ui.w - Tok.edge)) - x).testTag("np.next.$i"))
                }
                if (canLyrics) {
                    Glyph(GlyphName.MIC, markSide, white(Roles.npNext.alpha), Modifier.at(ui.px(markX), ui.px(markY)).testTag("np.mark"))
                    val cx = markX + (MIC_INK_LEFT + MIC_INK_RIGHT) / 2 * markSide / Tok.glyphBox
                    val cy = markY + (MIC_INK_TOP + MIC_INK_BOTTOM) / 2 * markSide / Tok.glyphBox
                    Box(Modifier.at(ui.px(cx), ui.px(cy), 0.5f, 0.5f).size(ui.px(Tok.npRing).dp()).press({ toggleLyrics() }, forward).testTag("np.mark.hit"))
                }
            }
        }
        val repeat = s.repeat ?: Enums.Repeat.off
        val queued = s.track != null || (s.slots ?: 0L) > 0L
        val hitW = ui.px(ui.npRingCenter(1) - ui.npRingCenter(0)) / 2
        val hitH = ui.px(Tok.overlayRing)
        val items = listOfNotNull(
            SHUFFLE to ui.npRingCenter(0),
            (PREV to ui.npTransportX(0)).takeIf { queued },
            (TOGGLE to ui.npRingCenter(1)).takeIf { queued },
            (NEXT to ui.npTransportX(1)).takeIf { queued },
            REPEAT to ui.npRingCenter(2),
        )
        items.forEach { (kind, x) ->
            key(kind) {
                val cx = ui.px(x)
                val focused = ringFocus == kind
                val alpha = if (focused) 1f else idleAlpha
                val hit = Modifier.at(cx, ringY, 0.5f, 0.5f).size(hitW.dp(), hitH.dp()).testTag(ITEM_TAGS.getValue(kind))
                if (kind == PREV || kind == NEXT) {
                    Glyph(if (kind == PREV) GlyphName.PREVIOUS else GlyphName.NEXT, Tok.overlayGlyph, white(if (focused) Tok.alpha_full else Tok.alpha_mid), Modifier.at(cx, ringY, 0.5f, 0.5f).alpha(alpha))
                    Box(
                        hit.press({
                            ringFocus = kind
                            control.transport(if (kind == PREV) Enums.TransportCmd.prev else Enums.TransportCmd.next)
                        }),
                    )
                } else {
                    var swaps by remember { mutableIntStateOf(0) }
                    var down by remember { mutableStateOf(false) }
                    Box(Modifier.at(cx, ringY, 0.5f, 0.5f).alpha(alpha)) {
                        when (kind) {
                            SHUFFLE -> Ring(Tok.npRing, GlyphName.SHUFFLE, focused, toggle = true, on = s.shuffle == true, swaps = swaps, pressed = down)
                            REPEAT -> Ring(Tok.npRing, if (repeat == Enums.Repeat.one) GlyphName.REPEAT_ONE else GlyphName.REPEAT, focused, toggle = true, on = repeat != Enums.Repeat.off, swaps = swaps, pressed = down)
                            else -> Ring(Tok.overlayRing, if (playing) GlyphName.PAUSE else GlyphName.PLAY, focused, Modifier.testTag(if (playing) "np.toggle.pause" else "np.toggle.play"), pressed = down)
                        }
                    }
                    Box(
                        hit.press({
                            ringFocus = kind
                            when (kind) {
                                SHUFFLE -> {
                                    swaps++
                                    control.transport(Enums.TransportCmd.shuffle, on = s.shuffle != true)
                                }
                                REPEAT -> {
                                    swaps++
                                    val next = when (repeat) {
                                        Enums.Repeat.off -> Enums.Repeat.all
                                        Enums.Repeat.all -> Enums.Repeat.one
                                        else -> Enums.Repeat.off
                                    }
                                    control.transport(Enums.TransportCmd.repeat, repeat = next)
                                }
                                else -> control.transport(Enums.TransportCmd.toggle)
                            }
                        }) { down = it },
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricsColumn(device: DeviceState, lyrics: LyricsFetch.Show, next: String?, lifecycle: Lifecycle, active: Boolean, onTap: () -> Unit, onLong: () -> Unit) {
    val ui = LocalUi.current
    val s = device.playback.state
    val x = ui.px(Tok.edge)
    val w = ui.px(ui.w - Tok.edge) - x
    val room = ui.npLyricBottom - ui.npLyricTop
    val surface = Modifier.at(x, ui.px(ui.npLyricTop)).size(w.dp(), ui.px(room).coerceAtLeast(0f).dp())
    val copy = (lyrics as? LyricsFetch.Show.Lines)?.takeIf { it.track == s.track && it.copy.crc == s.lyricsCrc }?.copy
    val measurer = rememberTextMeasurer()
    val styles = listOf(Roles.npTitle, Roles.npNext, PLAIN).map { textStyle(it, white(1f)) }
    val wraps = remember(copy, s.title, next, w, ui.u) { HashMap<Pair<String, Int>, List<String>>() }
    fun wrap(t: String, style: Int): List<String> = if (t.isEmpty()) emptyList() else wraps.getOrPut(t to style) {
        val r = measurer.measure(t, styles[style], constraints = Constraints(maxWidth = w.roundToInt().coerceAtLeast(1)))
        (0 until r.lineCount).map { t.substring(r.getLineStart(it), r.getLineEnd(it, visibleEnd = true)) }
    }
    if (copy != null && copy.kind == Enums.LyricsKind.plain) {
        val pitch = Tok.npAlbumBaseline - Tok.npTextTop
        val rows = remember(copy, w, ui.u) { LyricRows.plain(copy.lines.map { it.text }) { wrap(it, 2) } }
        val viewH = ui.px(LyricRows.plainRoom(room, pitch))
        val p = ui.px(pitch)
        var off by remember(copy, w, ui.u) { mutableFloatStateOf(0f) }
        val limit = (rows.size * p - viewH).coerceAtLeast(0f)
        val scroll = rememberScrollableState { d ->
            val old = off
            off = (off - d).coerceIn(0f, limit)
            old - off
        }
        Box(surface.scrollable(scroll, Orientation.Vertical, flingBehavior = rememberFling()).press({ onTap() }, onLong).testTag("np.lyrics")) {
            Box(Modifier.fillMaxWidth().height(viewH.dp()).clipToBounds()) {
                val half = ui.px(pitch + Tok.capHeight * PLAIN.size) / 2
                var i = (off / p).toInt()
                while (i < rows.size && i * p - off < viewH) {
                    if (rows[i].isNotEmpty()) RoleText(rows[i], PLAIN, Modifier.atBaseline(0f, i * p - off + half, w).testTag("np.lyric.plain.$i"))
                    i++
                }
            }
        }
        return
    }
    val lines = copy?.lines
    var at by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val playing = s.playing == true
    val held = s.positionHeld == true
    var leadFor by remember(lines) { mutableStateOf<Int?>(null) }
    val live by rememberUpdatedState(device)
    LaunchedEffect(lines, playing, device.playback.receivedAt, held) {
        at = SystemClock.elapsedRealtime()
        if (lines == null || !playing || held) {
            leadFor = null
            return@LaunchedEffect
        }
        val first = live.positionMs(at)
        val i0 = LyricsFetch.index(lines, first)
        val lead = leadFor
        val near = (LyricsFetch.dueMs(lines, i0, first) ?: Long.MAX_VALUE) <= PIN_LEAD_MS
        if (lead != i0 && !(lead == i0 + 1 && near)) leadFor = null
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                while (true) {
                    at = SystemClock.elapsedRealtime()
                    val pos = live.positionMs(at)
                    val i = LyricsFetch.index(lines, pos)
                    val due = LyricsFetch.dueMs(lines, i, pos) ?: break
                    val st = live.playback.state
                    if (st.positionMs == null || pos >= (st.durationMs?.takeIf { it > 0 } ?: Long.MAX_VALUE)) {
                        leadFor = null
                        delay(due)
                    } else if (due > PIN_LEAD_MS) {
                        delay(due - PIN_LEAD_MS)
                        leadFor = i + 1
                        delay(PIN_LEAD_MS)
                    } else {
                        leadFor = i + 1
                        delay(due)
                    }
                }
            } finally {
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) leadFor = null
            }
        }
    }
    val view = LocalView.current
    val index = lines?.let { LyricsFetch.index(it, device.positionMs(maxOf(at, device.playback.receivedAt))) } ?: -1
    Box(surface.press({ onTap() }, onLong).testTag("np.lyrics")) {
        if (copy == null && lyrics != LyricsFetch.Show.None) return@Box
        key(s.track, copy) {
            var shown by remember { mutableIntStateOf(index) }
            var slide by remember { mutableStateOf<LyricSlide.Slide?>(null) }
            var frame by remember { mutableLongStateOf(0L) }
            LaunchedEffect(index) {
                withFrameMillis { ms ->
                    slide = lines?.let { LyricSlide.start(shown, index, it.map { l -> l.ms ?: 0 }, device.positionMs(SystemClock.elapsedRealtime()), ms) }
                    frame = ms
                    shown = index
                    if (leadFor?.let { index >= it } == true) leadFor = null
                }
                val run = slide ?: return@LaunchedEffect
                while (!LyricSlide.done(run, frame)) withFrameMillis { frame = it }
                slide = null
            }
            val pinned = active && (leadFor != null || slide != null)
            DisposableEffect(view, pinned) {
                val handle = if (pinned) RefreshPin.of(view).hold() else null
                onDispose { handle?.release() }
            }
            val config = LocalConfiguration.current
            if (pinned) LaunchedEffect(view, config) { RefreshPin.of(view).repick() }
            val texts = lines?.map { it.text }
            val title = s.title ?: ""
            val wrapRow = { t: String, big: Boolean -> wrap(t, if (big) 0 else 1) }
            fun blocks(i: Int) = LyricRows.blocks(texts, i, title, next, wrapRow, room, Tok.npLyricPitch, Tok.npNextPitch)
            val edge = { v: Float -> ceil(ui.px(v)) }
            val run = slide
            val draws = if (run == null || texts == null) {
                LyricRows.column(null, blocks(shown), shown, 1f, room, edge)
            } else {
                val (a, b) = LyricRows.pair(blocks(run.from), blocks(shown), { wrapRow(texts[it], false).size }, Tok.npNextPitch)
                LyricRows.column(a, b, shown, LyricSlide.p(run, frame), room, edge)
            }
            var n = 0
            for (d in draws) {
                Box(Modifier.offset { IntOffset(0, d.y0.roundToInt()) }.size(w.dp(), (d.y1 - d.y0).dp()).clipToBounds().testTag("np.lyric.line.${d.line}")) {
                    for ((k, alpha) in d.forms) {
                        val role = if (k.big) Roles.npTitle else Roles.npNext
                        val kind = LyricRows.kind(k, shown).name.lowercase()
                        LyricRows.lines(k, texts, title, next, wrapRow).forEachIndexed { i, t ->
                            val baseline = ui.px(d.top + i * k.pitch + (k.pitch + Tok.capHeight * role.size) / 2) - d.y0
                            if (alpha > 0f) RoleText(t, role, Modifier.atBaseline(0f, baseline, w).testTag("np.lyric.$kind.$n"), alpha = alpha)
                            n++
                        }
                    }
                }
            }
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
