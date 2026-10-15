package net.seilent.funecomp.proto

import net.seilent.funecomp.proto.gen.Proto
import java.io.ByteArrayOutputStream

class Tlv(val tag: Int, val value: ByteArray) {
    companion object {
        fun parse(bytes: ByteArray): List<Tlv> {
            val out = ArrayList<Tlv>()
            var at = 0
            while (at < bytes.size) {
                if (bytes.size - at < Proto.tlvHeaderBytes) throw ProtoException("tlvOverrun", "TLV header past the payload at $at")
                val tag = bytes[at].toInt() and 0xFF
                val n = (bytes[at + 1].toInt() and 0xFF) or ((bytes[at + 2].toInt() and 0xFF) shl 8)
                at += Proto.tlvHeaderBytes
                if (n > bytes.size - at) throw ProtoException("tlvOverrun", "TLV $tag length $n past the payload")
                out += Tlv(tag, bytes.copyOfRange(at, at + n))
                at += n
            }
            return out
        }
    }
}

class TlvWriter {
    private val out = ByteArrayOutputStream()

    val size: Int get() = out.size()

    fun raw(tag: Int, value: ByteArray): TlvWriter {
        require(tag in 1..255 && value.size <= 0xFFFF)
        out.write(tag)
        out.write(value.size and 0xFF)
        out.write(value.size ushr 8)
        out.write(value)
        return this
    }

    private fun le(tag: Int, v: Long, width: Int): TlvWriter {
        val b = ByteArray(width)
        for (i in 0 until width) b[i] = (v ushr (8 * i)).toByte()
        return raw(tag, b)
    }

    fun u8(tag: Int, v: Int?) = apply { if (v != null) le(tag, v.toLong(), 1) }
    fun u16(tag: Int, v: Int?) = apply { if (v != null) le(tag, v.toLong(), 2) }
    fun u32(tag: Int, v: Long?) = apply { if (v != null) le(tag, v, 4) }
    fun u64(tag: Int, v: Long?) = apply { if (v != null) le(tag, v, 8) }
    fun i32(tag: Int, v: Int?) = apply { if (v != null) le(tag, v.toLong(), 4) }
    fun i64(tag: Int, v: Long?) = apply { if (v != null) le(tag, v, 8) }
    fun bool(tag: Int, v: Boolean?) = apply { if (v != null) raw(tag, byteArrayOf(if (v) 1 else 0)) }
    fun enum(tag: Int, v: Int?) = u8(tag, v)
    fun str(tag: Int, v: String?) = apply { if (v != null) raw(tag, v.toByteArray(Charsets.UTF_8)) }
    fun bytes(tag: Int, v: ByteArray?) = apply { if (v != null) raw(tag, v) }
    fun rec(tag: Int, block: TlvWriter.() -> Unit) = raw(tag, TlvWriter().apply(block).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()
}

fun tlv(block: TlvWriter.() -> Unit): ByteArray = TlvWriter().apply(block).toByteArray()
