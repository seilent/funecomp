package net.seilent.funecomp.link

import net.seilent.funecomp.proto.ArtGetReply
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.gen.Msg
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32

class ArtGet(private val session: Session, private val cancelled: () -> Boolean = { false }) {
    sealed class Outcome {
        class Image(val crc: Long, val w: Int, val h: Int, val jpeg: ByteArray) : Outcome()
        class Have(val crc: Long, val w: Int, val h: Int) : Outcome()
        class Failed(val code: Int?) : Outcome()
        object Cancelled : Outcome()
    }

    fun run(track: Long, px: Int, have: Long? = null): Outcome {
        session.checkNotOwn("ArtGet.run")
        var r = call(track, px, 0, have) ?: return failed()
        repeat(RESTARTS + 1) {
            val crc = r.crc ?: return Outcome.Failed(null)
            val total = r.total?.takeIf { it in 1..MAX_BYTES } ?: return Outcome.Failed(null)
            val w = r.w ?: 0
            val h = r.h ?: 0
            if (r.data == null && have == crc) return Outcome.Have(crc, w, h)
            val out = ByteArrayOutputStream(total.toInt().coerceAtLeast(0))
            out.write(r.data ?: ByteArray(0))
            var restart: ArtGetReply? = null
            while (out.size() < total) {
                if (cancelled()) return Outcome.Cancelled
                val c = call(track, px, out.size().toLong(), crc) ?: return failed()
                if (c.crc != null && c.crc != crc) {
                    restart = c
                    break
                }
                val d = c.data ?: return Outcome.Failed(null)
                if (c.offset != out.size().toLong() || d.isEmpty()) return Outcome.Failed(null)
                out.write(d)
            }
            if (restart != null) {
                r = restart
                return@repeat
            }
            val jpeg = out.toByteArray()
            if (jpeg.size.toLong() != total || CRC32().apply { update(jpeg) }.value != crc) return Outcome.Failed(null)
            return Outcome.Image(crc, w, h, jpeg)
        }
        return Outcome.Failed(null)
    }

    private var lastError: Int? = null

    private fun failed(): Outcome = if (cancelled()) Outcome.Cancelled else Outcome.Failed(lastError)

    private fun call(track: Long, px: Int, offset: Long, have: Long?): ArtGetReply? {
        if (cancelled()) return null
        val latch = CountDownLatch(1)
        val result = AtomicReference<Session.Result>(Session.Result.Closed)
        session.request(Msg.ART_GET, Req.artGet(track, px, offset, have)) {
            result.set(it)
            latch.countDown()
        }
        latch.await()
        return when (val r = result.get()) {
            is Session.Result.Ok -> ArtGetReply.from(r.body)
            is Session.Result.Failed -> {
                lastError = r.error.code
                null
            }
            else -> {
                lastError = null
                null
            }
        }
    }

    companion object {
        private const val RESTARTS = 2
        const val MAX_BYTES = 4L shl 20
    }
}
