package net.seilent.funecomp.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.MutatorMutex
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
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
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
import androidx.compose.ui.graphics.TransformOrigin
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
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.state.Heart
import net.seilent.funecomp.state.LyricsFetch
import net.seilent.funecomp.state.NpKey
import net.seilent.funecomp.state.PhoneKey

internal const val PIN_LEAD_MS = 200L
val LocalUptime = staticCompositionLocalOf<() -> Long> { SystemClock::uptimeMillis }

private val PLAIN = Roles.npAlbum.copy(case = Case.MIXED)
private const val SHUFFLE = 0
private const val PREV = 1
private const val TOGGLE = 2
private const val NEXT = 3
private const val REPEAT = 4
private const val HEART = 5
private val ITEM_TAGS = mapOf(SHUFFLE to "np.shuffle", PREV to "np.prev", TOGGLE to "np.toggle", NEXT to "np.nextTrack", REPEAT to "np.repeat")
private const val MODE_ON = "lyrics mode on"
private const val MODE_OFF = "lyrics mode off"
private const val ARTIST_PHOTO = "artistPhoto"
private const val WINDOW_MS = (Tok.motion_trackHide + Tok.motion_trackShow).toLong()

private data class Seen(val track: NpKey?, val at: Long, val f: Boolean?, val calm: Long, val hand: Boolean = false)

private fun phone(d: DeviceState) = d.playback.state.source == Enums.Source.phone

private fun phoneShow(d: DeviceState, show: LyricsFetch.Show) = (show as? LyricsFetch.Show.Phone)?.takeIf { it.key == PhoneKey.of(d.playback.state) }

private fun npKey(d: DeviceState, show: LyricsFetch.Show): NpKey? = when {
    d.playback.receivedAt == 0L -> null
    phone(d) -> phoneShow(d, show)?.key?.let { NpKey.Phone(it) }
    else -> d.playback.state.track?.let { NpKey.Lib(it) }
}

private fun has(d: DeviceState, show: LyricsFetch.Show) = if (phone(d)) phoneShow(d, show)?.copy != null else LyricsFetch.has(d)

private fun follow(d: DeviceState, show: LyricsFetch.Show): Boolean? = when {
    d.playback.receivedAt == 0L -> null
    phone(d) -> phoneShow(d, show)?.follow ?: if (PhoneKey.of(d.playback.state) == null) false else null
    else -> LyricsFetch.follow(d)
}

private fun keyable(d: DeviceState, show: LyricsFetch.Show) = d.playback.receivedAt != 0L && if (phone(d)) phoneShow(d, show) != null else d.has(LyricsFetch.GROUP) && d.playback.state.track != null

private fun copyFor(show: LyricsFetch.Show, key: NpKey?, s: State): LyricsFetch.Copy? = when (key) {
    is NpKey.Lib -> (show as? LyricsFetch.Show.Lines)?.takeIf { it.track == key.track && (it.held || it.copy.crc == s.lyricsCrc) }?.copy
    is NpKey.Phone -> (show as? LyricsFetch.Show.Phone)?.takeIf { it.key == key.key }?.copy
    null -> null
}

private fun none(show: LyricsFetch.Show) = show == LyricsFetch.Show.None || (show is LyricsFetch.Show.Phone && show.copy == null && show.follow == false)

private fun record(d: DeviceState, show: LyricsFetch.Show, old: Seen, calm: Boolean): Seen {
    val p = d.playback
    val track = npKey(d, show)
    val f = follow(d, show)
    return when {
        track == null && phone(d) && old.track != null -> Seen(null, p.receivedAt, false, if (calm) 0L else p.receivedAt + WINDOW_MS)
        track == null && f == null -> old
        track == old.track -> old.copy(f = f, hand = old.hand && (f == null || has(d, show)))
        else -> Seen(track, p.receivedAt, f, if (calm) 0L else p.receivedAt + WINDOW_MS)
    }
}

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
        val r = Crop.rect(img.width, img.height, w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1), art.face?.let { it.second * img.height })
        BitmapPainter(img, IntOffset(r.x, r.y), IntSize(r.w, r.h))
    }
    Image(
        painter,
        null,
        Modifier.requiredSize(w.dp(), h.dp()).graphicsLayer { this.alpha = alpha }.testTag("backdrop.photo"),
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
fun NowPlaying(device: DeviceState, control: Control, toast: ToastState, overlay: Overlay, onBack: () -> Unit, onQueue: () -> Unit, active: Boolean, art: BackdropArt? = null, lyrics: LyricsFetch.Show = LyricsFetch.Show.Off, live: () -> DeviceState = { device }, shell: Shell? = null, onBackdrop: ((BackdropArt?) -> Unit)? = null) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val onPhone = phone(device)
    val cover = if (onPhone) device.playback.state.phoneArtCrc?.let { crc -> control.phoneCover(crc)?.let { crc to it } } else null
    val last = remember { Last(device, lyrics, cover) }
    val swap = remember(onPhone) {
        val from = last.device
        val run = phone(from) != onPhone && from.playback.receivedAt != 0L && device.playback.receivedAt != 0L && active && !overlay.visible && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (run) Swap(if (onPhone) VariantMotion.Dir.In else VariantMotion.Dir.Out, from, last.lyrics, last.cover) else null
    }
    var e by remember(swap) { mutableFloatStateOf(0f) }
    var running by remember(swap) { mutableStateOf(swap != null) }
    val frame = { swap?.takeIf { running }?.let { VariantMotion.frame(it.dir, e) } }
    val held by remember(swap) { derivedStateOf { swap?.takeIf { frame()?.from == true } } }
    SideEffect {
        last.device = held?.from ?: device
        last.lyrics = held?.lyrics ?: lyrics
        last.cover = held?.cover ?: cover
    }
    LaunchedEffect(swap) {
        val w = swap ?: return@LaunchedEffect
        val k = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
        val total = VariantMotion.total(w.dir)
        var start: Long? = null
        while (k > 0f && e < total) {
            withFrameMillis { t -> e = ((t - (start ?: t.also { start = it })) / k).coerceIn(0f, total) }
        }
        running = false
    }
    NowPlayingPage(held?.from ?: device, control, toast, overlay, onBack, onQueue, active, art, held?.lyrics ?: lyrics, live, shell, onBackdrop, frame, swap?.dir, held?.cover ?: cover)
}

private class Last(var device: DeviceState, var lyrics: LyricsFetch.Show, var cover: Pair<Long, ImageBitmap>?)

private class Swap(val dir: VariantMotion.Dir, val from: DeviceState, val lyrics: LyricsFetch.Show, val cover: Pair<Long, ImageBitmap>?)

@Composable
private fun NowPlayingPage(device: DeviceState, control: Control, toast: ToastState, overlay: Overlay, onBack: () -> Unit, onQueue: () -> Unit, active: Boolean, art: BackdropArt?, lyrics: LyricsFetch.Show, live: () -> DeviceState, shell: Shell?, onBackdrop: ((BackdropArt?) -> Unit)?, swap: () -> VariantMotion.Frame?, swapDir: VariantMotion.Dir?, phoneCover: Pair<Long, ImageBitmap>?) {
    val ui = LocalUi.current
    val s = device.playback.state
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val playing = s.playing == true
    val scope = rememberCoroutineScope()
    val liveNow by rememberUpdatedState(live)
    val activeNow by rememberUpdatedState(active)
    val sheet = LocalSheet.current
    val lyricsNow by rememberUpdatedState(lyrics)
    val swapNow by rememberUpdatedState(swap)
    var pressing by remember { mutableStateOf(false) }
    var seen by remember { mutableStateOf(record(liveNow(), lyrics, Seen(null, 0L, null, 0L), true).copy(at = SystemClock.elapsedRealtime())) }
    fun mode() = if (phone(liveNow())) control.phoneLyricsMode() else control.lyricsMode()
    fun mode(on: Boolean) = if (phone(liveNow())) control.phoneLyricsMode(on) else control.lyricsMode(on)
    fun target(): Boolean? {
        val r = seen
        return when {
            !mode() -> false
            r.hand -> true
            r.track == null -> r.f
            else -> liveNow().let { d -> if (d.playback.receivedAt == 0L) r.f else LyricsFetch.resolve(r.f, r.at, SystemClock.elapsedRealtime(), phone(d) || LyricsFetch.lyrflags(d)) }
        }
    }
    var lyricTarget by remember { mutableStateOf(target() ?: false) }
    var fade by remember { mutableStateOf<LyricFade.Dir?>(null) }
    var reversing by remember { mutableStateOf(false) }
    var elapsed by remember { mutableFloatStateOf(0f) }
    var start by remember { mutableStateOf<Long?>(null) }
    val motion = remember { MutatorMutex() }
    val uptime = LocalUptime.current
    val canLyrics = has(device, lyrics)
    val npKey = npKey(device, lyrics)
    val onPhone = phone(device)
    fun fadeTo(on: Boolean) {
        lyricTarget = on
        fade = if (on) LyricFade.Dir.Enter else LyricFade.Dir.Close
        reversing = false
        start = null
        scope.launch {
            motion.mutate {
                start = null
                elapsed = 0f
                val k = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
                while (k > 0f && elapsed < LyricFade.T) {
                    withFrameMillis { t -> elapsed = LyricFade.forward(start ?: t.also { start = it }, t, k) }
                }
                fade = null
                elapsed = 0f
            }
        }
    }
    fun reverse() {
        lyricTarget = false
        reversing = true
        val from = start
        val at = uptime()
        scope.launch {
            motion.mutate {
                val k = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
                if (k > 0f) {
                    val b = LyricFade.back(from, at, k)
                    do {
                        withFrameMillis { t -> elapsed = LyricFade.reverse(b, t, k) }
                    } while (elapsed > 0f)
                }
                elapsed = 0f
                fade = null
                reversing = false
            }
        }
    }
    fun settle() {
        fade = null
        reversing = false
        target()?.let { lyricTarget = it }
        scope.launch { motion.mutate { elapsed = 0f } }
    }
    fun held() = SystemClock.elapsedRealtime() < seen.calm || overlay.visible || sheet?.shown == true || pressing || !activeNow
    fun step(input: Boolean = false) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            settle()
            return
        }
        val t = target()
        if (fade != null) {
            if (input && fade == LyricFade.Dir.Enter && !reversing && t == false) reverse()
            return
        }
        if (!input && held()) return
        if (t != null && t != lyricTarget) fadeTo(t)
    }
    fun leave() {
        mode(false)
        seen = seen.copy(hand = false)
        step(true)
    }
    fun lyricsKey() {
        if (swapNow() != null) return
        val d = liveNow()
        val settled = fade == null
        if (!settled && (fade != LyricFade.Dir.Enter || reversing)) return
        val l = lyricsNow
        if (settled && !lyricTarget && !keyable(d, l)) return
        if (overlay.visible) overlay.hide()
        seen = record(d, l, seen, false)
        when {
            !settled || lyricTarget -> leave()
            has(d, l) -> {
                mode(true)
                if (follow(d, l) != true) seen = seen.copy(hand = true)
                step(true)
            }
            else -> {
                val on = !mode()
                mode(on)
                toast.show(if (on) MODE_ON else MODE_OFF, "")
            }
        }
    }
    fun back() {
        when {
            swapNow() != null -> onBack()
            fade == LyricFade.Dir.Enter && !reversing -> leave()
            fade != null -> {}
            lyricTarget -> leave()
            else -> onBack()
        }
    }
    fun frame() = fade?.let { LyricFade.frame(it, elapsed) } ?: LyricFade.Frame(lyricTarget, 1f, if (lyricTarget) 1f else 0f)
    val drawn by remember { derivedStateOf { frame().lyrics } }
    val shaded by remember { derivedStateOf { frame().shade > 0f } }
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
    DisposableEffect(lifecycle) {
        val o = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> settle()
                Lifecycle.Event.ON_START -> {
                    seen = record(liveNow(), lyricsNow, seen, true)
                    settle()
                }
                else -> {}
            }
        }
        lifecycle.addObserver(o)
        onDispose { lifecycle.removeObserver(o) }
    }
    LaunchedEffect(npKey, device.playback.receivedAt == 0L, follow(device, lyrics), canLyrics) {
        seen = record(device, lyrics, seen, false)
    }
    var source by remember { mutableStateOf(onPhone) }
    LaunchedEffect(onPhone) {
        if (source == onPhone) return@LaunchedEffect
        source = onPhone
        fade = null
        reversing = false
        lyricTarget = false
        val r = record(device, lyrics, seen, false)
        seen = swapDir?.takeIf { swapNow() != null }?.let { r.copy(calm = SystemClock.elapsedRealtime() + VariantMotion.rest(it).toLong()) } ?: r
        motion.mutate { elapsed = 0f }
    }
    val blocked = sheet?.shown == true || pressing || !active
    LaunchedEffect(seen, blocked, overlay.until, fade == null, device.playback.receivedAt == 0L) {
        if (fade != null || blocked) return@LaunchedEffect
        while (true) {
            val r = seen
            val now = SystemClock.elapsedRealtime()
            var w = maxOf(r.calm - now, overlay.until - SystemClock.uptimeMillis())
            val d = liveNow()
            if (r.track != null && r.f == null && d.playback.receivedAt != 0L && !phone(d) && !LyricsFetch.lyrflags(d)) w = maxOf(w, r.at + Proto.stateIntervalMs - now)
            if (w <= 0) break
            delay(w)
        }
        step()
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
    BackHandler(active && sheet?.shown != true) { back() }
    val edit = shell?.offset
    val offsets = device.has(OffsetEdit.GROUP)
    LaunchedEffect(edit, s.track, npKey, s.lyricsPlain, lyrics, lyricTarget, active, offsets, sheet?.shown) {
        val e = edit ?: return@LaunchedEffect
        val p = e.phone
        if (p != null) {
            if (npKey != NpKey.Phone(p) || copyFor(lyrics, npKey, s)?.kind != Enums.LyricsKind.synced || !lyricTarget || !active || sheet?.shown != true) e.end(true)
        } else if (!offsets) e.end(false) else if (s.track != e.track || s.lyricsPlain == true || !lyricTarget || !active || sheet?.shown != true) e.end(true)
    }
    DisposableEffect(shell) {
        onDispose { shell?.offset?.let { it.end(it.phone != null || liveNow().has(OffsetEdit.GROUP)) } }
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
    val seekBottom = ui.px(ui.npSeekBottom)
    val titleBaseline = ui.px(ui.npTitleBaseline)
    val titleTop = titleBaseline - ui.px(Tok.capHeight * Roles.npTitle.size)
    val nextBaseline = ui.px(ui.npNextBaseline)
    val titleBottom = nextBaseline + ui.px(Tok.npNextPitch) * (NP_NEXT_COUNT - 1)
    val ringY = ui.px(ui.npRingY)
    val duration = s.durationMs ?: 0L
    val position = seekFraction?.let { (it * duration).toLong() } ?: device.positionMs(now)
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val slot = s.slot
    val upcoming = if (slot == null || onPhone) emptyList() else (1..NP_NEXT_COUNT).mapNotNull { device.queue.items[slot + it]?.title }
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
                pressing = true
                try {
                    do {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                    } while (e.changes.any { it.pressed })
                } finally {
                    pressing = false
                }
            }
        }.testTag("np"),
    ) {
        val photo = art?.takeIf { device.settings.values[ARTIST_PHOTO] != "off" }
        Backdrop(photo)
        if (photo != null && shaded) Box(Modifier.fillMaxSize().bleed(LocalBleed.current).graphicsLayer { alpha = frame().shade * (swap()?.shade ?: 1f) }.background(Color(Tok.color_bg).copy(alpha = Tok.scrim_sheet_alpha)).testTag("np.shade"))
        val held = photo?.takeIf { it.entry != null }
        val lyricsTap: () -> Unit = {
            val k = npKey
            val c = copyFor(lyrics, k, s)
            if (shell != null && c?.kind == Enums.LyricsKind.synced && fade == null && !reversing && lyricTarget && sheet?.shown != true && swapNow() == null) {
                when (k) {
                    is NpKey.Lib -> if (offsets && s.source == Enums.Source.local) lyricsSheet(shell, control, k.track, s.lyricsOffsetMs ?: 0)
                    is NpKey.Phone -> lyricsSheet(shell, control, 0, 0, k.key)
                    null -> {}
                }
            }
        }
        val backdrop = onBackdrop?.takeIf { held != null || s.artists.isNotEmpty() }
        val longPress: () -> Unit = { backdrop?.invoke(held) }
        if (backdrop != null) Box(Modifier.fillMaxSize().press({}, longPress).testTag("np.backdrop"))
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val f = swap()
                alpha = f?.alpha ?: 1f
                scaleX = f?.scale ?: 1f
                scaleY = f?.scale ?: 1f
                transformOrigin = TransformOrigin(Tok.motion_nowPlayingOriginX, Tok.motion_nowPlayingOriginY)
            }.testTag("np.layer"),
        ) {
            BackRing { back() }
            Box(Modifier.fillMaxSize().alpha(idleAlpha)) { Status(device) }
            if (onPhone) s.phoneName?.let { name ->
                RoleText("from $name", Roles.sectionLabel, Modifier.atBaseline(textX, ui.px(ui.npArtistBaseline - (Tok.npTextTop - Tok.npFromBaseline)), textW).testTag("np.from"))
            }
            if (drawn) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = frame().alpha }) {
                    LyricsColumn(device, lyrics, npKey, upcoming.firstOrNull(), lifecycle, lyricTarget, lyricsTap, longPress)
                }
            } else {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = frame().alpha }) {
                    RoleText(s.artist ?: "", Roles.npArtist, Modifier.atBaseline(textX, ui.px(ui.npArtistBaseline), textW).testTag("np.artist"))
                    RoleText(s.album ?: "", Roles.npAlbum, Modifier.atBaseline(textX, ui.px(ui.npAlbumBaseline), textW).testTag("np.album"))
                    if (artSide > 0f) {
                        val track = s.track
                        val px = artSide.roundToInt()
                        LaunchedEffect(track, px, device.hello, onPhone) { if (track != null && !onPhone) control.wantArt(Thumbs.NOW_PLAYING, track, px, s.album) }
                        val enter = if (keyable(device, lyrics)) Modifier.press({ lyricsKey() }, longPress) else Modifier
                        if (onPhone) {
                            val cover = s.phoneArtCrc?.let { crc -> phoneCover?.takeIf { it.first == crc }?.second }
                            Box(Modifier.at(artX, artY).size(artSide.dp()).then(enter).testTag("np.art")) {
                                cover?.let { Image(it, null, Modifier.fillMaxSize().testTag("np.art.image"), contentScale = ContentScale.Crop) }
                            }
                            if (cover == null) Glyph(GlyphName.BLUETOOTH, Tok.npBtGlyph, white(Tok.alpha_full), Modifier.at(artX + artSide / 2, artY + artSide / 2, 0.5f, 0.5f).testTag("np.art.bluetooth"))
                        } else {
                            Box(Modifier.at(artX, artY).size(artSide.dp()).background(Color(Tok.color_tile)).then(enter).testTag("np.art")) {
                                track?.let { control.art(it, px) }?.let { Image(it, null, Modifier.fillMaxSize().testTag("np.art.image"), contentScale = ContentScale.Crop) }
                            }
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
                    if (s.track != null && (!onPhone || duration > 0)) {
                        val thick = ui.px(Tok.npProgressThick).dp()
                        Box(Modifier.at(artX, progressY).size(artSide.dp(), thick).background(white(Tok.alpha_low)))
                        Box(Modifier.at(artX, progressY).size((artSide * fraction).dp(), thick).background(Accent).testTag("np.progress"))
                        RoleText(formatTime(position), Roles.time, Modifier.atBaseline(artX, timeBaseline).testTag("np.elapsed"))
                        val remaining = if (duration > 0) "-" + formatTime(duration - position) else ""
                        RoleText(remaining, Roles.time, Modifier.atBaseline(artX + artSide, timeBaseline, ax = 1f).testTag("np.remaining"))
                        val margin = ui.px(Tok.edge - Tok.npSeekBandX0)
                        val bandW = artSide + 2 * margin
                        if (!onPhone) Box(
                            Modifier.at(artX - margin, progressY).size(bandW.dp(), (seekBottom - progressY).dp()).systemGestureExclusion().testTag("np.seek")
                                .pointerInput(duration, artSide) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown()
                                        if (swapNow() != null) {
                                            down.consume()
                                            return@awaitEachGesture
                                        }
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
                    val n = s.phoneTrackNo
                    val m = s.phoneTracks
                    if (onPhone && n != null && m != null) {
                        val x = textX + ui.px(Tok.npNextIndent)
                        RoleText("$n of $m", Roles.npCount, Modifier.atBaseline(x, nextBaseline, ui.px(ui.w - Tok.edge) - x).testTag("np.count"))
                    }
                    val corner = ui.landscape
                    if (canLyrics && artSide > 0f && npMarkFits(ui.npArt, corner)) {
                        val ring = ui.px(Tok.npRing)
                        val cx = if (corner) artX + artSide - ui.px(Tok.detailRingInset) - ring / 2 else artX + artSide + ui.px(Tok.tileLGutter) + ring / 2
                        val cy = artY + artSide - (if (corner) ui.px(Tok.detailRingInset) else 0f) - ring / 2
                        var down by remember { mutableStateOf(false) }
                        Box(Modifier.at(cx, cy, 0.5f, 0.5f).testTag("np.mark")) {
                            Ring(Tok.npRing, GlyphName.MIC, false, pressed = down)
                        }
                        Box(Modifier.at(cx, cy, 0.5f, 0.5f).size(ring.dp()).press({ lyricsKey() }, longPress) { down = it }.testTag("np.mark.hit"))
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
                                        if (liveNow().transportRefusal(Enums.TransportCmd.shuffle) == null) swaps++
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
}

@Composable
private fun LyricsColumn(device: DeviceState, lyrics: LyricsFetch.Show, np: NpKey?, next: String?, lifecycle: Lifecycle, active: Boolean, onTap: () -> Unit, onLong: () -> Unit) {
    val ui = LocalUi.current
    val s = device.playback.state
    val x = ui.px(Tok.edge)
    val w = ui.px(ui.w - Tok.edge) - x
    val room = ui.npLyricBottom - ui.npLyricTop
    val surface = Modifier.at(x, ui.px(ui.npLyricTop)).size(w.dp(), ui.px(room).coerceAtLeast(0f).dp())
    val copy = copyFor(lyrics, np, s)
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
        if (copy == null && !none(lyrics)) return@Box
        key(np, copy) {
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
    val paused = !playing && (s.source == Enums.Source.phone || (s.slots ?: 0L) > 0L)
    if (playing || paused) {
        Glyph(
            if (playing) GlyphName.PLAY else GlyphName.PAUSE,
            Tok.statusHeight,
            white(Tok.alpha_full),
            Modifier.at(right, top, ax = 1f).testTag(if (playing) "status.play" else "status.pause"),
        )
    }
}
