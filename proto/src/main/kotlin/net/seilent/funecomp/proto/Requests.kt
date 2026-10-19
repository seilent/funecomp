package net.seilent.funecomp.proto

import net.seilent.funecomp.proto.gen.Tags

object Req {
    const val META_BYTES = 256

    fun cap(s: String?, max: Int = META_BYTES): String? {
        val b = s?.toByteArray(Charsets.UTF_8) ?: return null
        if (b.size <= max) return s
        var n = max
        while (n > 0 && b[n].toInt() and 0xC0 == 0x80) n--
        return String(b, 0, n, Charsets.UTF_8)
    }

    fun transport(cmd: Int, seekMs: Long? = null, on: Boolean? = null, repeat: Int? = null) = tlv {
        enum(Tags.Transport.cmd, cmd)
        u32(Tags.Transport.seekMs, seekMs)
        bool(Tags.Transport.on, on)
        enum(Tags.Transport.repeat, repeat)
    }

    fun volume(set: Int? = null, step: Int? = null, percent: Int? = null) = tlv {
        u16(Tags.Volume.set, set)
        i32(Tags.Volume.step, step)
        u8(Tags.Volume.percent, percent)
    }

    fun set(text: String) = tlv { str(Tags.Set.text, text) }

    fun draft(kind: Int, value: String? = null, role: Int? = null, minutes: Long? = null, unixS: Long? = null, zoneS: Int? = null, automatic: Boolean? = null) = tlv {
        enum(Tags.Draft.kind, kind)
        str(Tags.Draft.`value`, value)
        enum(Tags.Draft.role, role)
        u32(Tags.Draft.minutes, minutes)
        i64(Tags.Draft.unixS, unixS)
        i32(Tags.Draft.zoneS, zoneS)
        bool(Tags.Draft.automatic, automatic)
    }

    fun eqSet(output: Int, key: String? = null, ints: Profile? = null) = tlv {
        enum(Tags.EqSet.output, output)
        str(Tags.EqSet.key, key)
        ints?.let { p -> rec(Tags.EqSet.ints) { p.write(this) } }
    }

    fun eqPreset(op: Int, output: Int? = null, key: String? = null, name: String? = null, ints: Profile? = null, replace: Boolean? = null) = tlv {
        enum(Tags.EqPreset.op, op)
        enum(Tags.EqPreset.output, output)
        str(Tags.EqPreset.key, key)
        str(Tags.EqPreset.name, name)
        ints?.let { p -> rec(Tags.EqPreset.ints) { p.write(this) } }
        bool(Tags.EqPreset.replace, replace)
    }

    fun play(view: Int? = null, parent: String? = null, libRev: Long? = null, start: Long? = null, shuffle: Boolean? = null, tracks: List<Long> = emptyList(), snapRev: Long? = null) = tlv {
        enum(Tags.Play.view, view)
        str(Tags.Play.parent, parent)
        u32(Tags.Play.libRev, libRev)
        u32(Tags.Play.snapRev, snapRev)
        u32(Tags.Play.start, start)
        bool(Tags.Play.shuffle, shuffle)
        tracks.forEach { u64(Tags.Play.track, it) }
    }

    fun queueOp(cmd: Int, rev: Long? = null, offset: Long? = null, count: Long? = null, slot: Long? = null, to: Long? = null, tracks: List<Long> = emptyList()) = tlv {
        enum(Tags.QueueOp.cmd, cmd)
        u32(Tags.QueueOp.rev, rev)
        u32(Tags.QueueOp.offset, offset)
        u32(Tags.QueueOp.count, count)
        u32(Tags.QueueOp.slot, slot)
        u32(Tags.QueueOp.to, to)
        tracks.forEach { u64(Tags.QueueOp.track, it) }
    }

    fun libGet(view: Int, parent: String?, offset: Long, count: Long, libRev: Long?, snapRev: Long? = null) = tlv {
        enum(Tags.LibGet.view, view)
        str(Tags.LibGet.parent, parent)
        u32(Tags.LibGet.offset, offset)
        u32(Tags.LibGet.count, count)
        u32(Tags.LibGet.libRev, libRev)
        u32(Tags.LibGet.snapRev, snapRev)
    }

    fun action(cmd: Int, track: Long? = null) = tlv {
        enum(Tags.Action.cmd, cmd)
        u64(Tags.Action.track, track)
    }

    fun imgBegin(kind: Int, name: String, mbid: String?, total: Long, crc: Long, w: Int, h: Int, wrongArtist: Boolean? = null, source: String? = null, credit: String? = null) = tlv {
        enum(Tags.ImgBegin.kind, kind)
        str(Tags.ImgBegin.name, name)
        str(Tags.ImgBegin.mbid, mbid)
        u32(Tags.ImgBegin.total, total)
        u32(Tags.ImgBegin.crc, crc)
        u16(Tags.ImgBegin.w, w)
        u16(Tags.ImgBegin.h, h)
        bool(Tags.ImgBegin.wrongArtist, wrongArtist)
        str(Tags.ImgBegin.source, cap(source))
        str(Tags.ImgBegin.credit, cap(credit))
    }

    fun imgData(offset: Long, data: ByteArray) = tlv {
        u32(Tags.ImgData.offset, offset)
        bytes(Tags.ImgData.`data`, data)
    }

    fun imgEnd(crc: Long) = tlv { u32(Tags.ImgEnd.crc, crc) }

    fun imgList(offset: Long, count: Long) = tlv {
        u32(Tags.ImgList.offset, offset)
        u32(Tags.ImgList.count, count)
    }

    fun imgDel(name: String) = tlv { str(Tags.ImgDel.name, name) }

    fun artGet(track: Long, px: Int, offset: Long = 0, have: Long? = null) = tlv {
        u64(Tags.ArtGet.track, track)
        u16(Tags.ArtGet.px, px)
        u32(Tags.ArtGet.offset, offset)
        u32(Tags.ArtGet.have, have)
    }

    fun lyrGet(track: Long, offset: Long, have: Long?) = tlv {
        u64(Tags.LyrGet.track, track)
        u32(Tags.LyrGet.offset, offset)
        u32(Tags.LyrGet.have, have)
    }

    fun lrcPut(track: Long, libRev: Long?, text: ByteArray, crc: Long, snapRev: Long? = null) = tlv {
        u64(Tags.LrcPut.track, track)
        u32(Tags.LrcPut.libRev, libRev)
        u32(Tags.LrcPut.snapRev, snapRev)
        bytes(Tags.LrcPut.text, text)
        u32(Tags.LrcPut.crc, crc)
    }

    fun lrcDel(track: Long) = tlv { u64(Tags.LrcDel.track, track) }

    fun updBegin(board: String, size: Long, md5: String) = tlv {
        str(Tags.UpdBegin.board, board)
        u32(Tags.UpdBegin.size, size)
        str(Tags.UpdBegin.md5, md5)
    }

    fun updData(offset: Long, data: ByteArray) = tlv {
        u32(Tags.UpdData.offset, offset)
        bytes(Tags.UpdData.data, data)
    }

    fun updEnd(md5: String) = tlv { str(Tags.UpdEnd.md5, md5) }
}
