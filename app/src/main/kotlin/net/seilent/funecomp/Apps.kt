package net.seilent.funecomp

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.seilent.funecomp.link.AppPush
import net.seilent.funecomp.proto.AppBeginReply
import net.seilent.funecomp.proto.AppState
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.state.CardView
import net.seilent.funecomp.state.PushGate
import net.seilent.funecomp.ui.AppsView
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.byeText
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Apps(
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val retryMs: Long = BUSY_RETRY_MS,
    private val busyBoundMs: Long = BUSY_BOUND_MS,
    private val resultMs: Long = RESULT_MS,
    private val liveMs: Long = LIVE_MS,
    private val later: ((Long, () -> Unit) -> Unit)? = null,
) {
    class Statement(val id: String, val version: Long)

    class Source(val name: String, val size: Long, val sha: ByteArray, val id: String, val version: Long, val open: (Long) -> InputStream)

    private class Push(val src: Source, val dap: String) {
        var stage: Long? = null
        var ended = false
        var replied = false
        var waitFrom: Long? = null
        var liveAt: Long? = null
        var mismatch = 0
        var last = -1L
        var part = 0L
        var busySince: Long? = null
        var cardWait: Int? = null
        var held: String? = null
        var line = ""
        var cardHeld = false
        @Volatile var cancelled = false
    }

    private val exec = Executors.newSingleThreadScheduledExecutor { Thread(it, THREAD).apply { isDaemon = true } }
    private var prefs: SharedPreferences? = null
    private var picked: Source? = null
    @Volatile private var push: Push? = null
    private var running = false
    private var dap: String? = null
    private var linked = false
    private val taken = ArrayList<String>()

    @Volatile var bulk = false
        private set

    var view by mutableStateOf(AppsView())
        private set

    var onToast: (String, String) -> Unit = { _, _ -> }

    @Volatile internal var lives = 0
        private set

    @Volatile internal var states = 0
        private set

    private fun set(f: (AppsView) -> AppsView) = synchronized(this) { view = f(view) }

    private fun schedule(ms: Long, f: () -> Unit) {
        val l = later
        if (l != null) l(ms) { exec.execute(f) } else exec.schedule(f, ms, TimeUnit.MILLISECONDS)
    }

    internal fun sync() {
        check(Thread.currentThread().name != THREAD) { "sync on the $THREAD thread" }
        exec.submit {}.get()
    }

    internal fun close() {
        exec.shutdownNow()
    }

    fun init(c: Context) {
        prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { p ->
            synchronized(taken) {
                taken.clear()
                taken += p.getString(TAKEN, "").orEmpty().split('\n').filter { it.isNotEmpty() }
            }
        }
    }

    private fun key(dap: String, stage: Long) = "$dap|$stage"

    private fun isTaken(dap: String, stage: Long) = synchronized(taken) { key(dap, stage) in taken }

    private fun take(dap: String, stage: Long?) {
        stage ?: return
        synchronized(taken) {
            taken.remove(key(dap, stage))
            taken += key(dap, stage)
            while (taken.size > TAKEN_MAX) taken.removeAt(0)
            prefs?.edit()?.putString(TAKEN, taken.joinToString("\n"))?.apply()
        }
    }

    private fun untake(dap: String, stage: Long?) {
        stage ?: return
        synchronized(taken) {
            if (taken.remove(key(dap, stage))) prefs?.edit()?.putString(TAKEN, taken.joinToString("\n"))?.apply()
        }
    }

    fun pick(src: Source) = exec.execute {
        picked = src
        set { it.copy(picked = "${src.id} ${src.name}", info = "") }
    }

    fun read(c: Context, uri: Uri, name: String) {
        set { if (push == null) it.copy(send = CHECKING_FILE, info = "") else it }
        exec.execute {
            val r = try {
                val head = c.contentResolver.openInputStream(uri)?.use { head(it) } ?: throw IOException("no stream")
                val (size, sha) = c.contentResolver.openInputStream(uri)?.use { digest(it) } ?: throw IOException("no stream")
                source(name, size, sha, head) { off -> (c.contentResolver.openInputStream(uri) ?: throw IOException("no stream")).also { skip(it, off) } }
            } catch (e: Exception) {
                Log.w(Remote.TAG, "app read: $e")
                null
            }
            set { if (push == null) it.copy(send = "") else it }
            if (r == null) set { it.copy(info = BAD_PACKAGE) } else pick(r)
        }
    }

    fun send() = exec.execute {
        val src = picked ?: return@execute
        val d = dap
        if (d == null) {
            set { it.copy(info = byeText(null)) }
            return@execute
        }
        if (Remote.phase == Phase.LIVE && !Remote.device.has(APP_BIT)) {
            set { it.copy(info = NO_INSTALLER) }
            return@execute
        }
        drop()
        push = Push(src, d)
        attempt()
    }

    fun hello(board: String?, spec: String?) = exec.execute {
        val d = "$spec|$board"
        dap = d
        linked = true
        lives++
        val p = push
        if (p != null && p.dap != d) {
            drop()
            set { it.copy(send = "", info = OTHER_DAP) }
            return@execute
        }
        if (!Remote.device.has(APP_BIT)) {
            drop()
            set { it.copy(send = "", info = NO_INSTALLER) }
            return@execute
        }
        if (p == null) {
            if (view.info == NO_INSTALLER) set { it.copy(info = "") }
            return@execute
        }
        p.busySince = null
        p.held = null
        p.cardWait = null
        if (p.ended) {
            p.liveAt = now()
            if (p.replied) set { it.copy(info = p.line) }
            schedule(liveMs) { afterLive(p) }
        } else attempt()
    }

    fun closed(bye: Int?) = exec.execute {
        linked = false
        val p = push ?: return@execute
        when (bye) {
            Enums.ByeReason.refused, Enums.ByeReason.version, Enums.ByeReason.streaming -> {
                drop()
                set { it.copy(send = "", info = if (bye == Enums.ByeReason.streaming) STREAMING else byeText(bye)) }
                return@execute
            }
        }
        p.held = byeText(bye)
        if (p.ended && p.waitFrom == null && !p.cardHeld) {
            p.waitFrom = now()
            schedule(resultMs) { bound(p) }
        }
        set { it.copy(info = p.held.orEmpty()) }
    }

    fun card() = exec.execute {
        val p = push ?: return@execute
        if (p.replied || p.cancelled) return@execute
        val w = p.cardWait
        if (w != null) {
            if (Remote.device.card.epoch <= w) return@execute
            p.cardWait = null
            attempt()
        } else if (p.held != null && linked && refusal() == null) attempt()
    }

    fun state(s: AppState) = exec.execute {
        states++
        val d = dap ?: return@execute
        val stage = s.stage ?: return@execute
        if (isTaken(d, stage)) return@execute
        take(d, stage)
        val line = result(s)
        val p = push
        if (p != null && p.dap == d && p.stage == stage) {
            drop()
            set { it.copy(send = "", info = line) }
        } else onToast(s.id.orEmpty(), line)
    }

    fun stop() {
        val p = push
        p?.cancelled = true
        exec.execute {
            if (push !== p) return@execute
            drop()
            set { it.copy(send = "", info = "") }
        }
    }

    private fun drop() {
        push = null
        unbulk()
    }

    private fun unbulk() {
        if (!bulk) return
        bulk = false
        Remote.sourcesChanged()
    }

    private fun refusal(): String? {
        if (Remote.phase != Phase.LIVE) return byeText(null)
        val d = Remote.device
        if (!d.has(APP_BIT)) return NO_INSTALLER
        if (!linked) return byeText(null)
        val st = d.playback.state
        if (st.output != null && st.output != Enums.Output.wired && !PushGate.receiver(d)) return BLUETOOTH
        if (PushGate.card(d)) return CardView.REFUSED
        if (Firmwares.sending) return FIRMWARE
        return null
    }

    private fun hold(p: Push, why: String) {
        p.held = why
        if (why == CardView.REFUSED && p.cardWait == null) {
            p.cardWait = Remote.device.card.epoch
            p.cardHeld = true
            if (!p.replied) p.waitFrom = null
        }
        set { it.copy(info = why) }
        if (linked && p.cardWait == null) schedule(Proto.pushGateMs.toLong()) { retry(p) }
    }

    private fun retry(p: Push) {
        if (push !== p || running || p.replied || p.cancelled || p.cardWait != null || p.held == null || !linked) return
        val why = refusal()
        if (why == null) attempt() else hold(p, why)
    }

    private fun attempt() {
        val p = push ?: return
        if (running || p.cancelled) return
        refusal()?.let { why ->
            if (why == NO_INSTALLER) return end(p, NO_INSTALLER)
            return hold(p, if (linked) why else p.held ?: why)
        }
        val s = Remote.session() ?: return hold(p, byeText(null))
        p.held = null
        p.cardWait = null
        running = true
        bulk = true
        val r = try {
            AppPush(s, p.src.id, p.src.version, p.src.size, p.src.sha, p.src.open, gate = { push === p && !p.cancelled && refusal() == null }, progress = { at ->
                val t = "app ${if (p.src.size > 0) at * 100 / p.src.size else 0}%"
                if (t != view.send) set { it.copy(send = t, info = "") }
            }, begun = { reply -> begun(p, reply) }).run()
        } catch (e: Exception) {
            Log.w(Remote.TAG, "app send: $e")
            null
        } finally {
            running = false
        }
        Log.i(Remote.TAG, "app send ${p.src.name} -> ${r?.javaClass?.simpleName?.lowercase() ?: "read failed"}")
        if (push !== p || p.cancelled) return
        if (r == null) return end(p, SEND_FAILED)
        if (r is AppPush.Outcome.Aborted) {
            p.part = r.abort.offset
            p.ended = false
        } else if (r.ended) {
            p.ended = true
            p.part = p.src.size
        }
        outcome(p, r)
    }

    private fun begun(p: Push, reply: AppBeginReply) {
        p.cardHeld = false
        if (reply.offset == 0L && (p.last > 0 || p.part > 0)) {
            p.mismatch = 0
            p.ended = false
            p.waitFrom = null
            p.liveAt = null
        }
        if (reply.offset > p.last) p.mismatch = 0
        p.last = reply.offset
        p.part = reply.offset
        p.stage = reply.stage
        untake(p.dap, reply.stage)
    }

    private fun outcome(p: Push, r: AppPush.Outcome) {
        when (r) {
            AppPush.Outcome.Asked -> wait(p, CONFIRM)
            AppPush.Outcome.Checking -> wait(p, CHECKING)
            is AppPush.Outcome.Retry -> busy(p)
            is AppPush.Outcome.Aborted -> when (r.abort.code) {
                Enums.AbortCode.offsetMismatch -> if (p.mismatch++ == 0) exec.execute { attempt() } else end(p, SEND_FAILED)
                Enums.AbortCode.busy -> busy(p)
                Enums.AbortCode.noCard -> cardWait(p)
                Enums.AbortCode.noSpace -> end(p, NO_ROOM)
                else -> end(p, SEND_FAILED)
            }
            is AppPush.Outcome.Failed -> failed(p, r)
            is AppPush.Outcome.Paused -> {
                if (r.ended && p.waitFrom == null) {
                    p.waitFrom = now()
                    schedule(resultMs) { bound(p) }
                }
                hold(p, p.held ?: refusal() ?: byeText(null))
            }
        }
    }

    private fun failed(p: Push, r: AppPush.Outcome.Failed) {
        val e = r.error ?: return end(p, NO_INSTALLER)
        when (e.code) {
            Err.noCard -> cardWait(p)
            Err.unknown -> end(p, NO_INSTALLER)
            else -> end(p, endLine(e.code, e.text), taken = e.code == Err.badSignature || r.ended)
        }
    }

    private fun end(p: Push, line: String, taken: Boolean = false) {
        if (taken) take(p.dap, p.stage)
        drop()
        set { it.copy(send = "", info = line) }
    }

    private fun cardWait(p: Push) {
        p.cardWait = Remote.device.card.epoch
        p.cardHeld = true
        p.waitFrom = null
        set { it.copy(info = CardView.REFUSED) }
    }

    private fun busy(p: Push) {
        val t = now()
        val since = p.busySince ?: t.also { p.busySince = it }
        if (t - since >= busyBoundMs) return end(p, BUSY_STOP)
        set { it.copy(info = DAP_BUSY) }
        schedule(retryMs) { if (push === p) attempt() }
    }

    private fun wait(p: Push, line: String) {
        p.replied = true
        p.cardHeld = false
        p.line = line
        p.waitFrom = now()
        unbulk()
        set { it.copy(send = "", info = line) }
        schedule(resultMs) { bound(p) }
    }

    private fun bound(p: Push) {
        if (push !== p) return
        val from = p.waitFrom ?: return
        if (now() - from < resultMs) return
        if (!linked) return
        val live = p.liveAt
        if (live != null && now() - live < liveMs) return
        end(p, NO_ANSWER)
    }

    private fun afterLive(p: Push) {
        if (push !== p || !linked) return
        val from = p.waitFrom
        if (!p.replied) {
            if (from != null && now() - from >= resultMs) return end(p, NO_ANSWER)
            attempt()
            return
        }
        if (from != null && now() - from >= resultMs) end(p, NO_ANSWER)
    }

    companion object {
        const val BUSY_RETRY_MS = 5_000L
        const val BUSY_BOUND_MS = 120_000L
        const val RESULT_MS = 150_000L
        const val LIVE_MS = 15_000L
        const val BUNDLE_MAX = 8667136L
        const val TAKEN_MAX = 16
        const val APP_BIT = "appPkg"
        const val CHECKING_FILE = "checking file"
        const val CONFIRM = "confirm on the dap"
        const val CHECKING = "checking on the dap"
        const val INSTALLED = "installed"
        const val DECLINED = "declined on the dap"
        const val EXPIRED = "not confirmed in time"
        const val PENDING = "installs after this track"
        const val NO_ANSWER = "no answer from the dap"
        const val SIGNATURE = "signature not accepted"
        const val OLDER = "older than installed"
        const val NO_ROOM = "no room"
        const val BOARD = "wrong board"
        const val DAMAGED = "damaged package"
        const val NEWER = "needs newer firmware"
        const val SEND_FAILED = "send failed"
        const val BLUETOOTH = "dap on bluetooth output"
        const val BUSY_STOP = "dap busy, send again later"
        const val DAP_BUSY = "dap busy"
        const val OTHER_DAP = "sent to another dap"
        const val STREAMING = "streaming on the dap"
        const val NO_INSTALLER = "dap has no app installer"
        const val BAD_PACKAGE = "not an app package"
        const val FIRMWARE = "firmware sending"
        const val SENDING = "app sending"
        private const val THREAD = "apps"
        private const val PREFS = "apps"
        private const val TAKEN = "taken"
        private const val STATEMENT_HEAD = 64
        private val MAGIC = "FNSVCST2".toByteArray()
        private val ID = Regex("^[a-z0-9]{1,12}$")

        fun endLine(code: Int, text: String?) = when (code) {
            Err.badSignature -> SIGNATURE
            Err.noSpace -> NO_ROOM
            Err.refused -> word(text)
            else -> SEND_FAILED
        }

        fun word(text: String?) = when (text) {
            "signature" -> SIGNATURE
            "older" -> OLDER
            "no room" -> NO_ROOM
            "board" -> BOARD
            "layout" -> DAMAGED
            "fit" -> NEWER
            else -> SEND_FAILED
        }

        fun result(s: AppState) = when (s.state) {
            Enums.AppResult.installed -> INSTALLED
            Enums.AppResult.declined -> if (s.text == "expired") EXPIRED else DECLINED
            Enums.AppResult.updatePending -> PENDING
            else -> word(s.text)
        }

        fun statement(head: ByteArray): Statement? {
            if (head.size < MAGIC.size + 9 || !head.copyOf(MAGIC.size).contentEquals(MAGIC)) return null
            var i = MAGIC.size + 8
            val n = head[i++].toInt() and 0xff
            if (n !in 1..12 || i + n + 1 > head.size) return null
            val id = String(head, i, n, Charsets.US_ASCII)
            if (!ID.matches(id)) return null
            i += n
            val boards = head[i++].toInt() and 0xff
            if (boards !in 1..3 || i + boards + 4 > head.size) return null
            i += boards
            val version = (0..3).fold(0L) { v, k -> v or ((head[i + k].toLong() and 0xff) shl (8 * k)) }
            return Statement(id, version)
        }

        fun source(name: String, size: Long, sha: ByteArray, head: ByteArray, open: (Long) -> InputStream): Source? {
            if (size <= 0 || size > BUNDLE_MAX) return null
            val st = statement(head) ?: return null
            return Source(name, size, sha, st.id, st.version, open)
        }

        fun sha256(input: InputStream): ByteArray = digest(input).second

        private fun digest(input: InputStream): Pair<Long, ByteArray> {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(1 shl 16)
            var size = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                size += n
            }
            return size to md.digest()
        }

        fun head(input: InputStream): ByteArray {
            val b = ByteArray(STATEMENT_HEAD)
            var n = 0
            while (n < b.size) {
                val r = input.read(b, n, b.size - n)
                if (r < 0) break
                n += r
            }
            return b.copyOf(n)
        }

        private fun skip(input: InputStream, n: Long) {
            var left = n
            while (left > 0) {
                val s = input.skip(left)
                if (s <= 0) {
                    if (input.read() < 0) throw IOException("short")
                    left--
                } else left -= s
            }
        }
    }
}
