package net.seilent.funecomp.artist

import net.seilent.funecomp.proto.gen.Enums
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BlockTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val utada = "b539e453-c4fe-47e3-8a07-8517eac74429"
    private val radiohead = "a74b1b7f-71a5-4011-9441-d0b5e4122711"

    private val http = Fixtures()
    private val time = FakeTime()
    private var sources = Sources.COMMONS
    private var online = true
    private val cache by lazy { ArtCache(tmp.newFolder("art")) }
    private val blocks by lazy { ArtBlocks(java.io.File(tmp.root, "artblock.tsv")) }

    private fun art() = ArtistArt(http, AwtCodec(), cache, { sources }, "0.1.0", { time.now }, time, { online }, blocks).also {
        http.now = { time.now }
    }

    private fun block(e: ArtCache.Entry) = blocks.add(ArtBlocks.Block(e.key, e.mbid, e.source, ImageIds.of(e), cache.sha(e), e.name, time.now++))

    private fun shown(name: String, mbid: String?) = art().cachedEntry(name, mbid)!!

    @Test
    fun aBlockGivesTheNextCandidateThenTheNextSourceThenAMiss() {
        sources = Sources.COMMONS.copy(theAudioDb = true, discogs = true)
        val seen = ArrayList<Pair<String, String>>()
        while (true) {
            val a = art().get("Radiohead", radiohead, 384, 480) ?: break
            val e = shown("Radiohead", radiohead)
            assertEquals(a.source, e.source)
            seen += e.source to ImageIds.of(e)
            block(e)
            assertNull(art().cachedEntry("Radiohead", radiohead))
            assertTrue(seen.size < 20)
        }
        assertEquals(seen.distinct(), seen)
        assertEquals(Sources.THE_AUDIO_DB, seen.first().first)
        assertEquals(Sources.DISCOGS, seen.last().first)
        assertTrue(seen.count { it.first == Sources.THE_AUDIO_DB } > 1)
        assertNotNull(cache.miss(radiohead))
    }

    @Test
    fun aBlockOfTheFirstCommonsFileGivesTheSecond() {
        val first = art().get("宇多田ヒカル", utada, 384, 480)!!
        val e = shown("宇多田ヒカル", utada)
        assertTrue(ImageIds.of(e).startsWith("File:"))
        block(e)
        val second = art().get("宇多田ヒカル", utada, 384, 480)!!
        assertEquals(Wikimedia.SOURCE, second.source)
        assertNotEquals(first.credit, second.credit)
        assertNotEquals(ImageIds.of(e), ImageIds.of(shown("宇多田ヒカル", utada)))
    }

    @Test
    fun aBlockIsScopedToItsArtistKey() {
        val b = ArtBlocks.Block("k1", null, "deezer", "abc", "s1", "A", 1)
        blocks.add(b)
        assertTrue(blocks.blocked("k1", "deezer", "abc"))
        assertTrue(blocks.blocked("k1", "commons", "zz", "s1"))
        assertFalse(blocks.blocked("k2", "deezer", "abc"))
        assertFalse(blocks.blocked("k2", "commons", "zz", "s1"))
        assertFalse(blocks.blocked("k1", "deezer", ""))
    }

    @Test
    fun theSameBytesUnderAnotherUrlAreSkippedForThatKey() {
        sources = Sources.COMMONS.copy(theAudioDb = true)
        art().get("Radiohead", radiohead, 384, 480)!!
        val e = shown("Radiohead", radiohead)
        blocks.add(ArtBlocks.Block(e.key, e.mbid, e.source, "another id", cache.sha(e), e.name, 1))
        art().get("Radiohead", radiohead, 384, 480)
        val now = art().cachedEntry("Radiohead", radiohead)
        assertTrue(now == null || now.url != e.url)
    }

    @Test
    fun imageIdsPerSource() {
        assertEquals("0d58cfbc90f2e776608bcdc0c45a4711", ImageIds.of(Sources.DEEZER, "https://cdn-images.dzcdn.net/images/artist/0d58cfbc90f2e776608bcdc0c45a4711/1000x1000-000000-80-0-0.jpg"))
        assertEquals(
            "s3://discogs-database-images/A-3840-1386352285-8822.jpeg",
            ImageIds.of(Sources.DISCOGS, "https://i.discogs.com/eG3bUGOvNZSm8nRHMz3xf4tRfaxTz5sq9PpfsEMdTSY/rs:fit/g:sm/q:90/h:500/w:500/czM6Ly9kaXNjb2dz/LWRhdGFiYXNlLWlt/YWdlcy9BLTM4NDAt/MTM4NjM1MjI4NS04/ODIyLmpwZWc.jpeg"),
        )
        assertEquals(
            ImageIds.of(Sources.YOUTUBE, "https://yt3.googleusercontent.com/ytc/AIdro_l2Ct30OyvxG3NA=s900-c-k-c0x00ffffff-no-rj"),
            ImageIds.of(Sources.YOUTUBE, "https://yt3.googleusercontent.com/ytc/AIdro_l2Ct30OyvxG3NA=s176-c-k"),
        )
        assertEquals("images/media/artist/thumb/35poth1668098458.jpg", ImageIds.of(Sources.THE_AUDIO_DB, "https://r2.theaudiodb.com/images/media/artist/thumb/35poth1668098458.jpg"))
        val old = ArtCache.Entry("k", "Utada", null, Wikimedia.SOURCE, Credit("a", "CC BY 4.0", "https://commons.wikimedia.org/wiki/File:Hikaru_Utada_at_Spotify_Tokyo.png"), "https://upload.wikimedia.org/x.png", 0)
        assertEquals("File:Hikaru Utada at Spotify Tokyo.png", ImageIds.of(old))
    }

    @Test
    fun aVariantWinKeepsItsResolutionAndABlockIsAMissUnderTheOuterKey() {
        val name = "宇多田ヒカル/Utada Hikaru【公式】"
        assertNotNull(art().get(name, null, 240, 320))
        val e = art().cachedEntry(name, null)!!
        assertEquals(utada, e.mbid)
        assertTrue(e.resolvedBy.isNotEmpty() && e.resolvedBy != name)
        assertTrue(art().wrongArtistOffered(e))
        var blocked = 0
        while (true) {
            val x = art().cachedEntry(name, null) ?: break
            block(x)
            blocked++
            art().get(name, null, 240, 320)
            assertTrue(blocked < 20)
        }
        assertTrue(blocked > 1)
        assertNotNull(cache.miss(e.key))
    }

    @Test
    fun aTagMbidIsNotOfferedAsWrongArtist() {
        art().get("宇多田ヒカル", utada, 384, 480)!!
        assertFalse(art().wrongArtistOffered(shown("宇多田ヒカル", utada)))
    }

    @Test
    fun aRejectedMbidIsSkippedAndItsUndoSearchesAgain() {
        val query = "https://musicbrainz.org/ws/2/artist/?query=%E5%AE%87%E5%A4%9A%E7%94%B0%E3%83%92%E3%82%AB%E3%83%AB&fmt=json&limit=10"
        art().get("宇多田ヒカル", null, 384, 480)!!
        val e = shown("宇多田ヒカル", null)
        assertEquals(utada, e.key)
        assertTrue(art().wrongArtistOffered(e))
        val r = ArtBlocks.Reject(e.key, e.mbid, e.resolvedBy, e.name, 5)
        blocks.add(r)
        art().forget(e.key)
        assertNull(art().get("宇多田ヒカル", null, 384, 480))
        assertEquals(2, http.calls.count { it == query })
        assertNotNull(cache.miss("name:宇多田ヒカル"))
        assertNull(art().cachedEntry("宇多田ヒカル", null))

        blocks.remove(r)
        art().forgetName(e.resolvedBy)
        art().forget("name:宇多田ヒカル")
        assertNotNull(art().get("宇多田ヒカル", null, 384, 480))
        assertEquals(3, http.calls.count { it == query })
        assertEquals(utada, shown("宇多田ヒカル", null).key)
    }

    @Test
    fun recordsSurviveARestartAndATornLineIsSkipped() {
        val f = java.io.File(tmp.root, "b.tsv")
        val logs = ArrayList<String>()
        val b = ArtBlocks(f)
        b.add(ArtBlocks.Block("k\tx", "m", "deezer", "id", "sha", "Name\twith tab", 3))
        b.add(ArtBlocks.Reject("k2", "m2", "Muto", "むと", 4))
        b.applied(b.records().first { it is ArtBlocks.Block }, "rfcomm:AA")
        b.remove(b.records().first { it is ArtBlocks.Reject })
        b.pushed(b.pushes("rfcomm:AA").single(), "rfcomm:BB")
        b.save()
        f.appendText("block\tonly\tthree\n")
        val c = ArtBlocks(f) { logs += it }
        assertEquals(b.encode(), c.encode())
        assertEquals(1, logs.size)
        assertTrue(c.blocked("k\tx", "deezer", "id"))
        assertEquals(emptyList<ArtBlocks.Record>(), c.pending("rfcomm:AA"))
        assertEquals(1, c.pending("rfcomm:BB").size)
        assertEquals(listOf("むと"), c.pushes("rfcomm:AA").map { it.name })
        assertTrue(c.pushes("rfcomm:BB").isEmpty())
    }

    @Test
    fun theOrderRanksTheEnabledSources() {
        sources = Sources.COMMONS.copy(theAudioDb = true, discogs = true)
        assertEquals(Sources.THE_AUDIO_DB, art().get("Radiohead", radiohead, 384, 480)!!.source)
        cache.clear()
        sources = sources.moved(Sources.DISCOGS, -2)
        assertEquals(listOf(Wikimedia.SOURCE, Sources.DISCOGS, Sources.THE_AUDIO_DB, Sources.FANART_TV, Sources.YOUTUBE, Sources.DEEZER), sources.order)
        assertEquals(Sources.DISCOGS, art().get("Radiohead", radiohead, 384, 480)!!.source)
        cache.clear()
        sources = Sources.COMMONS.copy(theAudioDb = true, order = Sources.order("deezer,discogs"))
        assertEquals(Sources.THE_AUDIO_DB, art().get("Radiohead", radiohead, 384, 480)!!.source)
        assertEquals(listOf("deezer", "discogs", "theaudiodb", "fanarttv", "youtube", "commons"), Sources.order("deezer,bogus,discogs,deezer"))
        assertEquals(Sources.ORDER, Sources.order(null))
        assertEquals(listOf("commons", "discogs", "theaudiodb"), Sources.COMMONS.copy(theAudioDb = true, discogs = true).moved(Sources.DISCOGS, -2).ordered)
    }

    @Test
    fun aReorderKeepsHitsAndMisses() {
        sources = Sources.COMMONS.copy(theAudioDb = true, discogs = true)
        art().get("Radiohead", radiohead, 384, 480)!!
        assertNull(art().get("Ado", null, 384, 480))
        val n = http.calls.size
        sources = sources.moved(Sources.DISCOGS, -2)
        assertEquals(Sources.THE_AUDIO_DB, art().get("Radiohead", radiohead, 384, 480)!!.source)
        assertNull(art().get("Ado", null, 384, 480))
        assertEquals(n, http.calls.size)
    }

    @Test
    fun aRefreshTakesTheNewOrderAndKeepsTheOldImageOnFailure() {
        sources = Sources.COMMONS.copy(theAudioDb = true, discogs = true)
        art().get("Radiohead", radiohead, 384, 480)!!
        sources = sources.moved(Sources.DISCOGS, -2)
        online = false
        assertEquals(Sources.THE_AUDIO_DB, art().refresh("Radiohead", radiohead, 384, 480)!!.source)
        assertEquals(Sources.THE_AUDIO_DB, shown("Radiohead", radiohead).source)
        online = true
        assertEquals(Sources.DISCOGS, art().refresh("Radiohead", radiohead, 384, 480)!!.source)
        assertEquals(Sources.DISCOGS, shown("Radiohead", radiohead).source)
    }

    @Test
    fun aNameKeyedBlockStillAppliesAfterTheNameResolvesToAnMbid() {
        val muto = "6ac7acd1-2720-4733-9f98-cc5163bdce21"
        val md5 = "a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0"
        val pic = "https://cdn-images.dzcdn.net/images/artist/$md5/1000x1000-000000-80-0-0.jpg"
        val dz = Response(200, "{\"data\":[{\"id\":1,\"name\":\"むと\",\"nb_fan\":5,\"picture_xl\":\"$pic\"}]}".toByteArray())
        val mbArtist = Response(200, "{\"id\":\"$muto\",\"name\":\"むﾄ\",\"relations\":[]}".toByteArray())
        http.queued["https://api.deezer.com/search/artist?q=${Api.enc("むと")}"] = ArrayDeque(List(2) { dz })
        http.queued["https://musicbrainz.org/ws/2/artist/$muto?inc=url-rels&fmt=json"] = ArrayDeque(List(2) { mbArtist })
        http.queued[pic] = ArrayDeque(List(2) { Response(200, Images.jpeg(Images.photo(1000, 1000), 92)) })
        sources = Sources.COMMONS.copy(deezer = true)
        blocks.add(ArtBlocks.Block("name:other", null, Sources.DEEZER, md5, "", "other", 1))
        assertNotNull(art().get("むと", null, 240, 320))
        assertEquals(muto, shown("むと", null).key)
        blocks.add(ArtBlocks.Block("name:むと", null, Sources.DEEZER, md5, "", "むと", 2))
        assertNull(art().cachedEntry("むと", null))
        assertNull(art().get("むと", null, 240, 320))
        assertNotNull(cache.miss(muto))
        assertTrue("missing ${http.missing}", http.missing.isEmpty())
    }

    @Test
    fun aDeezerBlockGivesTheNextExactNameMatchByFans() {
        val shiho = "7f8015a0-d7c2-4566-89a3-0174f0dfaa27"
        val pic = { md5: String -> "https://cdn-images.dzcdn.net/images/artist/$md5/1000x1000-000000-80-0-0.jpg" }
        http.queued["https://musicbrainz.org/ws/2/artist/$shiho?inc=url-rels&fmt=json"] =
            ArrayDeque(List(3) { Response(200, "{\"id\":\"$shiho\",\"name\":\"shiho\",\"relations\":[]}".toByteArray()) })
        http.queued[pic("26cc4f7884f4a1122ee11c72fcbff31d")] = ArrayDeque(List(2) { Response(200, Images.jpeg(Images.photo(1000, 1000, 1), 92)) })
        http.queued[pic("7d0d00dbfa6f87119195f6edb1a83170")] = ArrayDeque(List(2) { Response(200, Images.jpeg(Images.photo(1000, 1000, 2), 92)) })
        sources = Sources.COMMONS.copy(deezer = true)
        assertEquals(Sources.DEEZER, art().get("shiho", shiho, 240, 320)!!.source)
        val first = shown("shiho", shiho)
        assertEquals("26cc4f7884f4a1122ee11c72fcbff31d", ImageIds.of(first))
        block(first)
        assertNotNull(art().get("shiho", shiho, 240, 320))
        assertEquals("7d0d00dbfa6f87119195f6edb1a83170", ImageIds.of(shown("shiho", shiho)))
        block(shown("shiho", shiho))
        assertNull(art().get("shiho", shiho, 240, 320))
        assertNotNull(cache.miss(shiho))
        assertTrue("missing ${http.missing}", http.missing.isEmpty())
    }

    @Test
    fun aYouTubeBlockFetchesTheNextChannelOnlyThen() {
        val id = "33333333-3333-3333-3333-333333333333"
        val ch = listOf("https://www.youtube.com/channel/UCaaaa", "https://www.youtube.com/channel/UCbbbb")
        val avatar = { k: String -> "https://yt3.googleusercontent.com/ytc/$k=s900-c-k-c0x00ffffff-no-rj" }
        val rels = ch.joinToString(",") { "{\"type\":\"youtube\",\"ended\":false,\"url\":{\"resource\":\"$it\"}}" }
        http.queued["https://musicbrainz.org/ws/2/artist/$id?inc=url-rels&fmt=json"] =
            ArrayDeque(List(2) { Response(200, "{\"id\":\"$id\",\"name\":\"Two\",\"relations\":[$rels]}".toByteArray()) })
        ch.zip(listOf("AAAA", "BBBB")).forEachIndexed { i, (url, k) ->
            http.queued[url] = ArrayDeque(List(2) { Response(200, "<meta property=\"og:image\" content=\"${avatar(k)}\">".toByteArray()) })
            http.queued[avatar(k)] = ArrayDeque(List(2) { Response(200, Images.jpeg(Images.photo(1000, 1000, i + 3L), 92)) })
        }
        sources = Sources.COMMONS.copy(youtube = true)
        assertEquals(Sources.YOUTUBE, art().get("Two", id, 240, 320)!!.source)
        val first = shown("Two", id)
        assertEquals(avatar("AAAA").substringBefore('='), ImageIds.of(first))
        assertEquals(1, http.calls.count { it == ch[0] })
        assertEquals(0, http.calls.count { it == ch[1] })
        block(first)
        assertNotNull(art().get("Two", id, 240, 320))
        assertEquals(avatar("BBBB").substringBefore('='), ImageIds.of(shown("Two", id)))
        assertEquals(1, http.calls.count { it == ch[1] })
        assertTrue("missing ${http.missing}", http.missing.isEmpty())
    }

    @Test
    fun anEntryFromBeforeResolvedByIsOfferedByItsStoredSearch() {
        cache.putMbid("宇多田ヒカル", utada, time.now)
        val searched = ArtCache.Entry(utada, "宇多田ヒカル", utada, Wikimedia.SOURCE, null, "https://upload.wikimedia.org/x.png", 0)
        assertEquals("宇多田ヒカル", art().resolvedBy(searched))
        assertTrue(art().wrongArtistOffered(searched))
        val tagged = ArtCache.Entry(utada, "Utada", utada, Wikimedia.SOURCE, null, "https://upload.wikimedia.org/x.png", 0)
        assertEquals("", art().resolvedBy(tagged))
        assertFalse(art().wrongArtistOffered(tagged))
        cache.putMbid("Other", radiohead, time.now)
        val other = ArtCache.Entry(utada, "Other", utada, Wikimedia.SOURCE, null, "https://upload.wikimedia.org/x.png", 0)
        assertFalse(art().wrongArtistOffered(other))
    }

    private val vtuber = "空星きらめ/Sorahoshi Kirame【にじさんじ】"
    private val kirame = "0e7b3c4f-1f2a-4b5c-8d6e-7f8091a2b3c4"

    private fun seed(key: String, name: String, mbid: String?, by: String, w: Int = 240, h: Int = 320): ArtCache.Entry {
        val src = Images.jpeg(Images.photo(600, 800), 92)
        val e = ArtCache.Entry(key, name, mbid, Wikimedia.SOURCE, Credit("Jane Doe", "CC BY 4.0", ""), "https://upload.wikimedia.org/$name.jpg", 1, "File:$name.jpg", "", by)
        cache.putHit(e, src)
        cache.putRendered(key, w, h, Backdrop.render(AwtCodec(), src, w, h)!!)
        return cache.hit(key)!!
    }

    private fun sharedMbid(): ArtCache.Entry {
        cache.putMbid("宇多田ヒカル", utada, time.now)
        cache.putMbid("Utada Hikaru", utada, time.now)
        return seed(utada, "宇多田ヒカル", utada, "宇多田ヒカル")
    }

    private fun variant(): ArtCache.Entry {
        cache.putMbid(vtuber, null, time.now)
        cache.putMbid("空星きらめ", kirame, time.now)
        return seed("name:${Names.fold(vtuber)}", vtuber, kirame, "空星きらめ")
    }

    @Test
    fun wrongArtistIsOfferedOnlyUnderTheResolverName() {
        val e = sharedMbid()
        assertTrue(art().wrongArtistOffered(e, "宇多田ヒカル"))
        assertFalse(art().wrongArtistOffered(e, "Utada Hikaru"))
        assertTrue(art().get("宇多田ヒカル", null, 240, 320)!!.wrongArtist)
        assertFalse(art().get("Utada Hikaru", null, 240, 320)!!.wrongArtist)
        assertFalse(art().get("宇多田ヒカル", utada, 240, 320)!!.wrongArtist)
        val v = variant()
        assertTrue(art().wrongArtistOffered(v, vtuber))
        assertTrue(art().get(vtuber, null, 240, 320)!!.wrongArtist)
        assertTrue("missing ${http.missing}", http.calls.isEmpty())
    }

    @Test
    fun candidatesAreTheFoldedNameTheKeyAndTheMbid() {
        val e = sharedMbid()
        assertEquals(listOf(e.key), art().candidates("Utada Hikaru", utada).map { it.key })
        assertEquals(listOf(e.key), art().candidates("Utada Hikaru", null).map { it.key })
        assertEquals(listOf(e.key), art().candidates("Hikki", utada.uppercase()).map { it.key })
        assertEquals(listOf(e.key), art().candidates(" 宇多田ヒカル ", null).map { it.key })
        assertTrue(art().candidates("Radiohead", radiohead).isEmpty())
        val crc = java.util.zip.CRC32().apply { update(cache.rendered(e.key, 240, 320)!!) }.value
        assertEquals(crc, art().renderedCrc(e, 240, 320))
        assertNull(art().renderedCrc(e, 360, 480))
    }

    @Test
    fun oldLinesLoadWithNoCrcAndNewLinesRoundTrip() {
        val f = java.io.File(tmp.root, "old.tsv")
        f.writeText("block\tk\t\tdeezer\tx\ts\tA\t1\tdev\nreject\tm\tm\tA\tA\t2\t\n")
        val old = ArtBlocks(f)
        assertEquals(2, old.records().size)
        assertTrue(old.records().all { (it as? ArtBlocks.Block)?.crc == null && (it as? ArtBlocks.Reject)?.crc == null })
        old.add(ArtBlocks.Block("k2", null, "deezer", "y", "s", "B", 3, crc = 2438839214))
        old.add(ArtBlocks.Reject("m2", "m2", "B", "B", 4, crc = 7))
        old.save()
        assertEquals(listOf(9, 10, 7, 8), f.readLines().map { it.split('\t').size })
        val again = ArtBlocks(f).records()
        assertEquals(old.records(), again)
        assertEquals(2438839214, again.filterIsInstance<ArtBlocks.Block>().single { it.key == "k2" }.crc)
    }

    @Test
    fun pushOriginsRoundTripOnTheirOwnLine() {
        val f = java.io.File(tmp.root, "origin.tsv")
        val logs = ArrayList<String>()
        val b = ArtBlocks(f)
        b.addPush("Gemma", null, Enums.ImgOrigin.phone)
        b.addPush("BoA", "b539e453-c4fe-47e3-8a07-8517eac74429", Enums.ImgOrigin.library)
        b.addPush("Hal", null)
        b.save()
        assertEquals(
            listOf("push\tGemma\t\t", "origin\tGemma\t\tphone", "push\tBoA\tb539e453-c4fe-47e3-8a07-8517eac74429\t", "origin\tBoA\tb539e453-c4fe-47e3-8a07-8517eac74429\tlibrary", "push\tHal\t\t"),
            f.readLines(),
        )
        val c = ArtBlocks(f)
        assertEquals(b.pushes("x"), c.pushes("x"))
        assertEquals(listOf(Enums.ImgOrigin.phone, Enums.ImgOrigin.library, null), c.pushes("x").map { it.origin })
        f.writeText("push\tGemma\t\t\npush\tHal\t\t\norigin\tHal\t\tradio\norigin\tNobody\t\tphone\norigin\tGemma\tm\tphone\n")
        val d = ArtBlocks(f) { logs += it }
        assertEquals(listOf(ArtBlocks.Push("Gemma", null), ArtBlocks.Push("Hal", null)), d.pushes("x"))
        assertEquals(3, logs.size)
    }

    @Test
    fun coveringNeedsTheKindAndTheCrcOrAPendingCrclessRecord() {
        val b = ArtBlocks.Block("k", utada, "deezer", "x", "s", "A", 1, crc = 5)
        val old = ArtBlocks.Block("k3", null, "deezer", "x", "s", "C", 2)
        val r = ArtBlocks.Reject(utada, utada, "A", "A", 3, crc = 9)
        listOf(b, old, r).forEach { blocks.add(it) }
        assertEquals(b, blocks.covering(false, "a", emptySet(), null, 5, "dev"))
        assertEquals(b, blocks.covering(false, "Z", setOf("k"), null, 5, "dev"))
        assertEquals(b, blocks.covering(false, "Z", emptySet(), utada.uppercase(), 5, "dev"))
        assertNull(blocks.covering(false, "Z", emptySet(), radiohead, 5, "dev"))
        assertNull(blocks.covering(false, "A", setOf("k"), utada, 6, "dev"))
        assertNull(blocks.covering(true, "A", setOf("k"), utada, 5, "dev"))
        assertEquals(r, blocks.covering(true, "A", emptySet(), null, 9, "dev"))
        assertNull(blocks.covering(false, "A", emptySet(), null, 9, "dev"))
        assertEquals(old, blocks.covering(false, "C", emptySet(), null, 123, "dev"))
        blocks.applied(old, "dev")
        assertNull(blocks.covering(false, "C", emptySet(), null, 123, "dev"))
        assertNotNull(blocks.covering(false, "C", emptySet(), null, 123, "other"))
        assertNull(blocks.covering(false, "C", emptySet(), null, 123, null))
    }

    @Test
    fun heldLinesRoundTripPerDevice() {
        val f = tmp.root.resolve("held.tsv")
        val mb = "b539e453-c4fe-47e3-8a07-8517eac74429"
        ArtBlocks(f).apply {
            hold("A\tb", null, "rfcomm:X")
            hold("C", mb, "rfcomm:Y")
            save()
        }
        val again = ArtBlocks(f)
        assertEquals(listOf(ArtBlocks.Held("A\tb", null, "rfcomm:X")), again.held("rfcomm:X"))
        assertEquals(listOf(ArtBlocks.Held("C", mb, "rfcomm:Y")), again.held("rfcomm:Y"))
        assertTrue(again.records().isEmpty())
    }
}

