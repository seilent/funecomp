package net.seilent.funecomp.proto

import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Feature
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.proto.gen.Tags
import java.util.zip.CRC32

object Groups {
    val core: Set<Int> = setOf(Msg.HELLO, Msg.PING, Msg.BYE, Msg.STATE, Msg.TRANSPORT, Msg.VOLUME)

    val members: Map<String, Set<Int>> = mapOf(
        "settings" to setOf(Msg.SCHEMA, Msg.SETTINGS, Msg.SET, Msg.DRAFT),
        "eq" to setOf(Msg.EQ, Msg.EQ_SET),
        "library" to setOf(Msg.LIB, Msg.LIB_GET, Msg.PLAY, Msg.ACTION),
        "queue" to setOf(Msg.QUEUE, Msg.QUEUE_OP),
        "image" to setOf(Msg.IMG_BEGIN, Msg.IMG_DATA, Msg.IMG_END, Msg.IMG_LIST, Msg.IMG_DEL, Msg.IMG_ABORT),
        "art" to setOf(Msg.ART_GET),
        "lyrics" to setOf(Msg.LRC_PUT, Msg.LRC_DEL),
        "update" to setOf(Msg.UPD_BEGIN, Msg.UPD_DATA, Msg.UPD_END, Msg.UPD_ABORT),
        "clockAuto" to emptySet(),
        "eqPresets" to setOf(Msg.EQ_PRESET),
        "artBlock" to setOf(Msg.ART_BLOCK),
        "lyricsGet" to setOf(Msg.LYR_GET),
        "artPick" to setOf(Msg.ART_PICK),
        "imgOrigin" to setOf(Msg.IMG_TAG),
        "stateClock" to emptySet(),
        "eqSlots" to emptySet(),
        "eqGraphic" to emptySet(),
        "eqPass" to emptySet(),
        "outputKinds" to emptySet(),
        "rowConfirm" to emptySet(),
        "search" to emptySet(),
        "lyricsOffset" to setOf(Msg.LYR_OFFSET),
        "playLog" to setOf(Msg.PLAYLOG_GET, Msg.PLAYLOG_ACK),
        "genres" to emptySet(),
        "queueAdd" to emptySet(),
        "receiver" to emptySet(),
        "phoneFiles" to setOf(Msg.STREAM_READ, Msg.STREAM_CANCEL, Msg.PHONE_LIST, Msg.PHONE_ART),
        "updSig" to emptySet(),
        "imgSource" to emptySet(),
        "eqStages" to emptySet(),
        "liveRows" to setOf(Msg.LIVE),
    )
    val unserved: Set<String> = setOf(
        "eqSlots",
        "eqGraphic",
        "eqPass",
        "outputKinds",
        "rowConfirm",
        "search",
        "lyricsOffset",
        "playLog",
        "genres",
        "queueAdd",
        "receiver",
        "phoneFiles",
        "updSig",
        "imgSource",
        "eqStages",
        "liveRows",
    )
    private val bits: Map<String, Int> = Feature.names.entries.associate { it.value to it.key }

    fun group(type: Int): String? = members.entries.firstOrNull { type in it.value }?.key

    fun bit(group: String): Int? = bits[group]

    fun allowed(type: Int, features: Long): Boolean {
        val g = group(type) ?: return true
        val b = bit(g) ?: return false
        return (features ushr b) and 1L == 1L
    }

    fun mask(vararg groups: String): Long = groups.fold(0L) { m, g -> m or (1L shl (bit(g) ?: error("no feature $g"))) }

    val all: Long get() = mask(*members.keys.filter { bit(it) != null && it !in unserved }.toTypedArray())
}

data class Hello(val major: Int, val minor: Int, val appVersion: String?, val features: Long? = null) {
    fun encode() = tlv {
        u32(Tags.Hello.magic, Proto.magic.toLong())
        u16(Tags.Hello.major, major)
        u16(Tags.Hello.minor, minor)
        str(Tags.Hello.appVersion, appVersion)
        u64(Tags.Hello.features, features)
    }

    companion object {
        fun from(n: Node) = Hello(n.int(Tags.Hello.major) ?: -1, n.int(Tags.Hello.minor) ?: -1, n.str(Tags.Hello.appVersion), n.long(Tags.Hello.features))
    }
}

data class HelloReply(
    val major: Int,
    val minor: Int,
    val board: String?,
    val version: String?,
    val screenW: Int?,
    val screenH: Int?,
    val backdropW: Int?,
    val backdropH: Int?,
    val maxPayload: Int,
    val idleMs: Long,
    val busyMs: Long,
    val features: Long,
    val libRev: Long?,
    val settingsRev: Long?,
    val snapRev: Long? = null,
    val eqSlots: List<EqSlot> = emptyList(),
    val bandTypes: List<Int> = emptyList(),
    val phoneMime: List<String> = emptyList(),
    val phoneMaxRate: Long? = null,
    val phoneMaxBits: Int? = null,
) {
    fun has(group: String) = Groups.bit(group)?.let { (features ushr it) and 1L == 1L } ?: false

    fun encode() = tlv {
        u32(Tags.HelloReply.magic, Proto.magic.toLong())
        u16(Tags.HelloReply.major, major)
        u16(Tags.HelloReply.minor, minor)
        str(Tags.HelloReply.board, board)
        str(Tags.HelloReply.version, version)
        u16(Tags.HelloReply.screenW, screenW)
        u16(Tags.HelloReply.screenH, screenH)
        u16(Tags.HelloReply.backdropW, backdropW)
        u16(Tags.HelloReply.backdropH, backdropH)
        u32(Tags.HelloReply.maxPayload, maxPayload.toLong())
        u32(Tags.HelloReply.idleMs, idleMs)
        u32(Tags.HelloReply.busyMs, busyMs)
        u64(Tags.HelloReply.features, features)
        u32(Tags.HelloReply.libRev, libRev)
        u32(Tags.HelloReply.settingsRev, settingsRev)
        u32(Tags.HelloReply.snapRev, snapRev)
        eqSlots.forEach { e -> rec(Tags.HelloReply.eqSlot) { e.write(this) } }
        bandTypes.forEach { enum(Tags.HelloReply.bandTypes, it) }
        phoneMime.forEach { str(Tags.HelloReply.phoneMime, it) }
        u32(Tags.HelloReply.phoneMaxRate, phoneMaxRate)
        u8(Tags.HelloReply.phoneMaxBits, phoneMaxBits)
    }

    companion object {
        fun from(n: Node) = HelloReply(
            n.int(Tags.HelloReply.major) ?: -1,
            n.int(Tags.HelloReply.minor) ?: -1,
            n.str(Tags.HelloReply.board),
            n.str(Tags.HelloReply.version),
            n.int(Tags.HelloReply.screenW),
            n.int(Tags.HelloReply.screenH),
            n.int(Tags.HelloReply.backdropW),
            n.int(Tags.HelloReply.backdropH),
            n.int(Tags.HelloReply.maxPayload)?.coerceIn(256, Proto.maxPayload) ?: Proto.maxPayload,
            n.long(Tags.HelloReply.idleMs)?.takeIf { it > 0 } ?: Proto.keepaliveIdleMs.toLong(),
            n.long(Tags.HelloReply.busyMs)?.takeIf { it > 0 } ?: Proto.keepaliveBusyMs.toLong(),
            n.long(Tags.HelloReply.features) ?: 0L,
            n.long(Tags.HelloReply.libRev),
            n.long(Tags.HelloReply.settingsRev),
            n.long(Tags.HelloReply.snapRev),
            n.nodes(Tags.HelloReply.eqSlot).map { EqSlot.from(it) },
            n.ints(Tags.HelloReply.bandTypes),
            n.strs(Tags.HelloReply.phoneMime),
            n.long(Tags.HelloReply.phoneMaxRate),
            n.int(Tags.HelloReply.phoneMaxBits),
        )
    }
}

data class Bye(val reason: Int, val major: Int? = null, val minor: Int? = null) {
    fun encode() = tlv {
        enum(Tags.Bye.reason, reason)
        u16(Tags.Bye.major, major)
        u16(Tags.Bye.minor, minor)
    }

    companion object {
        fun from(n: Node) = Bye(n.int(Tags.Bye.reason) ?: -1, n.int(Tags.Bye.major), n.int(Tags.Bye.minor))
    }
}

data class ErrorReply(val code: Int, val text: String? = null, val refused: List<String> = emptyList()) {
    fun encode() = tlv {
        u8(Tags.Error.code, code)
        str(Tags.Error.text, text)
        refused.forEach { str(Tags.Error.refused, it) }
    }

    companion object {
        fun from(n: Node) = ErrorReply(n.int(Tags.Error.code) ?: -1, n.str(Tags.Error.text), n.strs(Tags.Error.refused))
    }
}

data class PageInfo(val offset: Long, val total: Long, val more: Boolean) {
    companion object {
        val budget: Int get() = Proto.maxPayload * Proto.pageFillPct / 100

        fun read(n: Node, offset: Int, total: Int, more: Int) =
            PageInfo(n.long(offset) ?: 0, n.long(total) ?: 0, n.bool(more) ?: false)

        fun fill(sizes: List<Int>, from: Int, fixed: Int, budget: Int = this.budget, count: Int = Int.MAX_VALUE): Int {
            var used = fixed
            var n = 0
            while (from + n < sizes.size && n < count) {
                val s = sizes[from + n]
                if (n > 0 && used + s > budget) break
                used += s
                n++
            }
            return n
        }
    }
}

data class State(
    val source: Int? = null,
    val output: Int? = null,
    val track: Long? = null,
    val title: String? = null,
    val artist: String? = null,
    val artists: List<String> = emptyList(),
    val mbids: List<String> = emptyList(),
    val durationMs: Long? = null,
    val positionMs: Long? = null,
    val sampleMs: Long? = null,
    val sentMs: Long? = null,
    val playing: Boolean? = null,
    val shuffle: Boolean? = null,
    val repeat: Int? = null,
    val volume: Int? = null,
    val maxVolume: Int? = null,
    val battery: Int? = null,
    val queueRev: Long? = null,
    val slot: Long? = null,
    val slots: Long? = null,
    val libRev: Long? = null,
    val album: String? = null,
    val sleepLeftS: Long? = null,
    val charging: Boolean? = null,
    val cardFreeMiB: Long? = null,
    val cardTotalMiB: Long? = null,
    val volumePct: Int? = null,
    val volumeStepPct: Int? = null,
    val lyrics: Boolean? = null,
    val maxVolumePct: Int? = null,
    val favorite: Boolean? = null,
    val snapRev: Long? = null,
    val lyricsPlain: Boolean? = null,
    val lyricsCrc: Long? = null,
    val positionHeld: Boolean? = null,
    val eqSlot: String? = null,
    val card: Int? = null,
    val lyricsOffsetMs: Int? = null,
    val playLogSeq: Long? = null,
    val receiver: Boolean? = null,
    val phoneName: String? = null,
    val phoneArtCrc: Long? = null,
    val phoneTrackNo: Long? = null,
    val phoneTracks: Long? = null,
    val phoneFile: Long? = null,
    val buffering: Boolean? = null,
    val aheadMs: Long? = null,
    val imgHold: Boolean? = null,
    val updHold: Boolean? = null,
) {
    fun encode() = tlv {
        enum(Tags.State.source, source)
        enum(Tags.State.output, output)
        u64(Tags.State.track, track)
        str(Tags.State.title, title)
        str(Tags.State.artist, artist)
        artists.forEach { str(Tags.State.artists, it) }
        mbids.forEach { str(Tags.State.mbids, it) }
        u32(Tags.State.durationMs, durationMs)
        u32(Tags.State.positionMs, positionMs)
        u64(Tags.State.sampleMs, sampleMs)
        u64(Tags.State.sentMs, sentMs)
        bool(Tags.State.playing, playing)
        bool(Tags.State.positionHeld, positionHeld)
        bool(Tags.State.shuffle, shuffle)
        enum(Tags.State.repeat, repeat)
        u16(Tags.State.volume, volume)
        u16(Tags.State.maxVolume, maxVolume)
        u8(Tags.State.battery, battery)
        u32(Tags.State.queueRev, queueRev)
        u32(Tags.State.slot, slot)
        u32(Tags.State.slots, slots)
        u32(Tags.State.libRev, libRev)
        str(Tags.State.album, album)
        u32(Tags.State.sleepLeftS, sleepLeftS)
        bool(Tags.State.charging, charging)
        u32(Tags.State.cardFreeMiB, cardFreeMiB)
        u32(Tags.State.cardTotalMiB, cardTotalMiB)
        u8(Tags.State.volumePct, volumePct)
        u8(Tags.State.volumeStepPct, volumeStepPct)
        bool(Tags.State.lyrics, lyrics)
        bool(Tags.State.lyricsPlain, lyricsPlain)
        u8(Tags.State.maxVolumePct, maxVolumePct)
        bool(Tags.State.favorite, favorite)
        u32(Tags.State.snapRev, snapRev)
        u32(Tags.State.lyricsCrc, lyricsCrc)
        str(Tags.State.eqSlot, eqSlot)
        enum(Tags.State.card, card)
        i32(Tags.State.lyricsOffsetMs, lyricsOffsetMs)
        u32(Tags.State.playLogSeq, playLogSeq)
        bool(Tags.State.receiver, receiver)
        str(Tags.State.phoneName, phoneName)
        u32(Tags.State.phoneArtCrc, phoneArtCrc)
        u32(Tags.State.phoneTrackNo, phoneTrackNo)
        u32(Tags.State.phoneTracks, phoneTracks)
        u64(Tags.State.phoneFile, phoneFile)
        bool(Tags.State.buffering, buffering)
        u32(Tags.State.aheadMs, aheadMs)
        bool(Tags.State.imgHold, imgHold)
        bool(Tags.State.updHold, updHold)
    }

    companion object {
        fun from(n: Node) = State(
            n.int(Tags.State.source),
            n.int(Tags.State.output),
            n.long(Tags.State.track),
            n.str(Tags.State.title),
            n.str(Tags.State.artist),
            n.strs(Tags.State.artists),
            n.strs(Tags.State.mbids),
            n.long(Tags.State.durationMs),
            n.long(Tags.State.positionMs),
            n.long(Tags.State.sampleMs),
            n.long(Tags.State.sentMs),
            n.bool(Tags.State.playing),
            n.bool(Tags.State.shuffle),
            n.int(Tags.State.repeat),
            n.int(Tags.State.volume),
            n.int(Tags.State.maxVolume),
            n.int(Tags.State.battery),
            n.long(Tags.State.queueRev),
            n.long(Tags.State.slot),
            n.long(Tags.State.slots),
            n.long(Tags.State.libRev),
            n.str(Tags.State.album),
            n.long(Tags.State.sleepLeftS),
            n.bool(Tags.State.charging),
            n.long(Tags.State.cardFreeMiB),
            n.long(Tags.State.cardTotalMiB),
            n.int(Tags.State.volumePct),
            n.int(Tags.State.volumeStepPct),
            n.bool(Tags.State.lyrics),
            n.int(Tags.State.maxVolumePct),
            n.bool(Tags.State.favorite),
            n.long(Tags.State.snapRev),
            n.bool(Tags.State.lyricsPlain),
            n.long(Tags.State.lyricsCrc),
            n.bool(Tags.State.positionHeld),
            n.str(Tags.State.eqSlot),
            n.int(Tags.State.card),
            n.int(Tags.State.lyricsOffsetMs),
            n.long(Tags.State.playLogSeq),
            n.bool(Tags.State.receiver),
            n.str(Tags.State.phoneName),
            n.long(Tags.State.phoneArtCrc),
            n.long(Tags.State.phoneTrackNo),
            n.long(Tags.State.phoneTracks),
            n.long(Tags.State.phoneFile),
            n.bool(Tags.State.buffering),
            n.long(Tags.State.aheadMs),
            n.bool(Tags.State.imgHold),
            n.bool(Tags.State.updHold),
        )
    }
}

data class Row(
    val key: String?,
    val label: String?,
    val pivot: String?,
    val kind: Int?,
    val values: List<String> = emptyList(),
    val min: Int? = null,
    val max: Int? = null,
    val step: Int? = null,
    val words: List<String> = emptyList(),
    val flags: List<Int> = emptyList(),
) {
    fun write(o: TlvWriter) = o.apply {
        str(Tags.Row.key, key)
        str(Tags.Row.label, label)
        str(Tags.Row.pivot, pivot)
        enum(Tags.Row.kind, kind)
        values.forEach { str(Tags.Row.`value`, it) }
        i32(Tags.Row.min, min)
        i32(Tags.Row.max, max)
        i32(Tags.Row.step, step)
        words.forEach { str(Tags.Row.word, it) }
        flags.forEach { enum(Tags.Row.flag, it) }
    }

    val size: Int get() = TlvWriter().rec(Tags.Schema.row) { write(this) }.size

    companion object {
        fun from(n: Node) = Row(
            n.str(Tags.Row.key),
            n.str(Tags.Row.label),
            n.str(Tags.Row.pivot),
            n.int(Tags.Row.kind),
            n.strs(Tags.Row.`value`),
            n.int(Tags.Row.min),
            n.int(Tags.Row.max),
            n.int(Tags.Row.step),
            n.strs(Tags.Row.word),
            n.ints(Tags.Row.flag),
        )
    }
}

data class Schema(
    val rev: Long?,
    val pivot: String?,
    val pivotIndex: Int?,
    val pivotCount: Int?,
    val page: PageInfo,
    val rows: List<Row>,
) {
    val last: Boolean get() = !page.more && pivotIndex != null && pivotCount != null && pivotIndex == pivotCount - 1

    fun encode() = tlv {
        str(Tags.Schema.pivot, pivot)
        u32(Tags.Schema.offset, page.offset)
        u32(Tags.Schema.total, page.total)
        bool(Tags.Schema.more, page.more)
        rows.forEach { r -> rec(Tags.Schema.row) { r.write(this) } }
        u32(Tags.Schema.rev, rev)
        u8(Tags.Schema.pivotIndex, pivotIndex)
        u8(Tags.Schema.pivotCount, pivotCount)
    }

    companion object {
        fun from(n: Node) = Schema(
            n.long(Tags.Schema.rev),
            n.str(Tags.Schema.pivot),
            n.int(Tags.Schema.pivotIndex),
            n.int(Tags.Schema.pivotCount),
            PageInfo.read(n, Tags.Schema.offset, Tags.Schema.total, Tags.Schema.more),
            n.nodes(Tags.Schema.row).map { Row.from(it) },
        )
    }
}

data class Settings(val rev: Long, val text: String) {
    fun encode() = tlv {
        u32(Tags.Settings.rev, rev)
        str(Tags.Settings.text, text)
    }

    companion object {
        fun from(n: Node) = Settings(n.long(Tags.Settings.rev) ?: 0, n.str(Tags.Settings.text) ?: "")
    }
}

data class Band(val type: Int, val grid: Int, val milliHz: Int, val gain10: Int, val q100: Int) {
    fun write(o: TlvWriter) = o.apply {
        enum(Tags.Band.type, type)
        i32(Tags.Band.grid, grid)
        i32(Tags.Band.milliHz, milliHz)
        i32(Tags.Band.gain10, gain10)
        i32(Tags.Band.q100, q100)
    }

    companion object {
        fun from(n: Node) = Band(
            n.int(Tags.Band.type) ?: -1,
            n.int(Tags.Band.grid) ?: -1,
            n.int(Tags.Band.milliHz) ?: 0,
            n.int(Tags.Band.gain10) ?: 0,
            n.int(Tags.Band.q100) ?: 0,
        )
    }
}

data class Profile(val preamp10: Int, val bands: List<Band>) {
    fun write(o: TlvWriter) = o.apply {
        i32(Tags.Profile.preamp10, preamp10)
        bands.forEach { b -> rec(Tags.Profile.band) { b.write(this) } }
    }

    companion object {
        fun from(n: Node) = Profile(n.int(Tags.Profile.preamp10) ?: 0, n.nodes(Tags.Profile.band).map { Band.from(it) })
    }
}

data class EqName(val key: String, val name: String?, val preset: Boolean? = null) {
    fun write(o: TlvWriter) = o.apply {
        str(Tags.EqName.key, key)
        str(Tags.EqName.name, name)
        bool(Tags.EqName.preset, preset)
    }

    val size: Int get() = TlvWriter().rec(Tags.Eq.profile) { write(this) }.size
}

data class EqSlot(val key: String?, val label: String?) {
    fun write(o: TlvWriter) = o.apply {
        str(Tags.EqSlot.key, key)
        str(Tags.EqSlot.label, label)
    }

    companion object {
        fun from(n: Node) = EqSlot(n.str(Tags.EqSlot.key), n.str(Tags.EqSlot.label))
    }
}

data class GraphicBand(val grid: Int, val gain10: Int) {
    fun write(o: TlvWriter) = o.apply {
        i32(Tags.GraphicBand.grid, grid)
        i32(Tags.GraphicBand.gain10, gain10)
    }

    companion object {
        fun from(n: Node) = GraphicBand(n.int(Tags.GraphicBand.grid) ?: -1, n.int(Tags.GraphicBand.gain10) ?: 0)
    }
}

data class Graphic(val preamp10: Int, val bands: List<GraphicBand>) {
    fun write(o: TlvWriter) = o.apply {
        i32(Tags.Graphic.preamp10, preamp10)
        bands.forEach { b -> rec(Tags.Graphic.band) { b.write(this) } }
    }

    companion object {
        fun from(n: Node) = Graphic(n.int(Tags.Graphic.preamp10) ?: 0, n.nodes(Tags.Graphic.band).map { GraphicBand.from(it) })
    }
}

data class Eq(
    val output: Int?,
    val key: String?,
    val names: List<EqName>,
    val ints: Profile?,
    val page: PageInfo,
    val slot: String? = null,
    val mode: Int? = null,
    val graphicKey: String? = null,
    val graphicNames: List<EqName> = emptyList(),
    val gains: Graphic? = null,
    val graphicOffset: Long? = null,
    val graphicTotal: Long? = null,
) {
    fun encode() = tlv {
        enum(Tags.Eq.output, output)
        str(Tags.Eq.slot, slot)
        str(Tags.Eq.key, key)
        names.forEach { e -> rec(Tags.Eq.profile) { e.write(this) } }
        ints?.let { p -> rec(Tags.Eq.ints) { p.write(this) } }
        u32(Tags.Eq.offset, page.offset)
        u32(Tags.Eq.total, page.total)
        bool(Tags.Eq.more, page.more)
        enum(Tags.Eq.mode, mode)
        str(Tags.Eq.graphicKey, graphicKey)
        gains?.let { g -> rec(Tags.Eq.gains) { g.write(this) } }
        graphicNames.forEach { e -> rec(Tags.Eq.graphicName) { e.write(this) } }
        u32(Tags.Eq.graphicOffset, graphicOffset)
        u32(Tags.Eq.graphicTotal, graphicTotal)
    }

    companion object {
        private fun names(n: Node, tag: Int) = n.nodes(tag).map { EqName(it.str(Tags.EqName.key) ?: "", it.str(Tags.EqName.name), it.bool(Tags.EqName.preset)) }

        fun from(n: Node) = Eq(
            n.int(Tags.Eq.output),
            n.str(Tags.Eq.key),
            names(n, Tags.Eq.profile),
            n.node(Tags.Eq.ints)?.let { Profile.from(it) },
            PageInfo.read(n, Tags.Eq.offset, Tags.Eq.total, Tags.Eq.more),
            n.str(Tags.Eq.slot),
            n.int(Tags.Eq.mode),
            n.str(Tags.Eq.graphicKey),
            names(n, Tags.Eq.graphicName),
            n.node(Tags.Eq.gains)?.let { Graphic.from(it) },
            n.long(Tags.Eq.graphicOffset),
            n.long(Tags.Eq.graphicTotal),
        )
    }
}

data class QueueItem(
    val track: Long?,
    val title: String?,
    val artists: List<String> = emptyList(),
    val mbids: List<String> = emptyList(),
    val album: String? = null,
    val durationMs: Long? = null,
    val lyrics: Boolean? = null,
    val lyricsPlain: Boolean? = null,
    val phoneFile: Long? = null,
) {
    fun write(o: TlvWriter) = o.apply {
        u64(Tags.QueueItem.track, track)
        str(Tags.QueueItem.title, title)
        artists.forEach { str(Tags.QueueItem.artists, it) }
        mbids.forEach { str(Tags.QueueItem.mbids, it) }
        str(Tags.QueueItem.album, album)
        u32(Tags.QueueItem.durationMs, durationMs)
        bool(Tags.QueueItem.lyrics, lyrics)
        bool(Tags.QueueItem.lyricsPlain, lyricsPlain)
        u64(Tags.QueueItem.phoneFile, phoneFile)
    }

    val size: Int get() = TlvWriter().rec(Tags.Queue.item) { write(this) }.size
}

data class Queue(val rev: Long, val page: PageInfo, val items: List<QueueItem>) {
    fun encode() = tlv {
        u32(Tags.Queue.rev, rev)
        u32(Tags.Queue.offset, page.offset)
        u32(Tags.Queue.total, page.total)
        bool(Tags.Queue.more, page.more)
        items.forEach { i -> rec(Tags.Queue.item) { i.write(this) } }
    }

    companion object {
        fun from(n: Node) = Queue(
            n.long(Tags.Queue.rev) ?: 0,
            PageInfo.read(n, Tags.Queue.offset, Tags.Queue.total, Tags.Queue.more),
            n.nodes(Tags.Queue.item).map {
                QueueItem(
                    it.long(Tags.QueueItem.track), it.str(Tags.QueueItem.title), it.strs(Tags.QueueItem.artists), it.strs(Tags.QueueItem.mbids),
                    it.str(Tags.QueueItem.album), it.long(Tags.QueueItem.durationMs), it.bool(Tags.QueueItem.lyrics),
                    it.bool(Tags.QueueItem.lyricsPlain), it.long(Tags.QueueItem.phoneFile),
                )
            },
        )
    }
}

data class LibRow(val name: String?, val count: Long?, val first: Long?, val key: String?, val mbid: String? = null, val artist: String? = null) {
    fun write(o: TlvWriter) = o.apply {
        str(Tags.LibRow.name, name)
        u32(Tags.LibRow.count, count)
        u64(Tags.LibRow.first, first)
        str(Tags.LibRow.key, key)
        str(Tags.LibRow.mbid, mbid)
        str(Tags.LibRow.artist, artist)
    }

    val size: Int get() = TlvWriter().rec(Tags.Lib.row) { write(this) }.size
}

fun listView(view: Int?) = view == Enums.View.playlists || view == Enums.View.favorites

data class Lib(val libRev: Long, val view: Int, val parent: String?, val page: PageInfo, val rows: List<LibRow>, val snapRev: Long? = null) {
    fun encode() = tlv {
        u32(Tags.Lib.libRev, libRev)
        enum(Tags.Lib.view, view)
        str(Tags.Lib.parent, parent)
        u32(Tags.Lib.snapRev, snapRev)
        u32(Tags.Lib.offset, page.offset)
        u32(Tags.Lib.total, page.total)
        bool(Tags.Lib.more, page.more)
        rows.forEach { r -> rec(Tags.Lib.row) { r.write(this) } }
    }

    companion object {
        fun from(n: Node) = Lib(
            n.long(Tags.Lib.libRev) ?: 0,
            n.int(Tags.Lib.view) ?: -1,
            n.str(Tags.Lib.parent),
            PageInfo.read(n, Tags.Lib.offset, Tags.Lib.total, Tags.Lib.more),
            n.nodes(Tags.Lib.row).map {
                LibRow(it.str(Tags.LibRow.name), it.long(Tags.LibRow.count), it.long(Tags.LibRow.first), it.str(Tags.LibRow.key), it.str(Tags.LibRow.mbid), it.str(Tags.LibRow.artist))
            },
            n.long(Tags.Lib.snapRev),
        )
    }
}

data class SetReply(val rev: Long, val kept: List<String>, val refused: List<String>) {
    fun encode() = tlv {
        u32(Tags.SetReply.rev, rev)
        kept.forEach { str(Tags.SetReply.kept, it) }
        refused.forEach { str(Tags.SetReply.refused, it) }
    }

    companion object {
        fun from(n: Node) = SetReply(n.long(Tags.SetReply.rev) ?: 0, n.strs(Tags.SetReply.kept), n.strs(Tags.SetReply.refused))
    }
}

data class ImgBeginReply(val offset: Long, val crc: Long) {
    fun encode() = tlv {
        u32(Tags.ImgBeginReply.offset, offset)
        u32(Tags.ImgBeginReply.crc, crc)
    }

    companion object {
        fun from(n: Node) = ImgBeginReply(n.long(Tags.ImgBeginReply.offset) ?: 0, n.long(Tags.ImgBeginReply.crc) ?: 0)
    }
}

data class ImgEntry(
    val name: String,
    val mbid: String?,
    val origins: List<Int> = emptyList(),
    val full: Boolean? = null,
    val shownS: Long? = null,
    val crc: Long? = null,
    val srcCrc: Long? = null,
) {
    val size: Int get() = TlvWriter().rec(Tags.ImgListReply.entry) { write(this) }.size

    fun write(w: TlvWriter) = w.apply {
        str(Tags.ImgEntry.name, name)
        str(Tags.ImgEntry.mbid, mbid)
        origins.forEach { enum(Tags.ImgEntry.origin, it) }
        bool(Tags.ImgEntry.full, full)
        u32(Tags.ImgEntry.shownS, shownS)
        u32(Tags.ImgEntry.crc, crc)
        u32(Tags.ImgEntry.srcCrc, srcCrc)
    }

    companion object {
        fun from(n: Node) = ImgEntry(
            n.str(Tags.ImgEntry.name) ?: "",
            n.str(Tags.ImgEntry.mbid),
            n.ints(Tags.ImgEntry.origin),
            n.bool(Tags.ImgEntry.full),
            n.long(Tags.ImgEntry.shownS),
            n.long(Tags.ImgEntry.crc),
            n.long(Tags.ImgEntry.srcCrc),
        )
    }
}

data class ImgListReply(val page: PageInfo, val entries: List<ImgEntry>, val bytes: Long? = null, val cap: Long? = null, val srcRoom: Long? = null) {
    fun encode() = tlv {
        u32(Tags.ImgListReply.offset, page.offset)
        u32(Tags.ImgListReply.total, page.total)
        bool(Tags.ImgListReply.more, page.more)
        entries.forEach { e ->
            rec(Tags.ImgListReply.entry) { e.write(this) }
        }
        u64(Tags.ImgListReply.bytes, bytes)
        u64(Tags.ImgListReply.cap, cap)
        u64(Tags.ImgListReply.srcRoom, srcRoom)
    }

    companion object {
        fun from(n: Node) = ImgListReply(
            PageInfo.read(n, Tags.ImgListReply.offset, Tags.ImgListReply.total, Tags.ImgListReply.more),
            n.nodes(Tags.ImgListReply.entry).map { ImgEntry.from(it) },
            n.long(Tags.ImgListReply.bytes),
            n.long(Tags.ImgListReply.cap),
            n.long(Tags.ImgListReply.srcRoom),
        )
    }
}

data class ImgAbort(val name: String, val offset: Long, val code: Int) {
    fun encode() = tlv {
        str(Tags.ImgAbort.name, name)
        u32(Tags.ImgAbort.offset, offset)
        enum(Tags.ImgAbort.code, code)
    }

    companion object {
        fun from(n: Node) = ImgAbort(n.str(Tags.ImgAbort.name) ?: "", n.long(Tags.ImgAbort.offset) ?: 0, n.int(Tags.ImgAbort.code) ?: -1)
    }
}

data class ArtGetReply(val crc: Long?, val total: Long?, val w: Int?, val h: Int?, val offset: Long, val data: ByteArray?) {
    fun encode() = tlv {
        u32(Tags.ArtGetReply.crc, crc)
        u32(Tags.ArtGetReply.total, total)
        u16(Tags.ArtGetReply.w, w)
        u16(Tags.ArtGetReply.h, h)
        u32(Tags.ArtGetReply.offset, offset)
        bytes(Tags.ArtGetReply.data, data)
    }

    companion object {
        fun from(n: Node) = ArtGetReply(
            n.long(Tags.ArtGetReply.crc),
            n.long(Tags.ArtGetReply.total),
            n.int(Tags.ArtGetReply.w),
            n.int(Tags.ArtGetReply.h),
            n.long(Tags.ArtGetReply.offset) ?: 0,
            n.bytes(Tags.ArtGetReply.data),
        )
    }
}

data class ArtBlock(val cmd: Int?, val name: String?, val mbid: String?, val crc: Long?) {
    fun encode() = tlv {
        enum(Tags.ArtBlock.cmd, cmd)
        str(Tags.ArtBlock.name, name)
        str(Tags.ArtBlock.mbid, mbid)
        u32(Tags.ArtBlock.crc, crc)
    }

    companion object {
        fun from(n: Node) = ArtBlock(n.int(Tags.ArtBlock.cmd), n.str(Tags.ArtBlock.name), n.str(Tags.ArtBlock.mbid), n.long(Tags.ArtBlock.crc))
    }
}

data class LyrLine(val ms: Long?, val text: String) {
    fun write(o: TlvWriter) = o.apply {
        u32(Tags.LyrLine.ms, ms)
        str(Tags.LyrLine.text, text)
    }

    val size: Int get() = TlvWriter().rec(Tags.LyrGetReply.line) { write(this) }.size
}

data class ArtPick(val name: String?, val mbid: String?) {
    fun encode() = tlv {
        str(Tags.ArtPick.name, name)
        str(Tags.ArtPick.mbid, mbid)
    }

    companion object {
        fun from(n: Node) = ArtPick(n.str(Tags.ArtPick.name), n.str(Tags.ArtPick.mbid))
    }
}

data class ArtPickReply(val shown: Boolean?) {
    fun encode() = tlv { bool(Tags.ArtPickReply.shown, shown) }

    companion object {
        fun from(n: Node) = ArtPickReply(n.bool(Tags.ArtPickReply.shown))
    }
}

data class LyrGetReply(val crc: Long?, val kind: Int?, val total: Long?, val offset: Long?, val more: Boolean?, val lines: List<LyrLine>, val offsetMs: Int? = null) {
    fun encode() = tlv {
        u32(Tags.LyrGetReply.crc, crc)
        enum(Tags.LyrGetReply.kind, kind)
        u32(Tags.LyrGetReply.total, total)
        u32(Tags.LyrGetReply.offset, offset)
        bool(Tags.LyrGetReply.more, more)
        lines.forEach { l -> rec(Tags.LyrGetReply.line) { l.write(this) } }
        i32(Tags.LyrGetReply.offsetMs, offsetMs)
    }

    companion object {
        fun from(n: Node) = LyrGetReply(
            n.long(Tags.LyrGetReply.crc),
            n.int(Tags.LyrGetReply.kind),
            n.long(Tags.LyrGetReply.total),
            n.long(Tags.LyrGetReply.offset),
            n.bool(Tags.LyrGetReply.more),
            n.nodes(Tags.LyrGetReply.line).map { LyrLine(it.long(Tags.LyrLine.ms), it.str(Tags.LyrLine.text) ?: "") },
            n.int(Tags.LyrGetReply.offsetMs),
        )
    }
}

data class LyrOffsetReply(val ms: Int) {
    fun encode() = tlv { i32(Tags.LyrOffsetReply.ms, ms.takeIf { it != 0 }) }

    companion object {
        fun from(n: Node) = LyrOffsetReply(n.int(Tags.LyrOffsetReply.ms) ?: 0)
    }
}

data class LrcReply(val lyrics: Boolean?, val lyricsPlain: Boolean?) {
    fun encode() = tlv {
        bool(Tags.LrcPutReply.lyrics, lyrics)
        bool(Tags.LrcPutReply.lyricsPlain, lyricsPlain)
    }

    companion object {
        fun from(n: Node) = LrcReply(n.bool(Tags.LrcPutReply.lyrics), n.bool(Tags.LrcPutReply.lyricsPlain))
    }
}

data class QueueOpReply(val added: Long?, val rev: Long?) {
    fun encode() = tlv {
        u32(Tags.QueueOpReply.added, added)
        u32(Tags.QueueOpReply.rev, rev)
    }

    companion object {
        fun from(n: Node) = QueueOpReply(n.long(Tags.QueueOpReply.added), n.long(Tags.QueueOpReply.rev))
    }
}

data class PlayEntry(
    val seq: Long,
    val startLocalS: Long? = null,
    val playedMs: Long? = null,
    val durationMs: Long? = null,
    val track: Long? = null,
    val title: String? = null,
    val artist: String? = null,
    val artists: List<String> = emptyList(),
    val mbids: List<String> = emptyList(),
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNo: Int? = null,
    val zoneS: Int? = null,
    val liveSeen: Boolean? = null,
    val phoneFile: Long? = null,
) {
    fun write(o: TlvWriter) = o.apply {
        u32(Tags.PlayEntry.seq, seq)
        i64(Tags.PlayEntry.startLocalS, startLocalS)
        u32(Tags.PlayEntry.playedMs, playedMs)
        u32(Tags.PlayEntry.durationMs, durationMs)
        u64(Tags.PlayEntry.track, track)
        str(Tags.PlayEntry.title, title)
        str(Tags.PlayEntry.artist, artist)
        artists.forEach { str(Tags.PlayEntry.artists, it) }
        mbids.forEach { str(Tags.PlayEntry.mbids, it) }
        str(Tags.PlayEntry.album, album)
        str(Tags.PlayEntry.albumArtist, albumArtist)
        u16(Tags.PlayEntry.trackNo, trackNo)
        i32(Tags.PlayEntry.zoneS, zoneS)
        bool(Tags.PlayEntry.liveSeen, liveSeen)
        u64(Tags.PlayEntry.phoneFile, phoneFile)
    }

    val size: Int get() = TlvWriter().rec(Tags.PlaylogGetReply.entry) { write(this) }.size

    companion object {
        fun from(n: Node) = PlayEntry(
            n.long(Tags.PlayEntry.seq) ?: 0,
            n.long(Tags.PlayEntry.startLocalS),
            n.long(Tags.PlayEntry.playedMs),
            n.long(Tags.PlayEntry.durationMs),
            n.long(Tags.PlayEntry.track),
            n.str(Tags.PlayEntry.title),
            n.str(Tags.PlayEntry.artist),
            n.strs(Tags.PlayEntry.artists),
            n.strs(Tags.PlayEntry.mbids),
            n.str(Tags.PlayEntry.album),
            n.str(Tags.PlayEntry.albumArtist),
            n.int(Tags.PlayEntry.trackNo),
            n.int(Tags.PlayEntry.zoneS),
            n.bool(Tags.PlayEntry.liveSeen),
            n.long(Tags.PlayEntry.phoneFile),
        )
    }
}

data class PlayLogReply(val log: Long?, val newest: Long?, val more: Boolean?, val entries: List<PlayEntry>, val first: Long?) {
    fun encode() = tlv {
        u64(Tags.PlaylogGetReply.log, log)
        u32(Tags.PlaylogGetReply.newest, newest)
        bool(Tags.PlaylogGetReply.more, more)
        entries.forEach { e -> rec(Tags.PlaylogGetReply.entry) { e.write(this) } }
        u32(Tags.PlaylogGetReply.first, first)
    }

    companion object {
        fun from(n: Node) = PlayLogReply(
            n.long(Tags.PlaylogGetReply.log),
            n.long(Tags.PlaylogGetReply.newest),
            n.bool(Tags.PlaylogGetReply.more),
            n.nodes(Tags.PlaylogGetReply.entry).map { PlayEntry.from(it) },
            n.long(Tags.PlaylogGetReply.first),
        )
    }
}

fun lyricsCrc(kind: Int, lines: List<LyrLine>): Long {
    val c = CRC32()
    c.update(kind)
    for (l in lines) {
        if (kind == Enums.LyricsKind.synced) {
            val ms = l.ms ?: 0
            for (i in 0 until 4) c.update((ms ushr (8 * i)).toInt() and 0xFF)
        }
        c.update(l.text.toByteArray(Charsets.UTF_8))
        c.update(0x0A)
    }
    return c.value
}

data class EqPresetReply(val key: String?) {
    fun encode() = tlv { str(Tags.EqPresetReply.key, key) }

    companion object {
        fun from(n: Node) = EqPresetReply(n.str(Tags.EqPresetReply.key))
    }
}

data class UpdBeginReply(val offset: Long) {
    fun encode() = tlv { u32(Tags.UpdBeginReply.offset, offset) }

    companion object {
        fun from(n: Node) = UpdBeginReply(n.long(Tags.UpdBeginReply.offset) ?: 0)
    }
}

data class UpdAbort(val offset: Long, val code: Int) {
    fun encode() = tlv {
        u32(Tags.UpdAbort.offset, offset)
        enum(Tags.UpdAbort.code, code)
    }

    companion object {
        fun from(n: Node) = UpdAbort(n.long(Tags.UpdAbort.offset) ?: 0, n.int(Tags.UpdAbort.code) ?: -1)
    }
}

data class StreamRead(val file: Long?, val rev: Long?, val offset: Long?, val len: Long?, val landed: Long? = null) {
    fun encode() = tlv {
        u64(Tags.StreamRead.file, file)
        u64(Tags.StreamRead.rev, rev)
        u64(Tags.StreamRead.offset, offset)
        u32(Tags.StreamRead.len, len)
        u64(Tags.StreamRead.landed, landed)
    }

    companion object {
        fun from(n: Node) = StreamRead(n.long(Tags.StreamRead.file), n.long(Tags.StreamRead.rev), n.long(Tags.StreamRead.offset), n.long(Tags.StreamRead.len), n.long(Tags.StreamRead.landed))
    }
}

data class StreamReadReply(val offset: Long?, val size: Long?, val data: ByteArray?) {
    fun encode() = tlv {
        u64(Tags.StreamReadReply.offset, offset)
        u64(Tags.StreamReadReply.size, this@StreamReadReply.size)
        bytes(Tags.StreamReadReply.data, data)
    }

    companion object {
        fun from(n: Node) = StreamReadReply(n.long(Tags.StreamReadReply.offset), n.long(Tags.StreamReadReply.size), n.bytes(Tags.StreamReadReply.data))
    }
}

data class StreamCancel(val seqs: List<Int>) {
    fun encode() = tlv { seqs.forEach { u16(Tags.StreamCancel.seq, it) } }

    companion object {
        fun from(n: Node) = StreamCancel(n.ints(Tags.StreamCancel.seq))
    }
}

data class PhoneItem(
    val file: Long?,
    val rev: Long? = null,
    val size: Long? = null,
    val mime: String? = null,
    val durationMs: Long? = null,
    val title: String? = null,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNo: Int? = null,
    val discNo: Int? = null,
    val art: String? = null,
    val rate: Long? = null,
    val bits: Int? = null,
) {
    fun write(o: TlvWriter) = o.apply {
        u64(Tags.PhoneItem.file, file)
        u64(Tags.PhoneItem.rev, rev)
        u64(Tags.PhoneItem.size, this@PhoneItem.size)
        str(Tags.PhoneItem.mime, mime)
        u32(Tags.PhoneItem.durationMs, durationMs)
        str(Tags.PhoneItem.title, title)
        artists.forEach { str(Tags.PhoneItem.artists, it) }
        str(Tags.PhoneItem.album, album)
        str(Tags.PhoneItem.albumArtist, albumArtist)
        u16(Tags.PhoneItem.trackNo, trackNo)
        u16(Tags.PhoneItem.discNo, discNo)
        str(Tags.PhoneItem.art, art)
        u32(Tags.PhoneItem.rate, rate)
        u8(Tags.PhoneItem.bits, bits)
    }

    val recordBytes: Int get() = TlvWriter().rec(Tags.PhoneList.item) { write(this) }.size

    companion object {
        fun from(n: Node) = PhoneItem(
            n.long(Tags.PhoneItem.file),
            n.long(Tags.PhoneItem.rev),
            n.long(Tags.PhoneItem.size),
            n.str(Tags.PhoneItem.mime),
            n.long(Tags.PhoneItem.durationMs),
            n.str(Tags.PhoneItem.title),
            n.strs(Tags.PhoneItem.artists),
            n.str(Tags.PhoneItem.album),
            n.str(Tags.PhoneItem.albumArtist),
            n.int(Tags.PhoneItem.trackNo),
            n.int(Tags.PhoneItem.discNo),
            n.str(Tags.PhoneItem.art),
            n.long(Tags.PhoneItem.rate),
            n.int(Tags.PhoneItem.bits),
        )
    }
}

data class PhoneList(val list: Long?, val offset: Long?, val total: Long?, val items: List<PhoneItem>) {
    fun encode() = tlv {
        u32(Tags.PhoneList.list, list)
        u32(Tags.PhoneList.offset, offset)
        u32(Tags.PhoneList.total, total)
        items.forEach { i -> rec(Tags.PhoneList.item) { i.write(this) } }
    }

    companion object {
        fun from(n: Node) = PhoneList(
            n.long(Tags.PhoneList.list),
            n.long(Tags.PhoneList.offset),
            n.long(Tags.PhoneList.total),
            n.nodes(Tags.PhoneList.item).map { PhoneItem.from(it) },
        )
    }
}

data class PhoneArt(val art: String?, val px: Int?, val offset: Long?, val have: Long?) {
    fun encode() = tlv {
        str(Tags.PhoneArt.art, art)
        u16(Tags.PhoneArt.px, px)
        u32(Tags.PhoneArt.offset, offset)
        u32(Tags.PhoneArt.have, have)
    }

    companion object {
        fun from(n: Node) = PhoneArt(n.str(Tags.PhoneArt.art), n.int(Tags.PhoneArt.px), n.long(Tags.PhoneArt.offset), n.long(Tags.PhoneArt.have))
    }
}

data class PhoneArtReply(val crc: Long?, val total: Long?, val w: Int?, val h: Int?, val offset: Long?, val data: ByteArray?) {
    fun encode() = tlv {
        u32(Tags.PhoneArtReply.crc, crc)
        u32(Tags.PhoneArtReply.total, total)
        u16(Tags.PhoneArtReply.w, w)
        u16(Tags.PhoneArtReply.h, h)
        u32(Tags.PhoneArtReply.offset, offset)
        bytes(Tags.PhoneArtReply.data, data)
    }

    companion object {
        fun from(n: Node) = PhoneArtReply(
            n.long(Tags.PhoneArtReply.crc),
            n.long(Tags.PhoneArtReply.total),
            n.int(Tags.PhoneArtReply.w),
            n.int(Tags.PhoneArtReply.h),
            n.long(Tags.PhoneArtReply.offset),
            n.bytes(Tags.PhoneArtReply.data),
        )
    }
}
