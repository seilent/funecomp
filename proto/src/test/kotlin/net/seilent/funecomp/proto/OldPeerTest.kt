package net.seilent.funecomp.proto

import net.seilent.funecomp.proto.gen.Base
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Feature
import net.seilent.funecomp.proto.gen.Field
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.proto.gen.Tags
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
    private val v12 = table("/proto-1.2.txt")
    private val v13 = table("/proto-1.3.txt")
    private val v14 = table("/proto-1.4.txt")
    private val v15 = table("/proto-1.5.txt")
    private val v16 = table("/proto-1.6.txt")
    private val v17 = table("/proto-1.7.txt")
    private val v18 = table("/proto-1.8.txt")
    private val v19 = table("/proto-1.9.txt")
    private val v1_10 = table("/proto-1.10.txt")
    private val v1_11 = table("/proto-1.11.txt")
    private val v1_12 = table("/proto-1.12.txt")
    private val v1_13 = table("/proto-1.13.txt")
    private val v1_14 = table("/proto-1.14.txt")
    private val v1_15 = table("/proto-1.15-ebc0751.txt")
    private val last15 = table("/proto-1.15.txt")

    private fun text(name: String) = OldPeerTest::class.java.getResourceAsStream(name)!!.bufferedReader().readText()

    private fun enumValues(name: String, enum: String): Set<Int> =
        text(name).lines().map { it.trim().split(Regex("\\s+")) }.filter { it[0] == "enum" && it[1] == enum }.map { it[2].toInt() }.toSet()

    private fun table(name: String): Map<String, Map<Int, Field>> = run {
        val text = text(name)
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

    private fun frames(vec: String): List<Frame> {
        val path = System.getProperty("funecomp.vectors") ?: "../third_party/funeos/vectors.txt"
        val hex = File(path).readLines().filter { it.startsWith("hex $vec ") }.joinToString("") { it.substringAfter("hex $vec ") }
        val b = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return FrameReader().feed(b, 0, b.size)
    }

    private fun bodies(vec: String): List<ByteArray> = frames(vec).map { it.payload }

    private fun body(vec: String): ByteArray = bodies(vec).single()

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
    fun protocol13FramesDecodeWithThe12TablesToEmptyFields() {
        val st = State.from(Node.decode("STATE", body("state_fav"), v12))
        assertEquals(101L, st.track)
        assertNull(st.favorite)
        assertNull(st.snapRev)
        val now = State.from(Node.decode("STATE", body("state_fav")))
        assertEquals(true, now.favorite)
        assertEquals(4L, now.snapRev)

        assertNull(HelloReply.from(Node.decode("HELLO.reply", body("hello_reply"), v12)).snapRev)
        assertEquals(4L, HelloReply.from(Node.decode("HELLO.reply", body("hello_reply"))).snapRev)
        assertNull(Lib.from(Node.decode("LIB", body("lib_page"), v12)).snapRev)
        assertEquals(4L, Lib.from(Node.decode("LIB", body("lib_page"))).snapRev)
    }

    @Test
    fun protocol14FramesDecodeWithThe13TablesToEmptyFields() {
        val st = State.from(Node.decode("STATE", body("state_lyrics_plain"), v13))
        assertEquals(false, st.lyrics)
        assertNull(st.lyricsPlain)
        val now = State.from(Node.decode("STATE", body("state_lyrics_plain")))
        assertEquals(false, now.lyrics)
        assertEquals(true, now.lyricsPlain)

        val q = Queue.from(Node.decode("QUEUE", body("queue_lyrics_plain"), v13))
        assertEquals(listOf(false, true), q.items.map { it.lyrics })
        assertTrue(q.items.all { it.lyricsPlain == null })
        val q14 = Queue.from(Node.decode("QUEUE", body("queue_lyrics_plain")))
        assertEquals(listOf(false to true, true to false), q14.items.map { it.lyrics to it.lyricsPlain })
    }

    @Test
    fun protocol15FramesDecodeWithThe14TablesToEmptyFields() {
        val old = Eq.from(Node.decode("EQ", body("eq_presets"), v14))
        assertEquals(listOf(EqName("off", "OFF"), EqName("/data/funeos/eq/HD 650.txt", "HD 650")), old.names)
        val now = Eq.from(Node.decode("EQ", body("eq_presets")))
        assertEquals(listOf(EqName("off", "OFF", null), EqName("/data/funeos/eq/HD 650.txt", "HD 650", true)), now.names)
        assertNull(v14["EQ_PRESET"])

        assertEquals("/data/funeos/eq/HD 650.txt", EqPresetReply.from(Node.decode("EQ_PRESET.reply", body("eq_preset_save_reply"))).key)
        assertNull(EqPresetReply.from(Node.decode("EQ_PRESET.reply", body("eq_preset_remove_reply"))).key)
    }

    @Test
    fun protocol16FramesDecodeWithThe15TablesToEmptyFields() {
        assertNull(v15["ART_BLOCK"])
        assertEquals(listOf(8, 9, 10), Node.decode("IMG_BEGIN", body("img_begin_meta"), v15).unknownTags)
        assertEquals(listOf(5), Node.decode("HELLO", body("hello_feat"), v15).unknownTags)
        assertNull(Hello.from(Node.decode("HELLO", body("hello_feat"), v15)).features)
        assertEquals(1024L, Hello.from(Node.decode("HELLO", body("hello_feat"))).features)
        assertFalse(Groups.allowed(Msg.ART_BLOCK, Groups.all and (1L shl Feature.artBlock).inv()))
        assertTrue(Groups.allowed(Msg.ART_BLOCK, Groups.mask("artBlock")))
        assertEquals(10, Feature.artBlock)
    }

    @Test
    fun protocol17FramesDecodeWithThe16TablesToEmptyFields() {
        assertNull(v16["LYR_GET"])
        assertNull(v16["LYR_GET.reply"])
        assertEquals(listOf(34), Node.decode("STATE", body("state_lyrics_crc"), v16).unknownTags)
        assertNull(State.from(Node.decode("STATE", body("state_lyrics_crc"), v16)).lyricsCrc)
        assertEquals(3205543910L, State.from(Node.decode("STATE", body("state_lyrics_crc"))).lyricsCrc)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 6), Node.decode("LYR_GET.reply", body("lyr_get_synced"), v16).unknownTags)
        assertFalse(Groups.allowed(Msg.LYR_GET, Groups.all and (1L shl Feature.lyricsGet).inv()))
        assertTrue(Groups.allowed(Msg.LYR_GET, Groups.mask("lyricsGet")))
        assertEquals(11, Feature.lyricsGet)
    }

    @Test
    fun protocol18FramesDecodeWithThe17TablesToEmptyFields() {
        assertNull(v17["ART_PICK"])
        assertNull(v17["ART_PICK.reply"])
        assertEquals(listOf(1, 2), Node.decode("ART_PICK", body("art_pick"), v17).unknownTags)
        assertEquals(listOf(1), Node.decode("ART_PICK.reply", body("art_pick_reply_shown"), v17).unknownTags)
        assertFalse(Groups.allowed(Msg.ART_PICK, Groups.all and (1L shl Feature.artPick).inv()))
        assertTrue(Groups.allowed(Msg.ART_PICK, Groups.mask("artPick")))
        assertEquals(12, Feature.artPick)
    }

    @Test
    fun protocol19FramesDecodeWithThe18TablesToEmptyFields() {
        assertNull(v18["IMG_TAG"])
        assertEquals(listOf(11), Node.decode("IMG_BEGIN", body("img_begin_origin"), v18).unknownTags)
        assertEquals(listOf(1, 2), Node.decode("IMG_TAG", body("img_tag"), v18).unknownTags)
        val old = ImgListReply.from(Node.decode("IMG_LIST.reply", body("img_list_origin"), v18))
        assertEquals(listOf("Utada Hikaru", "Gemma", "Hal"), old.entries.map { it.name })
        assertTrue(old.entries.all { it.origins.isEmpty() })
        assertEquals(listOf(listOf(3), listOf(3, 3), emptyList()), Node.decode("IMG_LIST.reply", body("img_list_origin"), v18).nodes(Tags.ImgListReply.entry).map { it.unknownTags })
        assertFalse(Groups.allowed(Msg.IMG_TAG, Groups.all and (1L shl Feature.imgOrigin).inv()))
        assertTrue(Groups.allowed(Msg.IMG_TAG, Groups.mask("imgOrigin")))
        assertEquals(13, Feature.imgOrigin)
    }

    @Test
    fun protocol110FramesDecodeWithThe19TablesToEmptyFields() {
        assertEquals(listOf(35), Node.decode("STATE", body("state_clock"), v19).unknownTags)
        val old = State.from(Node.decode("STATE", body("state_clock"), v19))
        assertNull(old.positionHeld)
        assertEquals(4000100L, old.sampleMs)
        assertEquals(4000103L, old.sentMs)
        val now = State.from(Node.decode("STATE", body("state_clock")))
        assertEquals(true, now.positionHeld)
        assertEquals(20000L, now.positionMs)
        assertEquals(4000103L, now.sentMs)
        assertEquals(14, Feature.stateClock)
        assertEquals(32767L, Groups.all and 32767L)
    }

    @Test
    fun protocol111FramesDecodeWithThe110TablesToEmptyFields() {
        val old = Lib.from(Node.decode("LIB", body("lib_album_artist"), v1_10))
        assertEquals(listOf(listOf(6), listOf(6)), Node.decode("LIB", body("lib_album_artist"), v1_10).nodes(Tags.Lib.row).map { it.unknownTags })
        assertTrue(old.rows.all { it.artist == null })
        val now = Lib.from(Node.decode("LIB", body("lib_album_artist")))
        assertEquals(listOf("Utada Hikaru", "various artists"), now.rows.map { it.artist })
        assertEquals(now.copy(rows = now.rows.map { it.copy(artist = null) }), old)
    }

    @Test
    fun protocol112FramesDecodeWithThe111TablesToEmptyFields() {
        assertEquals(listOf(17, 17, 17, 18, 18, 18, 18, 18, 18, 18, 18), Node.decode("HELLO.reply", body("hello_reply_slots"), v1_11).unknownTags)
        val hello = HelloReply.from(Node.decode("HELLO.reply", body("hello_reply_slots")))
        assertEquals(listOf(EqSlot("headphones", "HEADPHONES"), EqSlot("bluetooth", "BLUETOOTH"), EqSlot("line", "LINE OUT")), hello.eqSlots)
        assertEquals((0..7).toList(), hello.bandTypes)
        assertEquals(hello.copy(eqSlots = emptyList(), bandTypes = emptyList()), HelloReply.from(Node.decode("HELLO.reply", body("hello_reply_slots"), v1_11)))

        val pages = bodies("eq_graphic")
        assertEquals(2, pages.size)
        for (b in pages) {
            val now = Eq.from(Node.decode("EQ", b))
            assertEquals(Enums.EqMode.graphic, now.mode)
            assertEquals("custom", now.graphicKey)
            assertEquals(10, now.gains!!.bands.size)
            val bare = now.copy(mode = null, graphicKey = null, graphicNames = emptyList(), gains = null, graphicOffset = null, graphicTotal = null)
            assertEquals(bare, Eq.from(Node.decode("EQ", b, v1_11)))
        }
        val last = Eq.from(Node.decode("EQ", pages[1]))
        assertEquals(EqName("/data/funeos/eq/graphic/Rock.ini", "Rock", true), last.graphicNames[2])
        assertEquals(0L to 4L, last.graphicOffset to last.graphicTotal)

        val slot = Eq.from(Node.decode("EQ", body("eq_slot")))
        assertNull(slot.output)
        assertEquals("line", slot.slot)
        assertEquals(listOf(8), Node.decode("EQ", body("eq_slot"), v1_11).unknownTags)

        assertEquals(listOf(36), Node.decode("STATE", body("state_eq_slot"), v1_11).unknownTags)
        assertEquals("bluetooth", State.from(Node.decode("STATE", body("state_eq_slot"))).eqSlot)
        assertNull(State.from(Node.decode("STATE", body("state_eq_slot"), v1_11)).eqSlot)

        val speaker = State.from(Node.decode("STATE", body("state_output_speaker"), v1_11))
        assertEquals(Enums.Output.speaker, speaker.output)
        assertFalse(Enums.Output.speaker in enumValues("/proto-1.11.txt", "OUTPUT"))
        assertFalse(Enums.Output.usbDac in enumValues("/proto-1.11.txt", "OUTPUT"))
        for (f in listOf(Feature.eqSlots, Feature.eqGraphic, Feature.eqPass, Feature.outputKinds, Feature.rowConfirm)) assertEquals(0L, Groups.all and (1L shl f))
    }

    @Test
    fun protocol113FramesDecodeWithThe112TablesToEmptyFields() {
        val lib = Lib.from(Node.decode("LIB", body("lib_search"), v1_12))
        assertEquals(Enums.View.searchSongs, lib.view)
        assertFalse(Enums.View.searchSongs in enumValues("/proto-1.12.txt", "VIEW"))
        assertEquals("Bohemian Rhapsody", lib.rows.single().name)
        assertEquals(20, Feature.search)
        assertEquals(0L, Groups.all and (1L shl Feature.search))
    }

    @Test
    fun protocol114FramesDecodeWithThe113TablesToEmptyFields() {
        val b = State(track = 101, card = Enums.Card.ready).encode()
        assertEquals(listOf(37), Node.decode("STATE", b, v1_13).unknownTags)
        assertNull(State.from(Node.decode("STATE", b, v1_13)).card)
        assertEquals(Enums.Card.ready, State.from(Node.decode("STATE", b)).card)
    }

    @Test
    fun updateSignatureFramesDecodeWithThe114TableToEmptyFields() {
        assertEquals(listOf(4), Node.decode("UPD_BEGIN", body("upd_begin_sig"), v1_14).unknownTags)
        assertEquals("updSig", Feature.names[27])
        assertEquals(15, Err.badSignature)
    }

    @Test
    fun imageSourceFramesDecodeWithTheEbc0751TableToEmptyFields() {
        assertEquals(listOf(14), Node.decode("IMG_BEGIN", body("img_begin_full"), v1_15).unknownTags)
        assertEquals(listOf(14), Node.decode("IMG_BEGIN", body("img_begin_full"), v1_14).unknownTags)
        assertEquals(true, Node.decode("IMG_BEGIN", body("img_begin_full")).bool(Tags.ImgBegin.full))

        val old = Node.decode("IMG_LIST.reply", body("img_list_reply_src"), v1_15)
        assertEquals(listOf(7), old.unknownTags)
        assertEquals(listOf(listOf(4, 5, 6), listOf(4, 5, 6, 7), listOf(4, 5)), old.nodes(Tags.ImgListReply.entry).map { it.unknownTags })
        val o = ImgListReply.from(old)
        assertNull(o.srcRoom)
        assertEquals(listOf("Utada Hikaru", "Gemma", "Hal"), o.entries.map { it.name })
        assertTrue(o.entries.all { it.full == null && it.shownS == null && it.crc == null && it.srcCrc == null })

        val r = ImgListReply.from(Node.decode("IMG_LIST.reply", body("img_list_reply_src")))
        assertEquals(6442450944L, r.srcRoom)
        assertEquals(listOf(true, false, false), r.entries.map { it.full })
        assertEquals(listOf(1780165693L, 195948557L, null), r.entries.map { it.crc })
        assertEquals(listOf(null, 305441741L, null), r.entries.map { it.srcCrc })
        assertEquals(listOf(1792790400L, 1792700000L, 0L), r.entries.map { it.shownS })

        assertFalse("feature imgSource 28" in text("/proto-1.15-ebc0751.txt"))
        assertEquals("imgSource", Feature.names[28])
    }

    @Test
    fun liveRowsFramesWithTheEbc0751Table() {
        val live = frames("live").single()
        assertNull(v1_15["LIVE"])
        assertEquals(0, live.flags and (Proto.flagWant or Proto.flagReply))
        val page = Node.decode("SCHEMA", body("schema_page_live"), v1_15)
        assertTrue(page.unknownTags.isEmpty())
        assertTrue(page.nodes(Tags.Schema.row).all { it.unknownTags.isEmpty() })
        val s = Schema.from(page)
        assertEquals(listOf("dateTime", "about"), s.rows.map { it.key })
        assertTrue(s.rows.all { it.words.isEmpty() })
        assertFalse("feature liveRows 30" in text("/proto-1.15-ebc0751.txt"))
        assertEquals("liveRows", Feature.names[30])
    }

    @Test
    fun protocol115FramesDecodeWithThe114TablesToEmptyFields() {
        assertEquals(listOf(7), Node.decode("LYR_GET.reply", body("lyr_get_offset"), v1_14).unknownTags)
        assertNull(LyrGetReply.from(Node.decode("LYR_GET.reply", body("lyr_get_offset"), v1_14)).offsetMs)
        assertEquals(-1500, LyrGetReply.from(Node.decode("LYR_GET.reply", body("lyr_get_offset"))).offsetMs)

        assertEquals(listOf(38), Node.decode("STATE", body("state_lyrics_offset"), v1_14).unknownTags)
        val crc = State.from(Node.decode("STATE", body("state_lyrics_crc")))
        assertEquals(crc, State.from(Node.decode("STATE", body("state_lyrics_offset"), v1_14)))
        assertEquals(crc.copy(lyricsOffsetMs = -1500), State.from(Node.decode("STATE", body("state_lyrics_offset"))))

        for (v in listOf("lrc_put_reply_flags", "lrc_del_reply_flags")) {
            val scope = if (v.startsWith("lrc_put")) "LRC_PUT.reply" else "LRC_DEL.reply"
            assertEquals(listOf(1, 2), Node.decode(scope, body(v), v1_14).unknownTags)
        }
        assertEquals(LrcReply(true, false), LrcReply.from(Node.decode("LRC_PUT.reply", body("lrc_put_reply_flags"))))
        assertEquals(LrcReply(false, false), LrcReply.from(Node.decode("LRC_DEL.reply", body("lrc_del_reply_flags"))))

        for (scope in listOf("LYR_OFFSET", "LYR_OFFSET.reply", "PLAYLOG_GET", "PLAYLOG_GET.reply", "PLAYLOG_ACK")) assertNull(scope, v1_14[scope])
        assertEquals(listOf(1, 2), Node.decode("PLAYLOG_GET", body("playlog_get"), v1_14).unknownTags)
        assertEquals(listOf(1, 2, 3, 4, 4, 5), Node.decode("PLAYLOG_GET.reply", body("playlog_get_reply"), v1_14).unknownTags)
        assertEquals(listOf(1, 2), Node.decode("PLAYLOG_ACK", body("playlog_ack"), v1_14).unknownTags)
        assertTrue(body("playlog_ack_reply").isEmpty())
        assertEquals(listOf(40), Node.decode("STATE", body("state_playlog_seq"), v1_14).unknownTags)
        assertNull(State.from(Node.decode("STATE", body("state_playlog_seq"), v1_14)).playLogSeq)
        assertEquals(43L, State.from(Node.decode("STATE", body("state_playlog_seq"))).playLogSeq)

        val genres = Node.decode("LIB_GET", body("lib_get_genres"), v1_14)
        assertEquals(Enums.View.genres, genres.int(Tags.LibGet.view))
        assertFalse(Enums.View.genres in enumValues("/proto-1.14.txt", "VIEW"))
        val add = Node.decode("QUEUE_OP", body("queue_op_add_view"), v1_14)
        assertEquals(Enums.QueueCmd.add, add.int(Tags.QueueOp.cmd))
        assertFalse(Enums.QueueCmd.add in enumValues("/proto-1.14.txt", "QUEUE_CMD"))
        assertEquals(listOf(8, 9, 10, 11), add.unknownTags)
        assertEquals(listOf(1, 2), Node.decode("QUEUE_OP.reply", body("queue_op_add_view_reply"), v1_14).unknownTags)
        assertEquals(QueueOpReply(12, 9), QueueOpReply.from(Node.decode("QUEUE_OP.reply", body("queue_op_add_view_reply"))))

        val log = PlayLogReply.from(Node.decode("PLAYLOG_GET.reply", body("playlog_get_reply")))
        assertEquals(6491872793561715441L, log.log)
        assertEquals(43L to 42L, log.newest to log.first)
        assertEquals(false, log.more)
        assertEquals(
            PlayEntry(42, null, 120000, 241000, 301, "Bohemian Rhapsody", "Queen", listOf("Queen"), listOf("0383dadf-2a4e-4d10-a46a-e9e041da8eb3"), "A Night at the Opera", trackNo = 11),
            log.entries[0],
        )
        assertEquals(
            PlayEntry(43, 1792656000, 240000, 512000, 302, "Innuendo", "Queen", listOf("Queen"), emptyList(), "Innuendo", "Queen", 1, 32400, true),
            log.entries[1],
        )
        assertArrayEquals(body("playlog_get_reply"), log.encode())

        for (f in listOf(Feature.lyricsOffset, Feature.playLog, Feature.genres, Feature.queueAdd)) assertEquals(1L shl f, Groups.all and (1L shl f))
        for (f in 15..20) assertEquals(0L, Groups.all and (1L shl f))
        assertEquals(Groups.members.keys.filter { it !in Groups.unserved }.fold(0L) { m, g -> m or (1L shl Groups.bit(g)!!) }, Groups.all)
        assertTrue(Groups.allowed(Msg.LYR_OFFSET, Groups.all))
    }

    @Test
    fun protocol116FramesDecodeWithThe115TableToEmptyFields() {
        for (scope in listOf("SVC_LOGIN", "SVC_LOGIN_END", "SVC_PKCE", "SVC_PKCE.reply", "SVC_KEYS", "SvcKey", "SvcInfo")) assertNull(scope, last15[scope])
        for (m in listOf("0x43", "0x44", "0x45", "0x46")) assertFalse(m, Regex("^msg \\S+ $m ", RegexOption.MULTILINE).containsMatchIn(text("/proto-1.15.txt")))
        assertFalse("feature svcLogin 32" in text("/proto-1.15.txt"))

        val oldState = Node.decode("STATE", body("state_service"), last15)
        assertEquals(listOf(51, 52), oldState.unknownTags)
        val os = State.from(oldState)
        assertNull(os.track)
        assertNull(os.service)
        assertNull(os.library)
        val st = State.from(Node.decode("STATE", body("state_service")))
        assertEquals("tidal" to 167L, st.service to st.serviceKey)
        assertNull(st.track)
        assertNull(st.library)

        val oldQueue = Node.decode("QUEUE", body("queue_service"), last15)
        assertEquals(listOf(listOf(10, 11)), oldQueue.nodes(Tags.Queue.item).map { it.unknownTags })
        val oq = Queue.from(oldQueue).items.single()
        assertNull(oq.track)
        assertNull(oq.library)
        assertEquals("Song", oq.title)
        val q = Queue.from(Node.decode("QUEUE", body("queue_service"))).items.single()
        assertEquals(QueueItem(null, "Song", listOf("Band"), durationMs = 200000, service = "tidal", serviceKey = 167), q)
        assertNull(q.library)

        val oldLog = Node.decode("PLAYLOG_GET.reply", body("playlog_entry_service"), last15)
        assertEquals(listOf(listOf(16, 17)), oldLog.nodes(Tags.PlaylogGetReply.entry).map { it.unknownTags })
        val e = PlayLogReply.from(Node.decode("PLAYLOG_GET.reply", body("playlog_entry_service"))).entries.single()
        assertEquals(PlayEntry(5, null, 180000, 200000, null, "Song", "Band", service = "tidal", serviceKey = 167), e)
        assertNull(e.library)

        val oldHello = Node.decode("HELLO.reply", body("hello_reply_services"), last15)
        assertEquals(listOf(22, 22), oldHello.unknownTags)
        assertTrue(HelloReply.from(oldHello).services.isEmpty())
        assertEquals(
            listOf(SvcInfo("svcdemo", "SVCDEMO", listOf("client_id", "client_secret"), true), SvcInfo("nightradio", "Night Radio", pkce = false)),
            HelloReply.from(Node.decode("HELLO.reply", body("hello_reply_services"))).services,
        )

        assertEquals(16, Err.off)
        assertEquals(13, Err.cancelled)
        assertEquals("svcLogin", Feature.names[32])
        assertEquals(listOf("done", "expired", "cancelled"), (0..2).map { Enums.SvcLoginResult.names.getValue(it) })
        assertEquals(0L, Groups.all and (1L shl Feature.svcLogin))
        for (m in listOf(Msg.SVC_LOGIN, Msg.SVC_LOGIN_END, Msg.SVC_PKCE, Msg.SVC_KEYS)) {
            assertEquals("svcLogin", Groups.group(m))
            assertFalse(Groups.allowed(m, Groups.all))
            assertTrue(Groups.allowed(m, Groups.mask("svcLogin")))
        }
    }

    @Test
    fun aTrackOfZeroOrBesideAServiceIsNoLibraryId() {
        assertNull(State(track = 0).library)
        assertNull(State(track = 9, service = "tidal", serviceKey = 167).library)
        assertEquals(9L, State(track = 9).library)
        assertNull(QueueItem(0, "Song").library)
        assertNull(QueueItem(9, "Song", service = "tidal").library)
        assertEquals(9L, QueueItem(9, "Song").library)
        assertNull(PlayEntry(1, track = 0).library)
        assertNull(PlayEntry(1, track = 9, service = "tidal").library)
        assertEquals(9L, PlayEntry(1, track = 9).library)
    }

    @Test
    fun protocol116BuildersMatchTheVectors() {
        assertArrayEquals(body("svc_keys"), Req.svcKeys("tidal", listOf(SvcKey("client_id", "abc123"), SvcKey("client_secret", "s3cr3t"))))
        assertArrayEquals(body("svc_pkce_reply"), SvcPkceReply("https://svcdemo.test/auth?code=c0de&state=ab12").encode())
        assertEquals(
            SvcPkce("tidal", "https://login.svcdemo.test/authorize?state=ab12", "login.svcdemo.test", "https://svcdemo.test/auth", 7),
            SvcPkce.from(Node.decode("SVC_PKCE", body("svc_pkce"))),
        )
        assertEquals(SvcLogin("tidal", "https://link.svcdemo.test/ABCDE", "link.svcdemo.test", "ABCDE", 300, 7), SvcLogin.from(Node.decode("SVC_LOGIN", body("svc_login"))))
        assertEquals(SvcLoginEnd("tidal", 7, Enums.SvcLoginResult.done), SvcLoginEnd.from(Node.decode("SVC_LOGIN_END", body("svc_login_end"))))
        assertEquals(Err.off, ErrorReply.from(Node.decode("error", body("svc_keys_off"))).code)
        assertEquals(Err.badValue, ErrorReply.from(Node.decode("error", body("svc_keys_bad"))).code)
        assertEquals(Err.cancelled, ErrorReply.from(Node.decode("error", body("svc_pkce_cancelled"))).code)
        assertTrue(body("svc_keys_reply").isEmpty())
        assertFalse("abc123" in SvcKey("client_id", "abc123").toString())
        assertEquals(90000, Proto.svcReplyMs)
        assertEquals(600000, Proto.svcPkceMs)
    }

    @Test
    fun protocol115BuildersMatchTheVectors() {
        assertArrayEquals(body("playlog_get"), Req.playLogGet(41, 50))
        assertArrayEquals(body("playlog_ack"), Req.playLogAck(6491872793561715441, 43))
        assertArrayEquals(body("lib_get_genres"), Req.libGet(Enums.View.genres, null, 0, 100, 7))
        assertArrayEquals(body("queue_op_add_view"), Req.queueOp(Enums.QueueCmd.add, view = Enums.View.genres, parent = "Rock", libRev = 7, snapRev = 4))
        assertArrayEquals(body("lyr_offset_read"), Req.lyrOffset(103))
        assertArrayEquals(body("lyr_offset_set"), Req.lyrOffset(103, -1500))
        assertArrayEquals(body("lyr_offset_bad"), Req.lyrOffset(103, 60001))
        assertArrayEquals(body("lib_get_search"), Req.libGet(Enums.View.searchSongs, "queen bohemian", 0, 100, 7))
        assertArrayEquals(body("play_search"), Req.play(Enums.View.searchArtists, "queen", 7, start = 0))
        assertEquals(LyrOffsetReply(0), LyrOffsetReply.from(Node.decode("LYR_OFFSET.reply", body("lyr_offset_read_reply"))))
        assertEquals(LyrOffsetReply(-1500), LyrOffsetReply.from(Node.decode("LYR_OFFSET.reply", body("lyr_offset_set_reply"))))
        assertTrue(LyrOffsetReply(0).encode().isEmpty())
        assertArrayEquals(body("lyr_offset_set_reply"), LyrOffsetReply(-1500).encode())
        assertEquals(Err.badValue, ErrorReply.from(Node.decode("error", body("lyr_offset_bad_error"))).code)
        assertEquals(60000, Proto.lyrOffsetMaxMs)
    }

    @Test
    fun imgOriginBuildersMatchTheVectors() {
        val mbid = "a1b2c3d4-0000-4000-8000-000000000001"
        assertArrayEquals(body("img_begin_origin"), Req.imgBegin(Enums.ImgKind.artist, "Utada Hikaru", mbid, 24, 2438839214, 384, 480, true, "Wikimedia Commons", "Jane Doe", Enums.ImgOrigin.phone))
        assertArrayEquals(body("img_begin_origin_library"), Req.imgBegin(Enums.ImgKind.artist, "Utada Hikaru", null, 24, 2438839214, 384, 480, origin = Enums.ImgOrigin.library))
        assertArrayEquals(body("img_begin_meta"), Req.imgBegin(Enums.ImgKind.artist, "Utada Hikaru", mbid, 24, 2438839214, 384, 480, true, "Wikimedia Commons", "Jane Doe", null))
        assertArrayEquals(body("img_tag"), Req.imgTag("Utada Hikaru", Enums.ImgOrigin.library))
        assertEquals(10, ErrorReply.from(Node.decode("error", body("img_tag_error_notfound"))).code)
        assertEquals(2, ErrorReply.from(Node.decode("error", body("img_begin_origin_bad_error"))).code)
        val list = ImgListReply.from(Node.decode("IMG_LIST.reply", body("img_list_origin")))
        assertEquals(
            listOf(
                ImgEntry("Utada Hikaru", null, listOf(Enums.ImgOrigin.library)),
                ImgEntry("Gemma", null, listOf(Enums.ImgOrigin.library, Enums.ImgOrigin.phone)),
                ImgEntry("Hal", null),
            ),
            list.entries,
        )
        assertArrayEquals(body("img_list_origin"), list.encode())
        assertEquals(listOf(22, 19, 9), list.entries.map { it.size })
    }

    @Test
    fun artPickBuildersMatchTheVectors() {
        val mbid = "a1b2c3d4-0000-4000-8000-000000000001"
        assertEquals(ArtPick("Utada Hikaru", mbid), ArtPick.from(Node.decode("ART_PICK", body("art_pick"))))
        assertArrayEquals(body("art_pick"), ArtPick("Utada Hikaru", mbid).encode())
        assertEquals(ArtPick("TSUKI", null), ArtPick.from(Node.decode("ART_PICK", body("art_pick_bare"))))
        assertArrayEquals(body("art_pick_reply_shown"), ArtPickReply(true).encode())
        assertArrayEquals(body("art_pick_reply_posted"), ArtPickReply(false).encode())
        val e = ErrorReply.from(Node.decode("error", body("art_pick_error_refused")))
        assertEquals(4, e.code)
        assertEquals("open the phone app", e.text)
    }

    @Test
    fun lyrGetBuildersAndCrcMatchTheVectors() {
        assertArrayEquals(body("lyr_get"), Req.lyrGet(103, 0, null))
        assertArrayEquals(body("lyr_get_have_ask"), Req.lyrGet(103, 0, 3205543910))
        val synced = LyrGetReply.from(Node.decode("LYR_GET.reply", body("lyr_get_synced")))
        assertEquals(LyrGetReply(3205543910, Enums.LyricsKind.synced, 2, 0, false, listOf(LyrLine(0, "Light on the water"), LyrLine(2000, "Every road leads back home"))), synced)
        assertArrayEquals(body("lyr_get_synced"), synced.encode())
        assertEquals(3205543910L, lyricsCrc(Enums.LyricsKind.synced, synced.lines))
        val plain = LyrGetReply.from(Node.decode("LYR_GET.reply", body("lyr_get_plain")))
        assertEquals(listOf(LyrLine(null, "Light on the water"), LyrLine(null, ""), LyrLine(null, "Every road leads back home")), plain.lines)
        assertArrayEquals(body("lyr_get_plain"), plain.encode())
        assertEquals(576987232L, lyricsCrc(Enums.LyricsKind.plain, plain.lines))
        assertEquals(576987232L, lyricsCrc(Enums.LyricsKind.plain, plain.lines.map { it.copy(ms = 2000) }))
        val have = LyrGetReply.from(Node.decode("LYR_GET.reply", body("lyr_get_have")))
        assertEquals(LyrGetReply(3205543910, Enums.LyricsKind.synced, 2, 0, false, emptyList()), have)
        assertEquals(10, ErrorReply.from(Node.decode("error", body("lyr_get_error_notfound"))).code)
    }

    @Test
    fun artBlockBuildersMatchTheVectors() {
        val mbid = "a1b2c3d4-0000-4000-8000-000000000001"
        assertArrayEquals(body("hello_feat"), Hello(1, 6, "0.1.0", 1024).encode())
        assertArrayEquals(body("art_block"), ArtBlock(Enums.BlockCmd.blockImage, "Utada Hikaru", mbid, 2438839214).encode())
        assertEquals(ArtBlock(Enums.BlockCmd.blockImage, "Utada Hikaru", mbid, 2438839214), ArtBlock.from(Node.decode("ART_BLOCK", body("art_block"))))
        val meta = body("img_begin_meta")
        assertArrayEquals(meta, Req.imgBegin(Enums.ImgKind.artist, "Utada Hikaru", mbid, 24, 2438839214, 384, 480, true, "Wikimedia Commons", "Jane Doe"))
        val plain = Req.imgBegin(Enums.ImgKind.artist, "Utada Hikaru", mbid, 24, 2438839214, 384, 480)
        assertArrayEquals(meta.copyOf(plain.size), plain)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), Tlv.parse(plain).map { it.tag })
        val no = Req.imgBegin(Enums.ImgKind.artist, "Utada Hikaru", mbid, 24, 2438839214, 384, 480, false, null, null)
        assertArrayEquals(plain + byteArrayOf(8, 1, 0, 0), no)
    }

    @Test
    fun metaStringsAreCutAt256BytesOnACodePoint() {
        assertEquals(256, Req.cap("a".repeat(300))!!.toByteArray().size)
        assertEquals("a".repeat(255), Req.cap("a".repeat(255) + "あ"))
        assertEquals(255, Req.cap("あ".repeat(100))!!.toByteArray().size)
        assertEquals("a".repeat(256), Req.cap("a".repeat(256)))
        assertNull(Req.cap(null))
        val begin = Req.imgBegin(Enums.ImgKind.artist, "A", null, 1, 1, 2, 2, true, "あ".repeat(100), "b".repeat(300))
        val n = Node.decode("IMG_BEGIN", begin)
        assertEquals("あ".repeat(85), n.str(Tags.ImgBegin.source))
        assertEquals("b".repeat(256), n.str(Tags.ImgBegin.credit))
        assertNull(Node.decode("IMG_BEGIN", Req.imgBegin(Enums.ImgKind.artist, "A", null, 1, 1, 2, 2, false, null, null)).str(Tags.ImgBegin.source))
    }

    @Test
    fun eqPresetBuildersMatchTheVectors() {
        val ints = Profile(-40, listOf(Band(Enums.BandType.peak, 34, 1000000, -30, 141)))
        assertArrayEquals(body("eq_preset_save"), Req.eqPreset(Enums.EqPresetOp.save, Enums.EqOutput.wired, name = "HD 650", ints = ints, replace = true))
        assertArrayEquals(body("eq_preset_remove"), Req.eqPreset(Enums.EqPresetOp.remove, key = "/data/funeos/eq/Preset 1.txt"))
        assertArrayEquals(body("eq_preset_rename"), Req.eqPreset(Enums.EqPresetOp.rename, key = "/data/funeos/eq/Preset 1.txt", name = "Sennheiser"))
        val noReplace = Req.eqPreset(Enums.EqPresetOp.save, Enums.EqOutput.wired, name = "HD 650", ints = ints, replace = false)
        assertArrayEquals(body("eq_preset_save").copyOf(body("eq_preset_save").size - 1) + byteArrayOf(0), noReplace)
    }

    @Test
    fun eqPresetIsGatedByItsOwnBit() {
        assertFalse(Groups.allowed(Msg.EQ_PRESET, Groups.mask("eq")))
        assertTrue(Groups.allowed(Msg.EQ_PRESET, Groups.mask("eq", "eqPresets")))
        assertEquals(9, Feature.eqPresets)
        assertTrue(Groups.allowed(Msg.EQ_SET, Groups.mask("eq")))
    }

    @Test
    fun requestBuildersMatchTheVectors() {
        assertArrayEquals(body("art_get"), Req.artGet(101, 512))
        assertArrayEquals(body("art_get_have"), Req.artGet(101, 256, have = 2400070225))
        val text = "[00:12.00]First line\n[00:17.20]Second line\n".toByteArray()
        assertArrayEquals(body("lrc_put"), Req.lrcPut(101, 7, text, 2802745864))
        assertArrayEquals(body("lrc_put_snap"), Req.lrcPut(101, null, text, 2802745864, snapRev = 4))
        assertArrayEquals(body("lrc_put_plain"), Req.lrcPut(101, null, "First line\nSecond line\n".toByteArray(), 1200721228, snapRev = 4))
        assertArrayEquals(body("play_view_snap"), Req.play(Enums.View.albums, start = 0, shuffle = true, snapRev = 4))
        assertArrayEquals(body("lib_get_snap_stale"), Req.libGet(Enums.View.albums, null, 0, 100, null, snapRev = 3))
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
