package net.seilent.funecomp.proto

import net.seilent.funecomp.proto.gen.Base
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Field
import net.seilent.funecomp.proto.gen.Tags

class Entry(val tag: Int, val field: Field?, val value: Any)

class Node(val scope: String, val entries: List<Entry>) {
    fun first(tag: Int): Any? = entries.firstOrNull { it.tag == tag }?.value
    fun all(tag: Int): List<Any> = entries.filter { it.tag == tag }.map { it.value }

    fun long(tag: Int): Long? = first(tag) as Long?
    fun int(tag: Int): Int? = long(tag)?.toInt()
    fun str(tag: Int): String? = first(tag) as String?
    fun bool(tag: Int): Boolean? = first(tag) as Boolean?
    fun bytes(tag: Int): ByteArray? = first(tag) as ByteArray?
    fun node(tag: Int): Node? = first(tag) as Node?
    fun longs(tag: Int): List<Long> = all(tag).map { it as Long }
    fun ints(tag: Int): List<Int> = all(tag).map { (it as Long).toInt() }
    fun strs(tag: Int): List<String> = all(tag).map { it as String }
    fun nodes(tag: Int): List<Node> = all(tag).map { it as Node }

    val unknownTags: List<Int> get() = entries.filter { it.field == null }.map { it.tag }

    fun hasUnknownEnum(): Boolean = entries.any { e ->
        val f = e.field ?: return@any false
        when (f.base) {
            Base.ENUM -> !known(f, (e.value as Long).toInt())
            Base.RECORD -> (e.value as Node).hasUnknownEnum()
            else -> false
        }
    }

    fun encode(): ByteArray = tlv {
        for (e in entries) {
            val f = e.field ?: continue
            when (f.base) {
                Base.U8, Base.ENUM -> u8(e.tag, (e.value as Long).toInt())
                Base.U16 -> u16(e.tag, (e.value as Long).toInt())
                Base.U32 -> u32(e.tag, e.value as Long)
                Base.U64 -> u64(e.tag, e.value as Long)
                Base.I32 -> i32(e.tag, (e.value as Long).toInt())
                Base.I64 -> i64(e.tag, e.value as Long)
                Base.BOOL -> bool(e.tag, e.value as Boolean)
                Base.STR -> str(e.tag, e.value as String)
                Base.BYTES -> bytes(e.tag, e.value as ByteArray)
                Base.RECORD -> raw(e.tag, (e.value as Node).encode())
            }
        }
    }

    fun print(prefix: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val index = HashMap<Int, Int>()
        var unknown = 0
        for (e in entries) {
            val f = e.field
            if (f == null) {
                out += "$prefix@unknown[${unknown++}]" to e.tag.toString()
                continue
            }
            val i = index.getOrDefault(e.tag, 0)
            index[e.tag] = i + 1
            val path = prefix + f.name + if (f.list) "[$i]" else ""
            when (f.base) {
                Base.RECORD -> out += (e.value as Node).print("$path.")
                Base.ENUM -> {
                    val v = (e.value as Long).toInt()
                    out += path to (Enums.byName[f.ref]?.get(v) ?: v.toString())
                }
                Base.U64 -> out += path to java.lang.Long.toUnsignedString(e.value as Long)
                Base.BYTES -> out += path to (e.value as ByteArray).joinToString("") { "%02x".format(it) }
                else -> out += path to e.value.toString()
            }
        }
        return out
    }

    companion object {
        private fun known(f: Field, v: Int) = Enums.byName[f.ref]?.containsKey(v) == true

        fun decode(scope: String, bytes: ByteArray): Node {
            val fields = Tags.scopes[scope] ?: emptyMap()
            val seen = HashSet<Int>()
            val entries = Tlv.parse(bytes).map { t ->
                val f = fields[t.tag] ?: return@map Entry(t.tag, null, t.value)
                if (!f.list && !seen.add(t.tag)) throw ProtoException("duplicate", "$scope.${f.name} sent twice")
                Entry(t.tag, f, value(scope, f, t.value))
            }
            return Node(scope, entries)
        }

        private fun value(scope: String, f: Field, b: ByteArray): Any {
            fun width(n: Int) {
                if (b.size != n) throw ProtoException("badValue", "$scope.${f.name} has ${b.size} bytes, wants $n")
            }
            fun le(): Long {
                var v = 0L
                for (i in b.indices.reversed()) v = (v shl 8) or (b[i].toLong() and 0xFF)
                return v
            }
            return when (f.base) {
                Base.U8, Base.ENUM -> { width(1); le() }
                Base.U16 -> { width(2); le() }
                Base.U32 -> { width(4); le() }
                Base.U64 -> { width(8); le() }
                Base.I32 -> { width(4); le().toInt().toLong() }
                Base.I64 -> { width(8); le() }
                Base.BOOL -> {
                    width(1)
                    if (b[0] > 1 || b[0] < 0) throw ProtoException("badValue", "$scope.${f.name} bool is ${b[0]}")
                    b[0].toInt() == 1
                }
                Base.STR -> String(b, Charsets.UTF_8)
                Base.BYTES -> b
                Base.RECORD -> decode(f.ref!!, b)
            }
        }
    }
}
