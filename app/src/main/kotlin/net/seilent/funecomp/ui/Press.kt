package net.seilent.funecomp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.seilent.funecomp.proto.gen.Tok
import kotlin.math.roundToLong

class PressState internal constructor(private val scope: CoroutineScope?, private val slots: PressSlots?) {
    var scaled = false

    var scale by mutableFloatStateOf(1f)
        private set

    var lit by mutableFloatStateOf(0f)
        private set

    var held by mutableStateOf(false)
        private set

    val word = GlowWord()

    internal var mask: GlowMask? = null

    internal var probe: (() -> Unit)? = null

    private var from = 1f
    private var to = 1f
    private var start = 0L
    private var span = 0L
    private var lookFrom = 0f
    private var lookTo = 0f
    private var lookStart = 0L
    private var lookSpan = 0L
    private var rise = false
    private var releaseAt = -1L
    private var cutAt = -1L
    private var job: Job? = null

    private fun ms(token: Float): Long {
        val k = scope?.coroutineContext?.get(MotionDurationScale)?.scaleFactor ?: 1f
        return (token * k).roundToLong()
    }

    private fun tween(now: Long): Float {
        if (span <= 0L || now >= start + span) return to
        if (now <= start) return from
        return from + (to - from) * (now - start) / span
    }

    private fun value(now: Long): Float {
        if (releaseAt < 0 || now < releaseAt) return tween(now)
        val e = now - releaseAt
        val back = ms(Tok.motion_press)
        if (e >= back) return 1f
        val v = tween(releaseAt)
        return v + (1f - v) * e / back
    }

    private fun glow(now: Long): Float {
        if (lookSpan <= 0L || now >= lookStart + lookSpan) return lookTo
        if (now <= lookStart) return lookFrom
        val t = (now - lookStart).toFloat() / lookSpan
        return lookFrom + (lookTo - lookFrom) * if (rise) CubicOut.transform(t) else t
    }

    fun look(now: Long): Float = if (cutAt in 0L..now) 0f else glow(now)

    fun scaleAt(now: Long): Float = if (scaled) value(now) else 1f

    private fun run(now: Long, a: Float, b: Float) {
        from = a
        to = b
        start = now
        span = ms(Tok.motion_press)
    }

    private fun set(v: Float) {
        from = v
        to = v
        span = 0L
    }

    private fun lookRun(now: Long, a: Float, b: Float, length: Long, cubic: Boolean) {
        lookFrom = a
        lookTo = b
        lookStart = now
        lookSpan = length
        rise = cubic
    }

    private fun lookSet(v: Float) {
        lookFrom = v
        lookTo = v
        lookSpan = 0L
    }

    private fun moving(now: Long): Boolean {
        val glowing = lookSpan > 0L && now < lookStart + lookSpan
        if (!scaled) return glowing
        return glowing || (span > 0L && now < start + span) || (releaseAt >= 0 && now >= releaseAt && now < releaseAt + ms(Tok.motion_press))
    }

    fun down(now: Long) {
        if (slots != null) slots.down(this, now) else press(now)
    }

    internal fun press(now: Long) {
        val v = value(now)
        val l = look(now)
        releaseAt = -1L
        cutAt = -1L
        run(now, v, Tok.motion_pressScale)
        lookRun(now, l, 1f, ms(Tok.motion_pressGlow), true)
        held = true
        kick()
    }

    fun tap(now: Long) {
        if (to != 1f && releaseAt < 0) {
            releaseAt = now + Tok.motion_touchReturnDelay.toLong()
            cutAt = now
            lit = 0f
        }
        held = false
        kick()
    }

    fun cancel(now: Long) {
        if (!held) return
        held = false
        val v = value(now)
        val l = look(now)
        releaseAt = -1L
        cutAt = -1L
        if (v == 1f) set(1f) else run(now, v, 1f)
        if (l == 0f) lookSet(0f) else lookRun(now, l, 0f, ms(Tok.motion_press), false)
        kick()
    }

    fun cut() {
        lookSet(0f)
        cutAt = -1L
        lit = 0f
        kick()
    }

    fun reset() {
        set(1f)
        lookSet(0f)
        releaseAt = -1L
        cutAt = -1L
        job?.cancel()
        job = null
        scale = 1f
        lit = 0f
        held = false
        releaseGlow(this)
    }

    fun drop() {
        if (held) reset()
    }

    internal fun rest(now: Long) = !moving(now) && value(now) == 1f

    private fun framed(now: Long) = to != 1f && (releaseAt < 0 || now < releaseAt)

    private fun kick() {
        val s = scope ?: return
        job?.cancel()
        job = s.launch(start = CoroutineStart.UNDISPATCHED) { drive() }
    }

    private suspend fun frame(): Long {
        probe?.invoke()
        return withFrameMillis { it }
    }

    private fun show(now: Long) {
        scale = scaleAt(now)
        lit = look(now)
    }

    private suspend fun drive() {
        var now = frame()
        while (true) {
            show(now)
            now = when {
                moving(now) -> frame()
                releaseAt > now -> {
                    delay(releaseAt - now)
                    frame()
                }
                else -> {
                    if (!framed(now) && look(now) == 0f) releaseGlow(this)
                    return
                }
            }
        }
    }
}

class PressSlots {
    private var on: PressState? = null
    private var back: PressState? = null

    internal fun down(p: PressState, now: Long) {
        if (p !== on) {
            if (p === back) back = null
            letGo(now)
            on = p
        }
        p.press(now)
    }

    private fun letGo(now: Long) {
        val o = on ?: return
        on = null
        if (o.rest(now)) return
        o.cancel(now)
        back?.let { if (it !== o) it.reset() }
        back = o
    }

    internal fun look(now: Long) = maxOf(on?.look(now) ?: 0f, back?.look(now) ?: 0f)

    internal fun lit() = maxOf(on?.lit ?: 0f, back?.lit ?: 0f)

    fun cut() {
        on?.cut()
        back?.cut()
    }

    fun drop() {
        on?.drop()
        back?.drop()
    }

    fun reset() {
        on?.reset()
        back?.reset()
        on = null
        back = null
    }
}

class PressedStep internal constructor(private val scope: CoroutineScope?) {
    var on by mutableStateOf(false)
        private set

    private var at = 0L
    private var end = 0L
    private var job: Job? = null

    fun down(now: Long) {
        job?.cancel()
        at = now
        end = now
        on = true
    }

    fun fade(ms: Long) {
        end = at + ms
    }

    fun up(now: Long) {
        if (!on) return
        job?.cancel()
        if (end <= now) on = false else job = scope?.launch { delay(end - now); on = false }
    }

    fun hold(ms: Long) {
        job?.cancel()
        on = true
        job = scope?.launch { delay(ms); on = false }
    }

    fun keep() {
        job?.cancel()
        on = true
    }

    fun reset() {
        job?.cancel()
        on = false
    }
}

fun Modifier.pressScale(p: PressState?, origin: TransformOrigin = TransformOrigin.Center): Modifier = if (p == null || !p.scaled) this else graphicsLayer {
    scaleX = p.scale
    scaleY = p.scale
    transformOrigin = origin
}

val LocalPressSlots = staticCompositionLocalOf<PressSlots?> { null }

val LocalPressProbe = staticCompositionLocalOf<(() -> Unit)?> { null }

val LocalScrolling = staticCompositionLocalOf<() -> Boolean> { { false } }

@Composable
fun rememberStep(): PressedStep {
    val scope = rememberCoroutineScope()
    return remember { PressedStep(scope) }
}

@Composable
fun rememberPress(): PressState {
    val scope = rememberCoroutineScope()
    val slots = LocalPressSlots.current
    val probe = LocalPressProbe.current
    return remember(slots) { PressState(scope, slots) }.also { it.probe = probe }
}
