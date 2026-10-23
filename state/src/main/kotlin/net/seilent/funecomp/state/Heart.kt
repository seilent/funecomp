package net.seilent.funecomp.state

import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Proto

class Heart(private val windowMs: Long = WINDOW_MS) {
    data class Toast(val line: String, val title: String)

    class Tap internal constructor(val track: Long)

    private class Pending(val tap: Tap, val title: String, val before: Boolean?) {
        val track: Long get() = tap.track
        var repliedAt: Long? = null
        var result: Boolean? = null
    }

    private var pending: Pending? = null

    @Synchronized
    fun tap(s: State): Tap? {
        if (pending != null) return null
        val t = Tap(s.track ?: return null)
        pending = Pending(t, s.title ?: "", s.favorite)
        return t
    }

    @Synchronized
    fun replied(t: Tap, code: Int?, text: String?, now: Long, minor: Int = 0): Toast? {
        val p = pending?.takeIf { it.tap === t } ?: return null
        if (code != null) {
            pending = null
            return when {
                code == Err.noCard -> Toast(CARD, "")
                code != Err.busy -> null
                minor < CARD_MINOR -> Toast(text?.takeIf { it in COPY } ?: CARD, "")
                text == UNREADABLE -> Toast(UNREADABLE, "")
                else -> null
            }
        }
        p.result?.let {
            pending = null
            return toast(it, p.title)
        }
        p.repliedAt = now
        return null
    }

    @Synchronized
    fun state(s: State): Toast? {
        val p = pending ?: return null
        val v = s.favorite
        if (p.repliedAt == null) {
            if (s.track == p.track && v != null && v != p.before) p.result = v
            return null
        }
        if (s.track != p.track) {
            pending = null
            return fallback(p)
        }
        if (v == null) return null
        pending = null
        return toast(v, p.title)
    }

    @Synchronized
    fun tick(t: Tap, now: Long): Toast? {
        val p = pending?.takeIf { it.tap === t } ?: return null
        val at = p.repliedAt ?: return null
        if (now - at < windowMs) return null
        pending = null
        return fallback(p)
    }

    @Synchronized
    fun down() {
        pending = null
    }

    private fun fallback(p: Pending) = p.before?.let { toast(!it, p.title) }

    private fun toast(on: Boolean, title: String) = Toast(if (on) ADDED else REMOVED, title)

    companion object {
        val WINDOW_MS = Proto.stateIntervalMs.toLong()
        const val ADDED = "added to favorites"
        const val REMOVED = "removed from favorites"
        const val CARD = CardView.REFUSED
        const val UNREADABLE = "favorites not readable"
        private val COPY = setOf(CARD, UNREADABLE)
        const val CARD_MINOR = 15

        fun cardToast(code: Int?, minor: Int) = code == Err.noCard || code == Err.busy && minor < CARD_MINOR

        fun shown(hello: HelloReply?, s: State) = hello?.snapRev != null && s.track != null && s.source != Enums.Source.phone

        fun lit(s: State) = s.favorite == true
    }
}
