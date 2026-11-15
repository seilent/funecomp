package net.seilent.funecomp.state

import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.LibDeleteReply
import net.seilent.funecomp.proto.LibRow
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Proto

class DeleteAsk(
    val store: String,
    val session: Any?,
    val album: Boolean,
    val first: Long,
    val count: Long?,
    val name: String,
    val key: String?,
    val artist: String?,
    val snap: Long,
    val bootId: Long,
    val hello: HelloReply,
) {
    val what: String get() = if (album) "album" else "song"
}

class Owed(val store: String, val bootId: Long, val album: String?, val artist: String?, val base: Long?)

object LibDelete {
    const val NOT_ALLOWED = "not allowed"
    const val CUE = "part of a cue image"
    const val READ_ONLY = "card is read only"

    enum class Unanswered { TIMEOUT, CLOSED, NOT_READY, UNSUPPORTED }

    enum class Why { SCREEN_OFF, DECLINED, BUSY, NO_ANSWER }

    sealed interface Outcome {
        val log: String

        data class Toast(val line: String, val title: String, override val log: String) : Outcome

        data class Quiet(override val log: String) : Outcome

        data class NotAllowed(override val log: String) : Outcome

        data class Card(override val log: String) : Outcome

        data class Send(override val log: String) : Outcome

        data class Wait(val why: Why, override val log: String) : Outcome
    }

    sealed interface Exit {
        data object Pop : Exit

        data class Artist(val name: String) : Exit

        data object End : Exit
    }

    enum class Due { WAIT, DUE, DROP }

    fun albumPage(key: String) = "album:$key"

    fun artistPage(name: String) = "artist:$name"

    fun ask(device: DeviceState, row: LibRow, album: Boolean, store: String, session: Any? = null, waiting: Boolean = false): DeleteAsk? {
        val hello = device.hello ?: return null
        val boot = hello.bootId ?: return null
        val snap = device.lib.snap ?: return null
        val first = row.first ?: return null
        if (waiting) return null
        if (album && (row.count == null || row.noDelete == true)) return null
        return DeleteAsk(store, session, album, first, row.count.takeIf { album }, row.name.orEmpty(), row.key, row.artist.takeIf { album }, snap, boot, hello)
    }

    fun stale(ask: DeleteAsk, device: DeviceState) = device.hello !== ask.hello || device.lib.snap != ask.snap

    fun request(ask: DeleteAsk, token: ByteArray): ByteArray =
        Req.libDelete(ask.first.takeIf { !ask.album }, ask.first.takeIf { ask.album }, ask.count.takeIf { ask.album }, ask.snap, token, ask.bootId)

    private fun no(ask: DeleteAsk, title: String, log: String) = Outcome.Toast("${ask.what} not deleted", title, log)

    fun outcome(ask: DeleteAsk, reply: LibDeleteReply?, code: Int?, text: String?, unanswered: Unanswered? = null): Outcome {
        val head = "lib delete ${ask.what}"
        if (unanswered != null) return Outcome.Quiet("$head -> ${unanswered.name.lowercase()}")
        if (code == null) {
            val failed = reply?.failed ?: 0
            val n = (reply?.deleted ?: 0) + failed
            val log = "$head -> ${reply?.deleted ?: 0} deleted, $failed failed"
            return when {
                failed <= 0 -> Outcome.Quiet(log)
                failed >= n -> no(ask, ask.name, log)
                else -> Outcome.Toast(if (failed == 1L) "1 song not deleted" else "$failed songs not deleted", ask.name, log)
            }
        }
        val log = "$head -> error $code ${text.orEmpty()}".trimEnd()
        return when (code) {
            Err.refused -> when (text) {
                NOT_ALLOWED -> Outcome.NotAllowed(log)
                CUE -> no(ask, CUE, log)
                else -> no(ask, ask.name, log)
            }
            Err.readOnly -> no(ask, READ_ONLY, log)
            Err.staleRev -> no(ask, ask.name, log)
            Err.notFound -> if (ask.album) no(ask, ask.name, log) else Outcome.Quiet(log)
            Err.noCard -> Outcome.Card(log)
            else -> Outcome.Quiet(log)
        }
    }

    fun bindOutcome(ask: DeleteAsk, token: ByteArray?, code: Int?, text: String?, unanswered: Unanswered? = null): Outcome {
        val head = "bind ask ${ask.what}"
        val log = when {
            unanswered != null -> "$head -> ${unanswered.name.lowercase()}"
            code != null -> "$head -> error $code ${text.orEmpty()}".trimEnd()
            else -> "$head -> token ${token?.size ?: 0} bytes"
        }
        return when {
            unanswered == Unanswered.TIMEOUT || unanswered == Unanswered.CLOSED -> Outcome.Wait(Why.NO_ANSWER, log)
            unanswered != null -> no(ask, ask.name, log)
            code == null -> if (token?.size == Proto.bindTokenBytes) Outcome.Send(log) else no(ask, ask.name, log)
            code == Err.busy && text == SCREEN_OFF -> Outcome.Wait(Why.SCREEN_OFF, log)
            code == Err.busy -> Outcome.Wait(Why.BUSY, log)
            code == Err.refused && text == DECLINED -> Outcome.Wait(Why.DECLINED, log)
            else -> no(ask, ask.name, log)
        }
    }

    fun owe(ask: DeleteAsk, reply: LibDeleteReply, snap: Long?): Owed? =
        if (ask.album && (reply.failed ?: 0) == 0L) Owed(ask.store, ask.bootId, ask.key, ask.artist, snap) else null

    fun due(owed: Owed, device: DeviceState, store: String): Due {
        val hello = device.hello ?: return Due.WAIT
        return when {
            store != owed.store || hello.bootId != owed.bootId -> Due.DROP
            device.lib.snap != owed.base -> Due.DUE
            else -> Due.WAIT
        }
    }

    fun next(owed: Owed, keys: List<String>): Exit {
        val top = keys.lastOrNull() ?: return Exit.End
        return when {
            owed.album != null && top == albumPage(owed.album) -> Exit.Pop
            owed.artist != null && top == artistPage(owed.artist) -> Exit.Artist(owed.artist)
            else -> Exit.End
        }
    }

    private const val SCREEN_OFF = "screen off"
    private const val DECLINED = "declined"
}
