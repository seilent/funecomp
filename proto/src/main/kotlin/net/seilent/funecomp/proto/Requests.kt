package net.seilent.funecomp.proto

import net.seilent.funecomp.proto.gen.Tags

object Req {
    fun transport(cmd: Int, seekMs: Long? = null, on: Boolean? = null, repeat: Int? = null) = tlv {
        enum(Tags.Transport.cmd, cmd)
        u32(Tags.Transport.seekMs, seekMs)
        bool(Tags.Transport.on, on)
        enum(Tags.Transport.repeat, repeat)
    }

    fun volume(set: Int? = null, step: Int? = null) = tlv {
        u16(Tags.Volume.set, set)
        i32(Tags.Volume.step, step)
    }

    fun set(text: String) = tlv { str(Tags.Set.text, text) }

    fun draft(kind: Int, value: String? = null, role: Int? = null, minutes: Long? = null, unixS: Long? = null, zoneS: Int? = null) = tlv {
        enum(Tags.Draft.kind, kind)
        str(Tags.Draft.`value`, value)
        enum(Tags.Draft.role, role)
        u32(Tags.Draft.minutes, minutes)
        i64(Tags.Draft.unixS, unixS)
        i32(Tags.Draft.zoneS, zoneS)
    }

    fun eqSet(output: Int, key: String? = null, ints: Profile? = null) = tlv {
        enum(Tags.EqSet.output, output)
        str(Tags.EqSet.key, key)
        ints?.let { p -> rec(Tags.EqSet.ints) { p.write(this) } }
    }

    fun play(view: Int? = null, parent: String? = null, libRev: Long? = null, start: Long? = null, shuffle: Boolean? = null, tracks: List<Long> = emptyList()) = tlv {
        enum(Tags.Play.view, view)
        str(Tags.Play.parent, parent)
        u32(Tags.Play.libRev, libRev)
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

    fun libGet(view: Int, parent: String?, offset: Long, count: Long, libRev: Long?) = tlv {
        enum(Tags.LibGet.view, view)
        str(Tags.LibGet.parent, parent)
        u32(Tags.LibGet.offset, offset)
        u32(Tags.LibGet.count, count)
        u32(Tags.LibGet.libRev, libRev)
    }

    fun action(cmd: Int, track: Long? = null) = tlv {
        enum(Tags.Action.cmd, cmd)
        u64(Tags.Action.track, track)
    }

    fun imgBegin(kind: Int, name: String, mbid: String?, total: Long, crc: Long, w: Int, h: Int) = tlv {
        enum(Tags.ImgBegin.kind, kind)
        str(Tags.ImgBegin.name, name)
        str(Tags.ImgBegin.mbid, mbid)
        u32(Tags.ImgBegin.total, total)
        u32(Tags.ImgBegin.crc, crc)
        u16(Tags.ImgBegin.w, w)
        u16(Tags.ImgBegin.h, h)
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
}
