package net.seilent.funecomp.proto

import net.seilent.funecomp.proto.gen.Proto

class ProtoException(val reason: String, message: String) : Exception(message)

class Frame(val type: Int, val flags: Int, val seq: Int, val payload: ByteArray = ByteArray(0)) {
    val isReply: Boolean get() = flags and Proto.flagReply != 0
    val isError: Boolean get() = flags and Proto.flagError != 0
    val wantsReply: Boolean get() = flags and Proto.flagWant != 0

    fun encode(): ByteArray {
        require(payload.size <= Proto.maxPayload) { "payload ${payload.size} over ${Proto.maxPayload}" }
        require(flags and Proto.flagReserved == 0) { "reserved flag bits in 0x%02x".format(flags) }
        val out = ByteArray(Proto.headerBytes + payload.size)
        out[0] = payload.size.toByte()
        out[1] = (payload.size ushr 8).toByte()
        out[2] = type.toByte()
        out[3] = flags.toByte()
        out[4] = seq.toByte()
        out[5] = (seq ushr 8).toByte()
        payload.copyInto(out, Proto.headerBytes)
        return out
    }

    override fun toString(): String = "Frame(type=0x%02x flags=0x%02x seq=%d len=%d)".format(type, flags, seq, payload.size)

    companion object {
        fun decode(bytes: ByteArray): Frame {
            val frames = FrameReader().feed(bytes)
            if (frames.size != 1) throw ProtoException("oversize", "expected one frame, got ${frames.size}")
            return frames[0]
        }
    }
}

class FrameReader(private val max: Int = Proto.maxPayload) {
    private val buf = ByteArray(Proto.headerBytes + max)
    private var have = 0

    val pending: Int get() = have

    fun feed(bytes: ByteArray, off: Int = 0, len: Int = bytes.size - off): List<Frame> {
        val out = ArrayList<Frame>()
        var at = off
        val end = off + len
        while (at < end) {
            val need = if (have < Proto.headerBytes) Proto.headerBytes - have else Proto.headerBytes + length() - have
            val n = minOf(need, end - at)
            bytes.copyInto(buf, have, at, at + n)
            have += n
            at += n
            if (have == Proto.headerBytes) {
                if (length() > max) throw ProtoException("oversize", "payload length ${length()} over $max")
                if (buf[3].toInt() and Proto.flagReserved != 0)
                    throw ProtoException("reservedFlags", "reserved flag bits set: 0x%02x".format(buf[3].toInt() and 0xFF))
            }
            if (have >= Proto.headerBytes && have == Proto.headerBytes + length()) {
                out += Frame(
                    buf[2].toInt() and 0xFF,
                    buf[3].toInt() and 0xFF,
                    (buf[4].toInt() and 0xFF) or ((buf[5].toInt() and 0xFF) shl 8),
                    buf.copyOfRange(Proto.headerBytes, have),
                )
                have = 0
            }
        }
        return out
    }

    private fun length() = (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8)
}
