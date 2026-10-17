package net.seilent.funecomp.proto

import net.seilent.funecomp.proto.gen.Base
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Feature
import net.seilent.funecomp.proto.gen.Field
import net.seilent.funecomp.proto.gen.Msg
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OldPeerTest {
    private val v10 = table("/proto-1.0.txt")
    private val v11 = table("/proto-1.1.txt")

    private fun table(name: String): Map<String, Map<Int, Field>> = run {
        val text = OldPeerTest::class.java.getResourceAsStream(name)!!.bufferedReader().readText()
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

    @Test
    fun protocol12FramesDecodeWithThe11TablesToEmptyFields() {
        val st = State.from(Node.decode("STATE", body("state_comp2"), v11))
        assertEquals(101L, st.track)
        assertEquals(152, st.volume)
        assertNull(st.charging)
        assertNull(st.cardFreeMiB)
        assertNull(st.volumePct)
        assertNull(st.maxVolumePct)
        assertNull(st.lyrics)
        val now = State.from(Node.decode("STATE", body("state_comp2")))
        assertEquals(true, now.charging)
        assertEquals(158234L, now.cardFreeMiB)
        assertEquals(233440L, now.cardTotalMiB)
        assertEquals(60, now.volumePct)
        assertEquals(5, now.volumeStepPct)
        assertEquals(true, now.lyrics)
        assertEquals(80, now.maxVolumePct)

        val q = Queue.from(Node.decode("QUEUE", body("queue_comp2"), v11))
        assertTrue(q.items.all { it.album == null && it.durationMs == null && it.lyrics == null })
        val q12 = Queue.from(Node.decode("QUEUE", body("queue_comp2")))
        assertEquals(QueueItem(101, "One", listOf("Utada Hikaru"), listOf(""), "Bad Mode", 261_000, true), q12.items[0])

        val d = Node.decode("DRAFT", body("draft_auto"), v11)
        assertEquals(listOf(7), d.unknownTags)
        val v = Node.decode("VOLUME", body("volume_percent"), v11)
        assertEquals(listOf(3), v.unknownTags)
        assertNull(v11["ART_GET"])
        assertNull(v11["UPD_BEGIN"])
    }

    @Test
    fun requestBuildersMatchTheVectors() {
        assertArrayEquals(body("art_get"), Req.artGet(101, 512))
        assertArrayEquals(body("art_get_have"), Req.artGet(101, 256, have = 2400070225))
        val text = "[00:12.00]First line\n[00:17.20]Second line\n".toByteArray()
        assertArrayEquals(body("lrc_put"), Req.lrcPut(101, 7, text, 2802745864))
        assertArrayEquals(body("lrc_del"), Req.lrcDel(101))
        assertArrayEquals(body("upd_begin"), Req.updBegin("v1", 28043264, "9e107d9d372bb6826bd81d3542a419d6"))
        assertArrayEquals(body("upd_end"), Req.updEnd("9e107d9d372bb6826bd81d3542a419d6"))
        assertArrayEquals(body("volume_percent"), Req.volume(percent = 35))
        assertArrayEquals(body("draft_auto"), Req.draft(Enums.DraftKind.clock, unixS = 1792224000, zoneS = 32400, automatic = true))
    }

    @Test
    fun every12MessageIsGroupedAndRefusedWithoutItsBit() {
        val added = listOf(Msg.ART_GET, Msg.LRC_PUT, Msg.LRC_DEL, Msg.UPD_BEGIN, Msg.UPD_DATA, Msg.UPD_END, Msg.UPD_ABORT)
        val groups = mapOf("art" to Feature.art, "lyrics" to Feature.lyrics, "update" to Feature.update)
        for (m in added) {
            val g = Groups.group(m)!!
            val bit = groups.getValue(g)
            assertFalse(Groups.allowed(m, Groups.all and (1L shl bit).inv()))
            assertTrue(Groups.allowed(m, 1L shl bit))
        }
        for ((id, _) in Msg.info) if (id !in Groups.core) assertTrue("ungrouped ${Msg.info.getValue(id).name}", Groups.group(id) != null)
        assertEquals(8, Feature.clockAuto)
    }
}
