package net.seilent.funecomp.state

import net.seilent.funecomp.proto.Eq
import net.seilent.funecomp.proto.EqName
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.Lib
import net.seilent.funecomp.proto.LibRow
import net.seilent.funecomp.proto.Live
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Profile
import net.seilent.funecomp.proto.Queue
import net.seilent.funecomp.proto.QueueItem
import net.seilent.funecomp.proto.Row
import net.seilent.funecomp.proto.Schema
import net.seilent.funecomp.proto.Settings
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.listView

data class Playback(val state: State = State(), val receivedAt: Long = 0, val gateAt: Long? = null, val lateMs: Long = 0, val stoppedAt: Long? = null)

data class SchemaMirror(
    val rev: Long? = null,
    val rows: List<Row> = emptyList(),
    val pendingRev: Long? = null,
    val pending: Map<Int, List<Row>> = emptyMap(),
)

fun keyLines(text: String): Map<String, String> = text.lines().mapNotNull { l ->
    val eq = l.indexOf('=')
    if (eq <= 0) null else l.substring(0, eq).trim() to l.substring(eq + 1).trim()
}.toMap()

data class SettingsMirror(val rev: Long = -1, val text: String = "", val floor: Long = -1) {
    val values: Map<String, String> by lazy { keyLines(text) }
}

data class LiveMirror(val rev: Long = -1, val text: String = "") {
    val values: Map<String, String> by lazy { keyLines(text) }
}

data class EqMirror(
    val key: String? = null,
    val ints: Profile? = null,
    val names: List<EqName> = emptyList(),
    val total: Long = 0,
    val complete: Boolean = false,
)

data class QueueMirror(val rev: Long? = null, val total: Long = 0, val items: Map<Long, QueueItem> = emptyMap())

data class LibKey(val view: Int, val parent: String)

data class LibMirror(val rev: Long? = null, val pages: Map<LibKey, LibList> = emptyMap(), val snap: Long? = null) {
    val dataRev: Long? get() = snap ?: rev

    fun keyed(view: Int?) = snap != null && !listView(view)

    fun libRev(view: Int?) = if (keyed(view)) null else rev

    fun snapRev(view: Int?) = if (keyed(view)) snap else null

    fun askKey(view: Int, parent: String, offset: Long) = "lib $view $parent $offset ${snapRev(view) ?: libRev(view)}"

    fun lists(libRev: Long) = copy(rev = libRev, pages = pages.filterKeys { !listView(it.view) })
}

data class LibList(val total: Long = 0, val rows: Map<Long, LibRow> = emptyMap())

data class CardView(val value: Int? = null, val seen: Boolean = false, val epoch: Int = 0) {
    val down: Boolean get() = value != null && value != Enums.Card.ready

    fun with(v: Int?): CardView = when {
        v == null -> this
        v != Enums.Card.ready -> CardView(v, false, epoch)
        seen -> copy(value = v)
        else -> CardView(v, true, epoch + 1)
    }

    fun refused(): CardView = copy(seen = false)

    companion object {
        const val REFUSED = "card not available"
    }
}

data class DeviceState(
    val hello: HelloReply? = null,
    val playback: Playback = Playback(),
    val rttMs: Long? = null,
    val schema: SchemaMirror = SchemaMirror(),
    val settings: SettingsMirror = SettingsMirror(),
    val liveRows: LiveMirror = LiveMirror(),
    val eq: Map<Int, EqMirror> = emptyMap(),
    val queue: QueueMirror = QueueMirror(),
    val lib: LibMirror = LibMirror(),
    val clock: DeviceClock = DeviceClock(),
    val card: CardView = CardView(),
) {
    val cardDown: Boolean get() = card.down

    val playRefusal: String? get() = when {
        cardDown -> CardView.REFUSED
        playback.state.source == Enums.Source.phone -> RECEIVER
        else -> null
    }

    val queueRefusal: String? get() = if (cardDown) CardView.REFUSED else null

    fun transportRefusal(cmd: Int): String? {
        val s = playback.state
        if (!cardDown || s.source == Enums.Source.phone) return null
        val playing = s.playing == true
        val receiver = s.receiver == true
        return when (cmd) {
            Enums.TransportCmd.toggle, Enums.TransportCmd.play -> CardView.REFUSED.takeUnless { playing || receiver }
            Enums.TransportCmd.next, Enums.TransportCmd.prev -> CardView.REFUSED.takeUnless { receiver && !playing }
            Enums.TransportCmd.seek, Enums.TransportCmd.shuffle -> CardView.REFUSED
            else -> null
        }
    }

    fun has(group: String) = hello?.has(group) ?: false

    fun liveWord(key: String?): String? = key?.let { liveRows.values[it] }

    fun positionMs(now: Long): Long = Position.at(playback, rttMs, now)

    fun sleepLeftMs(now: Long): Long = SleepLeft.leftMs(playback, rttMs, now)

    fun reduce(type: Int, body: Node, now: Long): DeviceState = when (type) {
        Msg.STATE -> withState(State.from(body), now)
        Msg.SCHEMA -> withSchema(Schema.from(body))
        Msg.SETTINGS -> withSettings(Settings.from(body))
        Msg.LIVE -> withLive(Live.from(body))
        Msg.EQ -> withEq(Eq.from(body))
        Msg.QUEUE -> withQueue(Queue.from(body))
        Msg.LIB -> withLib(Lib.from(body))
        else -> this
    }

    fun withHello(h: HelloReply) = DeviceState(hello = h, lib = LibMirror(h.libRev, snap = h.snapRev))

    fun withRtt(ms: Long) = copy(rttMs = rttMs?.let { minOf(it, ms) } ?: ms)

    fun withState(s: State, now: Long): DeviceState {
        val old = playback.state
        val started = s.playing == true && old.playing != true
        val gateAt = if (started || (old.output != null && s.output != old.output)) now else playback.gateAt
        val stoppedAt = if (s.playing == true) null else if (old.playing == true || playback.stoppedAt == null) now else playback.stoppedAt
        val q = if (s.queueRev != null && s.queueRev != queue.rev) QueueMirror(s.queueRev) else queue
        val rev = s.libRev
        val l = when {
            s.snapRev != null && s.snapRev != lib.snap -> LibMirror(rev ?: lib.rev, snap = s.snapRev)
            rev == null || rev == lib.rev -> lib
            lib.snap != null -> lib.lists(rev)
            else -> LibMirror(rev)
        }
        val sent = s.sentMs?.takeIf { s.sampleMs != null && has("stateClock") }
        val c = sent?.let { clock.add(now, now - it) } ?: clock
        val late = sent?.let { now - it - (c.offset ?: 0) } ?: 0
        return copy(playback = Playback(s, now, gateAt, late, stoppedAt), queue = q, lib = l, clock = c, card = card.with(s.card))
    }

    fun withSchema(s: Schema): DeviceState {
        val rev = s.rev ?: return this
        if (rev == schema.rev) return this
        val base = if (schema.pendingRev == rev) schema.pending else emptyMap()
        val index = s.pivotIndex ?: 0
        val have = if (s.page.offset == 0L) emptyList() else base[index].orEmpty()
        val pending = base + (index to have + s.rows)
        if (!s.last) return copy(schema = schema.copy(pendingRev = rev, pending = pending))
        val rows = pending.toSortedMap().values.flatten().map { Rows.effective(it) }
        return copy(schema = SchemaMirror(rev, rows))
    }

    fun withSettings(s: Settings): DeviceState {
        if (s.rev < settings.floor || s.rev < settings.rev) return this
        return copy(settings = settings.copy(rev = s.rev, text = s.text))
    }

    fun withLive(l: Live) = if (l.rev <= liveRows.rev) this else copy(liveRows = LiveMirror(l.rev, l.text))

    fun withSetReply(rev: Long) = copy(settings = settings.copy(floor = maxOf(settings.floor, rev)))

    fun withQueueRev(rev: Long) = if (rev <= (queue.rev ?: -1)) this else copy(queue = QueueMirror(rev))

    fun withEq(e: Eq): DeviceState {
        val output = e.output ?: -1
        val old = eq[output] ?: EqMirror()
        val names = if (e.page.offset == 0L) e.names else old.names + e.names
        return copy(eq = eq + (output to EqMirror(e.key, e.ints, names, e.page.total, !e.page.more)))
    }

    fun withQueue(q: Queue): DeviceState {
        val base = if (q.rev == queue.rev) queue.items else emptyMap()
        val items = base + q.items.mapIndexed { i, it -> q.page.offset + i to it }
        return copy(queue = QueueMirror(q.rev, q.page.total, items.filterKeys { it < q.page.total }))
    }

    fun withLib(l: Lib): DeviceState {
        val base = when {
            l.snapRev != lib.snap -> LibMirror(l.libRev, snap = l.snapRev)
            l.libRev == lib.rev -> lib
            lib.snap == null -> LibMirror(l.libRev)
            listView(l.view) -> lib.lists(l.libRev)
            else -> lib
        }
        val key = LibKey(l.view, l.parent ?: "")
        val old = base.pages[key] ?: LibList()
        val rows = old.rows + l.rows.mapIndexed { i, r -> l.page.offset + i to r }
        return copy(lib = base.copy(pages = base.pages + (key to LibList(l.page.total, rows))))
    }

    companion object {
        const val RECEIVER = "bluetooth receiver"
    }
}

object Rows {
    fun known(r: Row): Boolean = r.kind != null && Enums.RowKind.names.containsKey(r.kind)

    fun effective(r: Row): Row = if (known(r)) r else r.copy(
        kind = Enums.RowKind.info,
        flags = (r.flags + Enums.RowFlag.readOnly).distinct(),
    )

    fun readOnly(r: Row) = Enums.RowFlag.readOnly in r.flags
}
