package net.seilent.funecomp.artist

import net.seilent.funecomp.link.ImagePush
import net.seilent.funecomp.link.Session
import net.seilent.funecomp.proto.ImgListReply
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg

class ArtPusher(
    private val session: Session,
    private val art: (String, String?, Int, Int) -> Art?,
    private val gate: () -> Boolean,
) {
    enum class Stop { DONE, UNSUPPORTED, GATE, CLOSED, ABORTED }

    class Report(
        val stop: Stop,
        val pushed: Map<String, Long> = emptyMap(),
        val stored: List<String> = emptyList(),
        val missing: List<String> = emptyList(),
        val failed: List<String> = emptyList(),
    )

    fun stored(): Set<String>? {
        val out = HashSet<String>()
        var offset = 0L
        while (true) {
            val r = session.call(Msg.IMG_LIST, Req.imgList(offset, 100))
            if (r !is Session.Result.Ok) return null
            val page = ImgListReply.from(r.body)
            page.entries.forEach { out += it.name }
            offset += page.entries.size
            if (!page.page.more || page.entries.isEmpty()) return out
        }
    }

    fun run(artists: List<Pair<String, String?>>): Report {
        val hello = session.hello
        val w = hello?.backdropW
        val h = hello?.backdropH
        if (hello == null || w == null || h == null || !hello.has("image")) return Report(Stop.UNSUPPORTED)
        val have = stored() ?: return Report(if (session.closedReason != null) Stop.CLOSED else Stop.UNSUPPORTED)
        val pushed = LinkedHashMap<String, Long>()
        val stored = ArrayList<String>()
        val missing = ArrayList<String>()
        val failed = ArrayList<String>()
        fun report(stop: Stop) = Report(stop, pushed, stored, missing, failed)
        val seen = HashSet<String>()
        for ((name, mbid) in artists) {
            if (name.isBlank() || !seen.add(name)) continue
            if (name in have) {
                stored += name
                continue
            }
            if (!gate()) return report(Stop.GATE)
            val a = try {
                art(name, mbid, w, h)
            } catch (e: Exception) {
                failed += name
                continue
            }
            if (a == null) {
                missing += name
                continue
            }
            if (!gate()) return report(Stop.GATE)
            var tries = 0
            while (true) {
                when (val o = ImagePush(session, name, a.mbid, a.jpeg, w, h, gate).run()) {
                    is ImagePush.Outcome.Done -> pushed[name] = o.from
                    is ImagePush.Outcome.Have -> stored += name
                    is ImagePush.Outcome.Gated -> return report(Stop.GATE)
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
        return report(Stop.DONE)
    }

    companion object {
        private val finalAborts = setOf(Enums.AbortCode.busy, Enums.AbortCode.noSpace, Enums.AbortCode.superseded)
        private val finalErrors = setOf(Err.busy, Err.noSpace)

        fun order(state: State, upcoming: List<Pair<String, String?>> = emptyList()): List<Pair<String, String?>> =
            state.artists.mapIndexed { i, n -> n to state.mbids.getOrNull(i)?.takeIf { it.isNotBlank() } } + upcoming
    }
}
