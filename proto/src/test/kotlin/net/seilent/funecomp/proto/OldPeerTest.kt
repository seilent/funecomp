package net.seilent.funecomp.proto

import net.seilent.funecomp.proto.gen.Base
import net.seilent.funecomp.proto.gen.Field
import net.seilent.funecomp.proto.gen.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OldPeerTest {
    private val v10: Map<String, Map<Int, Field>> = run {
        val text = OldPeerTest::class.java.getResourceAsStream("/proto-1.0.txt")!!.bufferedReader().readText()
        val out = LinkedHashMap<String, MutableMap<Int, Field>>()
        for (line in text.lines()) {
            val p = line.trim().split(Regex("\\s+"))
            if (p[0] != "tag") continue
            val base = Base.entries.firstOrNull { it.name.lowercase() == p[4] }
            val f = when {
                base != null -> Field(p[2].toInt(), p[3], base, null, p[5] == "list")
                p[4] == p[4].uppercase() -> Field(p[2].toInt(), p[3], Base.ENUM, p[4], p[5] == "list")
                else -> Field(p[2].toInt(), p[3], Base.RECORD, p[4], p[5] == "list")
            }
            out.getOrPut(p[1]) { LinkedHashMap() }[f.tag] = f
        }
        out
    }

    private fun body(vec: String): ByteArray {
        val path = System.getProperty("funecomp.vectors") ?: "../third_party/funeos/vectors.txt"
        val hex = File(path).readLines().filter { it.startsWith("hex $vec ") }.joinToString("") { it.substringAfter("hex $vec ") }
        val b = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return FrameReader().feed(b, 0, b.size).single().payload
    }

    @Test
    fun protocol11FramesDecodeWithThe10TablesToEmptyFields() {
        val q = Queue.from(Node.decode("QUEUE", body("queue_artists"), v10))
        assertTrue(q.items.isNotEmpty())
        assertTrue(q.items.all { it.title != null && it.artists.isEmpty() && it.mbids.isEmpty() })
        assertTrue(Queue.from(Node.decode("QUEUE", body("queue_artists"))).items.any { it.artists.isNotEmpty() })

        val lib = Lib.from(Node.decode("LIB", body("lib_artists"), v10))
        assertTrue(lib.rows.isNotEmpty())
        assertTrue(lib.rows.all { it.name != null && it.mbid == null })

        val img = ImgListReply.from(Node.decode("IMG_LIST.reply", body("img_list_reply_bytes"), v10))
        assertNull(img.bytes)
        assertNull(img.cap)
        assertEquals(Msg.IMG_LIST, Msg.byName["IMG_LIST"])
    }
}
