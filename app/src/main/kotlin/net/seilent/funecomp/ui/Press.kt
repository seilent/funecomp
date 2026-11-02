package net.seilent.funecomp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.seilent.funecomp.proto.gen.Tok
import kotlin.math.roundToLong

class PressState internal constructor(private val scope: CoroutineScope?, private val slots: PressSlots?) {
    var scale by mutableFloatStateOf(1f)
        private set

    var lit by mutableStateOf(false)
        private set

    internal var frozen: () -> Boolean = { false }

    internal var probe: (() -> Unit)? = null

    private var from = 1f
    private var to = 1f
    private var start = 0L
    private var span = 0L
    private var releaseAt = -1L
    private var job: Job? = null

    private val held get() = to != 1f && releaseAt < 0

    private fun legMs(): Long {
        val k = scope?.coroutineContext?.get(MotionDurationScale)?.scaleFactor ?: 1f
        return (Tok.motion_press * k).roundToLong()
    }

    private fun tween(now: Long): Float {
        if (span <= 0L || now >= start + span) return to
        if (now <= start) return from
        return from + (to - from) * (now - start) / span
    }

    private fun value(now: Long): Float {
        if (releaseAt < 0 || now < releaseAt) return tween(now)
        val e = now - releaseAt
        val back = legMs()
        if (e >= back) return 1f
        val v = tween(releaseAt)
        return v + (1f - v) * e / back
    }

    private fun run(now: Long, a: Float, b: Float) {
        from = a
        to = b
        start = now
        span = legMs()
    }

    private fun show(v: Float, now: Long) {
        scale = v
        lit = v != 1f || moving(now)
    }

    private fun set(v: Float) {
        from = v
        to = v
        span = 0L
    }

    private fun moving(now: Long) = (span > 0L && now < start + span) || (releaseAt >= 0 && now >= releaseAt && now < releaseAt + legMs())

    fun down(now: Long) {
        if (slots != null) slots.down(this, now) else press(now)
    }

    internal fun press(now: Long) {
        val v = value(now)
        releaseAt = -1L
        run(now, v, Tok.motion_pressScale)
        lit = true
        kick()
    }

    fun tap(now: Long) {
        if (to != 1f && releaseAt < 0) releaseAt = now + Tok.motion_touchReturnDelay.toLong()
        kick()
    }

    fun cancel(now: Long) {
        if (!held) return
        val v = value(now)
        releaseAt = -1L
        if (v == 1f) set(1f) else run(now, v, 1f)
        kick()
    }

    fun reset() {
        set(1f)
        releaseAt = -1L
        job?.cancel()
        job = null
        scale = 1f
        lit = false
    }

    fun drop() {
        if (held) reset()
    }

    internal fun rest(now: Long) = !moving(now) && value(now) == 1f

    private fun kick() {
        val s = scope ?: return
        job?.cancel()
        job = s.launch(start = CoroutineStart.UNDISPATCHED) { drive() }
    }

    private suspend fun frame(): Long {
        probe?.invoke()
        return withFrameMillis { it }
    }

    private suspend fun drive() {
        var now = frame()
        while (true) {
            val stop = frozen()
            if (stop && to != 1f && (releaseAt < 0 || now < releaseAt)) set(Tok.motion_pressScale)
            show(value(now), now)
            now = when {
                stop -> {
                    snapshotFlow { frozen() }.first { !it }
                    frame()
                }
                moving(now) -> frame()
                releaseAt > now -> {
                    withTimeoutOrNull(releaseAt - now) { snapshotFlow { frozen() }.first { it } }
                    frame()
                }
                else -> return
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

fun Modifier.pressScale(p: PressState?): Modifier = if (p == null || !p.lit) this else graphicsLayer {
    scaleX = p.scale
    scaleY = p.scale
}

val LocalPressSlots = staticCompositionLocalOf<PressSlots?> { null }

val LocalPressFrozen = staticCompositionLocalOf<() -> Boolean> { { false } }

val LocalPressProbe = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
fun rememberPress(): PressState {
    val scope = rememberCoroutineScope()
    val slots = LocalPressSlots.current
    val frozen = LocalPressFrozen.current
    val probe = LocalPressProbe.current
    return remember(slots) { PressState(scope, slots) }.also {
        it.frozen = frozen
        it.probe = probe
    }
}
