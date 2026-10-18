package net.seilent.funecomp.artist

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
    private var sources = Sources()
    private var online = true
    private val cache by lazy { ArtCache(tmp.newFolder("art")) }
    private val blocks by lazy { ArtBlocks(java.io.File(tmp.root, "artblock.tsv")) }

    private fun art() = ArtistArt(http, AwtCodec(), cache, { sources }, "0.1.0", { time.now }, time.sleep, { time.now }, { online }, blocks).also {
        http.now = { time.now }
    }

    private fun block(e: ArtCache.Entry) = blocks.add(ArtBlocks.Block(e.key, e.mbid, e.source, ImageIds.of(e), cache.sha(e), e.name, time.now++))

    private fun shown(name: String, mbid: String?) = art().cachedEntry(name, mbid)!!

    @Test
    fun aBlockGivesTheNextCandidateThenTheNextSourceThenAMiss() {
        sources = Sources(theAudioDb = true, discogs = true)
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
        sources = Sources(theAudioDb = true)
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
        sources = Sources(theAudioDb = true, discogs = true)
        assertEquals(Sources.THE_AUDIO_DB, art().get("Radiohead", radiohead, 384, 480)!!.source)
        cache.clear()
        sources = sources.moved(Sources.DISCOGS, -2)
        assertEquals(listOf(Wikimedia.SOURCE, Sources.DISCOGS, Sources.THE_AUDIO_DB, Sources.FANART_TV, Sources.YOUTUBE, Sources.DEEZER), sources.order)
        assertEquals(Sources.DISCOGS, art().get("Radiohead", radiohead, 384, 480)!!.source)
        cache.clear()
        sources = Sources(theAudioDb = true, order = Sources.order("deezer,discogs"))
        assertEquals(Sources.THE_AUDIO_DB, art().get("Radiohead", radiohead, 384, 480)!!.source)
        assertEquals(listOf("deezer", "discogs", "commons", "theaudiodb", "fanarttv", "youtube"), Sources.order("deezer,bogus,discogs,deezer"))
        assertEquals(Sources.ORDER, Sources.order(null))
        assertEquals(listOf("commons", "discogs", "theaudiodb"), Sources(theAudioDb = true, discogs = true).moved(Sources.DISCOGS, -2).ordered)
    }

    @Test
    fun aReorderKeepsHitsAndMisses() {
        sources = Sources(theAudioDb = true, discogs = true)
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
        sources = Sources(theAudioDb = true, discogs = true)
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
        sources = Sources(deezer = true)
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
}
