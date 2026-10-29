package net.seilent.funecomp.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.seilent.funecomp.proto.gen.Tok
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class ThumbSpan(val top: Int, val bottom: Int, val view: Long, val content: Long, val scrolled: Long, val at: Long = scrolled)

class ThumbBar(val y: Int, val len: Int)

object ThumbMath {
    fun of(s: ThumbSpan, min: Int, one: Int): ThumbBar? {
        val t = s.bottom - s.top
        if (s.view <= 0 || s.content <= s.view || t - min <= one) return null
        val len = min + (((t - min).toLong() * s.view + s.content / 2) / s.content).toInt()
        val room = s.content - s.view
        val y = s.top + (((t - len).toLong() * s.scrolled.coerceIn(0, room) + room / 2) / room).toInt()
        return ThumbBar(y, len)
    }
}

class ThumbFade {
    private var from = 0f
    private var to = 0f
    private var start = 0L
    private var dur = 0L
    private var seen = Long.MIN_VALUE / 4
    private var drawn = 0L
    private var on = false
    private var held = false
    var scale = 1f

    private fun fade() = (FADE * scale).toLong()

    private fun tween(now: Long): Float {
        val e = now - start
        if (dur <= 0 || e >= dur) return to
        if (e <= 0) return from
        return from + (to - from) * Pop.transform(e.toFloat() / dur)
    }

    private fun tweenEnd() = if (dur <= 0) 0L else start + dur

    private fun holdEnd() = seen + HOLD

    fun show(now: Long) {
        from = 1f
        to = 1f
        dur = 0
        seen = now
        held = false
    }

    fun track(on: Boolean, moving: Boolean, now: Long) {
        if (on && !this.on) {
            show(now)
        } else if (moving) {
            if (now >= holdEnd()) {
                from = at(now)
                to = 1f
                start = now
                dur = fade()
            }
            seen = now
        }
        this.on = on
        held = on && moving
        drawn = now
    }

    fun release(now: Long) {
        if (held) {
            held = false
            seen = now
        }
    }

    fun at(now: Long): Float {
        val end = holdEnd()
        if (held || now < end) return tween(now)
        val e = now - end
        val f = fade()
        if (e >= f) return 0f
        return tween(end) * (1f - Pop.transform(e.toFloat() / f))
    }

    fun fading(now: Long): Boolean {
        if (!on) return false
        val end = holdEnd()
        return if (held || now < end) dur > 0 && drawn < tweenEnd() else drawn < end + fade()
    }

    fun wakeAt(now: Long): Long = if (on && !held && now < holdEnd()) holdEnd() else -1

    companion object {
        val HOLD = Tok.scrollbar_hold.toLong()
        val FADE = Tok.scrollbar_fade.toLong()
    }
}

class ListSpan {
    private var seen = IntArray(0)

    fun of(state: LazyListState, size: ((Int) -> Float)?, tail: Int = 0, under: Int = 0, past: Int = 0): ThumbSpan? {
        val info = state.layoutInfo
        val rows = info.totalItemsCount - tail
        val view = info.viewportSize.height - under - past
        if (rows <= 0 || view <= 0) return null
        if (seen.size != rows) seen = IntArray(rows)
        info.visibleItemsInfo.forEach { if (it.index < rows) seen[it.index] = it.size }
        val first = state.firstVisibleItemIndex.coerceIn(0, rows)
        var content = 0.0
        var before = 0.0
        if (size != null) {
            for (i in 0 until rows) {
                val h = size(i).roundToInt()
                content += h
                if (i < first) before += h
            }
        } else {
            var sum = 0L
            var n = 0
            seen.forEach { if (it > 0) { sum += it; n++ } }
            if (n == 0) return null
            val avg = sum.toDouble() / n
            for (i in 0 until rows) {
                val h = if (seen[i] > 0) seen[i].toDouble() else avg
                content += h
                if (i < first) before += h
            }
        }
        val offset = state.firstVisibleItemScrollOffset
        return ThumbSpan(0, view, view.toLong(), content.roundToLong(), before.roundToLong() + offset, (first.toLong() shl 32) or offset.toLong())
    }
}

@Composable
fun ScrollThumb(show: Any?, moving: () -> Boolean, span: () -> ThumbSpan?, modifier: Modifier = Modifier.fillMaxSize()) {
    val ui = LocalUi.current
    val min = ui.px(Tok.scrollbar_min).roundToInt()
    val inset = ui.px(Tok.canvasW - Tok.scrollbar_x).roundToInt()
    Spacer(modifier.then(ThumbElement(show, moving, span, min, ui.u.roundToInt(), inset, ui.px(Tok.scrollbar_width).roundToInt())))
}

private data class ThumbElement(
    val show: Any?,
    val moving: () -> Boolean,
    val span: () -> ThumbSpan?,
    val min: Int,
    val one: Int,
    val inset: Int,
    val width: Int,
) : ModifierNodeElement<ThumbNode>() {
    override fun create() = ThumbNode(this)

    override fun update(node: ThumbNode) = node.set(this)
}

private class ThumbNode(private var e: ThumbElement) : Modifier.Node(), DrawModifierNode {
    private var alpha = 0f

    fun set(next: ThumbElement) {
        val again = next.show != e.show || next.min != e.min || next.one != e.one
        e = next
        if (again && isAttached) start()
        invalidateDraw()
    }

    override fun onAttach() = start()

    private fun bar() = e.span()?.let { ThumbMath.of(it, e.min, e.one) }

    private fun start() {
        alpha = 0f
        val fade = ThumbFade()
        coroutineScope.coroutineContext.cancelChildren()
        coroutineScope.launch { run(fade) }
    }

    private suspend fun run(fade: ThumbFade) {
        var at: Long? = null
        var was = false
        while (true) {
            val now = withFrameMillis { it }
            fade.scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
            val s = e.span()
            val on = bar() != null
            val scrolling = e.moving()
            val moved = s != null && at != null && s.at != at
            at = s?.at
            if (was && !scrolling) fade.release(now)
            was = scrolling
            fade.track(on, scrolling || moved, now)
            val a = fade.at(now)
            if (a != alpha) {
                alpha = a
                invalidateDraw()
            }
            if (fade.fading(now) || moved) continue
            val wake = fade.wakeAt(now)
            val key = Triple(s?.at, on, scrolling)
            val change = snapshotFlow { Triple(e.span()?.at, bar() != null, e.moving()) }
            if (wake >= 0) withTimeoutOrNull(wake - now) { change.first { it != key } } else change.first { it != key }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val a = alpha
        if (a <= 0f) return
        val b = bar() ?: return
        val x = size.width.roundToInt() - e.inset
        drawRect(white(Tok.scrollbar_alpha * a), Offset(x.toFloat(), b.y.toFloat()), Size(e.width.toFloat(), b.len.toFloat()))
    }
}
