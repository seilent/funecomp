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
import net.seilent.funecomp.proto.SvcLogin
import net.seilent.funecomp.proto.SvcLoginEnd
import net.seilent.funecomp.proto.SvcLoginStartReply
import net.seilent.funecomp.proto.SvcPkce
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.state.HostRule
import net.seilent.funecomp.state.PkcePolicy
import net.seilent.funecomp.state.SvcAccount
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
    const val SIGNED_OUT = "signed out"
    const val NO_KEYS = "no keys on the dap"
    const val NOT_INSTALLED = "not installed"
    const val NO_CARD = "card not available"
    const val ACCOUNT_CHANGED = "account changed"
    const val NOT_COUNTED = "downloads not counted yet"
    const val WAITING = "waiting for the dap"
    const val EXTRA_TOKEN = "token"
    private const val GONE = 8
    internal const val START_MS = 10_000L
    internal const val TAP_MS = 10_000L
    internal const val ARM_MS = Proto.svcReplyMs + 30_000L
    internal const val REASK_MS = Proto.requestTimeoutMs + 5_000L
    private const val TIMEOUT = "timeout"
    private const val CHANGED = "downloads changed"
    private const val HOSTS = "hosts"

    sealed interface Answer {
        class Redirect(val url: String) : Answer
        class Error(val code: Int, val text: String) : Answer
    }

    private val CANCELLED = Answer.Error(Err.cancelled, "cancelled")

    private class Open(val service: String, val seq: Long, val name: String, val authorizeUrl: String, val host: String, val redirectPrefix: String, var answer: ((Answer) -> Unit)?, val at: Long) {
        val prefix = PkcePolicy.prefix(redirectPrefix)
        val q = SvcPkce(service, authorizeUrl, host, redirectPrefix, seq)
        var token: String? = null
        var page: ISvcLoginPage? = null
        var timer: Future<*>? = null
        var start: Future<*>? = null
        var closed = false
    }

    private class Kept(val q: SvcPkce, val name: String, val redirect: String, var session: Int) {
        var timer: Future<*>? = null
    }

    private class Arm(val service: String) {
        var seq: Long? = null
        var link: String? = null
        var timer: Future<*>? = null
        var noticed = false
    }

    private class Decline(val service: String, val device: String?, var seq: Long?, val session: Int) {
        var q: SvcPkce? = null
        var used = false
        var timer: Future<*>? = null
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
    private var kept: Kept? = null
    private var arm: Arm? = null
    private val declines = HashMap<String, Decline>()
    private val pending = HashSet<String>()
    private var reopen: String? = null
    private var session = 0
    private var device: String? = null
    private var holds = false
    private var liveAt = 0L
    private var reask: Future<*>? = null
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
        var page: ISvcLoginPage? = null
        val out = synchronized(lock) {
            val bad = service == null || seq == null || q.authorizeUrl == null || q.host == null || q.redirectPrefix == null
            val overtaken = service to seq in gone
            val declined = !bad && !overtaken && declined(q)
            if (!bad && !overtaken && !declined) {
                resumed(q, answer)?.let { return it }
                val k = kept
                val d = open?.takeIf { it.answer == null }
                if (k != null) {
                    dropKept()
                    line(k.name, ENDED)
                }
                if (d != null) {
                    finish(d, ENDED)
                    page = d.page
                }
            }
            val refuse = when {
                bad -> Answer.Error(Err.badValue, "bad value")
                overtaken || declined -> CANCELLED
                open != null -> Answer.Error(Err.busy, "busy")
                info?.pkce != true -> Answer.Error(Err.refused, "refused")
                PkcePolicy.prefix(q.redirectPrefix) == null || !HostRule.link(q.authorizeUrl, q.host) -> Answer.Error(Err.refused, "refused").also { refused(service, seq, q.authorizeUrl, name) }
                else -> null
            }
            if (refuse != null) {
                answer(refuse)
                "$service $seq -> ${refuse.code}"
            } else {
                Open(service!!, seq!!, name, q.authorizeUrl!!, q.host!!, q.redirectPrefix!!, answer, SystemClock.elapsedRealtime()).also {
                    open = it
                    replied = null
                    it.timer = later(Proto.svcPkceMs.toLong()) { timedOut(it) }
                    arm?.takeIf { a -> a.service == it.service && a.seq == it.seq && a.link == null }?.link = it.authorizeUrl
                    view = view.copy(ask = SvcAsk(it.service, it.name, it.seq, it.host, it.authorizeUrl))
                    holdView()
                }
                "$service $seq held"
            }
        }
        page?.let { ended(it) }
        return out
    }

    fun resume(q: SvcPkce, answer: (Answer) -> Unit): String? = synchronized(lock) {
        if (declines[q.service]?.let { hits(it, q) } == true || q.service to q.seq in gone) return null
        resumed(q, answer)
    }

    private fun resumed(q: SvcPkce, answer: (Answer) -> Unit): String? {
        val k = kept
        if (k != null && k.q == q) {
            k.session = session
            answer(Answer.Redirect(k.redirect))
            return "${q.service} ${q.seq} kept"
        }
        val d = open?.takeIf { it.answer == null && it.q == q } ?: return null
        if (d.closed) {
            finish(d, ENDED)
            answer(CANCELLED)
            return "${q.service} ${q.seq} closed"
        }
        d.answer = answer
        holdView()
        return "${q.service} ${q.seq} attached"
    }

    private fun hits(d: Decline, q: SvcPkce): Boolean {
        if (d.device != device) return false
        return d.q?.let { it == q } ?: when {
            d.session == session -> d.seq == q.seq
            else -> !d.used && (d.seq == null || d.seq == q.seq) && SystemClock.elapsedRealtime() - liveAt <= REASK_MS
        }
    }

    private fun declined(q: SvcPkce): Boolean {
        val d = declines[q.service] ?: return false
        if (d.device != device) return false
        val hit = hits(d, q)
        if (d.q == null && d.session != session) d.used = true
        if (hit) d.q = q
        return hit
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
        if (arm?.let { it.service == service && it.seq == seq } == true) endArm()
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
        o.answer?.invoke(CANCELLED)
        o.page?.let { ended(it) }
    }

    fun ended(e: SvcLoginEnd): Boolean {
        val done = e.result == Enums.SvcLoginResult.done
        val o = synchronized(lock) {
            over(e)
            val k = kept?.takeIf { it.q.service == e.service && it.q.seq == e.seq }
            if (k != null) dropKept()
            val o = open?.takeIf { it.service == e.service && it.seq == e.seq }
            if (o == null) {
                if (k == null && replied != e.service to e.seq) {
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

    fun over(e: SvcLoginEnd) = synchronized(lock) {
        if (arm?.let { it.service == e.service && it.seq == e.seq } == true) endArm()
        declines[e.service]?.takeIf { it.seq == e.seq }?.let { dropDecline(it) }
    }

    fun dropped(keep: Boolean = false) {
        val o = synchronized(lock) {
            replied = null
            gone.clear()
            pending.clear()
            reopen = null
            reask?.cancel(false)
            if (!keep || arm?.seq == null) endArm()
            val hold = keep && holds
            if (!hold) dropKept()
            val o = open
            if (hold && o != null && o.token != null) {
                o.answer = null
                holdView()
                null
            } else {
                o?.also { finish(it, if (it.token != null) ENDED else null) }
            }
        } ?: return
        o.page?.let { ended(it) }
    }

    private fun timedOut(o: Open) {
        synchronized(lock) {
            if (open !== o) return
            finish(o, if (o.token != null || o.answer == null) ENDED else null)
        }
        o.answer?.invoke(CANCELLED)
        o.page?.let { ended(it) }
    }

    private fun unstarted(o: Open, token: String) {
        synchronized(lock) {
            if (open !== o || o.token != token || o.page != null) return
            finish(o, ENDED)
        }
        o.answer?.invoke(CANCELLED)
    }

    private fun finish(o: Open, line: String?) {
        open = null
        o.timer?.cancel(false)
        o.start?.cancel(false)
        view = view.copy(ask = null)
        holdView()
        line?.let { line(o.name, it) }
    }

    private fun keep(o: Open, redirect: String) {
        dropKept()
        val k = Kept(o.q, o.name, redirect, session)
        kept = k
        k.timer = later(maxOf(0L, o.at + Proto.svcPkceMs - SystemClock.elapsedRealtime())) { synchronized(lock) { if (kept === k) dropKept() } }
        holdView()
    }

    private fun dropKept() {
        kept?.timer?.cancel(false)
        kept = null
        holdView()
    }

    private fun dropDecline(d: Decline) {
        d.timer?.cancel(false)
        if (declines[d.service] === d) declines.remove(d.service)
    }

    private fun endArm() {
        arm?.timer?.cancel(false)
        arm = null
    }

    private fun holdView() {
        val h = pending + listOfNotNull(kept?.q?.service, open?.takeIf { it.answer == null }?.service)
        if (h != view.held) view = view.copy(held = h)
    }

    fun started(service: String) = synchronized(lock) {
        declines[service]?.let { dropDecline(it) }
        endArm()
        val a = Arm(service)
        a.timer = later(ARM_MS) { armOut(a) }
        arm = a
        pending += service
        holdView()
    }

    private fun armOut(a: Arm) {
        synchronized(lock) {
            if (arm !== a) return
            arm = null
            if (a.seq != null && !a.noticed) line(nameOf(a.service), NO_REPLY)
        }
    }

    fun startReply(service: String, r: Session.Result, link: (Long) -> String?): String? = synchronized(lock) {
        pending -= service
        holdView()
        val seq = (r as? Session.Result.Ok)?.let { SvcLoginStartReply.from(it.body).seq }
        val a = arm?.takeIf { it.service == service }
        if (seq == null) {
            if (a != null) endArm()
            return null
        }
        if (a == null) {
            declines[service]?.takeIf { it.session == session && it.seq == null }?.seq = seq
            return null
        }
        a.seq = seq
        a.link = link(seq)
        val o = open?.takeIf { it.service == service && it.seq == seq } ?: return null
        if (a.link == null) a.link = o.authorizeUrl
        val t = o.token
        if (t != null && o.answer != null) {
            endArm()
            return t
        }
        view = view.copy(ask = view.ask?.copy(armed = true))
        null
    }

    fun armed(service: String, seq: Long, link: String): Boolean = synchronized(lock) { arm?.let { it.service == service && it.seq == seq && it.link != null && it.link == link } == true }

    fun armTaken(service: String, seq: Long, link: String) = synchronized(lock) { if (armed(service, seq, link)) endArm() }

    fun armNoticed(service: String, seq: Long, link: String) = synchronized(lock) { if (armed(service, seq, link)) arm?.noticed = true }

    fun attached(service: String): String? = synchronized(lock) { open?.takeIf { it.service == service && it.answer != null }?.token }

    fun decline(service: String) = synchronized(lock) {
        val a = arm?.takeIf { it.service == service }
        if (a != null) endArm()
        val d = Decline(service, device, a?.seq, session)
        declines.put(service, d)?.timer?.cancel(false)
        d.timer = later(Proto.svcPkceMs.toLong()) { synchronized(lock) { dropDecline(d) } }
    }

    fun loginSeen(l: SvcLogin) {
        val service = l.service ?: return
        val page = synchronized(lock) {
            arm?.takeIf { it.service == service && it.seq == l.seq && it.link == null }?.link = l.uri
            val d = open?.takeIf { it.answer == null && it.service == service } ?: return
            finish(d, ENDED)
            d.page
        }
        page?.let { ended(it) }
    }

    fun state(accounts: Map<String, SvcAccount>?) {
        val a = accounts ?: return
        val page = synchronized(lock) {
            if (arm?.let { a[it.service]?.signedIn == true } == true) endArm()
            declines.values.filter { a[it.service]?.signedIn == true }.forEach { dropDecline(it) }
            kept?.let { k ->
                val on = a[k.q.service]?.signedIn
                if (on == true || on == false && k.session < session && SystemClock.elapsedRealtime() - liveAt >= REASK_MS) dropKept()
            }
            reopen?.takeIf { it in a }?.let {
                reopen = null
                view = view.copy(reopen = it)
            }
            val d = open?.takeIf { it.answer == null && a[it.service]?.signedIn == true } ?: return
            finish(d, ENDED)
            d.page
        }
        page?.let { ended(it) }
    }

    fun leaving(service: String) = synchronized(lock) {
        pending += service
        holdView()
    }

    fun logoutReply(service: String, r: Session.Result, signedOut: Boolean): String? {
        synchronized(lock) {
            pending -= service
            holdView()
            if (r is Session.Result.Failed && r.error.code == Err.staleRev && r.error.text == CHANGED) reopen = service
        }
        return logoutLine(r, signedOut)
    }

    fun reopened() = synchronized(lock) { view = view.copy(reopen = null) }

    private fun reaskOut(s: Int) {
        val page = synchronized(lock) {
            if (s != session) return
            if (kept?.let { it.session < s } == true) dropKept()
            val d = open?.takeIf { it.answer == null } ?: return
            finish(d, ENDED)
            d.page
        }
        page?.let { ended(it) }
    }

    fun lineShown(id: Int) = synchronized(lock) {
        if (view.line?.id == id) view = view.copy(line = null)
    }

    private fun line(title: String, text: String) {
        view = view.copy(line = SvcLine(title, text, ++lines))
    }

    private var names = emptyMap<String, String>()

    fun hello(h: HelloReply?, spec: String? = null) {
        names = h?.services?.mapNotNull { s -> s.id?.let { it to (s.name?.takeIf { n -> n.isNotBlank() } ?: it) } }?.toMap().orEmpty()
        val page = synchronized(lock) {
            session++
            liveAt = SystemClock.elapsedRealtime()
            holds = h?.has("svcAccount") == true
            reopen = null
            reask?.cancel(false)
            val other = spec != device
            device = spec
            if (other) {
                declines.values.toList().forEach { dropDecline(it) }
                dropKept()
                endArm()
            }
            val d = open?.takeIf { it.answer == null }
            if (other && d != null) {
                finish(d, ENDED)
                d.page
            } else {
                val s = session
                if (kept != null || d != null) reask = later(REASK_MS) { reaskOut(s) }
                null
            }
        }
        page?.let { ended(it) }
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
                    if (holds) keep(o, url)
                    finish(o, if (o.answer == null) WAITING else null)
                    o to Answer.Redirect(url)
                }
                PkcePolicy.Seen.TooLong -> {
                    finish(o, TOO_LONG)
                    o to CANCELLED
                }
                else -> return false
            }
        }
        o.answer?.invoke(a)
        return true
    }

    internal fun cancelled(token: String?, line: String? = null): Boolean {
        val o = synchronized(lock) {
            val o = held(token) ?: return false
            if (o.answer == null) {
                if (o.closed) return false
                o.closed = true
                return true
            }
            finish(o, line)
            o
        }
        o.answer?.invoke(CANCELLED)
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

    fun start(service: String, request: (Int, ByteArray, Long, (Session.Result) -> Unit) -> Unit, join: (Long) -> String?, front: (String) -> Unit, log: (String) -> Unit, done: (String?) -> Unit) {
        attached(service)?.let {
            front(it)
            return done(null)
        }
        started(service)
        request(Msg.SVC_LOGIN_START, Req.svcLoginStart(service), Proto.svcReplyMs.toLong()) { r ->
            startReply(service, r, join)?.let(front)
            log("svc start $service -> ${result(r)}${(r as? Session.Result.Ok)?.let { " ${SvcLoginStartReply.from(it.body).seq}" }.orEmpty()}")
            done(startLine(r))
        }
    }

    fun logout(service: String, account: String, downloads: Long?, request: (Int, ByteArray, Long, (Session.Result) -> Unit) -> Unit, signedOut: () -> Boolean, log: (String) -> Unit, done: (String?) -> Unit) {
        leaving(service)
        request(Msg.SVC_LOGOUT, Req.svcLogout(service, account, downloads), Proto.svcReplyMs.toLong()) { r ->
            val line = logoutReply(service, r, signedOut())
            log("svc logout $service -> ${result(r)}")
            done(line)
        }
    }

    private fun result(r: Session.Result): String = when (r) {
        is Session.Result.Ok -> "ok"
        is Session.Result.Failed -> "error ${r.error.code}"
        else -> r.javaClass.simpleName.lowercase()
    }

    fun startLine(r: Session.Result): String? = when (r) {
        is Session.Result.Ok, Session.Result.Closed -> null
        is Session.Result.Failed -> when (r.error.code) {
            Err.exists -> SIGNED_IN
            Err.off -> OFF
            Err.notFound -> NOT_INSTALLED
            Err.noCard -> NO_CARD
            Err.cancelled -> ENDED
            Err.refused -> when (r.error.text) {
                "no keys" -> NO_KEYS
                TIMEOUT -> NO_REPLY
                "retry later" -> BUSY
                else -> REFUSED
            }
            Err.busy -> if (r.error.text == TIMEOUT) NO_REPLY else BUSY
            else -> REFUSED
        }
        else -> NO_REPLY
    }

    fun logoutLine(r: Session.Result, signedOut: Boolean): String? = when (r) {
        is Session.Result.Ok -> SIGNED_OUT
        Session.Result.Closed -> null
        is Session.Result.Failed -> when (r.error.code) {
            Err.staleRev -> when (r.error.text) {
                "stale" -> ACCOUNT_CHANGED
                CHANGED -> null
                else -> REFUSED
            }
            Err.off -> OFF
            Err.notFound -> NOT_INSTALLED
            Err.noCard -> NO_CARD
            Err.busy -> when (r.error.text) {
                TIMEOUT -> if (signedOut) SIGNED_OUT else NO_REPLY
                "downloads not counted" -> NOT_COUNTED
                else -> BUSY
            }
            else -> REFUSED
        }
        else -> NO_REPLY
    }

    fun keysLine(r: Session.Result): String = when (r) {
        is Session.Result.Ok -> KEYS_SENT
        is Session.Result.Failed -> when (r.error.code) {
            Err.off -> OFF
            Err.badValue -> BAD_KEY
            Err.busy -> BUSY
            Err.notFound -> NOT_INSTALLED
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
        kept?.timer?.cancel(false)
        kept = null
        arm?.timer?.cancel(false)
        arm = null
        declines.values.forEach { it.timer?.cancel(false) }
        declines.clear()
        pending.clear()
        reopen = null
        reask?.cancel(false)
        reask = null
        session = 0
        device = null
        holds = false
        liveAt = 0L
        names = emptyMap()
        view = SvcView()
        opens = SvcOpens()
        stored = false
        tap = 0L
        watch = { b, died -> b.linkToDeath({ died() }, 0) }
        later = { ms, r -> timer.schedule(r, ms, TimeUnit.MILLISECONDS) }
    }
}
