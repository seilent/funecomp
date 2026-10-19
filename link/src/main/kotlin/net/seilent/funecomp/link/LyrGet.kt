package net.seilent.funecomp.link

import net.seilent.funecomp.proto.LyrGetReply
import net.seilent.funecomp.proto.LyrLine
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.proto.lyricsCrc
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class LyrGet(private val session: Session, private val cancelled: () -> Boolean = { false }) {
    sealed class Outcome {
        data class Lines(val crc: Long, val kind: Int, val lines: List<LyrLine>) : Outcome()
        data class None(val crc: Long) : Outcome()
        data class Have(val crc: Long) : Outcome()
        data class Failed(val code: Int?) : Outcome()
        object Cancelled : Outcome()
    }

    private class Header(val crc: Long, val kind: Int, val total: Int)

    private fun header(r: LyrGetReply): Header? {
        val crc = r.crc ?: return null
        val kind = r.kind?.takeIf { it == Enums.LyricsKind.synced || it == Enums.LyricsKind.plain } ?: return null
        val total = r.total?.takeIf { it in 0..MAX_LINES } ?: return null
        if (r.offset != 0L) return null
        return Header(crc, kind, total.toInt())
    }

    private fun restart(r: LyrGetReply) = r.crc != null && r.offset == 0L && r.kind != null && r.total != null

    fun run(track: Long, have: Long? = null): Outcome {
        session.checkNotOwn("LyrGet.run")
        var r = call(track, 0, have) ?: return failed()
        repeat(RESTARTS + 1) {
            val h = header(r) ?: return Outcome.Failed(null)
            if (have == h.crc && r.lines.isEmpty() && r.more == false) return Outcome.Have(h.crc)
            if (h.total == 0) return Outcome.None(h.crc)
            val lines = ArrayList<LyrLine>(h.total)
            var page = r
            var restartAt: LyrGetReply? = null
            while (true) {
                if (page.lines.isEmpty() && lines.size < h.total) return Outcome.Failed(null)
                for (l in page.lines) {
                    if (l.text.toByteArray(Charsets.UTF_8).size > Proto.lyrLineMax) return Outcome.Failed(null)
                    if (h.kind == Enums.LyricsKind.synced) {
                        val ms = l.ms ?: return Outcome.Failed(null)
                        if (lines.isNotEmpty() && ms < lines.last().ms!!) return Outcome.Failed(null)
                    }
                    lines += l
                }
                if (lines.size > h.total) return Outcome.Failed(null)
                val more = page.more ?: return Outcome.Failed(null)
                if (more == (lines.size == h.total)) return Outcome.Failed(null)
                if (!more) break
                if (cancelled()) return Outcome.Cancelled
                val asked = lines.size.toLong()
                val c = call(track, asked, h.crc) ?: return failed()
                if (restart(c)) {
                    restartAt = c
                    break
                }
                if (c.crc != null || c.offset != asked) return Outcome.Failed(null)
                page = c
            }
            if (restartAt != null) {
                r = restartAt
                return@repeat
            }
            val kept = if (h.kind == Enums.LyricsKind.plain) lines.map { LyrLine(null, it.text) } else lines
            if (lyricsCrc(h.kind, kept) != h.crc) return Outcome.Failed(null)
            return Outcome.Lines(h.crc, h.kind, kept)
        }
        return Outcome.Failed(null)
    }

    private var lastError: Int? = null

    private fun failed(): Outcome = if (cancelled()) Outcome.Cancelled else Outcome.Failed(lastError)

    private fun call(track: Long, offset: Long, have: Long?): LyrGetReply? {
        if (cancelled()) return null
        val latch = CountDownLatch(1)
        val result = AtomicReference<Session.Result>(Session.Result.Closed)
        session.request(Msg.LYR_GET, Req.lyrGet(track, offset, have)) {
            result.set(it)
            latch.countDown()
        }
        latch.await()
        return when (val r = result.get()) {
            is Session.Result.Ok -> LyrGetReply.from(r.body)
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
        const val MAX_LINES = 1024L
    }
}
