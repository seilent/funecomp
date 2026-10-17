package net.seilent.funecomp.link

import net.seilent.funecomp.proto.ErrorReply
import net.seilent.funecomp.proto.ImgAbort
import net.seilent.funecomp.proto.ImgBeginReply
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32

class ImagePush(
    private val session: Session,
    private val name: String,
    private val mbid: String?,
    private val jpeg: ByteArray,
    private val w: Int,
    private val h: Int,
    private val gate: () -> Boolean = { true },
) {
    sealed class Outcome {
        class Done(val from: Long, val chunks: Int) : Outcome()
        object Have : Outcome()
        class Aborted(val abort: ImgAbort, val chunks: Int) : Outcome()
        class Failed(val error: ErrorReply?) : Outcome()
        class Gated(val chunks: Int) : Outcome()
    }

    fun run(): Outcome {
        session.checkNotOwn("ImagePush.run")
        val abort = AtomicReference<ImgAbort?>()
        val armed = AtomicBoolean(false)
        session.abortListener = { if (armed.get() && it.name == name) abort.set(it) }
        session.pushBegin()
        try {
            val total = jpeg.size.toLong()
            val crc = crc32(jpeg.size)
            val latch = CountDownLatch(1)
            val result = AtomicReference<Session.Result>(Session.Result.Closed)
            session.request(Msg.IMG_BEGIN, Req.imgBegin(Enums.ImgKind.artist, name, mbid, total, crc, w, h)) {
                armed.set(true)
                result.set(it)
                latch.countDown()
            }
            latch.await()
            val begin = result.get()
            if (begin !is Session.Result.Ok) return Outcome.Failed((begin as? Session.Result.Failed)?.error)
            val reply = ImgBeginReply.from(begin.body)
            if (reply.offset == total) return Outcome.Have
            val from = if (reply.offset in 1 until total && crc32(reply.offset.toInt()) == reply.crc) reply.offset else 0L
            val chunk = (session.hello?.maxPayload ?: Proto.maxPayload) - Proto.imgDataReserve
            var off = from
            var chunks = 0
            while (off < total) {
                abort.get()?.let { return Outcome.Aborted(it, chunks) }
                if (!gate()) return Outcome.Gated(chunks)
                val n = minOf(chunk.toLong(), total - off).toInt()
                if (!session.push(Msg.IMG_DATA, Req.imgData(off, jpeg.copyOfRange(off.toInt(), off.toInt() + n)))) {
                    return Outcome.Failed(null)
                }
                off += n
                chunks++
            }
            val end = session.call(Msg.IMG_END, Req.imgEnd(crc))
            abort.get()?.let { return Outcome.Aborted(it, chunks) }
            return when (end) {
                is Session.Result.Ok -> Outcome.Done(from, chunks)
                is Session.Result.Failed -> Outcome.Failed(end.error)
                else -> Outcome.Failed(null)
            }
        } finally {
            session.pushEnd()
            session.abortListener = null
        }
    }

    private fun crc32(n: Int): Long = CRC32().apply { update(jpeg, 0, n) }.value
}
