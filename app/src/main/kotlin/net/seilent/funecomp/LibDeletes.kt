package net.seilent.funecomp

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.seilent.funecomp.link.Session
import net.seilent.funecomp.proto.BindAskReply
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.LibDeleteReply
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.state.CardView
import net.seilent.funecomp.state.DeleteAsk
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.state.LibDelete
import net.seilent.funecomp.state.LibKey
import net.seilent.funecomp.state.Owed
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

class LibDeletes(
    private val exec: Executor,
    private val request: BindAsk.Request,
    private val bind: BindAsk,
    private val live: () -> DeviceState,
    private val sink: () -> ((String, String) -> Unit)?,
    private val post: (() -> Unit) -> Unit,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val log: (String) -> Unit = {},
) {
    private class Kept(val line: String, val title: String, val at: Long, val hello: HelloReply?)

    class Exits(val stack: () -> List<String>, val sheet: () -> Boolean, val pop: () -> Boolean)

    private val sending = AtomicBoolean(false)
    private val waits = HashSet<DeleteAsk>()
    private var kept: Kept? = null
    private var exits: Exits? = null
    private var artist: Owed? = null

    var owed by mutableStateOf<Owed?>(null)
        private set

    val waiting: Boolean get() = sending.get() || waits.isNotEmpty()

    fun delete(ask: DeleteAsk, tap: Tap) {
        val d = live()
        if (LibDelete.stale(ask, d)) return log("lib delete ${ask.what}: stale at the pick")
        if (d.cardDown) return show(CardView.REFUSED, "")
        exec.execute {
            val token = BindTokens.get(ask.store)
            when {
                token == null -> post { bindAsk(ask, tap) }
                !sending.compareAndSet(false, true) -> {
                    token.fill(0)
                    log("lib delete ${ask.what}: another waits")
                }
                else -> send(ask, tap, token)
            }
        }
    }

    fun started() {
        val k = kept ?: return
        kept = null
        if (k.hello === live().hello && clock() - k.at <= KEEP_MS) sink()?.invoke(k.line, k.title)
    }

    fun exits(store: String?, session: Any?, e: Exits) {
        exits = e
        val o = owed ?: return
        if (artist === o) return
        when (LibDelete.due(o, live(), store.orEmpty())) {
            LibDelete.Due.WAIT -> return
            LibDelete.Due.DROP -> return drop(o, "dropped")
            LibDelete.Due.DUE -> {}
        }
        if (!e.sheet()) leave(o, session, e)
    }

    internal fun reset() {
        sending.set(false)
        waits.clear()
        kept = null
        exits = null
        artist = null
        owed = null
    }

    private fun drop(o: Owed, why: String) {
        if (owed === o) {
            owed = null
            exits = null
        }
        log("lib delete exits $why")
    }

    private fun leave(o: Owed, session: Any?, e: Exits) {
        while (true) {
            when (val x = LibDelete.next(o, e.stack())) {
                LibDelete.Exit.Pop -> if (!e.pop()) return drop(o, "done")
                is LibDelete.Exit.Artist -> return askArtist(o, x.name, session)
                LibDelete.Exit.End -> return drop(o, "done")
            }
        }
    }

    private fun askArtist(o: Owed, name: String, session: Any?) {
        artist = o
        val view = Enums.View.artistAlbums
        request.send(session, Msg.LIB_GET, Req.libGet(view, name, 0, 1, null, null), bind.requestTimeoutMs) { r ->
            val gone = when {
                r is Session.Result.Ok -> live().lib.pages[LibKey(view, name)]?.total == 0L
                r is Session.Result.Failed -> r.error.code == Err.staleRev
                else -> false
            }
            post {
                if (artist === o) artist = null
                val e = exits
                when {
                    owed !== o || e == null -> {}
                    gone && !e.sheet() && LibDelete.next(o, e.stack()) == LibDelete.Exit.Artist(name) && e.pop() -> leave(o, session, e)
                    else -> drop(o, "kept the artist page")
                }
            }
        }
    }

    private fun send(ask: DeleteAsk, tap: Tap?, token: ByteArray) {
        if (LibDelete.stale(ask, live())) {
            token.fill(0)
            sending.set(false)
            return log("lib delete ${ask.what}: stale at the send")
        }
        val payload = LibDelete.request(ask, token)
        val once = AtomicBoolean(false)
        try {
            request.send(ask.session, Msg.LIB_DELETE, payload, Proto.libDeleteMs + bind.requestTimeoutMs) { r ->
                if (once.compareAndSet(false, true)) {
                    payload.fill(0)
                    answered(ask, tap, token, r)
                }
            }
        } catch (e: RuntimeException) {
            if (!once.compareAndSet(false, true)) return log("lib delete ${ask.what}: send threw after its answer, ${e.javaClass.simpleName}")
            payload.fill(0)
            token.fill(0)
            sending.set(false)
            log("lib delete ${ask.what}: not sent, ${e.javaClass.simpleName}")
        }
    }

    private fun answered(ask: DeleteAsk, tap: Tap?, token: ByteArray, r: Session.Result) {
        val base = live().lib.snap
        sending.set(false)
        val reply = (r as? Session.Result.Ok)?.let { LibDeleteReply.from(it.body) }
        val o = when (r) {
            is Session.Result.Ok -> LibDelete.outcome(ask, reply, null, null)
            is Session.Result.Failed -> LibDelete.outcome(ask, null, r.error.code, r.error.text)
            else -> LibDelete.outcome(ask, null, null, null, unanswered(r))
        }
        log(o.log)
        if (o is LibDelete.Outcome.NotAllowed) BindTokens.drop(ask.store, token)
        token.fill(0)
        reply?.let { LibDelete.owe(ask, it, base) }?.let { owe -> post { owed = owe } }
        when (o) {
            is LibDelete.Outcome.NotAllowed -> post { bindAsk(ask, tap) }
            is LibDelete.Outcome.Toast -> show(o.line, o.title)
            is LibDelete.Outcome.Card -> show(CardView.REFUSED, "")
            else -> {}
        }
    }

    private fun bindAsk(ask: DeleteAsk, tap: Tap?) {
        waits += ask
        if (tap?.good == true) show(DeviceState.CONFIRM, ask.name)
        bind.ask(ask.store, tap, ask.session) { r -> post { bound(ask, r) } }
    }

    private fun bound(ask: DeleteAsk, r: Session.Result) {
        waits -= ask
        val o = when (r) {
            is Session.Result.Ok -> BindAskReply.from(r.body).bindToken.let { t -> LibDelete.bindOutcome(ask, t, null, null).also { t?.fill(0) } }
            is Session.Result.Failed -> LibDelete.bindOutcome(ask, null, r.error.code, r.error.text)
            else -> LibDelete.bindOutcome(ask, null, null, null, unanswered(r))
        }
        log(o.log)
        when (o) {
            is LibDelete.Outcome.Send -> when {
                LibDelete.stale(ask, live()) -> show("${ask.what} not deleted", ask.name)
                !sending.compareAndSet(false, true) -> log("lib delete ${ask.what}: another waits")
                else -> {
                    exec.execute {
                        val token = BindTokens.get(ask.store)
                        if (token != null) {
                            send(ask, null, token)
                        } else {
                            sending.set(false)
                            show("${ask.what} not deleted", ask.name)
                        }
                    }
                }
            }
            is LibDelete.Outcome.Wait -> show("${ask.what} not deleted", line(o.why))
            is LibDelete.Outcome.Toast -> show(o.line, o.title)
            else -> {}
        }
    }

    internal fun show(line: String, title: String) = post {
        val s = sink()
        if (s != null) s(line, title) else kept = Kept(line, title, clock(), live().hello)
    }

    private fun unanswered(r: Session.Result) = when (r) {
        is Session.Result.Timeout -> LibDelete.Unanswered.TIMEOUT
        is Session.Result.NotReady -> LibDelete.Unanswered.NOT_READY
        is Session.Result.Unsupported -> LibDelete.Unanswered.UNSUPPORTED
        else -> LibDelete.Unanswered.CLOSED
    }

    private fun line(why: LibDelete.Why) = when (why) {
        LibDelete.Why.SCREEN_OFF -> WAKE
        LibDelete.Why.DECLINED -> Apps.DECLINED
        LibDelete.Why.BUSY -> Apps.BUSY_STOP
        LibDelete.Why.NO_ANSWER -> Apps.NO_ANSWER
    }

    companion object {
        const val WAKE = "wake and unlock the dap"
        const val KEEP_MS = 300_000L
    }
}
