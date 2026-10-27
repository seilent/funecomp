package net.seilent.funecomp.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class ChannelLookupTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val suisei = "8eeb59f4-0071-4261-b1f8-91d8294622d2"
    private val wrongSuisei = "e43c7000"
    private val chelly = "4dfe9c0b-7602-4189-9919-151d493d1028"
    private val fujiAoi = "03b96523-db37-4e85-ac9d-b7ceb6926e9b"
    private val itsuki = "61d436ef-099b-4e25-b936-de7bda576565"
    private val dazbee = "10623c33-205c-49f4-b8a0-55a105dd4fa3"
    private val day = 24 * 3600 * 1000L

    private val http = Fixtures()
    private val time = FakeTime()
    private var sources = Sources.COMMONS.copy(youtube = true, order = Sources.ORDER)
    private val cache by lazy { ArtCache(tmp.newFolder("art")) }
    private val blocks by lazy { ArtBlocks(java.io.File(tmp.root, "artblock.tsv")) }
    private val picks = ArtPicks()
    private val logs = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun art() = ArtistArt(http, AwtCodec(), cache, { sources }, "0.1.0", { time.now }, time, blocks = blocks, picks = picks, log = { logs += it }).also {
        http.now = { time.now }
    }

    private fun search(tag: String) = "https://www.youtube.com/results?search_query=${Api.enc(tag)}&sp=EgIQAg%253D%253D"

    private fun searches() = http.calls.count { it.startsWith("https://www.youtube.com/results?") && !it.endsWith("&hl=en&gl=US") }

    private fun key(tag: String) = "name:${Names.fold(tag)}"

    @Test
    fun aBareVariantResolvesOnlyOnAUniqueMusicBrainzMatch() {
        sources = Sources.COMMONS.copy(deezer = true)
        assertNull(art().get("Suisei Channel", null, 240, 320))
        assertTrue(http.calls.contains("https://musicbrainz.org/ws/2/artist/?query=Suisei&fmt=json&limit=10"))
        assertTrue(http.calls.none { it.contains("/artist/$wrongSuisei") })
        assertFalse(http.calls.contains("https://api.deezer.com/search/artist?q=Suisei"))
        assertNull(cache.bare("Suisei Channel")!!.mbid)
        assertNull(cache.mbid("Suisei"))
    }

    @Test
    fun aBareLookupIgnoresThePlainNameStore() {
        sources = Sources.COMMONS.copy(deezer = true)
        val full = "e43c7000-2a81-4a4b-a0a2-61c84fd96a34"
        cache.putMbid("Suisei", full, time.now)
        assertNull(art().get("Suisei Channel", null, 240, 320))
        assertTrue(http.calls.none { it.contains("/artist/$wrongSuisei") })
        assertEquals(full, art().keyOf("Suisei", null))
    }

    @Test
    fun aChannelSuffixKeepsItsUniqueBareHitAndDropsAnAmbiguousOne() {
        sources = Sources.COMMONS.copy(deezer = true)
        val a = art().get("DAZBEE official", null, 240, 320)!!
        assertEquals(dazbee, a.mbid)
        val e = cache.hit(key("DAZBEE official"))!!
        assertTrue(e.bare)
        assertEquals("DAZBEE official", e.resolvedBy)
        assertTrue(art().wrongArtistOffered(e, "DAZBEE official"))
        assertNull(art().get("Iris Official", null, 240, 320))
        assertTrue(http.calls.contains("https://musicbrainz.org/ws/2/artist/?query=Iris&fmt=json&limit=10"))
        assertTrue(http.calls.none { it.contains("/artist/a2aeb4e5") })
        assertFalse(http.calls.contains("https://api.deezer.com/search/artist?q=Iris"))
    }

    @Test
    fun aBareNullIsLookedUpAgainAfterTheTtl() {
        sources = Sources.COMMONS.copy(deezer = true)
        val q = "https://musicbrainz.org/ws/2/artist/?query=Suisei&fmt=json&limit=10"
        assertNull(art().get("Suisei Channel", null, 240, 320))
        time.now += 3 * day
        cache.drop(key("Suisei Channel"))
        assertNull(art().get("Suisei Channel", null, 240, 320))
        assertEquals(1, http.calls.count { it == q })
        time.now += 5 * day
        cache.drop(key("Suisei Channel"))
        assertNull(art().get("Suisei Channel", null, 240, 320))
        assertEquals(2, http.calls.count { it == q })
    }

    @Test
    fun clearEmptiesTheBareStore() {
        cache.putBare("Suisei", null, time.now)
        assertNotNull(cache.bare("Suisei"))
        cache.clear()
        assertNull(cache.bare("Suisei"))
        assertNull(ArtCache(java.io.File(tmp.root, "art")).bare("Suisei"))
    }

    @Test
    fun wrongArtistOnABareHitLeavesThePlainTag() {
        sources = Sources.COMMONS.copy(deezer = true)
        val a = art()
        assertNotNull(a.get("DAZBEE official", null, 240, 320))
        val e = cache.hit(key("DAZBEE official"))!!
        blocks.add(ArtBlocks.Reject(e.key, e.mbid, a.resolvedBy(e), e.name, time.now))
        a.forget(e)
        assertNull(art().get("DAZBEE official", null, 240, 320))
        assertEquals(dazbee, art().get("DAZBEE", null, 240, 320)!!.mbid)
    }

    @Test
    fun aChannelArtistWithAMusicBrainzLinkGetsTheFullChain() {
        val rows = listOf(
            Triple("Suisei Channel", "UC5CwaMl1eIgY8h02uZw7u8A", suisei),
            Triple("chelly Ch.", "UC0OvtxMEg001QRAyOk9qZ8Q", chelly),
            Triple("Aoi ch.", "UC3Ruo_5doyu514PesWGvCAg", fujiAoi),
            Triple("Chise Itsuki", "UC1FnOvfv5G6EkFIdUmbGGPg", itsuki),
        )
        for ((tag, channel, mbid) in rows) {
            http.calls.clear()
            val a = art().get(tag, null, 240, 320)
            assertNotNull(tag, a)
            assertEquals(tag, mbid, a!!.mbid)
            assertEquals(Sources.YOUTUBE, a.source)
            val e = cache.hit(key(tag))!!
            assertEquals(channel, e.channel)
            assertEquals(tag, e.resolvedBy)
            assertEquals(1, searches())
            assertTrue(http.calls.contains("https://musicbrainz.org/ws/2/artist/$mbid?inc=url-rels&fmt=json"))
            assertNull(cache.mbid(tag)!!.mbid)
        }
        assertTrue(http.calls.none { it.contains("/artist/$wrongSuisei") })
        assertTrue("missing ${http.missing}", http.missing.isEmpty())
    }

    @Test
    fun aBadgeOrAPartWithNoLinkGetsNoPhoto() {
        for (tag in listOf("NÄO Official Channel", "Inari Iroha")) {
            val t = time.now
            assertNull(tag, art().get(tag, null, 240, 320))
            assertNull(cache.hit(key(tag)))
            assertEquals(t, cache.miss(key(tag))!!.yt)
        }
        assertEquals(2, searches())
    }

    @Test
    fun hitsAndResolvedNamesNeverSearch() {
        sources = Sources.COMMONS.copy(youtube = true, deezer = true, order = Sources.ORDER)
        val tags = listOf(
            "Ado", "Radiohead", "コバソロ", "宇多田ヒカル", "Utada Hikaru", "Tenjin Kotone", "むと", "あいみょん", "TSUKI",
            "DAZBEE official", "Kano", "Kapi", "Memento Mori", "Albemuth",
        )
        for (t in tags) art().get(t, null, 240, 320)
        assertEquals(0, searches())
        assertNotNull(cache.hit(key("DAZBEE official")))
    }

    @Test
    fun noSearchWithTheToggleOffATagMbidOrAnArtistPick() {
        sources = Sources.COMMONS.copy(deezer = true)
        assertNull(art().get("Suisei Channel", null, 240, 320))
        sources = Sources.COMMONS.copy(youtube = true, order = Sources.ORDER)
        cache.clear()
        art().get("Suisei Channel", "e43c7000-2a81-4a4b-a0a2-61c84fd96a34", 240, 320)
        val (k, k2) = ArtPicks.keys("chelly Ch.", null, false)
        picks.add(ArtPicks.Pick(k, k2, "chelly Ch.", ArtPicks.ARTIST, Sources.MUSICBRAINZ, "https://musicbrainz.org/artist/$chelly", chelly, "", null, chelly, time.now))
        art().get("chelly Ch.", null, 240, 320)
        assertEquals(0, searches())
    }

    @Test
    fun aResolvedTagWithNoImageNeverReachesAChannelLinkedElsewhere() {
        assertNull(art().get("Kano", null, 240, 320))
        assertNull(art().get("Albemuth", null, 240, 320))
        assertEquals(0, searches())
        assertNull(cache.hit("2bf3971e-8595-4bd2-aace-8a71c42afb66"))
        assertNull(cache.hit(key("Kano")))
    }

    @Test
    fun aChannelIdTagIsTakenAsItself() {
        val dead = "UCmvsBE7E34DPvNkZ0gFyKhA"
        assertNull(art().get(dead, null, 240, 320, search = false))
        assertTrue(http.calls.none { it.contains("youtube.com") })
        assertTrue(http.calls.none { it.contains("query=") })
        assertNull(art().get(dead, null, 240, 320))
        assertEquals(1, http.calls.count { it == YouTube.channelUrl(dead) })
        assertEquals(1, http.calls.count { it.contains("ws/2/url?resource=") })
        assertTrue(http.calls.none { it.contains("query=") })
        assertNull(cache.miss(key(dead))!!.yt)
        assertNotNull(cache.miss(key(dead))!!.ytFailed)
        val aoi = "UC3Ruo_5doyu514PesWGvCAg"
        val a = art().get(aoi, null, 240, 320)!!
        assertEquals(fujiAoi, a.mbid)
        assertEquals(aoi, cache.hit(key(aoi))!!.channel)
        assertEquals(0, searches())
        assertTrue(http.calls.none { it.contains("query=") })
    }

    @Test
    fun aMissIsSearchedEvery30DaysWhileTheOtherSourcesRecheckEvery7() {
        val tag = "NÄO Official Channel"
        val t0 = time.now
        assertNull(art().get(tag, null, 240, 320))
        assertEquals(1, searches())
        assertEquals(t0, cache.miss(key(tag))!!.yt)
        val mb = http.calls.count { it.contains("query=") }
        time.now = t0 + 8 * day
        assertNull(art().get(tag, null, 240, 320))
        assertEquals(1, searches())
        assertTrue(http.calls.count { it.contains("query=") } > mb)
        assertEquals(t0, cache.miss(key(tag))!!.yt)
        time.now = t0 + 15 * day
        assertNull(art().get(tag, null, 240, 320))
        assertEquals(1, searches())
        assertEquals(t0, cache.miss(key(tag))!!.yt)
        time.now = t0 + 31 * day
        assertNull(art().get(tag, null, 240, 320))
        assertEquals(2, searches())
        assertEquals(t0 + 31 * day, cache.miss(key(tag))!!.yt)
    }

    @Test
    fun aLibrarySearchWaitsForAnUnmeteredNetwork() {
        val tag = "NÄO Official Channel"
        val t0 = time.now
        assertNull(art().get(tag, null, 240, 320))
        time.now = t0 + 31 * day
        val a = art()
        a.beginMark()
        assertNull(a.get(tag, null, 240, 320, search = false))
        assertEquals(1, searches())
        assertEquals(t0, cache.miss(key(tag))!!.yt)
        val m = a.mark()
        assertTrue(m.pending)
        assertTrue(a.holds(m))
        assertFalse(a.holds(m, searchAllowed = true))
        time.now += day
        val t1 = time.now
        assertNull(art().get(tag, null, 240, 320))
        assertEquals(2, searches())
        assertEquals(t1, cache.miss(key(tag))!!.yt)
    }

    @Test
    fun aFreshMissWithADueSearchMarksTheSweepPending() {
        val tag = "NÄO Official Channel"
        val t0 = time.now
        assertNull(art().get(tag, null, 240, 320))
        time.now = t0 + 25 * day
        assertNull(art().get(tag, null, 240, 320))
        time.now = t0 + 31 * day
        val a = art()
        a.beginMark()
        assertNull(a.get(tag, null, 240, 320, search = false))
        assertEquals(1, searches())
        assertTrue(a.mark().pending)
        assertTrue(a.mark().until <= t0 + 32 * day)
        assertNull(a.get(tag, null, 240, 320))
        assertEquals(2, searches())
        a.beginMark()
        assertFalse(a.mark().pending)
    }

    @Test
    fun aNewLibraryMissIsSearchedAtTheUnmeteredEdge() {
        val tag = "NÄO Official Channel"
        val a = art()
        a.beginMark()
        assertNull(a.get(tag, null, 240, 320, search = false))
        assertEquals(0, searches())
        assertTrue(a.mark().pending)
        assertNull(cache.miss(key(tag))!!.yt)
        assertNull(cache.miss(key(tag))!!.ytFailed)
        assertNull(a.get(tag, null, 240, 320, search = false))
        assertEquals(0, searches())
        time.now += 1000
        assertNull(art().get(tag, null, 240, 320))
        assertEquals(1, searches())
    }

    @Test
    fun aTransientSearchErrorWritesNoMiss() {
        val tag = "NÄO Official Channel"
        http.queued[search(tag)] = ArrayDeque(List(4) { Response(503, ByteArray(0)) })
        try {
            art().get(tag, null, 240, 320)
            fail("no error")
        } catch (e: IOException) {
        }
        assertNull(cache.miss(key(tag)))
    }

    @Test
    fun anUnavailableSearchWritesAMissWithNoStampAndIsDueAtTheWeeklyRecheck() {
        val tag = "NÄO Official Channel"
        http.queued[search(tag)] = ArrayDeque(listOf(Response(200, "<html></html>".toByteArray())))
        val t0 = time.now
        assertNull(art().get(tag, null, 240, 320))
        assertNotNull(cache.miss(key(tag)))
        assertNull(cache.miss(key(tag))!!.yt)
        assertEquals(t0, cache.miss(key(tag))!!.ytFailed)
        assertTrue(logs.contains("art youtube search unavailable $tag"))
        assertNull(art().get(tag, null, 240, 320))
        assertEquals(1, searches())
        time.now += 8 * day
        val t1 = time.now
        assertNull(art().get(tag, null, 240, 320))
        assertEquals(2, searches())
        assertEquals(t1, cache.miss(key(tag))!!.yt)
    }

    @Test
    fun aGatedPairIsUnavailable() {
        val tag = "NÄO Official Channel"
        val gated = Fixtures.load("yt-search-nanao.html")
        http.queued[search(tag)] = ArrayDeque(listOf(Response(200, gated)))
        http.queued[search(tag) + "&hl=en&gl=US"] = ArrayDeque(listOf(Response(200, gated)))
        assertNull(art().get(tag, null, 240, 320))
        assertNull(cache.miss(key(tag))!!.yt)
        assertNotNull(cache.miss(key(tag))!!.ytFailed)
        time.now += 8 * day
        assertNull(art().get(tag, null, 240, 320))
        assertEquals(2, searches())
    }

    @Test
    fun aCachedHitThatFailedToRenderIsNeverReplacedThroughAChannel() {
        val tag = "Suisei Channel"
        cache.putHit(ArtCache.Entry(key(tag), tag, null, Sources.YOUTUBE, null, "https://yt3.ggpht.com/x=s900", time.now), byteArrayOf(1, 2, 3))
        time.now += 8 * day
        assertNull(art().get(tag, null, 240, 320))
        assertTrue(http.calls.contains("https://musicbrainz.org/ws/2/artist/?query=Suisei&fmt=json&limit=10"))
        assertEquals(0, searches())
    }

    @Test
    fun anOlderMissIsLookedUpOnceUnderTheNewRules() {
        val tag = "NÄO Official Channel"
        cache.putMiss(key(tag), time.now, sources.enabled)
        val f = java.io.File(tmp.root, "art").listFiles { x -> x.name.endsWith(".meta") }!!.single()
        f.writeText(f.readText().lines().map { if (it.startsWith("cleaned")) "cleaned=2" else it }.joinToString("\n"))
        assertFalse(cache.miss(key(tag))!!.cleaned)
        assertNull(art().get(tag, null, 240, 320))
        assertEquals(1, searches())
        assertTrue(f.readText().lines().contains("cleaned=3"))
        assertNull(art().get(tag, null, 240, 320))
        assertEquals(1, searches())
    }

    @Test
    fun upgradesAndPreviewsNeverSearch() {
        assertNotNull(art().previewArtist("Suisei Channel", suisei, 240, 320))
        assertEquals(0, searches())
        val e = ArtCache.Entry(key("Suisei Channel"), "Suisei Channel", suisei, Sources.YOUTUBE, null, "https://yt3.ggpht.com/x=s88", time.now)
        cache.putHit(e, Images.jpeg(Images.photo(88, 88), 90))
        art().upgrade(cache.hit(e.key)!!, 240, 320)
        assertEquals(0, searches())
    }

    @Test
    fun theBareScanDropsAmbiguousOldHitsAndKeepsTheRest() {
        val iris = "a2aeb4e5-0000-4000-8000-000000000000"
        val megu = "86d34b4b-0000-4000-8000-000000000000"
        val mair = "944d7f7e-0000-4000-8000-000000000000"
        val img = Images.jpeg(Images.photo(500, 500), 90)
        fun seed(tag: String, mbid: String?, source: String, by: String) =
            cache.putHit(ArtCache.Entry(key(tag), tag, mbid, source, null, "https://e/$tag.jpg", time.now, resolvedBy = by), img)
        seed("Iris Official", iris, Sources.DEEZER, "Iris")
        seed("Megu Channel", megu, Sources.DEEZER, "")
        seed("DAZBEE official", dazbee, Sources.DEEZER, "DAZBEE")
        seed("MaiR Channel", mair, Sources.YOUTUBE, "MaiR")
        val (k, k2) = ArtPicks.keys("MaiR Channel", null, false)
        picks.add(ArtPicks.Pick(k, k2, "MaiR Channel", ArtPicks.IMAGE, Sources.LINK, "https://e/mair.jpg", "https://e/mair.jpg", "", null, null, time.now))
        assertEquals(1, art().scanBare())
        assertNull(cache.hit(key("Iris Official")))
        assertEquals(listOf(key("Iris Official")), blocks.drops().map { it.key })
        assertNotNull(cache.hit(key("Megu Channel")))
        assertNotNull(cache.hit(key("DAZBEE official")))
        assertNotNull(cache.hit(key("MaiR Channel")))
        assertTrue(http.calls.none { it.contains("query=MaiR") || it.contains("query=Megu") })
        val n = http.calls.size
        assertEquals(0, art().scanBare())
        assertEquals(n, http.calls.size)
    }

    @Test
    fun wrongArtistOnAChannelPhotoRejectsThatChannelForTheTag() {
        val tag = "chelly Ch."
        val a = art()
        assertEquals(chelly, a.get(tag, null, 240, 320)!!.mbid)
        val e = cache.hit(key(tag))!!
        assertTrue(a.wrongArtistOffered(e, tag))
        val r = ArtBlocks.Reject(e.key, e.mbid, a.resolvedBy(e), e.name, time.now, channel = e.channel)
        blocks.add(r)
        a.forget(e)
        assertEquals(setOf("UC0OvtxMEg001QRAyOk9qZ8Q"), blocks.rejectedChannels(tag))
        assertTrue(blocks.rejected(tag).isEmpty())
        assertNull(art().get(tag, null, 240, 320))
        assertTrue(http.calls.any { it.contains("UC_VOyyNwYloceNv0f9zbtwA") })
        blocks.save()
        val back = ArtBlocks(java.io.File(tmp.root, "artblock.tsv"))
        assertEquals(setOf("UC0OvtxMEg001QRAyOk9qZ8Q"), back.rejectedChannels(tag))
        blocks.remove(r)
        a.forget(key(tag))
        assertEquals(chelly, art().get(tag, null, 240, 320)!!.mbid)
    }

    @Test
    fun twoTagsSharingABareWordKeepTheirOwnAnswer() {
        sources = Sources.COMMONS.copy(deezer = true)
        val a = art()
        assertEquals(dazbee, a.get("DAZBEE official", null, 240, 320)!!.mbid)
        assertEquals(dazbee, a.get("DAZBEE Channel", null, 240, 320)!!.mbid)
        val e = cache.hit(key("DAZBEE official"))!!
        blocks.add(ArtBlocks.Reject(e.key, e.mbid, a.resolvedBy(e), e.name, time.now))
        a.forget(e)
        assertNull(art().get("DAZBEE official", null, 240, 320))
        assertNull(cache.bare("DAZBEE official")!!.mbid)
        a.forget(key("DAZBEE Channel"))
        assertEquals(dazbee, art().get("DAZBEE Channel", null, 240, 320)!!.mbid)
        assertEquals(dazbee, cache.bare("DAZBEE Channel")!!.mbid)
    }

    @Test
    fun aRefreshNeverTakesTheChannelStepItself() {
        val tag = "chelly Ch."
        assertEquals(chelly, art().get(tag, null, 240, 320)!!.mbid)
        assertEquals(1, searches())
        assertEquals(chelly, art().refresh(tag, null, 240, 320)!!.mbid)
        assertEquals(1, searches())
        val other = "NÄO Official Channel"
        val b = art()
        b.beginMark()
        assertNull(b.refresh(other, null, 240, 320, search = false))
        assertEquals(1, searches())
        assertTrue(b.mark().pending)
        assertNull(art().refresh(other, null, 240, 320))
        assertEquals(2, searches())
    }

    @Test
    fun theLookupNeverSearchesAnAlias() {
        val tag = "水波琴音Minami Kotone"
        val gated = Fixtures.load("yt-search-nanao.html")
        http.queued[search(tag)] = ArrayDeque(listOf(Response(200, gated)))
        http.queued[search(tag) + "&hl=en&gl=US"] = ArrayDeque(listOf(Response(200, gated)))
        assertNull(art().get(tag, null, 240, 320))
        assertEquals(2, http.calls.count { it.startsWith("https://www.youtube.com/results?") })
        assertNotNull(cache.miss(key(tag))!!.ytFailed)
        assertTrue(http.calls.none { it.contains("inc=aliases") })
    }
}
