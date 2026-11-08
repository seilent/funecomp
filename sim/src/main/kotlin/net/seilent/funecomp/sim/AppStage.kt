package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.AppAbort
import net.seilent.funecomp.proto.AppBegin
import net.seilent.funecomp.proto.AppBeginReply
import net.seilent.funecomp.proto.AppData
import net.seilent.funecomp.proto.AppEnd
import net.seilent.funecomp.proto.AppState
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

const val APP_BUNDLE_MAX = 8667136L
const val APP_RESULTS_MAX = 16
val APP_CUTS = setOf("stop", "step", "last")
val APP_STEPS = APP_CUTS + setOf("busy", "zero", "endcard")

fun appCutCode(step: String) = Enums.AbortCode.names.entries.firstOrNull { it.value == step.substringAfter('@', "offsetMismatch") }?.key
private val APP_ID = Regex("^[a-z0-9]{1,12}$")

class AppStage(
    private val opts: Options,
    private val now: () -> Long,
    private val later: (Long, () -> Unit) -> Unit,
    private val sessions: () -> Map<Out, Long>,
    private val send: (Out, Int, ByteArray) -> Unit,
) {
    private val faults = opts.faults

    private class Claim(val id: String, val version: Long, val size: Long, val sha: ByteArray) {
        fun same(o: Claim?) = o != null && id == o.id && version == o.version && size == o.size && sha.contentEquals(o.sha)
    }

    private class Kept(val phone: String, val state: AppState)

    private var claim: Claim? = null
    private var part = ByteArrayOutputStream()
    private var owner: Out? = null
    private var open = false
    private var opening = false
    private var closedUntil = 0L
    private var serial = 0L
    private var stage = 0L
    private var checking = false
    @Volatile var asking = false
        private set
    private var aborted = false
    private var endLost = false
    private var deferred = false
    private var begins = 0
    private var stopAt: Long? = null
    private var stopCode = Enums.AbortCode.offsetMismatch
    private var endNoCard = false
    private var late: Pair<Out, AppAbort>? = null
    private var endBusy = false
    private val kept = CopyOnWriteArrayList<Kept>()
    private val delivered = CopyOnWriteArrayList<Kept>()

    @Volatile var linkedRuns = 0
        private set

    val partSize: Long get() = part.size().toLong()

    fun kept(phone: String) = kept.count { it.phone == phone }

    private fun appBit(features: Long) = features and Groups.mask("appPkg") != 0L

    private fun phone(out: Out) = out.phone

    private fun close(lag: Long = 0) {
        open = false
        opening = false
        owner = null
        if (lag > 0) closedUntil = now() + lag
    }

    fun begin(b: Node, out: Out): Answer {
        if (!appBit(sessions()[out] ?: 0L)) return Answer.Fail(Err.unknown, "unknown")
        val c = AppBegin.from(b)
        val sha = c.sha
        if (sha == null || sha.size != 32) return Answer.Fail(Err.badValue, "")
        val id = c.id.orEmpty()
        val version = c.version ?: 0L
        val size = c.size ?: 0L
        val act = faults.appBegins.getOrNull(begins++).orEmpty()
        if ("busy" in act) return Answer.Fail(Err.busy, "")
        if (owner === out && open) close(faults.appDropMs)
        if (opening || checking || asking || faults.appStaged || now() < closedUntil || owner != null) return Answer.Fail(Err.busy, "")
        if (id.isEmpty() || version <= 0 || size <= 0) return Answer.Fail(Err.badValue, "")
        if (!APP_ID.matches(id) || id == "phone") return Answer.Fail(Err.badValue, "claim")
        if (size > APP_BUNDLE_MAX) return Answer.Fail(Err.tooBig, "size")
        val next = Claim(id, version, size, sha)
        if (!next.same(claim)) {
            claim = next
            part = ByteArrayOutputStream()
        }
        if ("zero" in act) part = ByteArrayOutputStream()
        val free = opts.cardFreeMiB * (1L shl 20)
        if (free < APP_BUNDLE_MAX || free - APP_BUNDLE_MAX < size - partSize) return Answer.Fail(Err.noSpace, "card full")
        owner = out
        opening = true
        val cut = act.firstOrNull { it.substringBefore('@') in APP_CUTS }
        stopAt = when (cut?.substringBefore('@')) {
            "stop" -> partSize
            "step" -> partSize + Proto.updChunk
            "last" -> size - 1
            else -> null
        }
        stopCode = cut?.let { appCutCode(it) } ?: Enums.AbortCode.offsetMismatch
        endNoCard = "endcard" in act
        val bound = opts.appReplyMs - 2000L
        val wait = if (deferred) 0L else faults.appOpenMs
        if (wait <= 0) return reply()
        deferred = true
        return Answer.Later(minOf(wait, bound.coerceAtLeast(0))) {
            if (owner !== out || !opening) Answer.Fail(Err.busy, "")
            else if (wait > bound) {
                close()
                Answer.Fail(Err.busy, "opening")
            } else reply()
        }
    }

    private fun reply(): Answer {
        opening = false
        open = true
        stage = ++serial
        opts.log("app begin stage=$stage offset=$partSize")
        return Answer.Ok(AppBeginReply(partSize, stage).encode())
    }

    fun data(b: Node, out: Out) {
        if (!open || owner !== out) return
        val d = AppData.from(b)
        val off = d.offset ?: return
        val bytes = d.`data` ?: return
        val c = claim ?: return
        if (bytes.isEmpty() || bytes.size > Proto.updChunk) return abort(out, Enums.AbortCode.badValue)
        if (off != partSize || off + bytes.size > c.size) return abort(out, Enums.AbortCode.offsetMismatch)
        stopAt?.let {
            if (partSize + bytes.size > it) {
                stopAt = null
                return abort(out, stopCode)
            }
        }
        faults.appAbortAt?.let { (at, code) ->
            if (!aborted && partSize + bytes.size > at) {
                aborted = true
                return abort(out, code)
            }
        }
        part.write(bytes)
    }

    private fun abort(out: Out, code: Int) {
        if (faults.appAbortLate) late = out to AppAbort(partSize, code)
        else send(out, Msg.APP_ABORT, AppAbort(partSize, code).encode())
        close(faults.appDropMs)
    }

    fun end(b: Node, out: Out): Answer {
        if (!appBit(sessions()[out] ?: 0L)) return Answer.Fail(Err.unknown, "unknown")
        val sha = AppEnd.from(b).sha
        if (sha == null || sha.size != 32) return Answer.Fail(Err.badValue, "")
        if (!open || owner !== out) {
            late?.takeIf { it.first === out }?.let {
                late = null
                send(out, Msg.APP_ABORT, it.second.encode())
            }
            return Answer.Fail(Err.badValue, "no stage")
        }
        val c = claim ?: return Answer.Fail(Err.badValue, "no stage")
        if (faults.appEndBusy && !endBusy) {
            endBusy = true
            close()
            return Answer.Fail(Err.busy, "")
        }
        if (endNoCard) {
            endNoCard = false
            close()
            return Answer.Fail(Err.noCard, "no card")
        }
        if (faults.appEndLost && !endLost) {
            endLost = true
            close()
            out.drop("app end lost")
            return Answer.Later(opts.appReplyMs) { Answer.Fail(Err.busy, "lost") }
        }
        if (!sha.contentEquals(c.sha)) return Answer.Fail(Err.badValue, "sha")
        if (partSize < c.size) return Answer.Fail(Err.badValue, "offset")
        val s = stage
        val who = phone(out)
        close()
        if (!MessageDigest.getInstance("SHA-256").digest(part.toByteArray()).contentEquals(c.sha)) {
            unlink()
            return Answer.Fail(Err.refused, "transport", then = { later(0) { deliver(who, AppState(c.id, c.version, Enums.AppResult.refused, "transport", s)) } })
        }
        faults.appEndFail?.let { (code, word) ->
            if (code == Err.noCard || code == Err.badValue) return Answer.Fail(code, word)
            unlink()
            return Answer.Fail(code, word, then = { later(0) { deliver(who, AppState(c.id, c.version, Enums.AppResult.refused, word, s)) } })
        }
        val bound = opts.appReplyMs - 2000L
        if (faults.appCheckMs > bound) {
            checking = true
            later(faults.appCheckMs) {
                checking = false
                ask(who, c, s)
            }
            return Answer.Later(bound.coerceAtLeast(0)) { Answer.Fail(Err.busy, "checking") }
        }
        asking = true
        return Answer.Ok(then = { later(0) { ask(who, c, s) } })
    }

    private fun ask(who: String, c: Claim, s: Long) {
        asking = true
        if (faults.appRestart) {
            restart()
            return
        }
        later(faults.appAskMs) {
            asking = false
            val state = when (faults.appAsk) {
                "no" -> AppState(c.id, c.version, Enums.AppResult.declined, "declined", s)
                "expire" -> AppState(c.id, c.version, Enums.AppResult.declined, "expired", s)
                else -> if (faults.appPending) AppState(c.id, c.version, Enums.AppResult.updatePending, "update pending", s)
                else AppState(c.id, c.version, Enums.AppResult.installed, "installed", s)
            }
            if (state.state != Enums.AppResult.updatePending) unlink()
            deliver(who, state)
        }
    }

    private fun unlink() {
        claim = null
        part = ByteArrayOutputStream()
    }

    fun restart() {
        close()
        claim = null
        part = ByteArrayOutputStream()
        serial = 0
        stage = 0
        checking = false
        asking = false
        kept.clear()
        delivered.clear()
        opts.log("app restart")
    }

    private fun deliver(who: String, state: AppState) {
        kept.removeAll { it.phone == who && it.state.id == state.id }
        kept += Kept(who, state)
        while (kept.size > APP_RESULTS_MAX) kept.removeAt(0)
        flush(who)
    }

    private fun flush(who: String) {
        val to = sessions().entries.firstOrNull { (o, f) -> phone(o) == who && appBit(f) }?.key ?: return
        for (k in kept.filter { it.phone == who }) {
            kept.remove(k)
            if (faults.appStateLost && !stateLost) {
                stateLost = true
                to.drop("app state lost")
                return
            }
            send(to, Msg.APP_STATE, k.state.encode())
            delivered += k
            while (delivered.size > APP_RESULTS_MAX) delivered.removeAt(0)
        }
    }

    private var stateLost = false

    fun linked(out: Out, features: Long) {
        if (!appBit(features)) return
        val who = phone(out)
        later(0) {
            linkedRuns++
            if (sessions()[out] == null) return@later
            if (faults.appRedeliver) for (k in delivered.filter { it.phone == who }) send(out, Msg.APP_STATE, k.state.encode())
            flush(who)
        }
    }

    fun unlinked(out: Out) {
        if (owner === out && !checking) close()
    }
}
