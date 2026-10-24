package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.PlayEntry
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.proto.gen.Tags
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class Board(
    val name: String,
    val screenW: Int,
    val screenH: Int,
    val backdropW: Int,
    val backdropH: Int,
    val schema: String,
) {
    fun withBackdrop(w: Int, h: Int) = Board(name, screenW, screenH, w, h, schema)

    companion object {
        fun of(name: String) = when (name) {
            "ap80" -> Board("ap80", 360, 480, 360, 480, "schema-ap80.txt")
            "v1" -> Board("v1", 240, 320, 240, 320, "schema-v1.txt")
            else -> throw IllegalArgumentException("board must be ap80 or v1, not $name")
        }
    }
}

class EnumFault(val type: Int, val tag: Int, val value: Int)

class CardRun(val holdMs: Long, val indexMs: Long, val start: Start) {
    sealed interface Start {
        class Delay(val ms: Long) : Start
        data object Hello : Start
        class Img(val bytes: Long) : Start
        class Upd(val bytes: Long) : Start
        data object Play : Start
        data object Manual : Start
        data object Queue : Start
        data object End : Start
    }

    companion object {
        fun parse(v: String): CardRun {
            val run = v.substringBefore('@')
            val at = v.substringAfter('@', "0")
            val hold = run.substringBefore('+').toLongOrNull() ?: throw IllegalArgumentException("card-run: hold missing in $v")
            val index = run.substringAfter('+', "").toLongOrNull() ?: throw IllegalArgumentException("card-run: index missing in $v")
            val start = when {
                at == "hello" -> Start.Hello
                at == "play" -> Start.Play
                at == "manual" -> Start.Manual
                at == "queue" -> Start.Queue
                at == "end" -> Start.End
                at.startsWith("img:") -> Start.Img(at.removePrefix("img:").toLongOrNull() ?: throw IllegalArgumentException("card-run: bad bytes in $v"))
                at.startsWith("upd:") -> Start.Upd(at.removePrefix("upd:").toLongOrNull() ?: throw IllegalArgumentException("card-run: bad bytes in $v"))
                else -> Start.Delay(at.toLongOrNull() ?: throw IllegalArgumentException("card-run: bad start in $v"))
            }
            return CardRun(hold, index, start)
        }

        fun seq(v: String): List<Pair<Int, Long>> {
            val steps = v.split(',').map { it.trim() }
            return steps.mapIndexed { i, step ->
                val name = step.substringBefore(':')
                val value = Enums.Card.names.entries.firstOrNull { it.value == name }?.key ?: throw IllegalArgumentException("card-seq: unknown value $name")
                val ms = if (i == steps.lastIndex) 0L else step.substringAfter(':', "").toLongOrNull() ?: throw IllegalArgumentException("card-seq: duration missing in $step")
                value to ms
            }
        }
    }
}

class Faults(specs: List<String>) {
    var dropAfterBytes: Long? = null
    var stallMs = 0L
    var noRead = false
    var major = Proto.major
    var slowBps: Long? = null
    val refuse = HashSet<Int>()
    val fail = HashMap<Int, Int>()
    val failLeft = HashMap<Int, AtomicInteger>()
    val busy = HashMap<Int, AtomicInteger>()
    val busyPages = HashSet<Long>()
    val mute = HashMap<Int, AtomicInteger>()
    val muteCmd = HashMap<Int, Int>()
    var bye: Int? = null
    var restart = false
    var abortAt: Long? = null
    var updAbortAt: Long? = null
    val enums = ArrayList<EnumFault>()
    var features = Groups.all
    var askBlock: Pair<Int, Long>? = null
    var askPick: Pair<String, Long>? = null
    var favsBusy = 0
    var favsBusyText = "busy"
    var staleState = false
    val eqHidden = HashSet<String>()
    var lyricsLate: Long? = null
    val lyricsAbsent = HashSet<String>()
    var newlog = false
    val dropAck = java.util.concurrent.atomic.AtomicBoolean(false)
    var cardRun: CardRun? = null
    var cardSeq: List<Pair<Int, Long>> = emptyList()
    var cardOld = false
    var lateState = 0L
    var playLag = 0L
    var playDrop = false
    var cardCoalesce = false
    var cardPage = false
    var artHold = 0L
    var snapLate = 0L
    var phoneLateMeta: Long? = null
    var phoneStaleDuration = false
    var phoneSeek: Pair<Long, Long>? = null
    var phoneSuspend: Long? = null
    var phoneExit: Long? = null
    var phoneArtLate: Long? = null
    var imgSource = false
    var srcRoom = 6_442_450_944L
    var srcRefuse = 0
    var srcNoSpace = false

    init {
        val tokens = specs.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.fold(ArrayList<String>()) { acc, t ->
            val step = '=' !in t && t.substringBefore(':') in Enums.Card.names.values
            if (step && acc.lastOrNull()?.startsWith("card-seq=") == true) acc[acc.lastIndex] = acc.last() + "," + t else acc += t
            acc
        }
        for (spec in tokens) {
            val k = spec.substringBefore('=')
            val v = spec.substringAfter('=', "")
            when (k) {
                "drop-after-bytes" -> dropAfterBytes = v.toLong()
                "stall-ms" -> stallMs = v.toLong()
                "no-read" -> noRead = true
                "major" -> major = v.toInt()
                "slow-bps" -> slowBps = v.toLong()
                "refuse" -> refuse += Msg.byName[v] ?: throw IllegalArgumentException("refuse: unknown msg $v")
                "fail" -> {
                    val msg = v.substringBefore(':')
                    val rest = v.substringAfter(':', "")
                    val code = rest.substringBefore(':').toIntOrNull() ?: throw IllegalArgumentException("fail: code missing in $v")
                    val type = Msg.byName[msg] ?: throw IllegalArgumentException("fail: unknown msg $msg")
                    fail[type] = code
                    if (':' in rest) failLeft[type] = AtomicInteger(rest.substringAfter(':').toIntOrNull() ?: throw IllegalArgumentException("fail: bad count in $v"))
                }
                "busy" -> {
                    val msg = v.substringBefore(':')
                    val n = v.substringAfter(':', "").toIntOrNull() ?: throw IllegalArgumentException("busy: count missing in $v")
                    busy[Msg.byName[msg] ?: throw IllegalArgumentException("busy: unknown msg $msg")] = AtomicInteger(n)
                }
                "busy-page" -> busyPages += v.toLongOrNull() ?: throw IllegalArgumentException("busy-page: offset missing in $v")
                "mute" -> {
                    val msg = v.substringBefore(':').substringBefore('.')
                    val n = v.substringAfter(':', "").toIntOrNull() ?: throw IllegalArgumentException("mute: count missing in $v")
                    val type = Msg.byName[msg] ?: throw IllegalArgumentException("mute: unknown msg $msg")
                    mute[type] = AtomicInteger(n)
                    val cmd = v.substringBefore(':').substringAfter('.', "")
                    if (cmd.isNotEmpty()) {
                        require(type == Msg.QUEUE_OP) { "mute: a cmd only for QUEUE_OP in $v" }
                        muteCmd[type] = Enums.QueueCmd.names.entries.firstOrNull { it.value == cmd }?.key ?: throw IllegalArgumentException("mute: unknown cmd $cmd")
                    }
                }
                "bye" -> bye = Enums.ByeReason.names.entries.firstOrNull { it.value == v }?.key
                    ?: throw IllegalArgumentException("bye: unknown reason $v")
                "restart" -> restart = true
                "img-source" -> imgSource = true
                "src-room" -> srcRoom = v.toLong()
                "src-refuse" -> srcRefuse = v.toInt()
                "src-nospace" -> srcNoSpace = true
                "abort-at" -> abortAt = v.toLong()
                "upd-abort-at" -> updAbortAt = v.toLong()
                "features" -> features = v.toLong()
                "favs-busy" -> {
                    favsBusy = v.substringBefore(':').toIntOrNull() ?: throw IllegalArgumentException("favs-busy: count missing in $v")
                    favsBusyText = v.substringAfter(':', "busy")
                }
                "ask-block" -> {
                    val cmd = Enums.BlockCmd.names.entries.firstOrNull { it.value == v.substringBefore('@') }?.key
                        ?: throw IllegalArgumentException("ask-block: unknown cmd in $v")
                    askBlock = cmd to (v.substringAfter('@', "").toLongOrNull() ?: throw IllegalArgumentException("ask-block: delay missing in $v"))
                }
                "ask-pick" -> {
                    val ms = v.substringAfterLast('@', "").toLongOrNull() ?: throw IllegalArgumentException("ask-pick: delay missing in $v")
                    askPick = v.substringBeforeLast('@') to ms
                }
                "stale-state" -> staleState = true
                "eq-hidden" -> eqHidden += v
                "lyrics-late" -> lyricsLate = v.toLong()
                "lyrics-absent" -> lyricsAbsent += v
                "newlog" -> newlog = true
                "drop-ack" -> dropAck.set(true)
                "card-run" -> cardRun = CardRun.parse(v)
                "card-seq" -> cardSeq = CardRun.seq(v)
                "card-device" -> cardOld = when (v) {
                    "landed" -> false
                    "old" -> true
                    else -> throw IllegalArgumentException("card-device: landed or old, not $v")
                }
                "late-state" -> lateState = v.toLong()
                "play-lag" -> playLag = v.toLong()
                "play-drop" -> playDrop = true
                "card-coalesce" -> cardCoalesce = true
                "card-page" -> cardPage = true
                "art-hold" -> artHold = v.toLong()
                "snap-late" -> snapLate = v.toLong()
                "phone-late-meta" -> phoneLateMeta = v.toLong()
                "phone-stale-duration" -> phoneStaleDuration = true
                "phone-seek" -> phoneSeek = (v.substringBefore(':').toLongOrNull() ?: throw IllegalArgumentException("phone-seek: at missing in $v")) to
                    (v.substringAfter(':', "").toLongOrNull() ?: throw IllegalArgumentException("phone-seek: to missing in $v"))
                "phone-suspend" -> phoneSuspend = v.toLong()
                "phone-exit" -> phoneExit = v.toLong()
                "phone-art-late" -> phoneArtLate = v.toLong()
                "enum" -> {
                    val target = v.substringBefore('=')
                    val msg = target.substringBefore('.')
                    val field = target.substringAfter('.')
                    val type = Msg.byName[msg] ?: throw IllegalArgumentException("enum: unknown msg $msg")
                    val tag = Tags.scopes[msg]?.values?.firstOrNull { it.name == field }?.tag
                        ?: throw IllegalArgumentException("enum: $msg has no field $field")
                    enums += EnumFault(type, tag, v.substringAfter('=').toInt())
                }
                else -> throw IllegalArgumentException("unknown fault $k")
            }
        }
        if (cardRun != null && cardSeq.isNotEmpty()) throw IllegalArgumentException("card-run and card-seq together")
    }
}

class Options(
    val board: Board = Board.of("ap80"),
    val faults: Faults = Faults(emptyList()),
    val idleMs: Long = Proto.keepaliveIdleMs.toLong(),
    val busyMs: Long = Proto.keepaliveBusyMs.toLong(),
    val helloTimeoutMs: Long = Proto.helloTimeoutMs.toLong(),
    val log: (String) -> Unit = {},
    val imgDir: File? = null,
    val minor: Int = Proto.minor,
    val imgCap: Long = IMG_CAP,
    val artDir: File? = null,
    val lrcDir: File? = null,
    val updDir: File? = null,
    val battery: Battery = Battery(87, 87, 0),
    val cardFreeMiB: Long = 158_234,
    val cardTotalMiB: Long = 233_440,
    val curve: Boolean = board.name == "v1",
    val maxPct: Int? = null,
    val cardEq: List<String> = emptyList(),
    val source: Int = Enums.Source.local,
    val rescanLib: Library? = null,
    val version: String? = null,
    val lyrOffsets: Map<Long, Int> = emptyMap(),
    val playLog: List<PlayEntry> = emptyList(),
    val playLogId: Long = PlayLog.ID,
    val playLogCap: Int = PlayLog.CAP,
    val wall: () -> Long = { System.currentTimeMillis() },
    val deviceClock: (() -> Long)? = null,
    val phone: List<PhoneLine> = emptyList(),
    val receiver: Boolean = false,
) {
    init {
        if (minor < 2) faults.features = faults.features and Groups.mask("art", "lyrics", "update", "clockAuto").inv()
        if (minor < 5) faults.features = faults.features and Groups.mask("eqPresets").inv()
        if (minor < 6) faults.features = faults.features and Groups.mask("artBlock").inv()
        if (minor < 7) faults.features = faults.features and Groups.mask("lyricsGet").inv()
        if (minor < 8) faults.features = faults.features and Groups.mask("artPick").inv()
        if (minor < 9) faults.features = faults.features and Groups.mask("imgOrigin").inv()
        if (minor < 10) faults.features = faults.features and Groups.mask("stateClock").inv()
        if (minor < 15) faults.features = faults.features and Groups.mask("lyricsOffset", "playLog", "genres", "queueAdd").inv()
        if (receiver && minor >= 15) faults.features = faults.features or Groups.mask("receiver")
        if (faults.imgSource && minor >= 15) faults.features = faults.features or Groups.mask("imgSource")
    }

    companion object {
        const val IMG_CAP = 128L shl 20
    }
}

class Battery(val from: Int, val to: Int, val everyMs: Long) {
    val charging: Boolean get() = to > from

    fun at(ms: Long): Int {
        if (everyMs <= 0 || from == to) return from
        val moved = (ms / everyMs).coerceAtMost(kotlin.math.abs(to - from).toLong()).toInt()
        return if (to > from) from + moved else from - moved
    }

    companion object {
        fun parse(s: String): Battery {
            val (range, every) = if ('@' in s) s.substringBefore('@') to s.substringAfter('@').toLong() else s to 0L
            val a = range.substringBefore(':').toInt()
            val b = range.substringAfter(':', range).toInt()
            return Battery(a.coerceIn(0, 100), b.coerceIn(0, 100), every)
        }
    }
}
