package net.seilent.funecomp.link

import net.seilent.funecomp.proto.AppAbort
import net.seilent.funecomp.proto.AppBeginReply
import net.seilent.funecomp.proto.ErrorReply
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

class AppPush(
    private val session: Session,
    private val id: String,
    private val version: Long,
    private val size: Long,
    private val sha: ByteArray,
    private val open: (Long) -> InputStream,
    private val gate: () -> Boolean = { true },
    private val progress: (Long) -> Unit = {},
    private val begun: (AppBeginReply) -> Unit = {},
    private val replyMs: Long = Proto.svcReplyMs.toLong(),
) {
    sealed class Outcome {
        abstract val ended: Boolean

        object Asked : Outcome() {
            override val ended = true
        }

        object Checking : Outcome() {
            override val ended = true
        }

        class Retry(val error: ErrorReply, override val ended: Boolean) : Outcome()
        class Aborted(val abort: AppAbort, override val ended: Boolean) : Outcome()
        class Failed(val error: ErrorReply?, override val ended: Boolean) : Outcome()
        class Paused(val at: Long, override val ended: Boolean) : Outcome()
    }

    fun run(): Outcome {
        session.checkNotOwn("AppPush.run")
        val abort = AtomicReference<AppAbort?>()
        session.appAbortListener = { abort.set(it) }
        session.pushBegin()
        try {
            val begin = when (val r = session.call(Msg.APP_BEGIN, Req.appBegin(id, version, size, sha), replyMs)) {
                is Session.Result.Ok -> r
                is Session.Result.Failed -> return if (r.error.code == Err.busy) Outcome.Retry(r.error, false) else Outcome.Failed(r.error, false)
                Session.Result.Unsupported -> return Outcome.Failed(null, false)
                Session.Result.Timeout -> return Outcome.Retry(ErrorReply(Err.busy, TIMEOUT), false)
                else -> return Outcome.Paused(0, false)
            }
            val reply = AppBeginReply.from(begin.body)
            begun(reply)
            var off = reply.offset.coerceIn(0, size)
            progress(off)
            if (off < size) open(off).use { input ->
                val buf = ByteArray(Proto.updChunk)
                while (off < size) {
                    abort.get()?.let { return Outcome.Aborted(it, false) }
                    val want = minOf(buf.size.toLong(), size - off).toInt()
                    var n = 0
                    while (n < want) {
                        val r = input.read(buf, n, want - n)
                        if (r < 0) throw IOException("short read at ${off + n}")
                        n += r
                    }
                    if (!gate()) return Outcome.Paused(off, false)
                    if (!session.push(Msg.APP_DATA, Req.appData(off, buf.copyOf(n)))) return Outcome.Paused(off, false)
                    off += n
                    progress(off)
                }
            }
            abort.get()?.let { return Outcome.Aborted(it, false) }
            return when (val end = session.call(Msg.APP_END, Req.appEnd(sha), replyMs)) {
                is Session.Result.Ok -> abort.get()?.let { Outcome.Aborted(it, true) } ?: Outcome.Asked
                is Session.Result.Failed -> abort.get()?.let { Outcome.Aborted(it, true) } ?: when {
                    end.error.code == Err.busy && end.error.text == CHECKING -> Outcome.Checking
                    end.error.code == Err.busy -> Outcome.Retry(end.error, true)
                    else -> Outcome.Failed(end.error, true)
                }
                Session.Result.Unsupported -> Outcome.Failed(null, true)
                Session.Result.Timeout -> abort.get()?.let { Outcome.Aborted(it, true) } ?: Outcome.Retry(ErrorReply(Err.busy, TIMEOUT), true)
                else -> Outcome.Paused(off, true)
            }
        } finally {
            session.pushEnd()
            session.appAbortListener = null
        }
    }

    companion object {
        const val CHECKING = "checking"
        const val TIMEOUT = "timeout"
    }
}
