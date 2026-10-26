package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.ArtGetReply
import net.seilent.funecomp.proto.Band
import net.seilent.funecomp.proto.Eq
import net.seilent.funecomp.proto.EqName
import net.seilent.funecomp.proto.EqPresetReply
import net.seilent.funecomp.proto.FwStatement
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.ImgAbort
import net.seilent.funecomp.proto.ImgBeginReply
import net.seilent.funecomp.proto.ImgEntry
import net.seilent.funecomp.proto.ImgListReply
import net.seilent.funecomp.proto.Lib
import net.seilent.funecomp.proto.LyrGetReply
import net.seilent.funecomp.proto.LyrLine
import net.seilent.funecomp.proto.LyrOffsetReply
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.PageInfo
import net.seilent.funecomp.proto.PlayEntry
import net.seilent.funecomp.proto.Profile
import net.seilent.funecomp.proto.Queue
import net.seilent.funecomp.proto.QueueItem
import net.seilent.funecomp.proto.QueueOpReply
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.Row
import net.seilent.funecomp.proto.Schema
import net.seilent.funecomp.proto.SetReply
import net.seilent.funecomp.proto.SourceJpeg
import net.seilent.funecomp.proto.Settings
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.UpdAbort
import net.seilent.funecomp.proto.UpdBeginReply
import net.seilent.funecomp.proto.listView
import net.seilent.funecomp.proto.lyricsCrc
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.proto.gen.Tags
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.proto.gen.TokBoard
import net.seilent.funecomp.state.ArtKey
import net.seilent.funecomp.state.AutoEq
import net.seilent.funecomp.state.CardView
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.roundToInt

interface Out {
    fun push(type: Int, payload: ByteArray)
}

sealed class Answer {
    class Ok(val payload: ByteArray = ByteArray(0), val state: Boolean = false) : Answer()
    class Fail(val code: Int, val text: String, val refused: List<String> = emptyList()) : Answer()
}

private const val FULL_VOLUME = 255
private const val V1_SPAN = 132
private const val ART_QUALITY = 0.85f
private const val UPDATE_BATTERY_MIN = 50
private const val PREV_RESTART_MS = 3000L
private const val MIB = 1L shl 20
private const val UPDATE_RESERVE = 64L shl 20
private const val LIBRARY = 1
private const val PHONE = 2
private const val CROSS_MIN_MS = 30_000L
private const val CROSS_CAP_MS = 240_000L
private val SYNCED = Regex("^\\[[0-9]+:[0-9]{2}(\\.[0-9]+)?]", RegexOption.MULTILINE)
private val STAMP = Regex("^\\[([0-9]+):([0-9]{2}(\\.[0-9]+)?)]")
private val ID_TAG = Regex("^\\[[A-Za-z]+:.*]$")
private val OFFSET = Regex("^\\[offset:\\s*([+-]?[0-9]+)\\s*]$", RegexOption.IGNORE_CASE)

class Lyr(val kind: Int, val lines: List<LyrLine>) {
    val crc: Long = lyricsCrc(kind, lines)

    fun shifted(by: Int): Lyr = if (kind != Enums.LyricsKind.synced) this else Lyr(kind, lines.map { it.copy(ms = ((it.ms ?: 0) - by).coerceAtLeast(0)) })

    companion object {
        fun parse(text: String): Lyr? {
            val raw = text.lines().map { it.trim() }
            if (!SYNCED.containsMatchIn(text)) {
                val plain = raw.filterNot { ID_TAG.matches(it) }.dropLastWhile { it.isEmpty() }
                return if (plain.isEmpty()) null else Lyr(Enums.LyricsKind.plain, plain.map { LyrLine(null, cut(it)) })
            }
            val shift = raw.firstNotNullOfOrNull { OFFSET.find(it)?.groupValues?.get(1)?.toLong() } ?: 0L
            val out = ArrayList<LyrLine>()
            for (l in raw) {
                var rest = l
                val stamps = ArrayList<Long>()
                while (true) {
                    val m = STAMP.find(rest) ?: break
                    stamps += m.groupValues[1].toLong() * 60_000 + Math.round(m.groupValues[2].toDouble() * 1000)
                    rest = rest.substring(m.range.last + 1)
                }
                for (ms in stamps) out += LyrLine(ms - shift, cut(rest.trim()))
            }
            return Lyr(Enums.LyricsKind.synced, out.sortedBy { it.ms })
        }

        private fun cut(s: String) = Req.cap(s, Proto.lyrLineMax)!!
    }
}
private val MD5 = Regex("^[0-9a-f]{32}$")
private const val EQ_DIR = "/data/funeos/eq/"
private val CARD_PROFILE = Profile(-30, listOf(Band(Enums.BandType.peak, -1, 1_000_000, -20, 141)))

private fun eqNameOrder(x: String, y: String): Int {
    val a = x.toByteArray(Charsets.UTF_8)
    val b = y.toByteArray(Charsets.UTF_8)
    fun u(v: Byte) = v.toInt() and 0xff
    fun digit(v: Byte) = u(v) in '0'.code..'9'.code
    fun fold(v: Int) = if (v in 'A'.code..'Z'.code) v + ('a' - 'A') else v
    var i = 0
    var j = 0
    while (i < a.size && j < b.size) {
        if (digit(a[i]) && digit(b[j])) {
            var ie = i
            var je = j
            while (ie < a.size && digit(a[ie])) ie++
            while (je < b.size && digit(b[je])) je++
            while (i + 1 < ie && u(a[i]) == '0'.code) i++
            while (j + 1 < je && u(b[j]) == '0'.code) j++
            if (ie - i != je - j) return (ie - i) - (je - j)
            for (k in 0 until ie - i) if (a[i + k] != b[j + k]) return u(a[i + k]) - u(b[j + k])
            i = ie
            j = je
            continue
        }
        val c = fold(u(a[i])) - fold(u(b[j]))
        if (c != 0) return c
        i++
        j++
    }
    if (a.size - i != b.size - j) return (a.size - i) - (b.size - j)
    return java.util.Arrays.compareUnsigned(a, b)
}

private fun whole(v: Float): String {
    val n = v.roundToInt()
    require(n.toFloat() == v) { "token $v is not a whole number" }
    return n.toString()
}

class Device(private val opts: Options, lib: Library, private val clock: () -> Long) {
    @Volatile var lib: Library = lib
        private set
    private val board = opts.board
    private val faults = opts.faults
    private val lock = Any()

    private val conditions = HashMap<String, Pair<String, String>>()
    val rows: List<Row> = loadRows()
    private val pivots = rows.mapNotNull { it.pivot }.distinct()
    private var schemaRev = 1L
    private var schemaShown: List<Row>? = null
    private val schemaAt = java.util.WeakHashMap<Out, Long>()
    private var scanUntil = Long.MIN_VALUE
    private var wallAt = System.currentTimeMillis() - clock()

    private val tok = TokBoard.of(board.name)
    private val prefs = linkedMapOf(
        "output" to "auto", "volumeStep" to whole(Tok.volume_stepDefault),
        "accentHue" to whole(Tok.accent_hueDefault), "saverSeconds" to whole(Tok.settings_saverDefault),
        "screenOffSeconds" to whole(Tok.settings_screenOffDefault),
        "idleShutdownMinutes" to whole(Tok.settings_idleDefault), "lockKnob" to "off", "screenOffKeys" to "all",
        "inlineRemote" to "on", "clock" to "24h", "batteryPercent" to "off", "playThrough" to "off",
        "usbMode" to "storage", "btCodec" to "auto", "btCodecHeadphones" to "auto", "digitalFilter" to "0",
        "balance" to "0", "maxVolume" to whole(Tok.settings_maxVolumeDefault), "powerOnVolume" to "0", "replaygain" to "off",
        "quickplay" to "on", "resume" to "position", "autoNowPlaying" to "off",
        "crossfade" to whole(Tok.settings_crossfadeDefault), "crossfeed" to "off", "artistPhoto" to "on",
        "saverLyrics" to "on", "brightness" to whole(tok.settings_brightnessDefault),
        "outputPriority" to "headphones", "outputGain" to "high", "dsdOutput" to "dop", "tube" to "off", "stereoWidth" to "off",
        "led" to "high", "saverDrift" to "on", "statusVolume" to "off", "navBar" to "off", "navOrder" to "backLeft",
        "navButton" to "play", "whenIdle" to "powerOff", "chargeLimit" to "off", "usbFeedback" to "on", "usbOutput" to "headphones",
        "companion" to if (opts.companion) "on" else "off", "scrobble" to "phone", "size" to "standard",
    ).apply {
        if (board.name == "r1") put("usbMode", "dock")
        keys.retainAll(rows.mapNotNull { it.key }.toSet() + setOf("btCodec", "btCodecHeadphones"))
        val steps = tok.settings_maxVolumeSteps.split(',').map { it.toInt() }
        opts.maxPct?.let { n -> put("maxVolume", steps.minBy { kotlin.math.abs(it - n) }.toString()) }
    }
    var settingsRev = 1L
        private set
    private var sleepLeftS = 0L
    private var sleepSetAt = 0L

    private class Preset(val name: String, val profile: Profile)

    private val presets = HashMap<String, Preset>()
    private val cards: List<EqName> = ((1..198).map { EqName("autoeq/oratory1990/Headphone Model %03d.txt".format(it), "Headphone Model %03d".format(it)) } +
        opts.cardEq.map { EqName("autoeq/$it.txt", it) }).sortedWith { a, b -> eqNameOrder(a.key.substringAfterLast('/'), b.key.substringAfterLast('/')) }
    private var eqNames: List<EqName> = listEq()
    private val eqKey = arrayOf("off", "off")
    private val eqCustom = arrayOf(flat(), flat())

    private var queue: MutableList<Long> = lib.tracks.map { it.id }.toMutableList()
    private var slot = 0
    private var playing = false
    private var positionMs = 0L
    private var positionAt = 0L
    private var shuffle = false
    private var repeat = Enums.Repeat.off
    private var volume = 60
    private val maxVolume: Int get() = percentVolume(prefs.getValue("maxVolume").toIntOrNull() ?: 100)
    private var queueRev = 1L
    private var lastStateAt = 0L
    private var stopped = false
    private val playLog = PlayLog(opts.playLogId, opts.playLogCap, opts.playLog)
    private var connects = 0
    private val linked = HashMap<Out, Long>()
    private var zoneS: Int? = null
    private var playTrack: Long? = null
    private var playFresh = true
    private var playStart = 0L
    private var played = 0L
    private var playAt = 0L
    private var playLogged = false

    data class Meta(val wrong: Boolean?, val source: String?, val credit: String?)
    class Stored(val name: String, val mbid: String?, val crc: Long, val bytes: ByteArray, var at: Long, var meta: Meta, var origins: Int = 0, val shownS: Long = 0)
    class Partial(val name: String, val total: Long, val crc: Long, val w: Int, val h: Int, val data: ByteArrayOutputStream = ByteArrayOutputStream(), var origins: Int = 0, val full: Boolean = false, var focus: Pair<Int, Int>? = null)
    class Src(val name: String, val mbid: String?, val crc: Long, val bytes: ByteArray, val w: Int, val h: Int, var aside: Boolean, var meta: Meta, var focus: Pair<Int, Int>?, var shownS: Long, var origins: Int = 0, val failed: Boolean = false)

    private val images = LinkedHashMap<String, Stored>()
    private val partials = HashMap<String, Partial>()
    private val srcs = LinkedHashMap<String, Src>()
    private var srcRefused = 0
    private var push: Partial? = null
    private var pushMbid: String? = null
    private var pushMeta = Meta(null, null, null)
    private var pushDropped = false
    private var abortFired = false
    private var updAbortFired = false
    private var storeClock = 0L
    private var shown: String? = null
    private val v11: Boolean get() = opts.minor >= 1
    private val v12: Boolean get() = opts.minor >= 2
    private val v13: Boolean get() = opts.minor >= 3
    private val v14: Boolean get() = opts.minor >= 4
    private val v15: Boolean get() = opts.minor >= 5
    private val v19: Boolean get() = opts.minor >= 9
    private val v110: Boolean get() = opts.minor >= 10
    private var source = opts.source
    private var output = Enums.Output.wired
    private var rescanLib = opts.rescanLib
    val begins: MutableList<Pair<String, Int?>> = java.util.Collections.synchronizedList(ArrayList())
    val updSigs = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
    val fulls: MutableList<Triple<String, Long, Pair<Int, Int>?>> = java.util.concurrent.CopyOnWriteArrayList()
    private val started = clock()
    private var lastBattery = -1
    private val lyrs = HashMap<Long, Lyr>()
    private val offsets = HashMap<Long, Int>(opts.lyrOffsets.filterValues { it != 0 })
    private val phone = HashMap<Long, ByteArray>()
    private val arts = HashMap<Pair<Long, Int>, Art>()
    private var upd: Upd? = null
    private var favsBusy = faults.favsBusy
    private var flagTrack: Long? = null
    private var flagAt = 0L
    private var late = false
    private class CardEdge(val at: Long, val value: Int, val mounted: Boolean, val index: Boolean, val art: Boolean = false)
    private var cardEdges: List<CardEdge> = emptyList()
    private var cardForced: CardEdge? = null
    private var lagBusy = false
    private var cardArmed = faults.cardRun != null || faults.cardSeq.isNotEmpty()
    private var cardGate = Enums.Card.ready
    private var cardLyrics: Long? = null
    private var snapAt: Long? = null
    private val cardSent = HashMap<Out, Triple<Int, Boolean, Long?>>()
    private var pushOut: Out? = null
    private var updOut: Out? = null
    private val stream = Phone(opts, clock)

    init {
        opts.lrcDir?.listFiles { f -> f.name.endsWith(".lrc") }?.forEach { f ->
            val id = f.name.removeSuffix(".lrc").toULongOrNull()?.toLong()
            val bytes = f.readBytes()
            val l = Lyr.parse(String(bytes, Charsets.UTF_8))
            if (id != null && l != null) {
                lyrs[id] = l
                phone[id] = bytes
            }
        }
    }

    private fun offsetOf(id: Long) = if (allowed(Msg.LYR_OFFSET)) offsets[id] ?: 0 else 0

    private fun served(id: Long) = lyrs[id]?.shifted(offsetOf(id))

    private fun offsetTag(id: Long) = if (allowed(Msg.LYR_OFFSET)) offsets[id]?.takeIf { it != 0 } else null

    private fun hasSynced(id: Long) = lyrs[id]?.kind == Enums.LyricsKind.synced

    private fun hasPlain(id: Long) = lyrs[id]?.kind == Enums.LyricsKind.plain

    class Art(val crc: Long, val w: Int, val h: Int, val bytes: ByteArray, val source: Pair<Long, Long>)
    class Upd(val board: String, val size: Long, val md5: String, val part: File, val sha256: String?, val sig: String?)
    @Volatile var droppedData = 0
        private set
    private val streamFrames = java.util.concurrent.atomic.AtomicInteger()
    val imgDuringStream: Int get() = streamFrames.get()

    val stored: Map<String, Stored> get() = synchronized(lock) { images.toMap() }
    val sources: Map<String, Src> get() = synchronized(lock) { srcs.toMap() }

    fun drawn(name: String): Triple<String, String?, Long>? = synchronized(lock) {
        val k = fold(name)
        srcs[k]?.takeIf { !it.aside && !it.failed }?.let { Triple(it.name, it.mbid, it.crc) } ?: images[k]?.let { Triple(it.name, it.mbid, it.crc) }
    }

    val pushing: String? get() = synchronized(lock) { push?.name }

    private fun flat() = Profile(0, emptyList())

    private fun n(v: Long?, default: Long): Int = (v ?: default).coerceIn(0L, 1_000_000L).toInt()

    private fun loadRows(): List<Row> {
        val text = Device::class.java.getResourceAsStream("/${board.schema}")!!.bufferedReader().readText()
        return text.lines().filter { it.isNotBlank() }.map { line ->
            val p = line.split('\t')
            fun list(s: String) = if (s == "-") emptyList() else s.split(',')
            val range = list(p[6]).map { it.toInt() }
            p.getOrNull(8)?.takeIf { it != "-" }?.let { conditions[p[1]] = it.substringBefore('=') to it.substringAfter('=') }
            Row(
                p[1], p[3], p[0],
                Enums.RowKind.names.entries.first { it.value == p[2] }.key,
                list(p[4]), range.getOrNull(0), range.getOrNull(1), range.getOrNull(2),
                list(p[5]),
                list(p[7]).map { f -> Enums.RowFlag.names.entries.first { it.value == f }.key },
            )
        }
    }

    fun startCard() = synchronized(lock) { cardStart(now()) }

    private fun cardStart(at: Long) {
        if (!cardArmed) return
        cardArmed = false
        cardLyrics = queue.getOrNull(slot)
        cardForced = null
        val run = faults.cardRun
        cardEdges = if (run != null) {
            val ready = at + run.holdMs + run.indexMs + faults.artHold
            if (faults.snapLate > 0) snapAt = ready + faults.snapLate
            listOfNotNull(
                CardEdge(at, Enums.Card.busy, false, false),
                CardEdge(at + run.holdMs, Enums.Card.busy, true, true),
                if (faults.artHold > 0) CardEdge(at + run.holdMs + run.indexMs, Enums.Card.busy, true, false, art = true) else null,
                CardEdge(ready, Enums.Card.ready, true, false),
            )
        } else {
            var t = at
            faults.cardSeq.map { (v, ms) -> CardEdge(t, v, v == Enums.Card.ready, false).also { t += ms } }
        }
        opts.log("card run from $at")
    }

    private fun edgeAt(t: Long) = cardEdges.lastOrNull { it.at <= t }

    private fun stateEdge(): CardEdge? {
        if (lagBusy) {
            if (lagging()) return CardEdge(now(), Enums.Card.busy, true, false)
            lagBusy = false
        }
        val lag = edgeAt(now() - faults.lateState)
        if (faults.cardCoalesce) return lag?.takeIf { it.value == Enums.Card.ready }
        val f = cardForced ?: return lag
        if (lag != null && lag.at >= f.at) {
            cardForced = null
            return lag
        }
        return f
    }

    private fun forceCard() {
        if (faults.cardCoalesce) return
        if (lagging()) {
            lagBusy = true
            return
        }
        val e = edgeAt(now()) ?: return
        if (e.value != Enums.Card.ready && stateCard() == Enums.Card.ready) cardForced = e
    }

    private fun stateCard() = stateEdge()?.value ?: Enums.Card.ready

    private fun mounted() = stateEdge()?.mounted ?: true

    private fun cardAbort() = if (!faults.cardOld && opts.minor >= 15) Enums.AbortCode.noCard else Enums.AbortCode.busy

    private fun cardEdge() {
        val v = edgeAt(now())?.value ?: Enums.Card.ready
        if (v == cardGate) return
        val was = cardGate
        cardGate = v
        opts.log("card gate ${Enums.Card.names[v]}")
        if (was != Enums.Card.ready || v == Enums.Card.ready) return
        if (faults.cardOld && edgeAt(now())?.mounted == true) return
        val p = push
        val o = pushOut
        if (cardAbort() == Enums.AbortCode.noCard && ((p != null && !pushDropped && o != null) || (upd != null && updOut != null))) forceCard()
        if (p != null && !pushDropped && o != null) {
            cardPush(o)
            opts.log("card stop img name=${p.name} at=${p.data.size()}")
            abort(o, p, cardAbort())
        }
        val u = upd
        val uo = updOut
        if (u != null && uo != null) {
            cardPush(uo)
            opts.log("card stop upd at=${u.part.length()}")
            send(uo, Msg.UPD_ABORT, UpdAbort(u.part.length(), cardAbort()).encode())
            upd = null
        }
    }

    private fun cardKey() = Triple(stateCard(), mounted(), if (faults.cardCoalesce) stateEdge()?.at else null)

    private fun cardPush(out: Out) {
        val c = cardKey()
        if (cardEdges.isEmpty() || cardSent[out] == c) return
        cardSent[out] = c
        opts.log("card state ${Enums.Card.names[c.first]}${if (c.second) "" else " unmounted"}")
        send(out, Msg.STATE, state())
    }

    private fun lagging(): Boolean {
        if (faults.playLag <= 0) return false
        val r = cardEdges.lastOrNull { it.value == Enums.Card.ready } ?: return false
        val from = r.at + faults.lateState
        return now() >= from && now() < from + faults.playLag
    }

    private fun playCmd(type: Int, b: Node): Boolean = when (type) {
        Msg.PLAY -> true
        Msg.QUEUE_OP -> b.int(Tags.QueueOp.cmd) in setOf(Enums.QueueCmd.playAt, Enums.QueueCmd.playNext, Enums.QueueCmd.add)
        Msg.ACTION -> b.int(Tags.Action.cmd) == Enums.ActionCmd.shuffleLibrary || b.int(Tags.Action.cmd) == Enums.ActionCmd.rescan
        Msg.TRANSPORT -> transportRefused(b.int(Tags.Transport.cmd))
        else -> false
    }

    private fun transportRefused(cmd: Int?): Boolean {
        if (stream.current() != null) return false
        return when (cmd) {
            Enums.TransportCmd.toggle, Enums.TransportCmd.play -> !playing && !stream.receiver
            Enums.TransportCmd.next, Enums.TransportCmd.prev -> !(stream.player() && !playing)
            Enums.TransportCmd.seek, Enums.TransportCmd.shuffle -> true
            else -> false
        }
    }

    private fun noCard() = if (opts.minor >= 15) Answer.Fail(Err.noCard, CardView.REFUSED) else Answer.Fail(Err.busy, CardView.REFUSED)

    private fun cardRefusal(type: Int, b: Node): Answer.Fail? {
        if (cardEdges.isEmpty()) return null
        val e = edgeAt(now())
        if (e == null || e.value == Enums.Card.ready) {
            if (!lagging() || !playCmd(type, b)) return null
            if (faults.cardOld && type == Msg.ACTION && lib.tracks.isEmpty()) return null
            return if (faults.cardOld) Answer.Fail(Err.busy, "busy") else noCard()
        }
        return if (faults.cardOld) oldRefusal(type, b, !e.mounted) else landedRefusal(type, b, !e.mounted || e.art)
    }

    private fun landedRefusal(type: Int, b: Node, artHeld: Boolean): Answer.Fail? = when (type) {
        Msg.LYR_GET -> if (b.long(Tags.LyrGet.track) == cardLyrics) null else noCard()
        Msg.PLAY, Msg.ACTION, Msg.LRC_PUT, Msg.LRC_DEL, Msg.LYR_OFFSET, Msg.IMG_BEGIN, Msg.IMG_END, Msg.IMG_DEL, Msg.IMG_TAG, Msg.UPD_BEGIN, Msg.UPD_END -> noCard()
        Msg.QUEUE_OP -> if (b.int(Tags.QueueOp.cmd) == Enums.QueueCmd.page && !faults.cardPage) null else noCard()
        Msg.TRANSPORT -> if (transportRefused(b.int(Tags.Transport.cmd))) noCard() else null
        Msg.IMG_LIST -> if (artHeld) noCard() else null
        else -> null
    }

    private fun oldRefusal(type: Int, b: Node, hold: Boolean): Answer.Fail? {
        val busy = Answer.Fail(Err.busy, "busy")
        return when (type) {
            Msg.PLAY, Msg.QUEUE_OP, Msg.IMG_BEGIN, Msg.IMG_LIST, Msg.IMG_DEL, Msg.IMG_TAG, Msg.LRC_PUT -> if (hold) busy else null
            Msg.UPD_BEGIN -> busy
            Msg.LYR_GET -> if (hold && b.long(Tags.LyrGet.track) != queue.getOrNull(slot)) busy else null
            Msg.ACTION -> when (b.int(Tags.Action.cmd)) {
                Enums.ActionCmd.shuffleLibrary -> if (lib.tracks.isEmpty() || !hold) null else Answer.Fail(Err.busy, CardView.REFUSED)
                Enums.ActionCmd.favorite -> Answer.Fail(Err.busy, CardView.REFUSED)
                else -> null
            }
            else -> null
        }
    }

    private fun dropped(type: Int): Boolean {
        if (type != Msg.PLAY || !faults.playDrop || !faults.cardOld) return false
        val e = edgeAt(now()) ?: return false
        return e.index && e.value != Enums.Card.ready
    }

    fun restart() = synchronized(lock) {
        partials.clear()
        push = null
    }

    fun helloReply() = HelloReply(
        faults.major, opts.minor, board.name, opts.version ?: "sim.${board.name}", board.screenW, board.screenH,
        board.backdropW, board.backdropH, Proto.maxPayload, opts.idleMs, opts.busyMs, faults.features, lib.rev, settingsRev,
        if (v13) lib.snapRev else null,
    )

    private fun allowed(type: Int) = Groups.allowed(type, faults.features)

    private fun send(out: Out, type: Int, payload: ByteArray) {
        if (allowed(type)) out.push(type, payload)
    }

    fun greet(out: Out, receipt: Long? = null) = synchronized(lock) {
        if (faults.cardSeq.isNotEmpty()) cardStart(now())
        cardEdge()
        cardSent[out] = cardKey()
        send(out, Msg.STATE, state(receipt))
        (faults.cardRun?.start as? CardRun.Start.Delay)?.let { cardStart(now() + it.ms) }
        pushSchema(out, schemaNow())
        pushSettings(out)
        pushEq(out, Enums.EqOutput.wired)
        pushEq(out, Enums.EqOutput.bt)
    }

    private val frozen = ThreadLocal<Long>()

    private fun now() = frozen.get() ?: clock()

    private fun position(): Long {
        val t = lib.byId[queue.getOrNull(slot)]
        val p = if (playing) positionMs + (now() - positionAt) else positionMs
        return minOf(p, t?.durationMs ?: p)
    }

    private fun mark() {
        positionMs = position()
        positionAt = now()
    }

    private fun sleepLeft(): Long = if (sleepLeftS <= 0) 0 else maxOf(0L, sleepLeftS - (now() - sleepSetAt) / 1000)

    fun state(receipt: Long? = null): ByteArray = synchronized(lock) {
        if (frozen.get() != null) return@synchronized build(receipt)
        frozen.set(clock())
        try {
            build(receipt)
        } finally {
            frozen.remove()
        }
    }

    private fun build(receipt: Long?): ByteArray = run {
        cardEdge()
        val t = lib.byId[queue.getOrNull(slot)]
        val on = mounted()
        lastStateAt = now()
        val sample = if (v110) now() else receipt ?: now()
        val sent = if (v110) now() else sample
        val key = t?.artists?.map { fold(it) }?.firstOrNull { it in images }
        if (key != null && key != shown) images[key]?.at = ++storeClock
        shown = key
        val bat = battery()
        lastBattery = bat
        if (t?.id != flagTrack) {
            flagTrack = t?.id
            flagAt = now()
            late = faults.lyricsLate != null
        }
        if (late && now() - flagAt >= (faults.lyricsLate ?: 0)) late = false
        val unread = t != null && (late || t.title in faults.lyricsAbsent)
        stream.over(State(
            source, output, t?.id, t?.title, t?.artist, t?.artists.orEmpty(),
            t?.mbids.orEmpty(), t?.durationMs, position(), sample, sent, playing, shuffle, repeat,
            volume, maxVolume, bat, queueRev, slot.toLong(), queue.size.toLong(), lib.rev, t?.album, sleepLeft(),
            if (v12) opts.battery.charging else null,
            if (v12 && on) opts.cardFreeMiB else null,
            if (v12 && on) opts.cardTotalMiB else null,
            if (v12) volumePercent(volume) else null,
            if (v12) prefs.getValue("volumeStep").toIntOrNull() ?: 5 else null,
            if (v12 && t != null && !unread) hasSynced(t.id) else null,
            if (v12) prefs.getValue("maxVolume").toIntOrNull() ?: 100 else null,
            if (v13 && t != null && favsBusy == 0) t.id in lib.favorites else null,
            if (v13) lib.snapRev else null,
            if (v14 && t != null && !unread) hasPlain(t.id) else null,
            if (allowed(Msg.LYR_GET) && t != null && !unread) served(t.id)?.crc else null,
            if (v110) false else null,
            lyricsOffsetMs = if (t != null && !unread && hasSynced(t.id)) offsetTag(t.id) else null,
            playLogSeq = if (allowed(Msg.PLAYLOG_GET)) playLog.newest else null,
            card = if (opts.minor >= 14) stateCard() else null,
        )).encode()
    }

    private fun battery() = opts.battery.at(now() - started)

    fun tick(out: Out) = synchronized(lock) {
        follow()
        btEdge()
        cardEdge()
        cardPush(out)
        snapAt?.let { at ->
            if (now() >= at) {
                snapAt = null
                lib.rev++
                lib.snapRev++
                opts.log("snap late rev=${lib.rev} snapRev=${lib.snapRev}")
                send(out, Msg.STATE, state())
            }
        }
        schemaCheck(out)
        val t = lib.byId[queue.getOrNull(slot)]
        if (stream.tick(lastStateAt)) {
            send(out, Msg.STATE, state())
        } else if (sleepLeftS > 0 && sleepLeft() == 0L) {
            sleepLeftS = 0
            mark()
            playing = false
            opts.log("sleep expired")
            send(out, Msg.STATE, state())
        } else if (playing && t != null && position() >= t.durationMs) {
            advance(1, auto = true)
            send(out, Msg.STATE, state())
        } else if (late && now() - flagAt >= (faults.lyricsLate ?: 0)) {
            send(out, Msg.STATE, state())
        } else if (playing && now() - lastStateAt >= Proto.stateIntervalMs) {
            send(out, Msg.STATE, state())
        } else if (battery() != lastBattery) {
            send(out, Msg.STATE, state())
        }
        follow()
    }

    fun linked(out: Out, features: Long) = synchronized(lock) {
        if (faults.cardRun?.start == CardRun.Start.Hello) cardStart(now())
        linked[out] = features
        if (++connects == 2 && faults.newlog) {
            playLog.renew(playLog.id + 1)
            opts.log("playlog new log ${java.lang.Long.toHexString(playLog.id)}")
        }
    }

    fun unlinked(out: Out) = synchronized(lock) {
        linked.remove(out)
        cardSent.remove(out)
        if (pushOut === out) pushOut = null
        if (updOut === out) updOut = null
    }

    val plays: List<PlayEntry> get() = synchronized(lock) { playLog.held }

    fun logPlay(e: PlayEntry): PlayEntry = synchronized(lock) { playLog.append(e) }

    private fun follow() {
        val now = now()
        if (playing) played += now - playAt
        playAt = now
        val t = lib.byId[queue.getOrNull(slot)]
        val pt = lib.byId[playTrack]
        if (pt != null && !playLogged && pt.durationMs > CROSS_MIN_MS && played >= minOf(pt.durationMs / 2, CROSS_CAP_MS)) {
            playLogged = true
            if (prefs["scrobble"] == "phone") {
                val e = playLog.append(entry(pt))
                opts.log("playlog append seq ${e.seq} ${pt.title}${if (e.liveSeen == true) " liveSeen" else ""}")
            }
        }
        if (t?.id != playTrack || playFresh) {
            playTrack = t?.id
            playFresh = false
            played = 0
            playLogged = false
            playStart = opts.wall() - position()
        }
    }

    private fun entry(t: Track): PlayEntry {
        val local = playStart / 1000 + (zoneS ?: 0)
        val mbids = t.mbids.takeIf { m -> m.size == t.artists.size && m.all { it.isNotEmpty() } }.orEmpty()
        val old = linked.values.any { !Groups.allowed(Msg.PLAYLOG_GET, it) }
        return PlayEntry(
            0, local, played, t.durationMs, t.id, t.title, t.artist, t.artists, mbids, t.album.ifEmpty { null },
            t.albumArtist.takeIf { it != t.artist }, t.no, zoneS, if (old) true else null,
        )
    }

    private fun advance(d: Int, auto: Boolean) {
        if (queue.isEmpty()) return
        if (auto && repeat == Enums.Repeat.one) {
            positionMs = 0
        } else {
            val s = slot + d
            when {
                s in queue.indices -> slot = s
                repeat == Enums.Repeat.all -> slot = if (s < 0) queue.size - 1 else 0
                auto -> {
                    playing = false
                    stopped = true
                }
                d > 0 -> return
            }
            positionMs = 0
        }
        positionAt = now()
        playFresh = true
    }

    fun conditionOf(key: String?): String? = conditions[key]?.let { (k, v) -> "$k=$v" }

    fun schemaRows(): List<Row> = synchronized(lock) {
        rows.filter { r -> r.key !in faults.rowsOff && conditions[r.key]?.let { (k, v) -> prefs[k] == v } != false }.map { live(it) }
    }

    private fun schemaNow(): List<Row> {
        val now = schemaRows()
        if (schemaShown != null && now != schemaShown) schemaRev++
        schemaShown = now
        return now
    }

    private fun schemaCheck(out: Out) {
        val sent = schemaAt[out] ?: return
        val now = schemaNow()
        if (sent != schemaRev) pushSchema(out, now)
    }

    private fun pushSchema(out: Out, shown: List<Row>) {
        schemaAt[out] = schemaRev
        val fixed = Schema(schemaRev, "system", 0, pivots.size, PageInfo(0, 0, false), emptyList()).encode().size + 8
        pivots.forEachIndexed { pi, pivot ->
            val prow = shown.filter { it.pivot == pivot }
            var at = 0
            do {
                val n = PageInfo.fill(prow.map { it.size }, at, fixed + pivot.length)
                val more = at + n < prow.size
                send(out, Msg.SCHEMA, Schema(schemaRev, pivot, pi, pivots.size, PageInfo(at.toLong(), prow.size.toLong(), more), prow.subList(at, at + n)).encode())
                at += n
            } while (at < prow.size)
        }
    }

    private fun live(r: Row): Row = when (r.key) {
        "about" -> r.copy(words = listOf(opts.version ?: "sim.${board.name}"))
        "rescan" -> r.copy(words = listOf(if (now() < scanUntil) "SCANNING" else if (lib.tracks.size == 1) "1 SONG" else "${lib.tracks.size} SONGS"))
        "dateTime" -> r.copy(words = listOf(wallClock()))
        "folders" -> r.copy(words = listOf("1 ON"))
        "sampleRate" -> r.copy(words = listOf("AUTO"))
        "eq" -> r.copy(words = listOf(eqWord()))
        "output" -> if (opts.headset || prefs["output"] == "bluetooth") r else r.copy(values = r.values.dropLast(1), words = r.words.dropLast(1))
        "usbOutput" -> if (opts.headset || prefs["usbOutput"] == "bluetooth") r else r.copy(values = r.values.dropLast(1), words = r.words.dropLast(1))
        else -> r
    }

    private fun wallClock(): String {
        val ms = wallAt + now()
        val zone = zoneS?.let { java.time.ZoneOffset.ofTotalSeconds(it) } ?: java.time.ZoneId.systemDefault()
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(java.time.Instant.ofEpochMilli(ms).atZone(zone))
    }

    private fun eqWord(): String = when (val k = eqKey[Enums.EqOutput.wired]) {
        "off" -> "OFF"
        "custom" -> "CUSTOM"
        else -> (eqNames.firstOrNull { it.key == k }?.name ?: k).uppercase()
    }

    private fun settingsText(): String {
        val shown = schemaRows().mapNotNull { it.key }.toSet() + setOf("btCodec", "btCodecHeadphones")
        return prefs.entries.filter { it.key in shown }.joinToString("") { "${it.key}=${it.value}\n" }
    }

    private fun pushSettings(out: Out) = send(out, Msg.SETTINGS, Settings(settingsRev, settingsText()).encode())

    private fun pushEq(out: Out, output: Int) {
        val fixed = Eq(output, eqKey[output], emptyList(), eqCustom[output], PageInfo(0, 0, false)).encode().size + 8
        var at = 0
        do {
            val n = PageInfo.fill(eqNames.map { it.size }, at, fixed)
            val more = at + n < eqNames.size
            val ints = if (eqKey[output] == "custom") eqCustom[output] else presets[eqKey[output]]?.profile ?: if (cards.any { it.key == eqKey[output] }) CARD_PROFILE else flat()
            send(out, Msg.EQ, Eq(output, eqKey[output], eqNames.subList(at, at + n), ints, PageInfo(at.toLong(), eqNames.size.toLong(), more)).encode())
            at += n
        } while (at < eqNames.size)
    }

    fun request(type: Int, body: Node, out: Out): Answer = synchronized(lock) {
        follow()
        if (type == Msg.QUEUE_OP && body.int(Tags.QueueOp.cmd) == Enums.QueueCmd.page && faults.cardRun?.start == CardRun.Start.Queue && cardArmed) cardStart(now())
        if ((type == Msg.IMG_END || type == Msg.UPD_END) && faults.cardRun?.start == CardRun.Start.End && cardArmed) {
            if (type == Msg.IMG_END) push = null else upd = null
            cardStart(now())
        }
        cardEdge()
        cardRefusal(type, body)?.let {
            if (it.code == Err.noCard) forceCard()
            cardPush(out)
            return@synchronized it
        }
        if (dropped(type)) return@synchronized Answer.Ok()
        when (type) {
            Msg.TRANSPORT -> transport(body)
            Msg.VOLUME -> volume(body)
            Msg.SET -> set(body, out)
            Msg.DRAFT -> draft(body, out)
            Msg.EQ_SET -> eqSet(body, out)
            Msg.EQ_PRESET -> eqPreset(body, out).also { a ->
                opts.log("eq preset ${body.int(Tags.EqPreset.op)} key=${body.str(Tags.EqPreset.key)} -> ${if (a is Answer.Fail) a.code else "ok " + EqPresetReply.from(Node.decode("EQ_PRESET.reply", (a as Answer.Ok).payload)).key}")
            }
            Msg.PLAY -> play(body)
            Msg.QUEUE_OP -> queueOp(body, out)
            Msg.LIB_GET -> libGet(body, out)
            Msg.ACTION -> action(body, out)
            Msg.IMG_BEGIN -> imgBegin(body, out).also { duringStream() }
            Msg.IMG_END -> imgEnd(body).also { duringStream() }
            Msg.IMG_LIST -> imgList(body, out)
            Msg.IMG_DEL -> imgDel(body)
            Msg.IMG_TAG -> imgTag(body)
            Msg.ART_GET -> artGet(body)
            Msg.LRC_PUT -> lrcPut(body)
            Msg.LRC_DEL -> lrcDel(body)
            Msg.LYR_GET -> lyrGet(body)
            Msg.LYR_OFFSET -> lyrOffset(body)
            Msg.UPD_BEGIN -> updBegin(body, out)
            Msg.UPD_END -> updEnd(body)
            Msg.PLAYLOG_GET -> playLogGet(body)
            Msg.PLAYLOG_ACK -> playLogAck(body)
            else -> Answer.Fail(Err.unknown, "unknown")
        }.also { follow() }
    }

    private fun playLogGet(b: Node): Answer {
        val after = b.long(Tags.PlaylogGet.after) ?: 0
        val r = playLog.page(after, b.long(Tags.PlaylogGet.count))
        opts.log("playlog get after $after: ${r.entries.size} entries to ${r.entries.lastOrNull()?.seq ?: after} of ${r.newest ?: 0}")
        return Answer.Ok(r.encode())
    }

    private fun playLogAck(b: Node): Answer {
        val upTo = b.long(Tags.PlaylogAck.upTo)
        val code = playLog.ack(b.long(Tags.PlaylogAck.log), upTo)
        opts.log("playlog ack up to $upTo, ${code?.let { Err.names[it] } ?: "ok"}")
        return if (code == null) Answer.Ok(state = true) else Answer.Fail(code, Err.names.getValue(code))
    }

    private fun viewServed(view: Int?) = view != Enums.View.genres || genres

    private val genres: Boolean get() = (faults.features ushr Groups.bit("genres")!!) and 1L == 1L

    private val queueAdd: Boolean get() = (faults.features ushr Groups.bit("queueAdd")!!) and 1L == 1L

    private fun changed() = Answer.Ok(state = true)

    private fun revCode(b: Node, libTag: Int, snapTag: Int, list: Boolean, need: Boolean): Int? {
        val snap = if (v13) b.long(snapTag) else null
        val rev = b.long(libTag)
        if (snap != null && list && rev == null) return Err.badValue
        if (snap != null && !list) return if (snap == lib.snapRev) null else Err.staleRev
        if (rev == null) return if (need) Err.staleRev else null
        return if (rev == lib.rev) null else Err.staleRev
    }

    private fun revFail(code: Int) = Answer.Fail(code, if (code == Err.staleRev) "stale rev" else "bad rev")

    private fun transport(b: Node): Answer {
        stream.transport(b)?.let { return it }
        mark()
        when (b.int(Tags.Transport.cmd)) {
            Enums.TransportCmd.toggle -> playing = !playing
            Enums.TransportCmd.play -> playing = true
            Enums.TransportCmd.pause -> playing = false
            Enums.TransportCmd.next -> advance(1, auto = false)
            Enums.TransportCmd.prev -> if (!stopped && position() >= PREV_RESTART_MS) positionMs = 0 else advance(-1, auto = false)
            Enums.TransportCmd.seek -> {
                val ms = b.long(Tags.Transport.seekMs) ?: return Answer.Fail(Err.badValue, "no seekMs")
                val t = lib.byId[queue.getOrNull(slot)] ?: return Answer.Fail(Err.busy, "nothing playing")
                if (ms > t.durationMs) return Answer.Fail(Err.badValue, "seek past end")
                positionMs = ms
            }
            Enums.TransportCmd.shuffle -> shuffle = b.bool(Tags.Transport.on) ?: return Answer.Fail(Err.badValue, "no on")
            Enums.TransportCmd.repeat -> repeat = b.int(Tags.Transport.repeat) ?: return Answer.Fail(Err.badValue, "no repeat")
            else -> return Answer.Fail(Err.badValue, "no cmd")
        }
        if (playing) stopped = false
        positionAt = now()
        if (playing && faults.cardRun?.start == CardRun.Start.Play && cardArmed) cardStart(now())
        return changed()
    }

    private val steps: IntArray = IntArray(101).also { t ->
        var n = FULL_VOLUME - V1_SPAN
        for (s in 1..100) {
            t[s] = n
            n += if (s <= V1_SPAN - 99) 2 else 1
        }
    }

    private fun volumePercent(v: Int): Int {
        if (!opts.curve) return (v.coerceIn(0, FULL_VOLUME) * 100 + FULL_VOLUME / 2) / FULL_VOLUME
        if (v <= 0) return 0
        var s = 100
        while (s > 1 && steps[s] > v) s--
        return s
    }

    private fun percentVolume(pct: Int) = if (opts.curve) steps[pct.coerceIn(0, 100)] else (pct.coerceIn(0, 100) * FULL_VOLUME + 50) / 100

    private fun stepVolume(v: Int, detents: Int, pct: Int): Int {
        var n = percentVolume(volumePercent(v) + detents * pct)
        if (detents > 0 && n <= v) n = v + 1
        if (detents < 0 && n >= v) n = v - 1
        return n.coerceIn(0, FULL_VOLUME)
    }

    private fun volume(b: Node): Answer {
        val set = b.int(Tags.Volume.set)
        val step = b.int(Tags.Volume.step)
        val pct = if (v12) b.int(Tags.Volume.percent) else null
        volume = when {
            set != null -> set
            pct != null -> percentVolume(pct)
            step == 0 -> volume
            step != null -> stepVolume(volume, step, prefs.getValue("volumeStep").toIntOrNull() ?: 5)
            else -> return Answer.Fail(Err.badValue, "no set or step")
        }.coerceIn(0, maxVolume)
        return changed()
    }

    private val readOnlyToSet = setOf("usbMode", "btCodec", "btCodecHeadphones")

    private fun accept(key: String, value: String): String? {
        val r = rows.firstOrNull { it.key == key } ?: return null
        if (Enums.RowFlag.readOnly in r.flags || key in readOnlyToSet) return null
        return when (r.kind) {
            Enums.RowKind.choice, Enums.RowKind.toggle -> value.takeIf { it in r.values }
            Enums.RowKind.steps -> {
                val n = value.toIntOrNull() ?: return null
                r.values.mapNotNull { it.toIntOrNull() }.minByOrNull { kotlin.math.abs(it - n) }?.toString()
            }
            Enums.RowKind.range -> {
                val n = value.toIntOrNull() ?: return null
                val lo = r.min ?: 0
                val st = (r.step ?: 1).coerceAtLeast(1)
                val snapped = lo + Math.floorDiv(n - lo + st / 2, st) * st
                val hi = r.max ?: Int.MAX_VALUE
                if (key == "accentHue" && hi != Int.MAX_VALUE) (lo + Math.floorMod(snapped - lo, hi - lo + st)).toString()
                else snapped.coerceIn(lo, hi).toString()
            }
            else -> null
        }
    }

    private fun set(b: Node, out: Out): Answer {
        val text = b.str(Tags.Set.text) ?: return Answer.Fail(Err.badValue, "no text")
        val kept = ArrayList<String>()
        val refused = ArrayList<String>()
        val next = LinkedHashMap(prefs)
        for (line in text.lines()) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val k = line.substring(0, eq).trim()
            val v = accept(k, line.substring(eq + 1).trim())
            if (v == null || k !in prefs) refused += k else {
                next[k] = v
                kept += k
            }
        }
        if (kept.isEmpty()) return Answer.Fail(Err.refused, "refused", refused)
        val differs = next != prefs
        if (differs) {
            prefs.putAll(next)
            settingsRev++
            volume = volume.coerceAtMost(maxVolume)
        }
        pushSettings(out)
        return Answer.Ok(SetReply(settingsRev, kept, refused).encode(), state = differs)
    }

    private fun draft(b: Node, out: Out): Answer {
        when (b.int(Tags.Draft.kind)) {
            Enums.DraftKind.usbMode -> {
                val r = rows.first { it.key == "usbMode" }
                if (Enums.RowFlag.readOnly in r.flags) return Answer.Fail(Err.refused, "refused")
                val v = b.str(Tags.Draft.`value`)?.takeIf { it in r.values } ?: return Answer.Fail(Err.badValue, "bad usb mode")
                prefs["usbMode"] = v
            }
            Enums.DraftKind.btCodec -> {
                val role = b.int(Tags.Draft.role) ?: Enums.BtRole.receiver
                if (role != Enums.BtRole.receiver) return Answer.Fail(Err.refused, "refused")
                val v = b.str(Tags.Draft.`value`)?.takeIf { it in rows.first { r -> r.key == "btCodec" }.values } ?: return Answer.Fail(Err.badValue, "bad codec")
                prefs["btCodec"] = v
            }
            Enums.DraftKind.sleep -> {
                val m = b.long(Tags.Draft.minutes) ?: return Answer.Fail(Err.badValue, "no minutes")
                val r = rows.firstOrNull { it.key == "sleep" }
                val ok = r?.max?.let { m in (r.min ?: 0).toLong()..it } ?: (m.toString() in r?.values.orEmpty())
                if (!ok) return Answer.Fail(Err.badValue, "bad minutes")
                sleepLeftS = m * 60
                sleepSetAt = now()
            }
            Enums.DraftKind.clock -> {
                val s = b.long(Tags.Draft.unixS) ?: return Answer.Fail(Err.badValue, "bad clock")
                if (s < 1735689600L || s > 4102444799L) return Answer.Fail(Err.badValue, "bad clock")
                wallAt = s * 1000 - now()
                zoneS = b.int(Tags.Draft.zoneS)
                if (v12 && b.bool(Tags.Draft.automatic) == true) {
                    opts.log("clock auto unixS=$s zoneS=${b.int(Tags.Draft.zoneS) ?: 0}")
                    return Answer.Ok(net.seilent.funecomp.proto.tlv { enum(Tags.DraftReply.status, Enums.DraftStatus.done) })
                }
            }
            else -> return Answer.Fail(Err.badValue, "no kind")
        }
        settingsRev++
        pushSettings(out)
        return Answer.Ok(net.seilent.funecomp.proto.tlv { enum(Tags.DraftReply.status, Enums.DraftStatus.done) }, state = true)
    }

    private fun eqSet(b: Node, out: Out): Answer {
        val o = b.int(Tags.EqSet.output) ?: return Answer.Fail(Err.badValue, "no output")
        val key = b.str(Tags.EqSet.key)
        val ints = b.node(Tags.EqSet.ints)?.let { Profile.from(it) }
        if (key != null && eqNames.none { it.key == key }) return Answer.Fail(Err.badValue, "unknown profile")
        if (ints != null) {
            if (!valid(ints)) return Answer.Fail(Err.badValue, "profile out of range")
            eqCustom[o] = ints
            eqKey[o] = "custom"
        }
        if (key != null) eqKey[o] = key
        if (key == null && ints == null) return Answer.Fail(Err.badValue, "no key or ints")
        pushEq(out, o)
        return Answer.Ok()
    }

    private fun listEq(): List<EqName> {
        val device = presets.entries.sortedWith { a, b -> eqNameOrder(a.key.removePrefix(EQ_DIR), b.key.removePrefix(EQ_DIR)) }
            .map { EqName(it.key, it.value.name, if (v15) true else null) }
        return listOf(EqName("off", "OFF"), EqName("custom", "CUSTOM")) + (device + cards).take(Tok.eq_profilesMax.toInt())
    }

    private fun eqTaken(name: String, except: String?): String? {
        val l = AutoEq.foldKey(name)
        eqNames.drop(2).firstOrNull { it.key != except && AutoEq.foldKey(it.name ?: "") == l }?.let { return it.key }
        val to = EQ_DIR + name + ".txt"
        return to.takeIf { it != except && (it in presets || name in faults.eqHidden) }
    }

    private fun eqNext(): String = generateSequence(1) { it + 1 }.map { "Preset $it" }.first { n ->
        eqNames.none { AutoEq.foldKey(it.name ?: "") == AutoEq.foldKey(n) } && EQ_DIR + n + ".txt" !in presets && n !in faults.eqHidden
    }

    private fun eqStored(out: Out, key: String?): Answer {
        eqNames = listEq()
        pushEq(out, Enums.EqOutput.wired)
        pushEq(out, Enums.EqOutput.bt)
        return Answer.Ok(EqPresetReply(key).encode())
    }

    private fun eqPreset(b: Node, out: Out): Answer {
        val op = b.int(Tags.EqPreset.op) ?: return Answer.Fail(Err.badValue, "no op")
        val name = b.str(Tags.EqPreset.name)
        val path = b.str(Tags.EqPreset.key)
        val replace = b.bool(Tags.EqPreset.replace) == true
        if (op == Enums.EqPresetOp.save) {
            val o = b.int(Tags.EqPreset.output)?.takeIf { it in Enums.EqOutput.names } ?: return Answer.Fail(Err.badValue, "no output")
            val sent = b.node(Tags.EqPreset.ints)?.let { Profile.from(it) }
            if (sent != null && (sent.bands.isEmpty() || !valid(sent))) return Answer.Fail(Err.badValue, "profile out of range")
            val n = name ?: eqNext()
            if (!AutoEq.validName(n)) return Answer.Fail(Err.badValue, "invalid name")
            val clash = eqTaken(n, null)
            if (clash != null && (!replace || clash !in presets)) return Answer.Fail(Err.exists, "exists")
            if (clash == null && presets.size >= Tok.eq_profilesMax.toInt()) return Answer.Fail(Err.noSpace, "no space")
            val to = clash ?: (EQ_DIR + n + ".txt")
            presets[to] = Preset(presets[to]?.name ?: n, sent ?: eqCustom[o])
            eqKey[o] = to
            return eqStored(out, to)
        }
        if (op != Enums.EqPresetOp.remove && op != Enums.EqPresetOp.rename) return Answer.Fail(Err.badValue, "bad op")
        if (path == null || (op == Enums.EqPresetOp.rename && name == null)) return Answer.Fail(Err.badValue, "missing tag")
        if (path == "off" || path == "custom") return Answer.Fail(Err.readOnly, "read only")
        if (eqNames.none { it.key == path }) return Answer.Fail(Err.notFound, "not found")
        val was = presets[path] ?: return Answer.Fail(Err.readOnly, "read only")
        if (op == Enums.EqPresetOp.remove) {
            presets.remove(path)
            for (o in eqKey.indices) if (eqKey[o] == path) eqKey[o] = "off"
            return eqStored(out, null)
        }
        val n = name ?: return Answer.Fail(Err.badValue, "missing tag")
        if (n == was.name) return eqStored(out, path)
        if (!AutoEq.validName(n)) return Answer.Fail(Err.badValue, "invalid name")
        val clash = eqTaken(n, path)
        if (clash != null && (!replace || clash !in presets)) return Answer.Fail(Err.exists, "exists")
        val to = clash ?: (EQ_DIR + n + ".txt")
        presets.remove(path)
        presets[to] = Preset(clash?.let { presets[it]?.name } ?: n, was.profile)
        for (o in eqKey.indices) if (eqKey[o] == path) eqKey[o] = to
        return eqStored(out, to)
    }

    private fun valid(p: Profile): Boolean {
        fun ok(b: Band) = b.type in Enums.BandType.off..Enums.BandType.highShelf && b.grid in -1..60 && b.milliHz in 20_000..20_000_000 &&
            b.gain10 in -240..120 && b.q100 in 10..1000
        return p.preamp10 in -240..0 && p.bands.size <= 10 && p.bands.all { ok(it) }
    }

    private fun play(b: Node): Answer {
        stream.refuse()?.let { return it }
        val ids = b.longs(Tags.Play.track)
        val list: List<Long> = if (ids.isNotEmpty()) {
            if (ids.size > Proto.playMaxTracks) return Answer.Fail(Err.tooBig, "too many tracks")
            if (ids.any { it !in lib.byId }) return Answer.Fail(Err.staleRev, "stale rev")
            ids
        } else {
            val view = b.int(Tags.Play.view) ?: return Answer.Fail(Err.badValue, "no view")
            if (!viewServed(view)) return Answer.Fail(Err.badValue, "bad view")
            revCode(b, Tags.Play.libRev, Tags.Play.snapRev, listView(view), true)?.let { return revFail(it) }
            lib.tracksOf(view, b.str(Tags.Play.parent) ?: "") ?: return Answer.Fail(Err.staleRev, "stale rev")
        }
        if (list.isEmpty()) return Answer.Fail(Err.badValue, "nothing to play")
        val start = n(b.long(Tags.Play.start), 0)
        if (start !in list.indices) return Answer.Fail(Err.badValue, "bad start")
        val sh = b.bool(Tags.Play.shuffle) ?: false
        queue = if (sh) (listOf(list[start]) + (list - list[start]).shuffled()).toMutableList() else list.toMutableList()
        slot = if (sh) 0 else start
        shuffle = sh
        playing = true
        stopped = false
        playFresh = true
        positionMs = 0
        positionAt = now()
        queueRev++
        return changed()
    }

    private fun queuePage(out: Out, offset: Int, count: Int) {
        val items = queue.map { id ->
            val t = lib.byId[id]
            when {
                v12 && t != null -> QueueItem(id, t.title, t.artists, t.mbids, t.album, t.durationMs, hasSynced(id), if (v14) hasPlain(id) else null)
                v11 && t != null -> QueueItem(id, t.title, t.artists, t.mbids)
                else -> QueueItem(id, t?.title)
            }
        }
        val end = minOf(items.size, offset + count)
        val fixed = Queue(queueRev, PageInfo(0, 0, false), emptyList()).encode().size + 8
        var at = offset
        do {
            val n = PageInfo.fill(items.map { it.size }, at, fixed, count = end - at)
            send(out, Msg.QUEUE, Queue(queueRev, PageInfo(at.toLong(), items.size.toLong(), at + n < items.size), items.subList(at, at + n)).encode())
            at += n
        } while (at < end)
    }

    private fun queueOp(b: Node, out: Out): Answer {
        val cmd = b.int(Tags.QueueOp.cmd) ?: return Answer.Fail(Err.badValue, "no cmd")
        if (cmd == Enums.QueueCmd.add) return add(b)
        val rev = b.long(Tags.QueueOp.rev)
        if (rev != null && rev != queueRev) return Answer.Fail(Err.staleRev, "stale rev")
        if (cmd != Enums.QueueCmd.page && rev == null) return Answer.Fail(Err.badValue, "no rev")
        val at = b.long(Tags.QueueOp.slot)?.let { n(it, 0) }
        fun inQueue(i: Int?) = i != null && i in queue.indices
        mark()
        when (cmd) {
            Enums.QueueCmd.page -> {
                val offset = n(b.long(Tags.QueueOp.offset), 0)
                if (offset > queue.size) return Answer.Fail(Err.badValue, "offset past end")
                queuePage(out, offset, n(b.long(Tags.QueueOp.count), 100))
                return Answer.Ok()
            }
            Enums.QueueCmd.playAt -> {
                stream.refuse()?.let { return it }
                if (!inQueue(at)) return Answer.Fail(Err.badValue, "bad slot")
                slot = at!!
                positionMs = 0
                playing = true
                stopped = false
                playFresh = true
            }
            Enums.QueueCmd.remove -> {
                if (!inQueue(at)) return Answer.Fail(Err.badValue, "bad slot")
                queue.removeAt(at!!)
                if (at < slot) slot-- else if (at == slot) positionMs = 0
                slot = slot.coerceIn(0, maxOf(0, queue.size - 1))
            }
            Enums.QueueCmd.move -> {
                val to = b.long(Tags.QueueOp.to)?.let { n(it, 0) }
                if (!inQueue(at) || !inQueue(to)) return Answer.Fail(Err.badValue, "bad slot")
                val cur = queue[slot]
                queue.add(to!!, queue.removeAt(at!!))
                slot = queue.indexOf(cur)
            }
            Enums.QueueCmd.playNext -> {
                val (ids, fail) = tracks(b)
                if (fail != null) return fail
                queue.addAll(minOf(slot + 1, queue.size), ids)
                positionAt = now()
                queueRev++
                return Answer.Ok(if (queueAdd) QueueOpReply(ids.size.toLong(), queueRev).encode() else ByteArray(0), state = true)
            }
            Enums.QueueCmd.clear -> {
                queue.clear()
                slot = 0
                playing = false
                stopped = true
                positionMs = 0
            }
            else -> return Answer.Fail(Err.badValue, "bad cmd")
        }
        positionAt = now()
        queueRev++
        return changed()
    }

    private fun tracks(b: Node): Pair<List<Long>, Answer.Fail?> {
        fun no(code: Int, text: String) = emptyList<Long>() to Answer.Fail(code, text)
        val ids = b.longs(Tags.QueueOp.track)
        val view = b.int(Tags.QueueOp.view)
        if (ids.isEmpty() && view != null && queueAdd) {
            if (!viewServed(view)) return no(Err.badValue, "bad view")
            revCode(b, Tags.QueueOp.libRev, Tags.QueueOp.snapRev, listView(view), true)?.let { return no(it, if (it == Err.staleRev) "stale rev" else "bad rev") }
            val list = lib.tracksOf(view, b.str(Tags.QueueOp.parent) ?: "") ?: return no(Err.staleRev, "stale rev")
            return if (list.isEmpty()) no(Err.badValue, "no tracks") else list to null
        }
        if (ids.isEmpty() || ids.any { it !in lib.byId }) return no(Err.badValue, "bad tracks")
        return ids to null
    }

    private fun add(b: Node): Answer {
        if (!queueAdd) return Answer.Fail(Err.badValue, "bad cmd")
        val (ids, fail) = tracks(b)
        if (fail != null) return fail
        mark()
        if (queue.isEmpty() || stopped) {
            queue = ids.toMutableList()
            slot = 0
            playing = false
            stopped = false
            positionMs = 0
            playFresh = true
        } else {
            queue.addAll(ids)
        }
        positionAt = now()
        queueRev++
        return Answer.Ok(QueueOpReply(ids.size.toLong(), queueRev).encode(), state = true)
    }

    private fun libGet(b: Node, out: Out): Answer {
        val view = b.int(Tags.LibGet.view) ?: return Answer.Fail(Err.badValue, "no view")
        if (!viewServed(view)) return Answer.Fail(Err.badValue, "bad view")
        revCode(b, Tags.LibGet.libRev, Tags.LibGet.snapRev, listView(view), false)?.let { return revFail(it) }
        val parent = b.str(Tags.LibGet.parent) ?: ""
        val rows = lib.rows(view, parent)?.map { if (v11) it else it.copy(mbid = null) } ?: return Answer.Fail(Err.staleRev, "stale rev")
        val offset = n(b.long(Tags.LibGet.offset), 0)
        if (offset > rows.size) return Answer.Fail(Err.badValue, "offset past end")
        val end = minOf(rows.size, offset + n(b.long(Tags.LibGet.count), 100))
        val fixed = Lib(lib.rev, view, parent, PageInfo(0, 0, false), emptyList(), if (v13) lib.snapRev else null).encode().size + 8
        var at = offset
        do {
            val n = PageInfo.fill(rows.map { it.size }, at, fixed, count = end - at)
            send(out, Msg.LIB, Lib(lib.rev, view, parent, PageInfo(at.toLong(), rows.size.toLong(), at + n < rows.size), rows.subList(at, at + n), if (v13) lib.snapRev else null).encode())
            at += n
        } while (at < end)
        return Answer.Ok()
    }

    private fun action(b: Node, out: Out): Answer {
        when (b.int(Tags.Action.cmd)) {
            Enums.ActionCmd.rescan -> rescan(rescanLib.also { rescanLib = null })
            Enums.ActionCmd.shuffleLibrary -> {
                stream.refuse()?.let { return it }
                if (lib.tracks.isEmpty()) return Answer.Fail(Err.noLibrary, "no library")
                queue = lib.tracks.map { it.id }.shuffled().toMutableList()
                slot = 0
                shuffle = true
                playing = true
                positionMs = 0
                positionAt = now()
                queueRev++
            }
            Enums.ActionCmd.favorite -> {
                val id = b.long(Tags.Action.track) ?: return Answer.Fail(Err.badValue, "no track")
                if (id !in lib.byId) return Answer.Fail(Err.staleRev, "stale rev")
                if (favsBusy > 0) {
                    favsBusy--
                    return Answer.Fail(Err.busy, faults.favsBusyText)
                }
                if (faults.staleState) send(out, Msg.STATE, state())
                if (!lib.favorites.add(id)) lib.favorites.remove(id)
                lib.rev++
            }
            else -> return Answer.Fail(Err.badValue, "no cmd")
        }
        return changed()
    }

    internal fun fold(name: String) = ArtKey.fold(name)

    fun source(v: Int) = synchronized(lock) { source = v }

    fun output(v: Int) = synchronized(lock) {
        output = v
        btEdge()
    }

    private fun appSource(out: Out?) = allowed(Msg.IMG_BEGIN) && (faults.features ushr Groups.bit("imgSource")!!) and 1L == 1L &&
        out != null && ((linked[out] ?: 0L) ushr Groups.bit("imgSource")!!) and 1L == 1L

    private fun btPlaying() = playing && (output == Enums.Output.btHeadset || output == Enums.Output.phoneReceiver)

    private fun btEdge() {
        val p = push ?: return
        val o = pushOut ?: return
        if (p.full && !pushDropped && btPlaying()) {
            opts.log("source abort bluetooth name=${p.name} at=${p.data.size()}")
            abort(o, p, Enums.AbortCode.busy)
        }
    }

    fun rescan(next: Library?) = synchronized(lock) {
        faults.scanMs?.let { scanUntil = now() + it }
        val old = lib
        if (next != null) {
            next.rev = old.rev
            next.snapRev = old.snapRev
            next.favorites += old.favorites.filter { it in next.byId }
            lib = next
            queue = next.tracks.map { it.id }.toMutableList()
            slot = 0
            queueRev++
        }
        lib.rev++
        lib.snapRev++
        if (v19) prune()
    }

    private fun prune() {
        val keep = lib.rows(Enums.View.artists, "").orEmpty().mapTo(HashSet()) { fold(it.name ?: "") }
        val joined = lib.tracks.filter { it.artists.size > 1 && it.albumArtist.isBlank() }.mapTo(HashSet()) { fold(it.artist) }
        val gone = images.entries.filter { (k, v) -> v.name.isNotBlank() && k !in keep && (v.origins == LIBRARY || (v.origins == 0 && k in joined)) }
        for ((k, v) in gone) {
            images.remove(k)
            opts.imgDir?.let { dir -> if (images.values.none { it.crc == v.crc }) File(dir, "%08x.jpg".format(v.crc)).delete() }
            opts.log("pruned name=${v.name}")
        }
    }

    private fun originBit(b: Node, tag: Int): Int = when (b.int(tag)) {
        null -> 0
        Enums.ImgOrigin.phone -> PHONE
        else -> LIBRARY
    }

    private fun origins(bits: Int) = listOfNotNull(Enums.ImgOrigin.library.takeIf { bits and LIBRARY != 0 }, Enums.ImgOrigin.phone.takeIf { bits and PHONE != 0 })

    private fun crc(b: ByteArray, n: Int = b.size) = CRC32().apply { update(b, 0, n) }.value

    private val chunk = Proto.maxPayload - Proto.imgDataReserve

    private fun abort(out: Out, p: Partial, code: Int) {
        send(out, Msg.IMG_ABORT, ImgAbort(p.name, p.data.size().toLong(), code).encode())
        pushDropped = true
    }

    private fun imgBegin(b: Node, out: Out): Answer {
        val name = b.str(Tags.ImgBegin.name)?.takeIf { it.isNotBlank() } ?: return Answer.Fail(Err.badValue, "no name")
        val total = b.long(Tags.ImgBegin.total) ?: return Answer.Fail(Err.badValue, "no total")
        val sum = b.long(Tags.ImgBegin.crc) ?: return Answer.Fail(Err.badValue, "no crc")
        val w = b.int(Tags.ImgBegin.w)
        val h = b.int(Tags.ImgBegin.h)
        if (b.int(Tags.ImgBegin.kind) != Enums.ImgKind.artist) return Answer.Fail(Err.badValue, "bad kind")
        val full = b.bool(Tags.ImgBegin.full) == true
        val key = fold(name)
        val meta = Meta(b.bool(Tags.ImgBegin.wrongArtist), b.str(Tags.ImgBegin.source), b.str(Tags.ImgBegin.credit))
        val fx = b.int(Tags.ImgBegin.focusX)
        val fy = b.int(Tags.ImgBegin.focusY)
        if (full) {
            if (!appSource(out)) return Answer.Fail(Err.badValue, "bad value")
            if (total > Proto.imgSrcMaxBytes) return Answer.Fail(Err.tooBig, "too big")
            if (w == null || h == null || w !in 1..Proto.imgSrcMaxSide || h !in 1..Proto.imgSrcMaxSide) return Answer.Fail(Err.badValue, "bad value")
            if ((fx == null) != (fy == null)) return Answer.Fail(Err.badValue, "bad value")
            val focus = if (fx != null && fy != null) fx to fy else null
            fulls += Triple(name, total, focus)
            srcs[key]?.takeIf { it.crc == sum && it.bytes.size.toLong() == total }?.let {
                it.aside = false
                it.meta = meta
                it.focus = focus
                push?.let { q -> if (!pushDropped) abort(out, q, Enums.AbortCode.superseded) }
                push = null
                opts.log("source rearm name=$name")
                return Answer.Ok(ImgBeginReply(total, sum).encode())
            }
            if (btPlaying()) return Answer.Fail(Err.busy, "busy")
            if (faults.srcNoSpace) return Answer.Fail(Err.noSpace, "no space")
        } else {
            if (total > Proto.imgMaxBytes) return Answer.Fail(Err.tooBig, "too big")
            if (w != board.backdropW || h != board.backdropH) return Answer.Fail(Err.badValue, "wrong size")
        }
        push?.let { if (!pushDropped) abort(out, it, Enums.AbortCode.superseded) }
        val bit = if (v19) originBit(b, Tags.ImgBegin.origin) else 0
        val origin = b.int(Tags.ImgBegin.origin)
        begins += name to origin
        opts.log("begin name=$name origin=${origin?.let { Enums.ImgOrigin.names[it] } ?: ""}")
        images[key]?.let { it.origins = it.origins or bit }
        images[key]?.takeIf { !full }?.let { if (it.crc == sum && it.bytes.size.toLong() == total) {
            srcs[key]?.let { s -> if (!s.aside) {
                s.aside = true
                opts.log("source aside name=${s.name}")
            } }
            if (it.meta != meta) {
                it.meta = meta
                opts.log("meta name=${it.name} ${line(meta)}")
            }
            push = null
            return Answer.Ok(ImgBeginReply(total, sum).encode())
        } }
        val kept = partials[key]?.takeIf { it.name == name && it.total == total && it.crc == sum && it.w == w && it.h == h && it.full == full }
        val p = kept ?: Partial(name, total, sum, w ?: 0, h ?: 0, full = full).also { partials[key] = it }
        if (full) p.focus = if (fx != null && fy != null) fx to fy else null
        p.origins = p.origins or bit
        val whole = p.data.size() / chunk * chunk
        if (whole != p.data.size()) {
            val bytes = p.data.toByteArray().copyOf(whole)
            p.data.reset()
            p.data.write(bytes)
        }
        push = p
        pushOut = out
        pushMbid = b.str(Tags.ImgBegin.mbid)
        pushMeta = meta
        pushDropped = false
        val bytes = p.data.toByteArray()
        return Answer.Ok(ImgBeginReply(bytes.size.toLong(), if (bytes.isEmpty()) 0 else crc(bytes)).encode())
    }

    private fun duringStream() {
        if (!stream.streaming()) return
        streamFrames.incrementAndGet()
        opts.log("img during stream")
    }

    fun imgData(b: Node, out: Out) = synchronized(lock) {
        cardEdge()
        duringStream()
        val p = push ?: return@synchronized
        if (pushDropped) {
            droppedData++
            return@synchronized
        }
        val off = b.long(Tags.ImgData.offset) ?: return@synchronized abort(out, p, Enums.AbortCode.badValue)
        val data = b.bytes(Tags.ImgData.`data`) ?: return@synchronized abort(out, p, Enums.AbortCode.badValue)
        if (off == 0L && p.data.size() > 0) p.data.reset()
        if (off != p.data.size().toLong()) return@synchronized abort(out, p, Enums.AbortCode.offsetMismatch)
        if (off + data.size > p.total) return@synchronized abort(out, p, Enums.AbortCode.badValue)
        p.data.write(data)
        (faults.cardRun?.start as? CardRun.Start.Img)?.let { if (p.data.size() >= it.bytes) cardStart(now()) }
        cardEdge()
        if (pushDropped) return@synchronized
        val at = faults.abortAt
        if (at != null && !abortFired && p.data.size() >= at) {
            abortFired = true
            abort(out, p, Enums.AbortCode.writeError)
        }
    }

    private fun endSource(p: Partial, key: String, bytes: ByteArray): Answer {
        val i = SourceJpeg.read(bytes)
        if (i == null || !i.baseline || !i.upright || i.w != p.w || i.h != p.h) {
            partials.remove(key)
            opts.log("source refused name=${p.name} not baseline")
            return Answer.Fail(Err.badValue, "bad value")
        }
        if (srcRefused < faults.srcRefuse) {
            srcRefused++
            partials.remove(key)
            opts.log("source refused name=${p.name} fault")
            return Answer.Fail(Err.badValue, "bad value")
        }
        partials.remove(key)
        val old = images[key]
        val failed = key in faults.srcFail
        if (!failed) images.remove(key)
        srcs[key] = Src(p.name, pushMbid, p.crc, bytes, p.w, p.h, false, pushMeta, p.focus, opts.wall() / 1000, (srcs[key]?.origins ?: old?.origins ?: 0) or p.origins, failed)
        opts.log("stored source name=${p.name} ${p.w}x${p.h} bytes=${bytes.size} focus=${p.focus?.let { "${it.first},${it.second}" } ?: "none"}")
        return Answer.Ok()
    }

    private fun imgEnd(b: Node): Answer {
        val p = push ?: return Answer.Fail(Err.badValue, "no push")
        if (pushDropped) return Answer.Fail(Err.refused, "aborted")
        val sum = b.long(Tags.ImgEnd.crc)
        val bytes = p.data.toByteArray()
        if (bytes.size.toLong() != p.total) return Answer.Fail(Err.badValue, "short")
        val key = fold(p.name)
        push = null
        if (sum != p.crc || crc(bytes) != p.crc) {
            partials.remove(key)
            return Answer.Fail(Err.crc, "crc")
        }
        if (p.full) return endSource(p, key, bytes)
        if (!Jpeg.baseline(bytes, p.w, p.h)) {
            partials.remove(key)
            return Answer.Fail(Err.badValue, "not baseline or wrong size")
        }
        partials.remove(key)
        srcs[key]?.let { s -> if (!s.aside) {
            s.aside = true
            opts.log("source aside name=${s.name}")
        } }
        images[key] = Stored(p.name, pushMbid, p.crc, bytes, ++storeClock, pushMeta, (images[key]?.origins ?: 0) or p.origins, opts.wall() / 1000)
        evict(key)
        opts.imgDir?.let { dir ->
            val f = File(dir.apply { mkdirs() }, "%08x.jpg".format(p.crc))
            f.writeBytes(bytes)
            opts.log("stored name=${p.name} mbid=${pushMbid ?: ""} ${p.w}x${p.h} bytes=${bytes.size} ${line(pushMeta)} file=${f.path}")
        }
        return Answer.Ok()
    }

    private fun line(m: Meta) = "wrong=${if (m.wrong == true) 1 else 0} source=${m.source ?: ""} credit=${m.credit ?: ""}"

    private fun bytes() = images.values.sumOf { it.bytes.size.toLong() }

    private fun evict(keep: String) {
        while (bytes() > opts.imgCap) {
            val old = images.entries.filter { it.key != keep }.minByOrNull { it.value.at } ?: return
            images.remove(old.key)
            opts.log("evicted name=${old.value.name}")
        }
    }

    private fun imgList(b: Node, out: Out?): Answer {
        val src = appSource(out)
        val all = if (!src) (images.keys + srcs.keys).map { k ->
            val i = images[k]
            val s = srcs[k]
            ImgEntry(i?.name ?: s!!.name, i?.mbid ?: s?.mbid, if (v19) origins((i?.origins ?: 0) or (s?.origins ?: 0)) else emptyList())
        } else (images.keys + srcs.keys).map { k ->
            val i = images[k]
            val s = srcs[k]
            val shows = s != null && !s.aside && !s.failed
            ImgEntry(
                s?.name ?: i!!.name, s?.mbid ?: i?.mbid, origins((i?.origins ?: 0) or (s?.origins ?: 0)),
                shows, s?.shownS ?: i?.shownS, if (shows) s!!.crc else i?.crc, s?.takeIf { it.aside || it.failed }?.crc,
            )
        }
        val offset = n(b.long(Tags.ImgList.offset), 0).coerceAtMost(all.size)
        val count = n(b.long(Tags.ImgList.count), 100)
        val size = if (!v11) null else if (src) bytes() + srcs.values.sumOf { it.bytes.size.toLong() } else bytes()
        val room = if (src) faults.srcRoom else null
        val cap = if (!v11) null else if (src) size!! + maxOf(room!!, opts.imgCap - bytes()) else opts.imgCap
        val fixed = ImgListReply(PageInfo(0, 0, false), emptyList(), size, cap, room).encode().size + 8
        val n = PageInfo.fill(all.map { it.size }, offset, fixed, count = count)
        return Answer.Ok(ImgListReply(PageInfo(offset.toLong(), all.size.toLong(), offset + n < all.size), all.subList(offset, offset + n), size, cap, room).encode())
    }

    private fun imgDel(b: Node): Answer {
        val name = b.str(Tags.ImgDel.name) ?: return Answer.Fail(Err.badValue, "no name")
        val gone = srcs.remove(fold(name))
        val old = images.remove(fold(name))
        if (old == null && gone != null) {
            opts.log("deleted name=$name")
            return Answer.Ok()
        }
        if (old == null) {
            opts.log("absent name=$name")
            return Answer.Ok()
        }
        opts.imgDir?.let { dir -> if (images.values.none { it.crc == old.crc }) File(dir, "%08x.jpg".format(old.crc)).delete() }
        opts.log("deleted name=$name")
        return Answer.Ok()
    }

    private fun imgTag(b: Node): Answer {
        val name = b.str(Tags.ImgTag.name)?.takeIf { it.isNotEmpty() }
        if (name == null || b.int(Tags.ImgTag.origin) == null) return Answer.Fail(Err.badValue, "bad value")
        val s = images[fold(name)] ?: return Answer.Fail(Err.notFound, "not found").also { opts.log("tag absent name=$name") }
        s.origins = s.origins or originBit(b, Tags.ImgTag.origin)
        opts.log("tag name=$name origin=${Enums.ImgOrigin.names[b.int(Tags.ImgTag.origin)]}")
        return Answer.Ok()
    }

    private fun artGet(b: Node): Answer {
        val track = b.long(Tags.ArtGet.track) ?: return Answer.Fail(Err.badValue, "no track")
        val px = b.int(Tags.ArtGet.px) ?: return Answer.Fail(Err.badValue, "no px")
        return artGet(track, px, b.long(Tags.ArtGet.offset) ?: 0, b.long(Tags.ArtGet.have))
    }

    private fun artGet(track: Long, px: Int, offset: Long, have: Long?): Answer {
        if (track == 0L) return phoneArt(px, offset, have)
        if (track !in lib.byId) return Answer.Fail(Err.staleRev, "stale rev")
        val s = px.coerceIn(Proto.artMinPx, Proto.artMaxPx)
        val a = arts[track to s]?.takeIf { it.source == source(track) } ?: encode(track, s)?.also { arts[track to s] = it } ?: return Answer.Fail(Err.notFound, "no art")
        return serve(a, track, px, s, offset, have)
    }

    private fun phoneArt(px: Int, offset: Long, have: Long?): Answer {
        if (stream.current() == null) return Answer.Fail(Err.staleRev, "stale rev")
        val c = stream.cover() ?: return Answer.Fail(Err.notFound, "no art")
        return serve(Art(c.crc, c.w, c.h, c.bytes, 0L to 0L), 0L, px, px, offset, have)
    }

    private fun serve(a: Art, track: Long, px: Int, s: Int, offset: Long, have: Long?): Answer {
        if (offset == 0L && have == a.crc) {
            opts.log("art have track=$track px=$s")
            return Answer.Ok(ArtGetReply(a.crc, a.bytes.size.toLong(), a.w, a.h, 0, null).encode())
        }
        if (offset > 0 && have != null && have != a.crc) {
            opts.log("art restart track=$track px=$s")
            return artGet(track, px, 0, null)
        }
        if (offset !in 0..a.bytes.size.toLong()) return Answer.Fail(Err.badValue, "bad offset")
        val end = minOf(a.bytes.size.toLong(), offset + Proto.artChunk)
        val data = a.bytes.copyOfRange(offset.toInt(), end.toInt())
        opts.log("art get track=$track px=$s offset=$offset bytes=${data.size} total=${a.bytes.size}")
        return Answer.Ok(
            if (offset == 0L) ArtGetReply(a.crc, a.bytes.size.toLong(), a.w, a.h, 0, data).encode()
            else ArtGetReply(null, null, null, null, offset, data).encode(),
        )
    }

    private fun source(track: Long): Pair<Long, Long>? {
        val f = File(opts.artDir ?: return null, "${lib.albumKey(track) ?: return null}.jpg")
        return if (f.isFile) f.lastModified() to f.length() else null
    }

    private fun encode(track: Long, px: Int): Art? {
        val dir = opts.artDir ?: return null
        val key = lib.albumKey(track) ?: return null
        val f = File(dir, "$key.jpg").takeIf { it.isFile } ?: return null
        val src = ImageIO.read(ByteArrayInputStream(f.readBytes())) ?: return null
        val side = minOf(src.width, src.height)
        val s = minOf(px, side)
        val out = BufferedImage(s, s, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        g.drawImage(src, 0, 0, s, s, x, y, x + side, y + side, null)
        g.dispose()
        val bytes = ByteArrayOutputStream()
        val w = ImageIO.getImageWritersByFormatName("jpeg").next()
        ImageIO.createImageOutputStream(bytes).use { ios ->
            w.output = ios
            val p = w.defaultWriteParam
            p.compressionMode = ImageWriteParam.MODE_EXPLICIT
            p.compressionQuality = ART_QUALITY
            w.write(null, IIOImage(out, null, null), p)
        }
        w.dispose()
        val jpeg = bytes.toByteArray()
        return Art(crc(jpeg), s, s, jpeg, f.lastModified() to f.length())
    }

    private fun lrcPut(b: Node): Answer {
        val track = b.long(Tags.LrcPut.track) ?: return Answer.Fail(Err.badValue, "no track")
        if (b.long(Tags.LrcPut.libRev) == null && (!v13 || b.long(Tags.LrcPut.snapRev) == null)) return Answer.Fail(Err.badValue, "no rev")
        revCode(b, Tags.LrcPut.libRev, Tags.LrcPut.snapRev, false, true)?.let { return revFail(it) }
        if (track !in lib.byId) return Answer.Fail(Err.staleRev, "stale rev")
        val text = b.bytes(Tags.LrcPut.text) ?: return Answer.Fail(Err.badValue, "no text")
        if (text.size > Proto.lrcMaxBytes) return Answer.Fail(Err.tooBig, "too big")
        if (b.long(Tags.LrcPut.crc) != crc(text)) return Answer.Fail(Err.crc, "crc")
        val s = String(text, Charsets.UTF_8)
        val synced = SYNCED.containsMatchIn(s)
        if (!synced && !v14) return Answer.Fail(Err.badValue, "not synced lrc")
        val parsed = Lyr.parse(s) ?: return Answer.Fail(Err.badValue, "empty")
        if (!synced && hasSynced(track)) return Answer.Fail(Err.refused, "refused")
        opts.lrcDir?.let { File(it.apply { mkdirs() }, "${java.lang.Long.toUnsignedString(track)}.lrc").writeBytes(text) }
        lyrs[track] = parsed
        val same = phone[track]?.contentEquals(text) == true
        phone[track] = text
        opts.log("stored lrc track=${java.lang.Long.toUnsignedString(track)} bytes=${text.size} kind=${if (synced) "synced" else "plain"}")
        if (!same && offsets.remove(track) != null) opts.log("lyr offset reset track=${java.lang.Long.toUnsignedString(track)}")
        return Answer.Ok(state = queue.getOrNull(slot) == track)
    }

    private fun lrcDel(b: Node): Answer {
        val track = b.long(Tags.LrcDel.track) ?: return Answer.Fail(Err.badValue, "no track")
        opts.lrcDir?.let { File(it, "${java.lang.Long.toUnsignedString(track)}.lrc").delete() }
        phone.remove(track)
        return Answer.Ok(state = lyrs.remove(track) != null && queue.getOrNull(slot) == track)
    }

    private fun lyrGet(b: Node): Answer {
        val track = b.long(Tags.LyrGet.track) ?: return Answer.Fail(Err.badValue, "no track")
        if (track !in lib.byId) return Answer.Fail(Err.staleRev, "stale rev")
        val l = served(track) ?: return Answer.Fail(Err.notFound, "not found")
        val offset = b.long(Tags.LyrGet.offset) ?: 0
        val have = b.long(Tags.LyrGet.have)
        val id = java.lang.Long.toUnsignedString(track)
        val ms = offsetTag(track)
        if (offset == 0L && have == l.crc) {
            opts.log("lyr have track=$id")
            return Answer.Ok(LyrGetReply(l.crc, l.kind, l.lines.size.toLong(), 0, false, emptyList(), ms).encode())
        }
        if (offset > 0 && have != null && have != l.crc) {
            opts.log("lyr restart track=$id")
            return lyrPage(id, l, 0, ms)
        }
        if (offset >= l.lines.size) return Answer.Fail(Err.badValue, "offset over total")
        return lyrPage(id, l, offset.toInt(), ms)
    }

    private fun lyrOffset(b: Node): Answer {
        val track = b.long(Tags.LyrOffset.track) ?: return Answer.Fail(Err.badValue, "no track")
        val ms = b.int(Tags.LyrOffset.ms)
        if (ms != null && ms !in -Proto.lyrOffsetMaxMs..Proto.lyrOffsetMaxMs) return Answer.Fail(Err.badValue, "out of range")
        if (lib.tracks.isEmpty()) return Answer.Fail(Err.noLibrary, "no library")
        if (track !in lib.byId) return Answer.Fail(Err.staleRev, "stale rev")
        val id = java.lang.Long.toUnsignedString(track)
        val old = offsets[track] ?: 0
        if (ms == null) {
            opts.log("lyr offset read track=$id ms=$old")
            return Answer.Ok(LyrOffsetReply(old).encode())
        }
        if (ms == 0) offsets.remove(track) else offsets[track] = ms
        opts.log("lyr offset set track=$id ms=$ms")
        return Answer.Ok(LyrOffsetReply(ms).encode(), state = ms != old && queue.getOrNull(slot) == track)
    }

    private fun lyrPage(id: String, l: Lyr, at: Int, ms: Int?): Answer {
        val total = l.lines.size.toLong()
        val head = if (at == 0) LyrGetReply(l.crc, l.kind, total, 0, true, emptyList(), ms) else LyrGetReply(null, null, null, at.toLong(), true, emptyList())
        val n = PageInfo.fill(l.lines.map { it.size }, at, head.encode().size)
        val more = at + n < l.lines.size
        opts.log("lyr get track=$id offset=$at lines=$n total=$total")
        return Answer.Ok(head.copy(more = more, lines = l.lines.subList(at, at + n)).encode())
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun updName() = when (board.name) {
        "v1" -> "v1.upt"
        "r1" -> "r1.upt"
        else -> "update.upt"
    }

    private fun updBegin(b: Node, out: Out): Answer {
        val dir = opts.updDir ?: return Answer.Fail(Err.refused, "no update dir")
        val name = b.str(Tags.UpdBegin.board) ?: return Answer.Fail(Err.badValue, "no board")
        val size = b.long(Tags.UpdBegin.size) ?: return Answer.Fail(Err.badValue, "no size")
        val md5 = b.str(Tags.UpdBegin.md5)?.lowercase()?.takeIf { MD5.matches(it) } ?: return Answer.Fail(Err.badValue, "bad md5")
        if (name != board.name) return Answer.Fail(Err.badValue, "wrong board")
        updSigs += b.bytes(Tags.UpdBegin.sig) ?: ByteArray(0)
        val signed = (faults.features ushr Groups.bit("updSig")!!) and 1L == 1L
        val sig = b.bytes(Tags.UpdBegin.sig)?.takeIf { signed }
        val st = sig?.let { FwStatement.parse(it) }
        if (size > 0xFFFFFFFFL) return Answer.Fail(Err.badValue, "size")
        if (signed) {
            val why = when {
                sig == null || sig.isEmpty() -> "unsigned"
                st == null -> "signature"
                opts.updKeys?.contains(st.keyId) == false -> "key"
                st.board != board.name -> "board"
                st.size != size -> "size"
                else -> null
            }
            if (why != null) return Answer.Fail(Err.badSignature, why)
        }
        if (battery() < UPDATE_BATTERY_MIN && !opts.battery.charging) return Answer.Fail(Err.refused, "battery")
        val other = File(dir, if (board.name == "ap80") "ap80.upt" else "update.upt")
        if (other.exists()) return Answer.Fail(Err.badValue, "remove ${other.name}")
        if (st != null) {
            faults.sigBad?.let { text -> if (faults.sigBadLeft.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) return Answer.Fail(Err.badSignature, text) }
            if (st.seq < opts.updSeq) return Answer.Fail(Err.badSignature, "older")
        }
        val sigHash = sig?.let { sha256(it) }
        val want = if (sigHash == null) "$size $md5" else "$size $md5 $sigHash"
        val part = File(dir.apply { mkdirs() }, "${updName()}.part")
        val inf = File(dir, "${updName()}.inf")
        val same = part.isFile && inf.isFile && inf.readText() == want
        if (!same) {
            part.delete()
            inf.writeText(want)
        }
        if (size - part.length() > opts.cardFreeMiB * MIB - UPDATE_RESERVE) return Answer.Fail(Err.noSpace, "no space")
        upd = Upd(name, size, md5, part, st?.sha256, sigHash)
        updOut = out
        opts.log("upd begin size=$size offset=${part.length()}")
        return Answer.Ok(UpdBeginReply(part.length()).encode())
    }

    fun updData(b: Node, out: Out) = synchronized(lock) {
        cardEdge()
        val u = upd ?: return@synchronized
        val off = b.long(Tags.UpdData.offset) ?: return@synchronized
        val data = b.bytes(Tags.UpdData.`data`) ?: return@synchronized
        if (off != u.part.length() || off + data.size > u.size) {
            send(out, Msg.UPD_ABORT, UpdAbort(u.part.length(), Enums.AbortCode.offsetMismatch).encode())
            upd = null
            return@synchronized
        }
        u.part.appendBytes(data)
        (faults.cardRun?.start as? CardRun.Start.Upd)?.let { if (u.part.length() >= it.bytes) cardStart(now()) }
        cardEdge()
        if (upd == null) return@synchronized
        val at = faults.updAbortAt
        if (at != null && !updAbortFired && u.part.length() >= at) {
            updAbortFired = true
            send(out, Msg.UPD_ABORT, UpdAbort(u.part.length(), Enums.AbortCode.busy).encode())
            upd = null
        }
    }

    private fun updEnd(b: Node): Answer {
        val u = upd ?: return Answer.Fail(Err.badValue, "no transfer")
        upd = null
        val md5 = b.str(Tags.UpdEnd.md5)?.lowercase()
        if (u.part.length() != u.size) return Answer.Fail(Err.badValue, "short")
        val got = MessageDigest.getInstance("MD5").digest(u.part.readBytes()).joinToString("") { "%02x".format(it) }
        if (md5 != u.md5 || got != u.md5) {
            u.part.delete()
            File(u.part.parentFile, "${updName()}.inf").delete()
            return Answer.Fail(Err.crc, "md5")
        }
        if (u.sha256 != null && sha256(u.part.readBytes()) != u.sha256) {
            u.part.delete()
            File(u.part.parentFile, "${updName()}.inf").delete()
            return Answer.Fail(Err.badSignature, "signature")
        }
        val f = File(u.part.parentFile, updName())
        f.delete()
        u.part.renameTo(f)
        File(u.part.parentFile, "${updName()}.inf").delete()
        opts.log("stored upd file=${f.path} bytes=${f.length()} md5=$got sig=${u.sig?.take(8) ?: "none"}")
        return Answer.Ok()
    }
}

object Jpeg {
    fun baseline(b: ByteArray, w: Int, h: Int): Boolean {
        if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return false
        var i = 2
        while (i + 4 <= b.size) {
            if (b[i] != 0xFF.toByte()) return false
            val m = b[i + 1].toInt() and 0xFF
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (m == 0xC0 || m == 0xC1) {
                if (i + 9 > b.size) return false
                val hh = ((b[i + 5].toInt() and 0xFF) shl 8) or (b[i + 6].toInt() and 0xFF)
                val ww = ((b[i + 7].toInt() and 0xFF) shl 8) or (b[i + 8].toInt() and 0xFF)
                return hh == h && ww == w
            }
            if (m in 0xC2..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) return false
            if (m == 0xDA) return false
            i += 2 + len
        }
        return false
    }
}
