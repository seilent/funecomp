package net.seilent.funecomp.link

import net.seilent.funecomp.proto.ErrorReply
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.UpdAbort
import net.seilent.funecomp.proto.UpdBeginReply
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

class UpdPush(
    private val session: Session,
    private val board: String,
    private val size: Long,
    private val md5: String,
    private val open: (Long) -> InputStream,
    private val gate: () -> Boolean = { true },
    private val progress: (Long) -> Unit = {},
) {
    sealed class Outcome {
        class Done(val from: Long, val chunks: Int) : Outcome()
        class Aborted(val abort: UpdAbort, val chunks: Int) : Outcome()
        class Failed(val error: ErrorReply?) : Outcome()
        class Paused(val at: Long) : Outcome()
    }

    fun run(): Outcome {
        session.checkNotOwn("UpdPush.run")
        val abort = AtomicReference<UpdAbort?>()
        session.updAbortListener = { abort.set(it) }
        session.pushBegin()
        try {
            val begin = when (val r = session.call(Msg.UPD_BEGIN, Req.updBegin(board, size, md5))) {
                is Session.Result.Ok -> r
                is Session.Result.Failed -> return Outcome.Failed(r.error)
                Session.Result.Unsupported -> return Outcome.Failed(null)
                else -> return Outcome.Paused(0)
            }
            val from = UpdBeginReply.from(begin.body).offset.coerceIn(0, size)
            var off = from
            var chunks = 0
            progress(off)
            if (off < size) open(off).use { input ->
                val buf = ByteArray(Proto.updChunk)
                while (off < size) {
                    abort.get()?.let { return Outcome.Aborted(it, chunks) }
                    if (!gate()) return Outcome.Paused(off)
                    val want = minOf(buf.size.toLong(), size - off).toInt()
                    var n = 0
                    while (n < want) {
                        val r = input.read(buf, n, want - n)
                        if (r < 0) return Outcome.Failed(null)
                        n += r
                    }
                    if (!session.push(Msg.UPD_DATA, Req.updData(off, buf.copyOf(n)))) return Outcome.Paused(off)
                    off += n
                    chunks++
                    progress(off)
                }
            }
            abort.get()?.let { return Outcome.Aborted(it, chunks) }
            return when (val end = session.call(Msg.UPD_END, Req.updEnd(md5), END_TIMEOUT_MS)) {
                is Session.Result.Ok -> Outcome.Done(from, chunks)
                is Session.Result.Failed -> abort.get()?.let { Outcome.Aborted(it, chunks) } ?: Outcome.Failed(end.error)
                else -> Outcome.Paused(off)
            }
        } finally {
            session.pushEnd()
            session.updAbortListener = null
        }
    }

    companion object {
        const val END_TIMEOUT_MS = 60_000L
    }
}
