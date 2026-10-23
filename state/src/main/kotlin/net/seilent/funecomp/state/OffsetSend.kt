package net.seilent.funecomp.state

import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Proto

class OffsetSend(val track: Long, shown: Int) {
    data class Send(val ms: Int?)

    data class Mark(val sent: Int, val idle: Boolean)

    data class Out(val send: Send? = null, val toast: Int? = null, val held: Boolean = false)

    sealed interface Reply {
        data class Ok(val ms: Int) : Reply
        data class Failed(val code: Int) : Reply
        data object Lost : Reply
    }

    private class Flight(val ms: Int?, val show: Boolean, val retry: Boolean)

    var shown: Int = shown
        @Synchronized get
        private set
    var stored: Int? = null
        @Synchronized get
        private set
    var editing = false
        @Synchronized get
        private set
    var closed = false
        @Synchronized get
        private set
    private var start: Int? = null
    private var startNext = false
    private var before = 0
    private var seeded: Flight? = null
    private var restoring = false
    private var flight: Flight? = null
    private var pending: Int? = null
    private var read: Flight? = null
    private var sent = 0

    @Synchronized
    fun open(): Out {
        if (stored != null || flight != null || read != null) return Out()
        read = Flight(null, show = false, retry = true)
        return Out(dispatch())
    }

    @Synchronized
    fun step(d: Int): Out = move(d * STEP)

    @Synchronized
    fun coarse(d: Int): Out = move(d * COARSE)

    private fun move(by: Int): Out {
        if (closed || !editing) return Out()
        val to = clamp(shown + by)
        if (to == shown) return Out()
        shown = to
        pending = to
        return Out(dispatch())
    }

    @Synchronized
    fun typed(ms: Int): Out {
        if (closed) return Out()
        keep()
        val to = clamp(ms)
        shown = to
        pending = to
        return Out(dispatch())
    }

    @Synchronized
    fun begin(): Out {
        if (closed || editing) return Out()
        editing = true
        restoring = false
        val s = stored
        if (s != null && flight == null && read == null && shown == s) {
            start = s
            startNext = false
            return Out()
        }
        val queued = pending
        if (queued != null) {
            start = queued
            startNext = false
            seeded = flight
            return Out()
        }
        start = null
        startNext = true
        before = shown
        if (flight == null && read == null) read = Flight(null, show = false, retry = true)
        return Out(dispatch())
    }

    @Synchronized
    fun keep() {
        editing = false
        start = null
        startNext = false
        restoring = false
    }

    @Synchronized
    fun restore(): Out {
        if (closed || !editing) return Out()
        editing = false
        pending = null
        val s = start
        start = null
        if (startNext) {
            restoring = true
            shown = before
            return Out()
        }
        if (s == null) return Out()
        shown = s
        pending = s
        return Out(dispatch())
    }

    @Synchronized
    fun close(sessionUp: Boolean): Out {
        if (closed) return Out()
        if (!sessionUp) {
            closed = true
            editing = false
            pending = null
            read = null
            startNext = false
            restoring = false
            return Out()
        }
        val out = restore()
        closed = true
        return out
    }

    @Synchronized
    fun replied(r: Reply): Out {
        val f = flight ?: return Out()
        flight = null
        if (closed) {
            if (r is Reply.Ok) stored = r.ms else pending = null
            read = null
            return Out(dispatch())
        }
        if (f === seeded) {
            seeded = null
            if (editing && r !is Reply.Ok) {
                start = null
                startNext = true
                before = f.ms ?: shown
            }
        }
        var toast: Int? = null
        var held = false
        when (r) {
            is Reply.Ok -> {
                if (f.ms != null && r.ms != stored) held = true
                stored = r.ms
                if (startNext) {
                    start = r.ms
                    startNext = false
                    if (restoring) {
                        restoring = false
                        pending = null
                    }
                    if (pending == null) shown = r.ms
                }
                if (f.show && pending == null) shown = r.ms
            }
            is Reply.Failed -> {
                pending = null
                stored?.let { shown = it }
                toast = r.code
                settleStart()
            }
            Reply.Lost -> {
                pending = null
                stored = null
                if (f.ms != null || f.retry) {
                    toast = LOST
                    if (read == null) read = Flight(null, show = true, retry = false)
                } else {
                    settleStart()
                }
            }
        }
        return Out(dispatch(), toast, held)
    }

    private fun settleStart() {
        if (!startNext) return
        startNext = false
        val s = stored
        if (s == null) {
            editing = false
            start = null
            restoring = false
        } else {
            start = s
            if (restoring) {
                restoring = false
                shown = s
            }
        }
    }

    @Synchronized
    fun mark() = Mark(sent, flight == null)

    @Synchronized
    fun observed(ms: Int, mark: Mark): Boolean {
        if (closed || !mark.idle || mark.sent != sent) return false
        if (ms == shown) return false
        shown = ms
        editing = false
        start = null
        startNext = false
        restoring = false
        return true
    }

    @Synchronized
    fun replaced(track: Long): Out {
        if (closed || track != this.track) return Out()
        editing = false
        start = null
        startNext = false
        restoring = false
        pending = null
        if (read == null) read = Flight(null, show = true, retry = true)
        return Out(dispatch())
    }

    private fun dispatch(): Send? {
        if (flight != null) return null
        read?.let {
            read = null
            flight = it
            sent++
            return Send(null)
        }
        val p = pending ?: return null
        pending = null
        if (p == stored) return null
        flight = Flight(p, show = false, retry = true)
        sent++
        return Send(p)
    }

    companion object {
        const val STEP = 100
        const val COARSE = 1000
        const val LOST = -1
        const val TITLE = "offset"
        val RANGE = "-${Proto.lyrOffsetMaxMs} to +${Proto.lyrOffsetMaxMs} ms"

        fun clamp(ms: Int) = ms.coerceIn(-Proto.lyrOffsetMaxMs, Proto.lyrOffsetMaxMs)

        fun text(ms: Int) = if (ms > 0) "+$ms ms" else "$ms ms"

        fun closes(code: Int) = code == Err.staleRev || code == Err.noLibrary

        fun toast(code: Int): String = when (code) {
            LOST -> "dap did not answer"
            Err.badValue -> RANGE
            Err.refused -> "refused"
            Err.staleRev -> "song not in library"
            Err.noLibrary, Err.noCard -> Heart.CARD
            Err.unknown -> "not supported"
            else -> "error $code"
        }

        fun parse(text: String): Int? {
            if (text.isEmpty() || text.length > 6) return null
            val digits = if (text[0] == '-') text.substring(1) else text
            if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
            val n = text.toInt()
            return n.takeIf { it in -Proto.lyrOffsetMaxMs..Proto.lyrOffsetMaxMs }
        }
    }
}
