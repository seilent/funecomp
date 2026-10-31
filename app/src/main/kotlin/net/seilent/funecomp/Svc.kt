package net.seilent.funecomp

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.seilent.funecomp.link.Session
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.SvcKey
import net.seilent.funecomp.proto.SvcLoginEnd
import net.seilent.funecomp.proto.SvcPkce
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.state.HostRule
import net.seilent.funecomp.state.PkcePolicy
import net.seilent.funecomp.state.SvcOpens
import net.seilent.funecomp.ui.SvcAsk
import net.seilent.funecomp.ui.SvcLine
import net.seilent.funecomp.ui.SvcView
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

object Svc {
    const val ENDED = "sign in ended"
    const val SIGNED_IN = "signed in"
    const val TOO_LONG = "sign in link too long"
    const val LINK_REFUSED = "login link refused"
    const val KEYS_SENT = "keys sent"
    const val REFUSED = "refused on the dap"
    const val OFF = "streaming is off on the dap"
    const val BAD_KEY = "the dap refused a key"
    const val BUSY = "dap busy"
    const val NO_REPLY = "dap not answering"
    const val EXTRA_TOKEN = "token"
    private const val GONE = 8
    internal const val START_MS = 10_000L
    internal const val TAP_MS = 10_000L
    private const val HOSTS = "hosts"

    sealed interface Answer {
        class Redirect(val url: String) : Answer
        class Error(val code: Int, val text: String) : Answer
    }

    private val CANCELLED = Answer.Error(Err.cancelled, "cancelled")

    private class Open(val service: String, val seq: Long, val name: String, val authorizeUrl: String, val host: String, val redirectPrefix: String, val answer: (Answer) -> Unit) {
        val prefix = PkcePolicy.prefix(redirectPrefix)
        var token: String? = null
        var page: ISvcLoginPage? = null
        var timer: Future<*>? = null
        var start: Future<*>? = null
    }

    var view by mutableStateOf(SvcView())
        private set

    internal var watch: (IBinder, () -> Unit) -> Unit = { b, died -> b.linkToDeath({ died() }, 0) }
    @Volatile var opens = SvcOpens()
        private set
    private var stored = false
    private val lock = Any()
    private var open: Open? = null
    private var tap = 0L
    private var replied: Pair<String, Long>? = null
    private val gone = ArrayDeque<Pair<String, Long>>()
    private var lines = 0
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "svc").apply { isDaemon = true } }
    internal var later: (Long, Runnable) -> Future<*> = { ms, r -> timer.schedule(r, ms, TimeUnit.MILLISECONDS) }

    val binder: ISvcLoginHost.Stub = object : ISvcLoginHost.Stub() {
        override fun ask(token: String?, page: ISvcLoginPage?): Array<String>? = if (ours()) this@Svc.ask(token, page) else null

        override fun caught(token: String?, url: String?): Boolean = ours() && this@Svc.caught(token, url)

        override fun cancelled(token: String?): Boolean = ours() && this@Svc.cancelled(token)

        override fun closed(token: String?): Boolean = ours() && this@Svc.closed(token)

        private fun ours() = Binder.getCallingUid() == Process.myUid()
    }

    fun pkce(q: SvcPkce, hello: HelloReply?, answer: (Answer) -> Unit): String {
        val service = q.service?.takeIf { it.isNotEmpty() }
        val seq = q.seq
        val info = hello?.services?.firstOrNull { it.id == service }
        val name = info?.name?.takeIf { it.isNotBlank() } ?: service.orEmpty()
        val o = synchronized(lock) {
            val refuse = when {
                service == null || seq == null || q.authorizeUrl == null || q.host == null || q.redirectPrefix == null -> Answer.Error(Err.badValue, "bad value")
                service to seq in gone -> CANCELLED
                open != null -> Answer.Error(Err.busy, "busy")
                info?.pkce != true -> Answer.Error(Err.refused, "refused")
                PkcePolicy.prefix(q.redirectPrefix) == null || !HostRule.link(q.authorizeUrl, q.host) -> Answer.Error(Err.refused, "refused").also { refused(service, seq, q.authorizeUrl, name) }
                else -> null
            }
            if (refuse != null) {
                answer(refuse)
                return "$service $seq -> ${refuse.code}"
            }
            Open(service!!, seq!!, name, q.authorizeUrl!!, q.host!!, q.redirectPrefix!!, answer).also {
                open = it
                replied = null
                it.timer = later(Proto.svcPkceMs.toLong()) { timedOut(it) }
                view = view.copy(ask = SvcAsk(it.service, it.name, it.seq, it.host, it.authorizeUrl))
            }
        }
        return "${o.service} ${o.seq} held"
    }

    val asking: String? get() = synchronized(lock) { open?.takeIf { it.token == null }?.name }

    val waiting: Triple<String, Long, String>? get() = synchronized(lock) { open?.takeIf { it.token == null }?.let { Triple(it.service, it.seq, it.authorizeUrl) } }

    fun init(c: Context) = synchronized(lock) {
        if (stored) return
        stored = true
        val p = c.getSharedPreferences(SvcNotice.PREFS, Context.MODE_PRIVATE)
        opens = SvcOpens(p.getString(HOSTS, null).orEmpty()) { p.edit().putString(HOSTS, it).apply() }
    }

    fun refusedLogin(service: String, seq: Long, link: String) = synchronized(lock) { refused(service, seq, link, nameOf(service)) }

    private fun refused(service: String?, seq: Long?, link: String?, name: String) {
        val v = opens.take(service ?: return, seq ?: return, link.orEmpty(), true, true, SvcOpens.Kind.TOAST, SystemClock.elapsedRealtime())
        if (v == SvcOpens.Verdict.OPEN) line(name, LINK_REFUSED)
    }

    fun tapped() = synchronized(lock) { tap = SystemClock.elapsedRealtime() + TAP_MS }

    fun spendTap(): Boolean = synchronized(lock) {
        val on = tap != 0L && SystemClock.elapsedRealtime() <= tap
        tap = 0L
        on
    }

    fun begin(): String? = synchronized(lock) {
        val o = open ?: return null
        val t = UUID.randomUUID().toString()
        o.token = t
        o.start?.cancel(false)
        o.start = later(START_MS) { unstarted(o, t) }
        view = view.copy(ask = view.ask?.copy(page = true))
        t
    }

    fun cancel() {
        val o = synchronized(lock) { open?.also { finish(it, null) } } ?: return
        o.answer(CANCELLED)
        o.page?.let { ended(it) }
    }

    fun ended(e: SvcLoginEnd): Boolean {
        val done = e.result == Enums.SvcLoginResult.done
        val o = synchronized(lock) {
            val o = open?.takeIf { it.service == e.service && it.seq == e.seq }
            if (o == null) {
                if (replied != e.service to e.seq) {
                    e.service?.let { lost(it to (e.seq ?: return false)) }
                    return false
                }
                replied = null
                line(nameOf(e.service), if (done) SIGNED_IN else ENDED)
                return true
            }
            finish(o, if (done) SIGNED_IN else if (o.token != null) ENDED else null)
            o
        }
        o.page?.let { ended(it) }
        return true
    }

    fun dropped() {
        val o = synchronized(lock) {
            replied = null
            gone.clear()
            open?.also { finish(it, if (it.token != null) ENDED else null) }
        } ?: return
        o.page?.let { ended(it) }
    }

    private fun timedOut(o: Open) {
        synchronized(lock) {
            if (open !== o) return
            finish(o, if (o.token != null) ENDED else null)
        }
        o.page?.let { ended(it) }
    }

    private fun unstarted(o: Open, token: String) {
        synchronized(lock) {
            if (open !== o || o.token != token || o.page != null) return
            finish(o, ENDED)
        }
        o.answer(CANCELLED)
    }

    private fun finish(o: Open, line: String?) {
        open = null
        o.timer?.cancel(false)
        o.start?.cancel(false)
        view = view.copy(ask = null)
        line?.let { line(o.name, it) }
    }

    fun lineShown(id: Int) = synchronized(lock) {
        if (view.line?.id == id) view = view.copy(line = null)
    }

    private fun line(title: String, text: String) {
        view = view.copy(line = SvcLine(title, text, ++lines))
    }

    private var names = emptyMap<String, String>()

    fun hello(h: HelloReply?) {
        names = h?.services?.mapNotNull { s -> s.id?.let { it to (s.name?.takeIf { n -> n.isNotBlank() } ?: it) } }?.toMap().orEmpty()
    }

    fun nameOf(service: String?): String = service?.let { names[it] ?: it }.orEmpty()

    fun signedIn(service: String) = synchronized(lock) { line(nameOf(service), SIGNED_IN) }

    private fun ended(page: ISvcLoginPage) {
        try {
            page.ended()
        } catch (_: RemoteException) {
        }
    }

    private fun held(token: String?): Open? = open?.takeIf { token != null && it.token == token }

    internal fun ask(token: String?, page: ISvcLoginPage?): Array<String>? {
        page ?: return null
        val o = synchronized(lock) { held(token)?.also { it.page = page } } ?: return null
        try {
            watch(page.asBinder()) { died(token) }
        } catch (_: RemoteException) {
            died(token)
            return null
        }
        return arrayOf(o.authorizeUrl, o.host, o.redirectPrefix, o.name)
    }

    internal fun caught(token: String?, url: String?): Boolean {
        val (o, a) = synchronized(lock) {
            val o = held(token) ?: return false
            val prefix = o.prefix ?: return false
            when (PkcePolicy.seen(url ?: return false, prefix)) {
                is PkcePolicy.Seen.Caught -> {
                    replied = o.service to o.seq
                    finish(o, null)
                    o to Answer.Redirect(url)
                }
                PkcePolicy.Seen.TooLong -> {
                    finish(o, TOO_LONG)
                    o to CANCELLED
                }
                else -> return false
            }
        }
        o.answer(a)
        return true
    }

    internal fun cancelled(token: String?, line: String? = null): Boolean {
        val o = synchronized(lock) { held(token)?.also { finish(it, line) } } ?: return false
        o.answer(CANCELLED)
        return true
    }

    internal fun closed(token: String?): Boolean = cancelled(token, ENDED)

    internal fun died(token: String?) {
        cancelled(token, ENDED)
    }

    private fun lost(p: Pair<String, Long>) {
        gone.remove(p)
        gone.addLast(p)
        while (gone.size > GONE) gone.removeFirst()
    }

    fun keys(service: String, keys: List<SvcKey>, request: (Int, ByteArray, Long, (Session.Result) -> Unit) -> Unit, log: (String) -> Unit, done: (String) -> Unit) {
        val payload = Req.svcKeys(service, keys)
        val size = payload.size
        val n = keys.size
        request(Msg.SVC_KEYS, payload, Proto.svcReplyMs.toLong()) { r ->
            val line = keysLine(r)
            log("svc keys $service n=$n bytes=$size -> ${if (r is Session.Result.Failed) "error ${r.error.code}" else r.javaClass.simpleName.lowercase()}")
            done(line)
        }
        payload.fill(0)
    }

    fun keysLine(r: Session.Result): String = when (r) {
        is Session.Result.Ok -> KEYS_SENT
        is Session.Result.Failed -> when (r.error.code) {
            Err.off -> OFF
            Err.badValue -> BAD_KEY
            Err.busy -> BUSY
            else -> REFUSED
        }
        else -> NO_REPLY
    }

    internal fun reset() = synchronized(lock) {
        open?.timer?.cancel(false)
        open?.start?.cancel(false)
        open = null
        replied = null
        gone.clear()
        names = emptyMap()
        view = SvcView()
        opens = SvcOpens()
        stored = false
        tap = 0L
        watch = { b, died -> b.linkToDeath({ died() }, 0) }
        later = { ms, r -> timer.schedule(r, ms, TimeUnit.MILLISECONDS) }
    }
}
