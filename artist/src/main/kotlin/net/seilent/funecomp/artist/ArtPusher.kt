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

class ArtPusher(
    private val session: Session,
    private val art: (String, String?, Int, Int) -> Art?,
    private val keep: () -> Boolean = { true },
    private val gate: () -> Boolean,
) {
    enum class Stop { DONE, UNSUPPORTED, GATE, CLOSED, ABORTED, PREEMPTED }

    class Report(
        val stop: Stop,
        val pushed: Map<String, Long> = emptyMap(),
        val stored: List<String> = emptyList(),
        val missing: List<String> = emptyList(),
        val failed: List<String> = emptyList(),
        val complete: Boolean = false,
    ) {
        fun counts(): String {
            val names = (pushed.keys + failed + missing).take(NAMES)
            return "pushed=${pushed.size} stored=${stored.size} missing=${missing.size} failed=${failed.size} names=$names"
        }
    }

    class Listing(val names: MutableSet<String>, val bytes: Long?, val cap: Long?, val entries: List<ImgEntry> = emptyList())

    fun stored(): Set<String>? = listing()?.names

    fun listing(): Listing? {
        val out = HashSet<String>()
        val entries = ArrayList<ImgEntry>()
        var offset = 0L
        var bytes: Long? = null
        var cap: Long? = null
        while (true) {
            if (!keep()) return null
            val r = session.call(Msg.IMG_LIST, Req.imgList(offset, 100))
            if (r !is Session.Result.Ok) return null
            val page = ImgListReply.from(r.body)
            if (offset == 0L) {
                bytes = page.bytes
                cap = page.cap
            }
            page.entries.forEach { out += it.name }
            entries += page.entries
            offset += page.entries.size
            if (!page.page.more || page.entries.isEmpty()) return Listing(out, bytes, cap, entries)
        }
    }

    fun run(artists: List<Pair<String, String?>>, have: Set<String> = emptySet(), origin: Int? = null): Report {
        val hello = session.hello
        val w = hello?.backdropW
        val h = hello?.backdropH
        if (hello == null || w == null || h == null || !hello.has("image")) return Report(Stop.UNSUPPORTED)
        val pushed = LinkedHashMap<String, Long>()
        val stored = ArrayList<String>()
        val missing = ArrayList<String>()
        val failed = ArrayList<String>()
        fun report(stop: Stop) = Report(stop, pushed, stored, missing, failed)
        val seen = HashSet<String>()
        val tag = origin.takeIf { hello.has("imgOrigin") }
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
                art(name, mbid, w, h)
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
            while (true) {
                when (val o = ImagePush(session, name, a.mbid, a.jpeg, w, h, meta, tag, gate).run()) {
                    is ImagePush.Outcome.Done -> pushed[name] = o.from
                    is ImagePush.Outcome.Have -> stored += name
                    is ImagePush.Outcome.Gated -> gated = true
                    is ImagePush.Outcome.Aborted -> {
                        if (session.closedReason != null) return report(Stop.CLOSED)
                        if (o.abort.code in finalAborts) return report(Stop.ABORTED)
                        if (++tries <= 1) continue
                        failed += name
                    }
                    is ImagePush.Outcome.Failed -> {
                        if (session.closedReason != null) return report(Stop.CLOSED)
                        if (o.error?.code in finalErrors) return report(Stop.ABORTED)
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
        private val finalAborts = setOf(Enums.AbortCode.busy, Enums.AbortCode.noSpace, Enums.AbortCode.superseded)
        private val finalErrors = setOf(Err.busy, Err.noSpace)

        fun origin(source: Int?): Int? = when (source) {
            Enums.Source.local -> Enums.ImgOrigin.library
            Enums.Source.phone -> Enums.ImgOrigin.phone
            else -> null
        }

        fun order(state: State, upcoming: List<Pair<String, String?>> = emptyList()): List<Pair<String, String?>> =
            state.artists.mapIndexed { i, n -> n to state.mbids.getOrNull(i)?.takeIf { it.isNotBlank() } } + upcoming
    }
}
