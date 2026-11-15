package net.seilent.funecomp

import net.seilent.funecomp.link.Session
import net.seilent.funecomp.proto.BindAskReply
import net.seilent.funecomp.proto.ErrorReply
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto

class BindAsk(private val request: Request, val requestTimeoutMs: Long = Proto.requestTimeoutMs.toLong(), private val log: (String) -> Unit = {}) {
    fun interface Request {
        fun send(session: Any?, type: Int, payload: ByteArray, timeoutMs: Long, done: (Session.Result) -> Unit)
    }

    init {
        require(requestTimeoutMs >= MIN_TIMEOUT_MS) { "request timeout under $MIN_TIMEOUT_MS ms" }
    }

    private val lock = Any()
    private var waiters: MutableList<(Session.Result) -> Unit>? = null

    val asking: Boolean get() = synchronized(lock) { waiters != null }

    fun ask(key: String, tap: Tap?, session: Any?, done: (Session.Result) -> Unit) {
        if (tap?.take() != true) {
            log("bind ask: no tap")
            return done(Session.Result.Failed(ErrorReply(Err.refused, NO_TAP)))
        }
        if (session == null) return done(Session.Result.Closed)
        synchronized(lock) {
            waiters?.let {
                it += done
                log("bind ask joined")
                return
            }
            waiters = mutableListOf(done)
        }
        request.send(session, Msg.BIND_ASK, Req.bindAsk(), Proto.svcReplyMs + requestTimeoutMs) { r ->
            val token = (r as? Session.Result.Ok)?.let { BindAskReply.from(it.body).bindToken }
            val stored = token != null && BindTokens.put(key, token)
            val all = synchronized(lock) { waiters.orEmpty().also { waiters = null } }
            val out = when (r) {
                is Session.Result.Ok -> if (stored) "token stored" else "token ${token?.size ?: 0} bytes"
                is Session.Result.Failed -> "error ${r.error.code} ${r.error.text}"
                else -> r.javaClass.simpleName.lowercase()
            }
            log("bind ask -> $out, ${all.size} waiting")
            all.forEach { it(r) }
            token?.fill(0)
        }
    }

    companion object {
        const val NO_TAP = "no tap"
        const val MIN_TIMEOUT_MS = 3000L
    }
}
