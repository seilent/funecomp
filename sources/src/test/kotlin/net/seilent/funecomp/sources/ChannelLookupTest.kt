package net.seilent.funecomp.sources

import net.seilent.funekit.artist.Api
import net.seilent.funekit.artist.ArtBlocks
import net.seilent.funekit.artist.ArtCache
import net.seilent.funekit.artist.ArtPicks
import net.seilent.funekit.artist.ArtistArt
import net.seilent.funekit.artist.AwtCodec
import net.seilent.funekit.artist.FakeTime
import net.seilent.funekit.artist.Fixtures
import net.seilent.funekit.artist.Images
import net.seilent.funekit.artist.Names
import net.seilent.funekit.artist.Response
import net.seilent.funekit.artist.Sources
import net.seilent.funekit.artist.TEST_AGENT
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
    private val day = 24 * 3600 * 1000L

    private val http = Fixtures()
    private val time = FakeTime()
    private var sources = YouTubeArt.with(ArtSources.COMMONS.copy(order = ArtSources.ORDER), true)
    private val cache by lazy { ArtCache(tmp.newFolder("art")) }
    private val blocks by lazy { ArtBlocks(java.io.File(tmp.root, "artblock.tsv")) }
    private val picks = ArtPicks()
    private val logs = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun art() = ArtistArt(http, AwtCodec(), cache, { sources }, TEST_AGENT, { time.now }, time, blocks = blocks, picks = picks, log = { logs += it }, extensions = listOf(YouTubeArt { logs += it })).also {
        http.now = { time.now }
    }

    private fun search(tag: String) = "https://www.youtube.com/results?search_query=${Api.enc(tag)}&sp=EgIQAg%253D%253D"

    private fun searches() = http.calls.count { it.startsWith("https://www.youtube.com/results?") && !it.endsWith("&hl=en&gl=US") }

    private fun key(tag: String) = "name:${Names.fold(tag)}"

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
            assertEquals(YouTubeArt.SOURCE, a.source)
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
            assertEquals(t, cache.miss(key(tag))!!.kept["yt"]?.toLong())
        }
        assertEquals(2, searches())
    }

    @Test
    fun hitsAndResolvedNamesNeverSearch() {
        sources = YouTubeArt.with(ArtSources.COMMONS.copy(deezer = true, order = ArtSources.ORDER), true)
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
        sources = ArtSources.COMMONS.copy(deezer = true)
        assertNull(art().get("Suisei Channel", null, 240, 320))
        sources = YouTubeArt.with(ArtSources.COMMONS.copy(order = ArtSources.ORDER), true)
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
        assertNull(cache.miss(key(dead))!!.kept["yt"]?.toLong())
        assertNotNull(cache.miss(key(dead))!!.kept["ytFailed"]?.toLong())
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
        assertEquals(t0, cache.miss(key(tag))!!.kept["yt"]?.toLong())
        val mb = http.calls.count { it.contains("query=") }
        time.now = t0 + 8 * day
        assertNull(art().get(tag, null, 240, 320))
        assertEquals(1, searches())
        assertTrue(http.calls.count { it.contains("query=") } > mb)
        assertEquals(t0, cache.miss(key(tag))!!.kept["yt"]?.toLong())
        time.now = t0 + 15 * day
        assertNull(art().get(tag, null, 240, 320))
        assertEquals(1, searches())
        assertEquals(t0, cache.miss(key(tag))!!.kept["yt"]?.toLong())
        time.now = t0 + 31 * day
        assertNull(art().get(tag, null, 240, 320))
        assertEquals(2, searches())
        assertEquals(t0 + 31 * day, cache.miss(key(tag))!!.kept["yt"]?.toLong())
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
        assertEquals(t0, cache.miss(key(tag))!!.kept["yt"]?.toLong())
        val m = a.mark()
        assertTrue(m.pending)
        assertTrue(a.holds(m))
        assertFalse(a.holds(m, searchAllowed = true))
        time.now += day
        val t1 = time.now
        assertNull(art().get(tag, null, 240, 320))
        assertEquals(2, searches())
        assertEquals(t1, cache.miss(key(tag))!!.kept["yt"]?.toLong())
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
        assertNull(cache.miss(key(tag))!!.kept["yt"]?.toLong())
        assertNull(cache.miss(key(tag))!!.kept["ytFailed"]?.toLong())
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
        assertNull(cache.miss(key(tag))!!.kept["yt"]?.toLong())
        assertEquals(t0, cache.miss(key(tag))!!.kept["ytFailed"]?.toLong())
        assertTrue(logs.contains("art youtube search unavailable $tag"))
        assertNull(art().get(tag, null, 240, 320))
        assertEquals(1, searches())
        time.now += 8 * day
        val t1 = time.now
        assertNull(art().get(tag, null, 240, 320))
        assertEquals(2, searches())
        assertEquals(t1, cache.miss(key(tag))!!.kept["yt"]?.toLong())
    }

    @Test
    fun aGatedPairIsUnavailable() {
        val tag = "NÄO Official Channel"
        val gated = Fixtures.load("yt-search-nanao.html")
        http.queued[search(tag)] = ArrayDeque(listOf(Response(200, gated)))
        http.queued[search(tag) + "&hl=en&gl=US"] = ArrayDeque(listOf(Response(200, gated)))
        assertNull(art().get(tag, null, 240, 320))
        assertNull(cache.miss(key(tag))!!.kept["yt"]?.toLong())
        assertNotNull(cache.miss(key(tag))!!.kept["ytFailed"]?.toLong())
        time.now += 8 * day
        assertNull(art().get(tag, null, 240, 320))
        assertEquals(2, searches())
    }

    @Test
    fun aCachedHitThatFailedToRenderIsNeverReplacedThroughAChannel() {
        val tag = "Suisei Channel"
        cache.putHit(ArtCache.Entry(key(tag), tag, null, YouTubeArt.SOURCE, null, "https://yt3.ggpht.com/x=s900", time.now), byteArrayOf(1, 2, 3))
        time.now += 8 * day
        assertNull(art().get(tag, null, 240, 320))
        assertTrue(http.calls.contains("https://musicbrainz.org/ws/2/artist/?query=Suisei&fmt=json&limit=10"))
        assertEquals(0, searches())
    }

    @Test
    fun upgradesAndPreviewsNeverSearch() {
        assertNotNull(art().previewArtist("Suisei Channel", suisei, 240, 320))
        assertEquals(0, searches())
        val e = ArtCache.Entry(key("Suisei Channel"), "Suisei Channel", suisei, YouTubeArt.SOURCE, null, "https://yt3.ggpht.com/x=s88", time.now)
        cache.putHit(e, Images.jpeg(Images.photo(88, 88), 90))
        art().upgrade(cache.hit(e.key)!!, 240, 320)
        assertEquals(0, searches())
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
        assertNotNull(cache.miss(key(tag))!!.kept["ytFailed"]?.toLong())
        assertTrue(http.calls.none { it.contains("inc=aliases") })
    }
}
