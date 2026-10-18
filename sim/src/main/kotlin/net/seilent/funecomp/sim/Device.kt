package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.ArtGetReply
import net.seilent.funecomp.proto.Band
import net.seilent.funecomp.proto.Eq
import net.seilent.funecomp.proto.EqName
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.ImgAbort
import net.seilent.funecomp.proto.ImgBeginReply
import net.seilent.funecomp.proto.ImgEntry
import net.seilent.funecomp.proto.ImgListReply
import net.seilent.funecomp.proto.Lib
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.PageInfo
import net.seilent.funecomp.proto.Profile
import net.seilent.funecomp.proto.Queue
import net.seilent.funecomp.proto.QueueItem
import net.seilent.funecomp.proto.Row
import net.seilent.funecomp.proto.Schema
import net.seilent.funecomp.proto.SetReply
import net.seilent.funecomp.proto.Settings
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.UpdAbort
import net.seilent.funecomp.proto.UpdBeginReply
import net.seilent.funecomp.proto.listView
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.proto.gen.Tags
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
private const val MIB = 1L shl 20
private const val UPDATE_RESERVE = 64L shl 20
private val SYNCED = Regex("^\\[[0-9]+:[0-9]{2}(\\.[0-9]+)?]", RegexOption.MULTILINE)
private val MD5 = Regex("^[0-9a-f]{32}$")

class Device(private val opts: Options, val lib: Library, private val clock: () -> Long) {
    private val board = opts.board
    private val faults = opts.faults
    private val lock = Any()

    val rows: List<Row> = loadRows()
    private val pivots = rows.mapNotNull { it.pivot }.distinct()
    private var schemaRev = 1L

    private val prefs = linkedMapOf(
        "output" to "auto", "volumeStep" to "5", "accentHue" to "350", "saverSeconds" to "30",
        "screenOffSeconds" to "300", "idleShutdownMinutes" to "0", "lockKnob" to "off", "screenOffKeys" to "all",
        "inlineRemote" to "on", "clock" to "24h", "batteryPercent" to "off", "playThrough" to "off",
        "usbMode" to "storage", "btCodec" to "auto", "btCodecHeadphones" to "auto", "digitalFilter" to "0",
        "balance" to "0", "maxVolume" to "100", "powerOnVolume" to "0", "replaygain" to "off",
        "quickplay" to "on", "resume" to "position", "autoNowPlaying" to "off", "playKey" to "nowPlaying",
        "dre" to "off", "crossfade" to "0",
    ).apply {
        keys.retainAll(rows.mapNotNull { it.key }.toSet() + setOf("btCodec", "btCodecHeadphones"))
        opts.maxPct?.let { put("maxVolume", it.coerceIn(50, 100).toString()) }
    }
    var settingsRev = 1L
        private set
    private var sleepLeftS = 0L
    private var sleepSetAt = 0L

    private val eqNames: List<EqName> = listOf(EqName("off", "OFF"), EqName("custom", "CUSTOM")) +
        (1..198).map { EqName("autoeq/oratory1990/Headphone Model %03d.txt".format(it), "Headphone Model %03d".format(it)) }
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

    class Stored(val name: String, val mbid: String?, val crc: Long, val bytes: ByteArray, var at: Long)
    class Partial(val name: String, val total: Long, val crc: Long, val w: Int, val h: Int, val data: ByteArrayOutputStream = ByteArrayOutputStream())

    private val images = LinkedHashMap<String, Stored>()
    private val partials = HashMap<String, Partial>()
    private var push: Partial? = null
    private var pushMbid: String? = null
    private var pushDropped = false
    private var abortFired = false
    private var updAbortFired = false
    private var storeClock = 0L
    private var shown: String? = null
    private val v11: Boolean get() = opts.minor >= 1
    private val v12: Boolean get() = opts.minor >= 2
    private val v13: Boolean get() = opts.minor >= 3
    private val started = clock()
    private var lastBattery = -1
    private val lyrics = HashSet<Long>()
    private val arts = HashMap<Pair<Long, Int>, Art>()
    private var upd: Upd? = null
    private var favsBusy = faults.favsBusy

    init {
        opts.lrcDir?.listFiles { f -> f.name.endsWith(".lrc") }?.forEach { f -> f.name.removeSuffix(".lrc").toULongOrNull()?.let { lyrics += it.toLong() } }
    }

    class Art(val crc: Long, val w: Int, val h: Int, val bytes: ByteArray, val source: Pair<Long, Long>)
    class Upd(val board: String, val size: Long, val md5: String, val part: File)
    @Volatile var droppedData = 0
        private set

    val stored: Map<String, Stored> get() = synchronized(lock) { images.toMap() }

    private fun flat() = Profile(0, emptyList())

    private fun n(v: Long?, default: Long): Int = (v ?: default).coerceIn(0L, 1_000_000L).toInt()

    private fun loadRows(): List<Row> {
        val text = Device::class.java.getResourceAsStream("/${board.schema}")!!.bufferedReader().readText()
        return text.lines().filter { it.isNotBlank() }.map { line ->
            val p = line.split('\t')
            fun list(s: String) = if (s == "-") emptyList() else s.split(',')
            val range = list(p[6]).map { it.toInt() }
            Row(
                p[1], p[3], p[0],
                Enums.RowKind.names.entries.first { it.value == p[2] }.key,
                list(p[4]), range.getOrNull(0), range.getOrNull(1), range.getOrNull(2),
                list(p[5]),
                list(p[7]).map { f -> Enums.RowFlag.names.entries.first { it.value == f }.key },
            )
        }
    }

    fun restart() = synchronized(lock) {
        partials.clear()
        push = null
    }

    fun helloReply() = HelloReply(
        faults.major, opts.minor, board.name, "sim.${board.name}", board.screenW, board.screenH,
        board.backdropW, board.backdropH, Proto.maxPayload, opts.idleMs, opts.busyMs, faults.features, lib.rev, settingsRev,
        if (v13) lib.snapRev else null,
    )

    private fun allowed(type: Int) = net.seilent.funecomp.proto.Groups.allowed(type, faults.features)

    private fun send(out: Out, type: Int, payload: ByteArray) {
        if (allowed(type)) out.push(type, payload)
    }

    fun greet(out: Out) = synchronized(lock) {
        send(out, Msg.STATE, state())
        pushSchema(out)
        pushSettings(out)
        pushEq(out, Enums.EqOutput.wired)
        pushEq(out, Enums.EqOutput.bt)
    }

    private fun now() = clock()

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

    fun state(): ByteArray = synchronized(lock) {
        val t = lib.byId[queue.getOrNull(slot)]
        val sample = now()
        lastStateAt = sample
        val key = t?.artists?.map { fold(it) }?.firstOrNull { it in images }
        if (key != null && key != shown) images[key]?.at = ++storeClock
        shown = key
        val bat = battery()
        lastBattery = bat
        State(
            Enums.Source.local, Enums.Output.wired, t?.id, t?.title, t?.artist, t?.artists.orEmpty(),
            t?.mbids.orEmpty(), t?.durationMs, position(), sample, now(), playing, shuffle, repeat,
            volume, maxVolume, bat, queueRev, slot.toLong(), queue.size.toLong(), lib.rev, t?.album, sleepLeft(),
            if (v12) opts.battery.charging else null,
            if (v12) opts.cardFreeMiB else null,
            if (v12) opts.cardTotalMiB else null,
            if (v12) volumePercent(volume) else null,
            if (v12) prefs.getValue("volumeStep").toIntOrNull() ?: 5 else null,
            if (v12 && t != null) t.id in lyrics else null,
            if (v12) prefs.getValue("maxVolume").toIntOrNull() ?: 100 else null,
            if (v13 && t != null && favsBusy == 0) t.id in lib.favorites else null,
            if (v13) lib.snapRev else null,
        ).encode()
    }

    private fun battery() = opts.battery.at(now() - started)

    fun tick(out: Out) = synchronized(lock) {
        val t = lib.byId[queue.getOrNull(slot)]
        if (playing && t != null && position() >= t.durationMs) {
            advance(1, auto = true)
            send(out, Msg.STATE, state())
        } else if (playing && now() - lastStateAt >= Proto.stateIntervalMs) {
            send(out, Msg.STATE, state())
        } else if (battery() != lastBattery) {
            send(out, Msg.STATE, state())
        }
    }

    private fun advance(d: Int, auto: Boolean) {
        if (queue.isEmpty()) return
        if (auto && repeat == Enums.Repeat.one) {
            positionMs = 0
        } else {
            val s = slot + d
            when {
                s < 0 -> slot = 0
                s < queue.size -> slot = s
                repeat == Enums.Repeat.all || !auto -> slot = 0
                else -> playing = false
            }
            positionMs = 0
        }
        positionAt = now()
    }

    private fun pushSchema(out: Out) {
        val fixed = Schema(schemaRev, "system", 0, pivots.size, PageInfo(0, 0, false), emptyList()).encode().size + 8
        pivots.forEachIndexed { pi, pivot ->
            val prow = rows.filter { it.pivot == pivot }.map { live(it) }
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
        "about" -> r.copy(words = listOf("sim.${board.name}"))
        "rescan" -> r.copy(words = listOf("${lib.tracks.size} SONGS"))
        "sleep" -> r.copy(words = if (sleepLeft() > 0) listOf("${(sleepLeft() + 59) / 60} MIN LEFT") else r.words)
        "dateTime" -> r.copy(words = listOf(java.time.LocalDateTime.now().withNano(0).toString().replace('T', ' ').dropLast(3)))
        else -> r
    }

    private fun settingsText() = prefs.entries.joinToString("") { "${it.key}=${it.value}\n" }

    private fun pushSettings(out: Out) = send(out, Msg.SETTINGS, Settings(settingsRev, settingsText()).encode())

    private fun pushEq(out: Out, output: Int) {
        val fixed = Eq(output, eqKey[output], emptyList(), eqCustom[output], PageInfo(0, 0, false)).encode().size + 8
        var at = 0
        do {
            val n = PageInfo.fill(eqNames.map { it.size }, at, fixed)
            val more = at + n < eqNames.size
            val ints = if (eqKey[output] == "custom") eqCustom[output] else flat()
            send(out, Msg.EQ, Eq(output, eqKey[output], eqNames.subList(at, at + n), ints, PageInfo(at.toLong(), eqNames.size.toLong(), more)).encode())
            at += n
        } while (at < eqNames.size)
    }

    fun request(type: Int, body: Node, out: Out): Answer = synchronized(lock) {
        when (type) {
            Msg.TRANSPORT -> transport(body)
            Msg.VOLUME -> volume(body)
            Msg.SET -> set(body, out)
            Msg.DRAFT -> draft(body, out)
            Msg.EQ_SET -> eqSet(body, out)
            Msg.PLAY -> play(body)
            Msg.QUEUE_OP -> queueOp(body, out)
            Msg.LIB_GET -> libGet(body, out)
            Msg.ACTION -> action(body, out)
            Msg.IMG_BEGIN -> imgBegin(body, out)
            Msg.IMG_END -> imgEnd(body)
            Msg.IMG_LIST -> imgList(body)
            Msg.IMG_DEL -> imgDel(body)
            Msg.ART_GET -> artGet(body)
            Msg.LRC_PUT -> lrcPut(body)
            Msg.LRC_DEL -> lrcDel(body)
            Msg.UPD_BEGIN -> updBegin(body)
            Msg.UPD_END -> updEnd(body)
            else -> Answer.Fail(Err.unknown, "unknown")
        }
    }

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
        mark()
        when (b.int(Tags.Transport.cmd)) {
            Enums.TransportCmd.toggle -> playing = !playing
            Enums.TransportCmd.play -> playing = true
            Enums.TransportCmd.pause -> playing = false
            Enums.TransportCmd.next -> advance(1, auto = false)
            Enums.TransportCmd.prev -> if (position() > 3000) positionMs = 0 else advance(-1, auto = false)
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
        positionAt = now()
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
                val v = b.str(Tags.Draft.`value`)?.takeIf { it in listOf("storage", "dock", "audio") }
                    ?: return Answer.Fail(Err.badValue, "bad usb mode")
                prefs["usbMode"] = v
            }
            Enums.DraftKind.btCodec -> {
                if (rows.none { it.key == "btCodec" }) return Answer.Fail(Err.refused, "refused")
                val role = b.int(Tags.Draft.role) ?: Enums.BtRole.receiver
                val ok = if (role == Enums.BtRole.receiver) listOf("auto", "aptx", "aac", "sbc") else listOf("auto", "ldac", "aptx", "aac", "sbc")
                val v = b.str(Tags.Draft.`value`)?.takeIf { it in ok } ?: return Answer.Fail(Err.badValue, "bad codec")
                prefs[if (role == Enums.BtRole.receiver) "btCodec" else "btCodecHeadphones"] = v
            }
            Enums.DraftKind.sleep -> {
                val m = b.long(Tags.Draft.minutes) ?: return Answer.Fail(Err.badValue, "no minutes")
                if (m !in listOf(0L, 15L, 30L, 45L, 60L, 90L, 120L)) return Answer.Fail(Err.badValue, "bad minutes")
                sleepLeftS = m * 60
                sleepSetAt = now()
            }
            Enums.DraftKind.clock -> {
                val s = b.long(Tags.Draft.unixS) ?: return Answer.Fail(Err.badValue, "bad clock")
                if (s < 1735689600L || s > 4102444799L) return Answer.Fail(Err.badValue, "bad clock")
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

    private fun valid(p: Profile): Boolean {
        fun ok(b: Band) = b.type in Enums.BandType.names && b.grid in -1..60 && b.milliHz in 20_000..20_000_000 &&
            b.gain10 in -240..120 && b.q100 in 10..1000
        return p.preamp10 in -240..0 && p.bands.size <= 10 && p.bands.all { ok(it) }
    }

    private fun play(b: Node): Answer {
        val ids = b.longs(Tags.Play.track)
        val list: List<Long> = if (ids.isNotEmpty()) {
            if (ids.size > Proto.playMaxTracks) return Answer.Fail(Err.tooBig, "too many tracks")
            if (ids.any { it !in lib.byId }) return Answer.Fail(Err.staleRev, "stale rev")
            ids
        } else {
            val view = b.int(Tags.Play.view) ?: return Answer.Fail(Err.badValue, "no view")
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
        positionMs = 0
        positionAt = now()
        queueRev++
        return changed()
    }

    private fun queuePage(out: Out, offset: Int, count: Int) {
        val items = queue.map { id ->
            val t = lib.byId[id]
            when {
                v12 && t != null -> QueueItem(id, t.title, t.artists, t.mbids, t.album, t.durationMs, id in lyrics)
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
                if (!inQueue(at)) return Answer.Fail(Err.badValue, "bad slot")
                slot = at!!
                positionMs = 0
                playing = true
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
                val ids = b.longs(Tags.QueueOp.track)
                if (ids.isEmpty() || ids.any { it !in lib.byId }) return Answer.Fail(Err.badValue, "bad tracks")
                queue.addAll(minOf(slot + 1, queue.size), ids)
            }
            Enums.QueueCmd.clear -> {
                queue.clear()
                slot = 0
                playing = false
                positionMs = 0
            }
            else -> return Answer.Fail(Err.badValue, "bad cmd")
        }
        positionAt = now()
        queueRev++
        return changed()
    }

    private fun libGet(b: Node, out: Out): Answer {
        val view = b.int(Tags.LibGet.view) ?: return Answer.Fail(Err.badValue, "no view")
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
            Enums.ActionCmd.rescan -> {
                lib.rev++
                lib.snapRev++
            }
            Enums.ActionCmd.shuffleLibrary -> {
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

    private fun fold(name: String) = name.trim().lowercase()

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
        if (total > Proto.imgMaxBytes) return Answer.Fail(Err.tooBig, "too big")
        if (w != board.backdropW || h != board.backdropH) return Answer.Fail(Err.badValue, "wrong size")
        push?.let { if (!pushDropped) abort(out, it, Enums.AbortCode.superseded) }
        val key = fold(name)
        images[key]?.let { if (it.crc == sum && it.bytes.size.toLong() == total) {
            push = null
            return Answer.Ok(ImgBeginReply(total, sum).encode())
        } }
        val kept = partials[key]?.takeIf { it.name == name && it.total == total && it.crc == sum && it.w == w && it.h == h }
        val p = kept ?: Partial(name, total, sum, w, h).also { partials[key] = it }
        val whole = p.data.size() / chunk * chunk
        if (whole != p.data.size()) {
            val bytes = p.data.toByteArray().copyOf(whole)
            p.data.reset()
            p.data.write(bytes)
        }
        push = p
        pushMbid = b.str(Tags.ImgBegin.mbid)
        pushDropped = false
        val bytes = p.data.toByteArray()
        return Answer.Ok(ImgBeginReply(bytes.size.toLong(), if (bytes.isEmpty()) 0 else crc(bytes)).encode())
    }

    fun imgData(b: Node, out: Out) = synchronized(lock) {
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
        val at = faults.abortAt
        if (at != null && !abortFired && p.data.size() >= at) {
            abortFired = true
            abort(out, p, Enums.AbortCode.writeError)
        }
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
        if (!Jpeg.baseline(bytes, p.w, p.h)) {
            partials.remove(key)
            return Answer.Fail(Err.badValue, "not baseline or wrong size")
        }
        partials.remove(key)
        images[key] = Stored(p.name, pushMbid, p.crc, bytes, ++storeClock)
        evict(key)
        opts.imgDir?.let { dir ->
            val f = File(dir.apply { mkdirs() }, "%08x.jpg".format(p.crc))
            f.writeBytes(bytes)
            opts.log("stored name=${p.name} mbid=${pushMbid ?: ""} ${p.w}x${p.h} bytes=${bytes.size} file=${f.path}")
        }
        return Answer.Ok()
    }

    private fun bytes() = images.values.sumOf { it.bytes.size.toLong() }

    private fun evict(keep: String) {
        while (bytes() > opts.imgCap) {
            val old = images.entries.filter { it.key != keep }.minByOrNull { it.value.at } ?: return
            images.remove(old.key)
            opts.log("evicted name=${old.value.name}")
        }
    }

    private fun imgList(b: Node): Answer {
        val all = images.values.map { ImgEntry(it.name, it.mbid) }
        val offset = n(b.long(Tags.ImgList.offset), 0).coerceAtMost(all.size)
        val count = n(b.long(Tags.ImgList.count), 100)
        val size = if (v11) bytes() else null
        val cap = if (v11) opts.imgCap else null
        val fixed = ImgListReply(PageInfo(0, 0, false), emptyList(), size, cap).encode().size + 8
        val n = PageInfo.fill(all.map { it.size }, offset, fixed, count = count)
        return Answer.Ok(ImgListReply(PageInfo(offset.toLong(), all.size.toLong(), offset + n < all.size), all.subList(offset, offset + n), size, cap).encode())
    }

    private fun imgDel(b: Node): Answer {
        val name = b.str(Tags.ImgDel.name) ?: return Answer.Fail(Err.badValue, "no name")
        val old = images.remove(fold(name))
        if (old == null) {
            opts.log("absent name=$name")
            return Answer.Ok()
        }
        opts.imgDir?.let { dir -> if (images.values.none { it.crc == old.crc }) File(dir, "%08x.jpg".format(old.crc)).delete() }
        opts.log("deleted name=$name")
        return Answer.Ok()
    }

    private fun artGet(b: Node): Answer {
        val track = b.long(Tags.ArtGet.track) ?: return Answer.Fail(Err.badValue, "no track")
        val px = b.int(Tags.ArtGet.px) ?: return Answer.Fail(Err.badValue, "no px")
        return artGet(track, px, b.long(Tags.ArtGet.offset) ?: 0, b.long(Tags.ArtGet.have))
    }

    private fun artGet(track: Long, px: Int, offset: Long, have: Long?): Answer {
        if (track !in lib.byId) return Answer.Fail(Err.staleRev, "stale rev")
        val s = px.coerceIn(Proto.artMinPx, Proto.artMaxPx)
        val a = arts[track to s]?.takeIf { it.source == source(track) } ?: encode(track, s)?.also { arts[track to s] = it } ?: return Answer.Fail(Err.notFound, "no art")
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
        if (!SYNCED.containsMatchIn(String(text, Charsets.UTF_8))) return Answer.Fail(Err.badValue, "not synced lrc")
        opts.lrcDir?.let { File(it.apply { mkdirs() }, "${java.lang.Long.toUnsignedString(track)}.lrc").writeBytes(text) }
        lyrics += track
        opts.log("stored lrc track=${java.lang.Long.toUnsignedString(track)} bytes=${text.size}")
        return Answer.Ok(state = queue.getOrNull(slot) == track)
    }

    private fun lrcDel(b: Node): Answer {
        val track = b.long(Tags.LrcDel.track) ?: return Answer.Fail(Err.badValue, "no track")
        opts.lrcDir?.let { File(it, "${java.lang.Long.toUnsignedString(track)}.lrc").delete() }
        return Answer.Ok(state = lyrics.remove(track) && queue.getOrNull(slot) == track)
    }

    private fun updName() = if (board.name == "v1") "v1.upt" else "update.upt"

    private fun updBegin(b: Node): Answer {
        val dir = opts.updDir ?: return Answer.Fail(Err.refused, "no update dir")
        val name = b.str(Tags.UpdBegin.board) ?: return Answer.Fail(Err.badValue, "no board")
        val size = b.long(Tags.UpdBegin.size) ?: return Answer.Fail(Err.badValue, "no size")
        val md5 = b.str(Tags.UpdBegin.md5)?.lowercase()?.takeIf { MD5.matches(it) } ?: return Answer.Fail(Err.badValue, "bad md5")
        if (name != board.name) return Answer.Fail(Err.badValue, "wrong board")
        val other = File(dir, if (board.name == "v1") "update.upt" else "ap80.upt")
        if (other.exists()) return Answer.Fail(Err.badValue, "remove ${other.name}")
        if (battery() < UPDATE_BATTERY_MIN && !opts.battery.charging) return Answer.Fail(Err.refused, "battery")
        val part = File(dir.apply { mkdirs() }, "${updName()}.part")
        val inf = File(dir, "${updName()}.inf")
        val same = part.isFile && inf.isFile && inf.readText() == "$size $md5"
        if (!same) {
            part.delete()
            inf.writeText("$size $md5")
        }
        if (size - part.length() > opts.cardFreeMiB * MIB - UPDATE_RESERVE) return Answer.Fail(Err.noSpace, "no space")
        upd = Upd(name, size, md5, part)
        opts.log("upd begin size=$size offset=${part.length()}")
        return Answer.Ok(UpdBeginReply(part.length()).encode())
    }

    fun updData(b: Node, out: Out) = synchronized(lock) {
        val u = upd ?: return@synchronized
        val off = b.long(Tags.UpdData.offset) ?: return@synchronized
        val data = b.bytes(Tags.UpdData.`data`) ?: return@synchronized
        if (off != u.part.length() || off + data.size > u.size) {
            send(out, Msg.UPD_ABORT, UpdAbort(u.part.length(), Enums.AbortCode.offsetMismatch).encode())
            upd = null
            return@synchronized
        }
        u.part.appendBytes(data)
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
        val f = File(u.part.parentFile, updName())
        f.delete()
        u.part.renameTo(f)
        File(u.part.parentFile, "${updName()}.inf").delete()
        opts.log("stored upd file=${f.path} bytes=${f.length()} md5=$got")
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
