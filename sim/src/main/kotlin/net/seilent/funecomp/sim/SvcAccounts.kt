package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.SvcInfo
import net.seilent.funecomp.proto.SvcLogin
import net.seilent.funecomp.proto.SvcLoginEnd
import net.seilent.funecomp.proto.SvcLoginStartReply
import net.seilent.funecomp.proto.SvcPkce
import net.seilent.funecomp.proto.SvcState
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.proto.gen.Tags
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SvcAccounts(private val opts: Options, private val lock: Any, private val now: () -> Long) {
    private val faults = opts.faults

    private class Acct(var signedIn: Boolean?, var account: String?, var downloads: Long?, var known: Boolean, val gated: Boolean)

    class Login(val service: String, val seq: Long, val pkce: SvcPkce?, val code: SvcLogin?) {
        var redirected = false
    }

    private class Sent(val key: List<SvcInfo>, val full: List<SvcInfo>, val at: Long)

    private val accts = HashMap<String, Acct>()
    private val logins = HashMap<String, Login>()
    private val seqs = HashMap<String, Long>()
    private var boot = 0
    private val starting = HashSet<String>()
    private val leaving = HashSet<String>()
    private val keysWait = HashSet<String>()
    private val sent = HashMap<Out, Sent>()
    private val due = HashMap<Out, MutableList<Login>>()
    private val starts = ArrayDeque(faults.svcStarts)
    private val logouts = ArrayDeque(faults.svcLogouts)
    val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sim-svc").apply { isDaemon = true } }

    init {
        opts.accounts.forEach { set(it) }
    }

    fun set(a: SvcAcct) {
        accts[a.id] = Acct(a.signedIn, a.account, a.downloads, a.downloads != null, a.gated || accts[a.id]?.gated == true)
    }

    private fun acct(id: String) = accts.getOrPut(id) { Acct(null, null, null, false, false) }

    private fun card(ok: Boolean) {
        if (!ok) accts.values.filter { it.gated }.forEach { it.known = false }
    }

    private fun list(cardOk: Boolean): List<SvcInfo> {
        card(cardOk)
        return opts.services.map { s ->
            val a = s.id?.let { accts[it] }
            SvcInfo(
                s.id, s.name, s.secrets, s.pkce,
                a?.signedIn,
                a?.account?.takeIf { a.signedIn == true && it.isNotEmpty() },
                a?.takeIf { it.gated && it.known }?.downloads,
            )
        }
    }

    fun push(out: Out, cardOk: Boolean, forced: Boolean = false) {
        val full = list(cardOk)
        val key = full.map { it.copy(downloads = null) }
        val last = sent[out]
        if (!forced && last != null) {
            if (last.full == full) return
            if (last.key == key && now() - last.at < Proto.stateIntervalMs) return
        }
        sent[out] = Sent(key, full, now())
        out.push(Msg.SVC_STATE, SvcState(full).encode())
    }

    fun forget(out: Out) {
        sent.remove(out)
        due.remove(out)
    }

    fun boot() {
        boot++
        seqs.clear()
        logins.clear()
    }

    fun seen(l: SvcLogin) {
        val id = l.service ?: return
        val seq = l.seq ?: return
        logins[id] = Login(id, seq, null, l)
        seqs[id] = maxOf(seqs[id] ?: 0, seq)
    }

    fun asked(q: SvcPkce): Login? {
        val id = q.service ?: return null
        val seq = q.seq ?: return null
        return Login(id, seq, q, null).also {
            logins[id] = it
            seqs[id] = maxOf(seqs[id] ?: 0, seq)
        }
    }

    fun ended(service: String, seq: Long) {
        if (logins[service]?.seq == seq) logins.remove(service)
    }

    fun reask(out: Out) {
        if (!faults.svcReask) return
        for (l in logins.values.toList()) {
            l.code?.let { out.push(Msg.SVC_LOGIN, it.encode()) }
            if (l.pkce != null && !l.redirected) due.getOrPut(out) { ArrayList() } += l
        }
        retry(out)
    }

    fun ask(out: Out, l: Login) {
        if (l.pkce == null) return
        due.getOrPut(out) { ArrayList() } += l
        retry(out)
    }

    fun retry(out: Out) {
        val list = due[out] ?: return
        while (list.isNotEmpty()) {
            val l = list.first()
            val q = l.pkce
            if (q != null && logins[l.service] === l && !l.redirected && !out.pkce(q) { r -> synchronized(lock) { answered(out, l, r) } }) return
            list.removeAt(0)
        }
    }

    private fun answered(out: Out, l: Login, r: String) {
        if (logins[l.service] !== l) return
        when {
            r == "closed" -> {}
            r.startsWith("ok") -> {
                l.redirected = true
                val ms = faults.svcDoneMs ?: return
                timer.schedule({ synchronized(lock) { done(out, l) } }, ms, TimeUnit.MILLISECONDS)
            }
            else -> {
                logins.remove(l.service)
                out.push(Msg.SVC_LOGIN_END, SvcLoginEnd(l.service, l.seq, Enums.SvcLoginResult.cancelled).encode())
            }
        }
    }

    private fun done(out: Out, l: Login) {
        if (logins[l.service] !== l) return
        logins.remove(l.service)
        if (faults.dropBeforeEnd) return out.drop("drop-before-end")
        val a = acct(l.service)
        a.signedIn = true
        a.account = opts.accounts.firstOrNull { it.id == l.service }?.account
        out.push(Msg.SVC_LOGIN_END, SvcLoginEnd(l.service, l.seq, Enums.SvcLoginResult.done).encode())
    }

    fun keys(service: String?): Answer? {
        val ms = faults.svcKeysWaitMs ?: return null
        val id = service ?: return null
        keysWait += id
        return Answer.Later(ms) {
            keysWait -= id
            Answer.Ok()
        }
    }

    fun start(b: Node, out: Out, cardOk: Boolean, off: Boolean, state: (Out) -> ByteArray): Answer {
        card(cardOk)
        val service = b.str(Tags.SvcLoginStart.service)!!
        val svc = opts.services.firstOrNull { it.id == service } ?: return Answer.Fail(Err.notFound, "not installed")
        if (off) return Answer.Fail(Err.off, "off")
        val a = acct(service)
        if (a.signedIn == true) return Answer.Fail(Err.exists, "signed in")
        if (starting.isNotEmpty() || logins.keys.any { it != service } || service in keysWait || (a.signedIn == false && faults.svcRemoving)) return Answer.Fail(Err.busy, "busy")
        if (a.gated && !cardOk) return Answer.Fail(Err.noCard, "card not available")
        if (service in faults.svcStopped) return Answer.Fail(Err.refused, "refused")
        logins[service]?.let { l ->
            opts.log("svc start $service joins ${l.seq}")
            return Answer.Ok(SvcLoginStartReply(l.seq).encode(), then = l.code?.let { c -> { o: Out -> o.push(Msg.SVC_LOGIN, c.encode()) } })
        }
        val r = starts.removeFirstOrNull()
        starting += service
        return Answer.Later(r?.ms ?: 0) {
            starting -= service
            if (r?.code != null) {
                opts.log("svc start $service -> ${r.code} ${r.text.orEmpty()}")
                return@Later Answer.Fail(r.code, r.text.orEmpty())
            }
            val seq = r?.seq ?: ((seqs[service] ?: 0) + 1)
            seqs[service] = maxOf(seqs[service] ?: 0, seq)
            val l = if (svc.pkce == true) {
                Login(service, seq, SvcPkce(service, "https://login.${Faults.SVC_HOST}/auth?state=b${boot}s$seq", "login.${Faults.SVC_HOST}", "https://${Faults.SVC_HOST}/cb", seq), null)
            } else {
                Login(service, seq, null, SvcLogin(service, (if (faults.svcLoginBare) "" else "https://") + "link.${Faults.SVC_HOST}/C$seq", "link.${Faults.SVC_HOST}", "C$seq", 300, seq))
            }
            logins[service] = l
            opts.log("svc start $service -> ok $seq")
            Answer.Ok(SvcLoginStartReply(seq).encode(), batch = faults.svcStartGap, then = { o ->
                if (faults.svcStartGap) o.push(Msg.STATE, state(o))
                if (l.code != null) o.push(Msg.SVC_LOGIN, l.code.encode()) else ask(o, l)
            })
        }
    }

    fun logout(b: Node, out: Out, cardOk: Boolean, off: Boolean): Answer {
        card(cardOk)
        val service = b.str(Tags.SvcLogout.service)!!
        val sentCount = b.long(Tags.SvcLogout.downloads)
        opts.log("svc logout $service downloads=$sentCount")
        if (opts.services.none { it.id == service }) return Answer.Fail(Err.notFound, "not installed")
        if (off) return Answer.Fail(Err.off, "off")
        val a = acct(service)
        val want = b.str(Tags.SvcLogout.account).orEmpty()
        val cur = if (a.signedIn == true) a.account.orEmpty() else ""
        if (want != cur) return Answer.Fail(Err.staleRev, "stale")
        if (a.signedIn == false) return Answer.Ok()
        if (service in leaving || a.signedIn == null) return Answer.Fail(Err.busy, "busy")
        if (a.gated) {
            if (!cardOk) return Answer.Fail(Err.noCard, "card not available")
            if (!a.known) return Answer.Fail(Err.busy, "downloads not counted")
            val have = a.downloads ?: 0
            if ((sentCount != null && sentCount != have) || (sentCount == null && have > 0)) {
                return Answer.Fail(Err.staleRev, "downloads changed", then = { o -> synchronized(lock) { push(o, cardOk, forced = true) } })
            }
        }
        if (service in faults.svcStopped) return Answer.Fail(Err.refused, "stopped")
        val r = logouts.removeFirstOrNull()
        leaving += service
        return Answer.Later(r?.ms ?: 0) {
            leaving -= service
            if (r?.code != null && !(r.code == Err.busy && r.text == "timeout")) return@Later Answer.Fail(r.code, r.text.orEmpty())
            a.signedIn = false
            a.account = null
            if (a.gated) a.downloads = 0
            if (r?.code != null) return@Later Answer.Fail(r.code, r.text.orEmpty(), before = { o -> synchronized(lock) { push(o, cardOk) } })
            Answer.Ok()
        }
    }
}
