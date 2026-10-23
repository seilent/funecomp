package net.seilent.funecomp.state

import net.seilent.funecomp.proto.PlayEntry
import net.seilent.funecomp.proto.PlayLogReply
import net.seilent.funecomp.proto.gen.Err

class PlayLogSync(private val cursor: (Long) -> Long?, private val log: (String) -> Unit = {}) {
    sealed class Do {
        abstract val gen: Int
    }

    data class Get(override val gen: Int, val after: Long, val count: Long = PAGE) : Do()

    data class Store(override val gen: Int, val log: Long, val entries: List<PlayEntry>, val more: Boolean) : Do()

    data class Ack(override val gen: Int, val log: Long, val upTo: Long, val more: Boolean) : Do()

    var gen = 0
        private set
    private var bit = false
    private var helloBit = false
    private var services = false
    private var sessionLog: Long? = null
    private var afterLog: Long? = null
    private var busy = false
    private var seq: Long? = null
    private var answered: Long? = null
    private val gaps = HashSet<Pair<Long, Long>>()
    private var retryAfter: Int? = null
    private var retryNext = false

    val logged: Boolean @Synchronized get() = helloBit

    private val active get() = bit && services

    @Synchronized
    fun hello(bit: Boolean, services: Boolean): Do? {
        gen++
        this.bit = bit
        helloBit = bit
        this.services = services
        sessionLog = null
        busy = false
        seq = null
        answered = null
        retryAfter = null
        retryNext = false
        return next()
    }

    @Synchronized
    fun closed() {
        gen++
        bit = false
        busy = false
        sessionLog = null
        retryAfter = null
        retryNext = false
    }

    @Synchronized
    fun services(on: Boolean, turnedOn: Boolean): Do? {
        services = on
        return if (turnedOn) next() else null
    }

    @Synchronized
    fun state(playLogSeq: Long?, card: CardView = CardView()): Do? {
        seq = playLogSeq
        if (card.down) return null
        retryAfter?.let { after ->
            if (card.epoch <= after) return null
            return next()
        }
        if (retryNext) return next()
        return if (above()) next() else null
    }

    @Synchronized
    fun reply(gen: Int, r: PlayLogReply): Do? {
        if (gen != this.gen) return null
        busy = false
        val l = r.log ?: return null.also { log("playlog: reply with no log") }
        val from = afterLog
        if (from != null && from != l) {
            log("playlog: reply names log ${hex(l)}, asked after a cursor of ${hex(from)}")
            sessionLog = null
            return get()
        }
        sessionLog = l
        val c = cursor(l)
        val first = r.first
        if (c != null && first != null && first > c + 1 && gaps.add(l to first)) log("playlog: ${first - c - 1} plays dropped on the DAP")
        if (r.entries.isEmpty()) {
            if (r.more == true) return get()
            answered = seq
            return null
        }
        busy = true
        return Store(gen, l, r.entries, r.more == true)
    }

    @Synchronized
    fun getFailed(gen: Int, code: Int?, epoch: Int = 0) {
        if (gen != this.gen) return
        busy = false
        log("playlog: get ${code?.let { "error $it" } ?: "no answer"}")
        if (code == Err.noCard) retryAfter = epoch else retryNext = true
    }

    @Synchronized
    fun stored(gen: Int, log: Long, upTo: Long?, more: Boolean): Do? {
        if (gen != this.gen) return null
        if (upTo == null) {
            busy = false
            return null
        }
        return Ack(gen, log, upTo, more)
    }

    @Synchronized
    fun acked(gen: Int, ack: Ack, code: Int?, answered: Boolean): Do? {
        if (gen != this.gen) return null
        busy = false
        if (!answered) return null
        if (code == Err.staleRev) {
            log("playlog: ack error 7, read again from the start")
            sessionLog = null
            return get()
        }
        if (code != null) {
            log("playlog: ack error $code")
            return null
        }
        return if (ack.more) get() else settle()
    }

    private fun settle(): Do? = if (above()) get() else null

    private fun above(): Boolean {
        val l = sessionLog ?: return false
        val s = seq ?: return false
        val a = answered
        return s > (cursor(l) ?: 0) && (a == null || s > a)
    }

    private fun next(): Do? = if (active && !busy) get() else null

    private fun get(): Do? {
        if (!active) return null
        val l = sessionLog
        val after = l?.let { cursor(it) } ?: 0
        afterLog = if (l != null && after > 0) l else null
        busy = true
        retryAfter = null
        retryNext = false
        return Get(gen, after)
    }

    private fun hex(l: Long) = java.lang.Long.toHexString(l)

    companion object {
        const val PAGE = 50L
    }
}
