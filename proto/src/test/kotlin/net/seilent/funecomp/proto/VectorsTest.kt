package net.seilent.funecomp.proto

import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VectorsTest {
    class Vec(val name: String, val from: String) {
        var bytes = ByteArray(0)
        var reads: List<Int>? = null
        var re: String? = null
        val dec = ArrayList<Pair<String, String>>()
        var act: String? = null
    }

    private val vecs = LinkedHashMap<String, Vec>()

    init {
        val path = System.getProperty("funecomp.vectors") ?: "../third_party/funeos/vectors.txt"
        File(path).readLines().forEach { line ->
            val p = line.split(' ', limit = 4)
            val v = if (p[0] == "vec") null else vecs.getValue(p[1])
            when (p[0]) {
                "vec" -> vecs[p[1]] = Vec(p[1], p[2])
                "hex" -> v!!.bytes += hex(p[2])
                "reads" -> v!!.reads = p[2].split(',').map { it.toInt() }
                "re" -> v!!.re = p[2]
                "dec" -> v!!.dec += p[2] to unescape(p.getOrElse(3) { "" })
                "act" -> v!!.act = p[2]
                else -> throw AssertionError("vectors.txt: unknown line kind ${p[0]}")
            }
        }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun unescape(s: String): String {
        val o = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                o.append(
                    when (s[i + 1]) {
                        'n' -> '\n'
                        't' -> '\t'
                        else -> s[i + 1]
                    },
                )
                i += 2
            } else o.append(s[i++])
        }
        return o.toString()
    }

    class Run(val frames: List<Frame>, val lines: List<Pair<String, String>>, val act: String)

    private fun header(i: Int, b: ByteArray, at: Int): List<Pair<String, String>> {
        val len = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
        val type = b[at + 2].toInt() and 0xFF
        val flags = b[at + 3].toInt() and 0xFF
        val seq = (b[at + 4].toInt() and 0xFF) or ((b[at + 5].toInt() and 0xFF) shl 8)
        return listOf(
            "$i.len" to len.toString(),
            "$i.type" to (Msg.info[type]?.name ?: "0x%02x".format(type)),
            "$i.flags" to "0x%02x".format(flags),
            "$i.seq" to seq.toString(),
        )
    }

    private fun run(v: Vec): Run {
        val reader = FrameReader()
        val frames = ArrayList<Frame>()
        val lines = ArrayList<Pair<String, String>>()
        val acts = ArrayList<String>()
        var at = 0
        var start = 0
        try {
            for (n in v.reads ?: listOf(v.bytes.size)) {
                for (f in reader.feed(v.bytes, at, n)) {
                    val i = frames.size
                    frames += f
                    lines += header(i, v.bytes, start)
                    start += Proto.headerBytes + f.payload.size
                    when (val c = Inbound.classify(f)) {
                        is Inbound.Accept -> {
                            lines += c.body.print("$i.body.")
                            acts += "accept"
                        }
                        is Inbound.Skip -> acts += "skip"
                        is Inbound.Reply -> {
                            if (Msg.info.containsKey(f.type)) lines += Node.decode(Inbound.scope(f), f.payload).print("$i.body.")
                            acts += "reply"
                        }
                        is Inbound.Close -> {
                            if (c.reason == "badMagic") lines += Node.decode(Inbound.scope(f), f.payload).print("$i.body.")
                            lines += "$i.close" to c.reason
                            acts += "close"
                        }
                    }
                }
                at += n
            }
            assertEquals("${v.name}: consumed", v.bytes.size, at)
        } catch (e: ProtoException) {
            lines += header(frames.size, v.bytes, start)
            lines += "${frames.size}.close" to e.reason
            acts += "close"
        }
        return Run(frames, lines, acts.distinct().single())
    }

    @Test
    fun everyVectorDecodesAsWritten() {
        assertTrue(vecs.isNotEmpty())
        for (v in vecs.values) {
            val r = run(v)
            assertEquals("${v.name}: dec lines", v.dec, r.lines)
            assertEquals("${v.name}: act", v.act, r.act)
        }
    }

    @Test
    fun repliesMatchTheirRequests() {
        for (v in vecs.values) {
            val req = v.re?.let { vecs.getValue(it) } ?: continue
            val a = run(v).frames.single()
            val b = run(req).frames.single()
            assertEquals(v.name, b.type, a.type)
            assertEquals(v.name, b.seq, a.seq)
            assertTrue(v.name, a.isReply)
            assertTrue(v.name, b.wantsReply)
            if (req.act == "reply") {
                val c = Inbound.classify(b) as Inbound.Reply
                assertTrue(v.name, a.isError)
                assertEquals(v.name, ErrorReply.from(Node.decode("error", a.payload)).code, c.code)
            }
        }
    }

    @Test
    fun acceptedFramesReEncodeByteEqual() {
        for (v in vecs.values) {
            if (v.act != "accept") continue
            val r = run(v)
            var at = 0
            for (f in r.frames) {
                val body = Node.decode(Inbound.scope(f), f.payload)
                val known = Tlv.parse(f.payload).filter { t -> body.entries.any { it.tag == t.tag && it.field != null } }
                val want = tlv { known.forEach { raw(it.tag, it.value) } }
                assertArrayEquals("${v.name}: node re encode", want, body.encode())
                assertArrayEquals("${v.name}: typed re encode", want, typed(f, body) ?: want)
                val whole = Frame(f.type, f.flags, f.seq, f.payload).encode()
                assertArrayEquals("${v.name}: frame re encode", v.bytes.copyOfRange(at, at + whole.size), whole)
                at += whole.size
            }
        }
    }

    private fun typed(f: Frame, n: Node): ByteArray? {
        if (f.isError) return ErrorReply.from(n).encode()
        if (f.isReply) return when (f.type) {
            Msg.HELLO -> HelloReply.from(n).encode()
            Msg.SET -> SetReply.from(n).encode()
            Msg.IMG_BEGIN -> ImgBeginReply.from(n).encode()
            Msg.IMG_LIST -> ImgListReply.from(n).encode()
            Msg.ART_GET -> ArtGetReply.from(n).encode()
            Msg.UPD_BEGIN -> UpdBeginReply.from(n).encode()
            Msg.EQ_PRESET -> EqPresetReply.from(n).encode()
            else -> null
        }
        return when (f.type) {
            Msg.HELLO -> Hello.from(n).encode()
            Msg.BYE -> Bye.from(n).encode()
            Msg.STATE -> State.from(n).encode()
            Msg.SCHEMA -> Schema.from(n).encode()
            Msg.SETTINGS -> Settings.from(n).encode()
            Msg.EQ -> Eq.from(n).encode()
            Msg.QUEUE -> Queue.from(n).encode()
            Msg.LIB -> Lib.from(n).encode()
            Msg.IMG_ABORT -> ImgAbort.from(n).encode()
            Msg.UPD_ABORT -> UpdAbort.from(n).encode()
            else -> null
        }
    }

    @Test
    fun everyMessageHasAVector() {
        val seen = vecs.values.flatMap { v -> run(v).frames.map { it.type } }.toSet()
        for ((id, info) in Msg.info) assertTrue("no vector for ${info.name}", id in seen)
    }

    @Test
    fun everyAcceptedFrameSplitsByteByByte() {
        for (v in vecs.values) {
            if (v.act != "accept") continue
            val whole = run(v).frames
            val r = FrameReader()
            val split = v.bytes.indices.flatMap { r.feed(v.bytes, it, 1) }
            assertEquals(v.name, whole.size, split.size)
            for (i in whole.indices) assertArrayEquals(v.name, whole[i].encode(), split[i].encode())
            assertEquals(v.name, 0, r.pending)
        }
    }
}
