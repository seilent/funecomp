package net.seilent.funecomp.artist

import net.seilent.funecomp.link.ImagePush
import net.seilent.funecomp.link.ImgMeta
import net.seilent.funecomp.link.Session
import net.seilent.funecomp.proto.ImgEntry
import net.seilent.funecomp.proto.ImgListReply
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import java.util.zip.CRC32

class ArtPusher(
    private val session: Session,
    private val art: (String, String?, Int, Int) -> Art?,
    private val full: FullPush?,
    private val keep: () -> Boolean,
    private val gate: () -> Boolean,
) {
    constructor(session: Session, art: (String, String?, Int, Int) -> Art?, keep: () -> Boolean = { true }, gate: () -> Boolean) : this(session, art, null, keep, gate)

    enum class Stop { DONE, UNSUPPORTED, GATE, CLOSED, ABORTED, PREEMPTED }

    class Report(
        val stop: Stop,
        val pushed: Map<String, Long> = emptyMap(),
        val stored: List<String> = emptyList(),
        val missing: List<String> = emptyList(),
        val failed: List<String> = emptyList(),
        val complete: Boolean = false,
        val code: Int? = null,
        val aborted: Boolean = false,
    ) {
        fun counts(): String {
            val names = (pushed.keys + failed + missing).take(NAMES)
            return "pushed=${pushed.size} stored=${stored.size} missing=${missing.size} failed=${failed.size} names=$names"
        }
    }

    class Listing(val names: MutableSet<String>, val bytes: Long?, val cap: Long?, val entries: List<ImgEntry> = emptyList(), val srcRoom: Long? = null)

    fun stored(): Set<String>? = listing()?.names

    var listFailed: Int? = null
        private set

    fun listing(): Listing? {
        listFailed = null
        val out = HashSet<String>()
        val entries = ArrayList<ImgEntry>()
        var offset = 0L
        var bytes: Long? = null
        var cap: Long? = null
        var room: Long? = null
        while (true) {
            if (!keep()) return null
            val r = session.call(Msg.IMG_LIST, Req.imgList(offset, 100))
            if (r !is Session.Result.Ok) {
                listFailed = (r as? Session.Result.Failed)?.error?.code
                return null
            }
            val page = ImgListReply.from(r.body)
            if (offset == 0L) {
                bytes = page.bytes
                cap = page.cap
                room = page.srcRoom
                full?.room(session, room)
            }
            page.entries.forEach { out += it.name }
            entries += page.entries
            offset += page.entries.size
            if (!page.page.more || page.entries.isEmpty()) return Listing(out, bytes, cap, entries, room).also { listed = it }
        }
    }

    private var listed: Listing? = null

    val sources: Boolean get() = full != null && session.hello?.has("imgSource") == true

    private sealed class Form {
        class Source(val f: Full, val key: String, val rearm: Boolean) : Form()
        class Crop(val a: Art) : Form()
        object Have : Form()
        object Missing : Form()
    }

    private fun entry(name: String): ImgEntry? {
        val l = listed ?: listing() ?: return null
        val f = Names.fold(name)
        return l.entries.firstOrNull { Names.fold(it.name) == f }
    }

    private fun crop(name: String, mbid: String?, w: Int, h: Int, a: Art): Form {
        if (a.w == w && a.h == h) return Form.Crop(a)
        return art(name, mbid, w, h)?.let { Form.Crop(it) } ?: Form.Missing
    }

    private fun form(fp: FullPush, name: String, mbid: String?, w: Int, h: Int, a: Art, rearm: Boolean = false): Form {
        val key = a.key ?: return crop(name, mbid, w, h, a)
        if (rearm) return fp.of(key)?.let { Form.Source(it, key, true) } ?: Form.Missing
        val f = fp.of(key) ?: return crop(name, mbid, w, h, a)
        if (!fp.low(session) && !fp.bluetooth()) return Form.Source(f, key, false)
        val e = entry(name)
        if (e?.full == true && e.crc == f.crc) return Form.Have
        if (e?.srcCrc != null && e.srcCrc == f.crc) return Form.Source(f, key, true)
        return crop(name, mbid, w, h, a)
    }

    fun run(artists: List<Pair<String, String?>>, have: Set<String> = emptySet(), origin: Int? = null, originOf: (String) -> Int? = { origin }, rearm: Boolean = false): Report {
        val hello = session.hello
        val w = hello?.backdropW
        val h = hello?.backdropH
        if (hello == null || w == null || h == null || !hello.has("image")) return Report(Stop.UNSUPPORTED)
        val fp = full?.takeIf { hello.has("imgSource") }
        val fitW = if (fp != null) FIT_W else w
        val fitH = if (fp != null) FIT_H else h
        val pushed = LinkedHashMap<String, Long>()
        val stored = ArrayList<String>()
        val missing = ArrayList<String>()
        val failed = ArrayList<String>()
        fun report(stop: Stop, code: Int? = null, aborted: Boolean = false) = Report(stop, pushed, stored, missing, failed, code = code, aborted = aborted)
        val seen = HashSet<String>()
        val tags = hello.has("imgOrigin")
        var gated = false
        for ((name, mbid) in artists) {
            if (name.isBlank() || !seen.add(name)) continue
            if (session.closedReason != null) return report(Stop.CLOSED)
            if (!keep()) return report(Stop.PREEMPTED)
            if (name in have) {
                stored += name
                continue
            }
            val a = try {
                art(name, mbid, fitW, fitH)
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                if (Thread.interrupted()) throw InterruptedException(name)
                failed += name
                continue
            }
            if (a == null) {
                missing += name
                continue
            }
            if (!keep()) return report(Stop.PREEMPTED)
            if (gated || !gate()) {
                gated = true
                continue
            }
            val meta = if (hello.has("artBlock")) ImgMeta(a.wrongArtist, Sources.label(a.source), a.credit?.author?.takeIf { it.isNotBlank() }) else null
            var tries = 0
            var forms = 0
            var form: Form = if (fp != null) form(fp, name, mbid, w, h, a, rearm) else Form.Crop(a)
            while (true) {
                val f = form
                val push = when (f) {
                    is Form.Have -> {
                        stored += name
                        break
                    }
                    is Form.Missing -> {
                        missing += name
                        break
                    }
                    is Form.Source -> ImagePush(session, name, a.mbid, f.f.bytes, f.f.w, f.f.h, meta, originOf(name).takeIf { tags }, true, f.f.focus, f.rearm) { gate() && (f.rearm || fp?.bluetooth() != true) }
                    is Form.Crop -> ImagePush(session, name, f.a.mbid, f.a.jpeg, w, h, meta, originOf(name).takeIf { tags }, gate = gate)
                }
                val o = push.run()
                if (fp != null && f is Form.Source && forms++ < MAX_FORMS) {
                    val code = (o as? ImagePush.Outcome.Failed)?.error?.code ?: (o as? ImagePush.Outcome.Aborted)?.abort?.code?.let { ABORT_ERRORS[it] }
                    val next = when {
                        f.rearm && o is ImagePush.Outcome.Gated -> if (rearm) Form.Missing else crop(name, mbid, w, h, a)
                        code == Err.badValue || code == Err.tooBig -> {
                            fp.refused(f.key)
                            form(fp, name, mbid, w, h, a, rearm)
                        }
                        code == Err.noSpace -> {
                            fp.noSpace(session)
                            crop(name, mbid, w, h, a)
                        }
                        else -> null
                    }
                    if (next != null && session.closedReason == null) {
                        form = next
                        continue
                    }
                }
                if (f is Form.Source && (o is ImagePush.Outcome.Done || o is ImagePush.Outcome.Have)) fp?.sent(name, f.f.crc, f.key)
                if (f is Form.Crop && o is ImagePush.Outcome.Done) (f.a.key ?: a.key)?.let { k -> fp?.crop(k, CRC32().apply { update(f.a.jpeg) }.value) }
                when (o) {
                    is ImagePush.Outcome.Done -> pushed[name] = o.from
                    is ImagePush.Outcome.Have -> stored += name
                    is ImagePush.Outcome.Gated -> gated = true
                    is ImagePush.Outcome.Aborted -> {
                        if (session.closedReason != null) return report(Stop.CLOSED)
                        if (o.abort.code in finalAborts) return report(Stop.ABORTED, o.abort.code, true)
                        if (++tries <= 1) continue
                        failed += name
                    }
                    is ImagePush.Outcome.Failed -> {
                        if (session.closedReason != null) return report(Stop.CLOSED)
                        if (o.error?.code in finalErrors) return report(Stop.ABORTED, o.error?.code)
                        failed += name
                    }
                }
                break
            }
        }
        return report(if (gated) Stop.GATE else Stop.DONE)
    }

    companion object {
        const val NAMES = 10
        const val FIT_W = 360
        const val FIT_H = 480
        private const val MAX_FORMS = 3
        private val ABORT_ERRORS = mapOf(Enums.AbortCode.badValue to Err.badValue, Enums.AbortCode.noSpace to Err.noSpace)
        private val finalAborts = setOf(Enums.AbortCode.busy, Enums.AbortCode.noSpace, Enums.AbortCode.superseded, Enums.AbortCode.noCard)
        private val finalErrors = setOf(Err.busy, Err.noSpace, Err.noCard, Err.readOnly)

        fun origin(source: Int?): Int? = if (source == Enums.Source.phone || source == Enums.Source.phoneFile) Enums.ImgOrigin.phone else null

        fun order(state: State, upcoming: List<Pair<String, String?>> = emptyList()): List<Pair<String, String?>> =
            state.artists.mapIndexed { i, n -> n to state.mbids.getOrNull(i)?.takeIf { it.isNotBlank() } } + upcoming
    }
}
