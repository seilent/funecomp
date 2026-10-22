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
import java.io.File
import java.io.IOException

class PickTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dj = "91352441-9f79-48c2-81ad-ccdd1e46915a"
    private val utada = "b539e453-c4fe-47e3-8a07-8517eac74429"
    private val avatar = "https://yt3.googleusercontent.com/0cNMCoNqAuBZzzxAwcjYYKMx_5KCtO0X1UYzzuVwaCP_Mf-EwDeYRZRebd-dvR3eJ_19szDpIQ=s900-c-k-c0x00ffffff-no-rj"
    private val channel = "https://www.youtube.com/@tsukiniterasarete"

    private val http = Fixtures()
    private val time = FakeTime()
    private var sources = Sources.NONE
    private var dir = "art"
    private val cache get() = ArtCache(File(tmp.root, dir))
    private val picks by lazy { ArtPicks(File(tmp.root, "artpick.tsv")) }

    private fun art() = ArtistArt(http, AwtCodec(), cache, { sources }, "0.1.0", { time.now }, time, picks = picks).also {
        http.now = { time.now }
    }

    private fun pick(name: String = "TSUKI", mbid: String? = null, url: String = avatar, library: Boolean = false, kind: String = ArtPicks.IMAGE, source: String = Sources.YOUTUBE): ArtPicks.Pick {
        val (k, k2) = ArtPicks.keys(name, mbid, library)
        return ArtPicks.Pick(k, k2, name, kind, source, url, ImageIds.of(source, url), "", null, if (kind == ArtPicks.ARTIST) url else Names.mbid(mbid), time.now++).also { picks.add(it) }
    }

    private fun mb() = http.calls.filter { it.contains("musicbrainz.org") }

    @Test
    fun aPickServesThePickedImageWithNoMusicBrainzCall() {
        pick()
        val a = art().get("TSUKI", null, 240, 320)!!
        assertEquals(Sources.YOUTUBE, a.source)
        assertNull(a.mbid)
        assertFalse(a.wrongArtist)
        assertEquals(listOf(avatar), http.calls)
        assertEquals(ArtPicks.PREFIX + "name:tsuki", art().cachedEntry("TSUKI", null)!!.key)
        assertTrue("missing ${http.missing}", http.missing.isEmpty())
    }

    @Test
    fun anMbidKeyServesOnlyThatTagAndALibraryPickAlsoTheName() {
        pick(mbid = dj)
        assertEquals(dj, art().get("TSUKI", dj, 240, 320)!!.mbid)
        assertNull(picks.of(null, "TSUKI"))
        val both = pick(mbid = dj, library = true)
        assertEquals(listOf(dj, "name:tsuki"), both.keys)
        assertEquals(1, picks.list().size)
        assertEquals(both, picks.of(null, "tsuki"))
        assertEquals(Sources.YOUTUBE, art().get("TSUKI", null, 240, 320)!!.source)
        assertTrue(mb().isEmpty())
    }

    @Test
    fun theChainHitForTheResolvedMbidStaysAfterAPick() {
        sources = Sources.NONE.copy(discogs = true)
        val chain = art().get("Tsuki", dj, 240, 320)!!
        assertEquals(Sources.DISCOGS, chain.source)
        val hit = cache.hit(dj)!!
        pick()
        assertEquals(Sources.YOUTUBE, art().get("TSUKI", null, 240, 320)!!.source)
        assertEquals(hit.at, cache.hit(dj)!!.at)
        assertEquals(Sources.DISCOGS, art().get("Tsuki", dj, 240, 320)!!.source)
        assertEquals(Sources.DISCOGS, art().cachedEntry("Tsuki", dj)!!.source)
        assertTrue("missing ${http.missing}", http.missing.isEmpty())
    }

    @Test
    fun aPickShowsWithItsToggleOffAndAfterAReorder() {
        sources = Sources.NONE.copy(youtube = false)
        pick()
        assertEquals(Sources.YOUTUBE, art().get("TSUKI", null, 240, 320)!!.source)
        sources = Sources.NONE.copy(order = Sources.NONE.order.reversed())
        assertEquals(Sources.YOUTUBE, art().refresh("TSUKI", null, 240, 320)!!.source)
        assertEquals(Sources.YOUTUBE, art().cachedEntry("TSUKI", null)!!.source)
        assertEquals(1, http.calls.size)
    }

    @Test
    fun aRestoreFetchesTheUrlOnce() {
        pick()
        art().get("TSUKI", null, 240, 320)
        dir = "restored"
        val saved = File(tmp.root, "artpick.tsv").also { picks.save() }.readText()
        val restored = ArtPicks(File(tmp.root, "artpick.tsv"))
        assertEquals(saved, restored.encode())
        val a = ArtistArt(http, AwtCodec(), cache, { sources }, "0.1.0", { time.now }, time, picks = restored)
        assertNotNull(a.get("TSUKI", null, 240, 320))
        assertNotNull(a.get("TSUKI", null, 360, 480))
        assertNotNull(a.get("TSUKI", null, 240, 320))
        assertEquals(2, http.calls.count { it == avatar })
    }

    @Test
    fun aMissingLinkSetsFailedAtAndRetriesAfterTheTtl() {
        val gone = "https://example.org/gone.jpg"
        val p = pick(url = gone, source = Sources.LINK)
        val a = art()
        a.beginMark()
        assertNull(a.get("TSUKI", null, 240, 320))
        val failed = picks.of(null, "TSUKI")!!.failedAt!!
        assertEquals(failed + ArtistArt.TTL_MS, a.mark().until)
        assertEquals(failed, ArtPicks(File(tmp.root, "artpick.tsv")).of(null, "TSUKI")!!.failedAt)
        time.now += ArtistArt.TTL_MS - 1
        assertNull(a.get("TSUKI", null, 240, 320))
        assertEquals(1, http.calls.count { it == gone })
        assertTrue(mb().isEmpty())
        time.now += 1
        http.queued[gone] = ArrayDeque(listOf(Response(200, Images.jpeg(Images.photo(900, 900), 92))))
        assertNotNull(a.get("TSUKI", null, 240, 320))
        assertEquals(2, http.calls.count { it == gone })
        assertNull(picks.of(null, p.name)!!.failedAt)
    }

    @Test
    fun aBusyOrTimedOutFetchThrowsAndWritesNoFailedAt() {
        val url = "https://example.org/busy.jpg"
        pick(url = url, source = Sources.LINK)
        http.queued[url] = ArrayDeque(List(4) { Response(503, ByteArray(0)) })
        try {
            art().get("TSUKI", null, 240, 320)
            fail()
        } catch (e: IOException) {
        }
        assertNull(picks.of(null, "TSUKI")!!.failedAt)
        val down = ArtistArt({ _, _ -> throw IOException("timeout") }, AwtCodec(), cache, { sources }, "0.1.0", { time.now }, time, picks = picks)
        try {
            down.get("TSUKI", null, 240, 320)
            fail()
        } catch (e: IOException) {
        }
        assertNull(picks.of(null, "TSUKI")!!.failedAt)
        assertFalse(File(tmp.root, "artpick.tsv").exists())
    }

    @Test
    fun undecodableOrSmallBytesAreADefiniteFailure() {
        val url = "https://example.org/x.jpg"
        pick(url = url, source = Sources.LINK)
        http.queued[url] = ArrayDeque(listOf(Response(200, "<html></html>".toByteArray())))
        assertNull(art().get("TSUKI", null, 240, 320))
        assertNotNull(picks.of(null, "TSUKI")!!.failedAt)
        pick(name = "Other", url = url, source = Sources.LINK)
        http.queued[url] = ArrayDeque(listOf(Response(200, Images.jpeg(Images.photo(300, 300), 92))))
        assertNull(art().get("Other", null, 240, 320))
        assertNotNull(picks.of(null, "Other")!!.failedAt)
    }

    @Test
    fun anArtistPickPinsTheMbidOnEveryPath() {
        cache.putMbid("TSUKI", dj, time.now)
        pick(url = utada, kind = ArtPicks.ARTIST, source = Sources.MUSICBRAINZ)
        val a = art().get("TSUKI", null, 384, 480)!!
        assertEquals(utada, a.mbid)
        assertFalse(a.wrongArtist)
        assertEquals(utada, art().cachedEntry("TSUKI", null)!!.key)
        assertEquals(utada, art().keyOf("TSUKI", null))
        assertEquals("", cache.hit(utada)!!.resolvedBy)
        assertFalse(art().wrongArtistOffered(cache.hit(utada)!!, "TSUKI"))
        assertEquals(dj, cache.mbid("TSUKI")!!.mbid)
        assertTrue(http.calls.none { it.contains("query=") })
        assertEquals(utada, art().refresh("TSUKI", null, 384, 480)!!.mbid)
        assertTrue("missing ${http.missing}", http.missing.isEmpty())
    }

    @Test
    fun aNonePickShowsNothingAndRunsNoChain() {
        val p = pick()
        art().get("TSUKI", null, 240, 320)
        picks.add(p.copy(kind = ArtPicks.NONE, was = p.kind))
        cache.drop(p.cacheKey)
        val calls = http.calls.size
        assertNull(art().get("TSUKI", null, 240, 320))
        assertNull(art().cachedEntry("TSUKI", null))
        assertEquals(calls, http.calls.size)
        assertEquals(p.copy(kind = ArtPicks.NONE, was = ArtPicks.IMAGE), picks.none("name:tsuki"))
    }

    @Test
    fun aPickEntryIsNeverBlockedAndIsACandidate() {
        val p = pick()
        art().get("TSUKI", null, 240, 320)
        val e = cache.hit(p.cacheKey)!!
        val blocks = ArtBlocks()
        blocks.add(ArtBlocks.Block("name:tsuki", null, e.source, ImageIds.of(e), cache.sha(e), "TSUKI", time.now))
        val a = ArtistArt(http, AwtCodec(), cache, { sources }, "0.1.0", { time.now }, time, blocks = blocks, picks = picks)
        assertFalse(a.blocked(e))
        assertTrue(a.candidates("TSUKI", null).any { it.key == p.cacheKey })
    }

    @Test
    fun recordsRoundTripAndABadLineIsSkipped() {
        val f = File(tmp.root, "artpick.tsv")
        assertTrue(ArtPicks(f).list().isEmpty())
        val c = Credit("A\tB", "CC BY 4.0", "https://commons.wikimedia.org/wiki/File:X.jpg", "https://creativecommons.org/licenses/by/4.0")
        val p = ArtPicks.Pick(utada, "name:utada", "Utada\nH", ArtPicks.IMAGE, Wikimedia.SOURCE, "https://upload.wikimedia.org/x.jpg", "File:X.jpg", "ab", c, utada, 5, 9)
        picks.add(p)
        picks.add(ArtPicks.Pick("name:x", null, "x", ArtPicks.NONE, Sources.LINK, "https://e/x.jpg", "https://e/x.jpg", "", null, null, 6, null, ArtPicks.IMAGE))
        picks.save()
        f.appendText("pick\ttorn\n")
        val skipped = ArrayList<String>()
        val back = ArtPicks(f) { skipped += it }
        assertEquals(picks.list(), back.list())
        assertEquals(1, skipped.size)
    }

    @Test
    fun addPushKeepsOnePushPerFoldedName() {
        val b = ArtBlocks()
        b.addPush("Tsuki", dj)
        b.addPush("TSUKI", null)
        assertEquals(listOf(ArtBlocks.Push("TSUKI", null)), b.pushes("dev"))
    }

    @Test
    fun theLinkTable() {
        val yt = "https://www.youtube.com/"
        val ytm = "https://music.youtube.com/"
        val table = mapOf(
            "https://www.youtube.com/@tsukiniterasarete" to PickLink.YouTube(yt + "@tsukiniterasarete"),
            "  https://m.youtube.com/@tsukiniterasarete/videos?x=1 " to PickLink.YouTube(yt + "@tsukiniterasarete"),
            "http://youtube.com/channel/UCLpqcg79IhmfrSrEC1dvNBw" to PickLink.YouTube(yt + "channel/UCLpqcg79IhmfrSrEC1dvNBw"),
            "https://www.youtube.com/user/kobasolo" to PickLink.YouTube(yt + "user/kobasolo"),
            "https://www.youtube.com/c/kobasolo/featured" to PickLink.YouTube(yt + "c/kobasolo"),
            "https://www.youtube.com/@月" to PickLink.YouTube(yt + "@月"),
            "https://www.youtube.com/channel/UCshort" to PickLink.Unsupported,
            "https://www.youtube.com/watch?v=abc" to PickLink.Unsupported,
            "https://youtu.be/@tsukiniterasarete" to PickLink.Unsupported,
            "https://music.youtube.com/channel/UC368BL46Vr1Y2ZbyWXY-ebw" to PickLink.YouTubeMusic(ytm + "channel/UC368BL46Vr1Y2ZbyWXY-ebw", yt + "channel/UC368BL46Vr1Y2ZbyWXY-ebw"),
            "http://music.youtube.com/channel/UC368BL46Vr1Y2ZbyWXY-ebw/releases?si=x" to PickLink.YouTubeMusic(ytm + "channel/UC368BL46Vr1Y2ZbyWXY-ebw", yt + "channel/UC368BL46Vr1Y2ZbyWXY-ebw"),
            "https://music.youtube.com/channel/UC368BL46Vr1Y2ZbyWXY-ebw?si=abc" to PickLink.YouTubeMusic(ytm + "channel/UC368BL46Vr1Y2ZbyWXY-ebw", yt + "channel/UC368BL46Vr1Y2ZbyWXY-ebw"),
            "https://music.youtube.com/browse/UC368BL46Vr1Y2ZbyWXY-ebw" to PickLink.YouTubeMusic(ytm + "channel/UC368BL46Vr1Y2ZbyWXY-ebw", yt + "channel/UC368BL46Vr1Y2ZbyWXY-ebw"),
            "https://music.youtube.com/@tsukiniterasarete" to PickLink.YouTubeMusic(ytm + "@tsukiniterasarete", yt + "@tsukiniterasarete"),
            "https://music.youtube.com/@月" to PickLink.YouTubeMusic(ytm + "@月", yt + "@月"),
            "https://music.youtube.com/watch?v=qwYhKlY4Tx0" to PickLink.Unsupported,
            "https://music.youtube.com/playlist?list=OLAK5uy_lIP2DkJ3PNan7s1VU1PurL-tKQCDkJBv0" to PickLink.Unsupported,
            "https://music.youtube.com/browse/MPREb_aaaaaaaaaaa" to PickLink.Unsupported,
            "https://music.youtube.com/browse/VLPLaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" to PickLink.Unsupported,
            "https://music.youtube.com/search?q=tsuki" to PickLink.Unsupported,
            "https://music.youtube.com/explore" to PickLink.Unsupported,
            "https://music.youtube.com/" to PickLink.Unsupported,
            "https://music.youtube.com/channel/UCshort" to PickLink.Unsupported,
            "https://musicbrainz.org/artist/91352441-9F79-48c2-81ad-ccdd1e46915a/relationships" to PickLink.MusicBrainz(dj),
            "https://beta.musicbrainz.org/artist/$dj" to PickLink.MusicBrainz(dj),
            "https://musicbrainz.org/release/$dj" to PickLink.Unsupported,
            "https://commons.wikimedia.org/wiki/File:Hikaru_Utada_at_Spotify_Tokyo.png" to PickLink.Commons("Hikaru Utada at Spotify Tokyo.png"),
            "https://upload.wikimedia.org/wikipedia/commons/thumb/b/b3/Hikaru_Utada_at_Spotify_Tokyo.png/800px-Hikaru_Utada_at_Spotify_Tokyo.png" to PickLink.Commons("Hikaru Utada at Spotify Tokyo.png"),
            "https://upload.wikimedia.org/wikipedia/commons/b/b3/Hikaru_Utada_at_Spotify_Tokyo.png" to PickLink.Commons("Hikaru Utada at Spotify Tokyo.png"),
            "https://commons.wikimedia.org/wiki/Main_Page" to PickLink.Unsupported,
            "https://open.spotify.com/artist/2yJbiseBZFvrCK5GueLnk7" to PickLink.Unsupported,
            "https://x.com/tsukiuk" to PickLink.Unsupported,
            "https://example.org/a.jpg?w=1" to PickLink.Direct("https://example.org/a.jpg?w=1"),
            "http://example.org/a.jpg" to PickLink.Direct("https://example.org/a.jpg"),
            "TSUKI" to PickLink.Unsupported,
            "ftp://example.org/a.jpg" to PickLink.Unsupported,
            "https://exa mple.org/a.jpg" to PickLink.Unsupported,
        )
        for ((text, want) in table) assertEquals(text, want, PickLink.parse(text))
    }

    private fun ok(o: Offered) = (o as? Offered.Ok)?.offers?.single() ?: error("refused ${(o as Offered.Refused).reason}")

    private fun refused(o: Offered) = (o as Offered.Refused).reason

    @Test
    fun offersFromEachKindOfLink() {
        val y = ok(art().offer(channel))
        assertEquals(ArtPicks.IMAGE, y.kind)
        assertEquals(Sources.YOUTUBE, y.source)
        assertEquals("月-Tsuki-", y.title)
        assertEquals(avatar, y.url)
        assertEquals(avatar.substringBefore('='), y.id)
        assertNotNull(y.src)
        val m = ok(art().offer("https://musicbrainz.org/artist/$dj"))
        assertEquals(ArtPicks.ARTIST, m.kind)
        assertEquals(dj, m.mbid)
        assertEquals("Tsuki", m.title)
        assertEquals(PickLink.NOT_FOUND, refused(art().offer("https://musicbrainz.org/artist/00000000-0000-0000-0000-000000000000")))
        val c = ok(art().offer("https://commons.wikimedia.org/wiki/File:Hikaru_Utada_at_Spotify_Tokyo.png"))
        assertEquals(Wikimedia.SOURCE, c.source)
        assertEquals("File:Hikaru Utada at Spotify Tokyo.png", c.id)
        assertEquals("CC BY 4.0", c.credit!!.license)
        assertEquals(PickLink.UNSUPPORTED, refused(art().offer("https://open.spotify.com/artist/2yJbiseBZFvrCK5GueLnk7")))
        assertEquals(listOf("https://musicbrainz.org/ws/2/artist/00000000-0000-0000-0000-000000000000?inc=url-rels&fmt=json"), http.missing)
    }

    @Test
    fun directLinksAreCheckedAtTheLargestBackdrop() {
        val url = "https://example.org/a.jpg"
        http.queued[url] = ArrayDeque(
            listOf(
                Response(200, Images.jpeg(Images.photo(300, 300), 92)),
                Response(200, "<html>".toByteArray()),
                Response(200, Images.jpeg(Images.photo(360, 480), 92)),
            ),
        )
        assertEquals(PickLink.TOO_SMALL, refused(art().offer(url)))
        assertEquals(PickLink.NOT_IMAGE, refused(art().offer(url)))
        val d = ok(art().offer(url))
        assertEquals(Sources.LINK, d.source)
        assertEquals(url, d.id)
        assertEquals("a.jpg", d.title)
        assertEquals(PickLink.UNREACHABLE, refused(art().offer("https://example.org/404.jpg")))
        assertEquals(PickLink.UNREACHABLE, refused(ArtistArt({ _, _ -> throw IOException("offline") }, AwtCodec(), cache, { sources }, "0.1.0", { time.now }, time).offer(url)))
    }

    @Test
    fun aYouTubePageWithNoImageAndAnUnreachablePage() {
        http.queued[channel] = ArrayDeque(listOf(Response(200, "<html></html>".toByteArray())))
        assertEquals(PickLink.NOT_IMAGE, refused(art().offer(channel)))
        assertEquals(PickLink.UNREACHABLE, refused(art().offer("https://www.youtube.com/@nobody")))
    }

    @Test
    fun aYouTubeMusicLinkOffersThePageImageAndTheChannelAvatar() {
        val topic = "UCI6B8NkZKqlFWoiC_xE-hzA"
        val o = art().offer("https://music.youtube.com/channel/$topic?si=x") as Offered.Ok
        assertEquals(2, o.offers.size)
        val (music, www) = o.offers
        assertEquals(Sources.YOUTUBE, music.source)
        assertEquals("youtube music", music.label)
        assertEquals("YOASOBI", music.title)
        assertEquals("https://yt3.googleusercontent.com/8BbiNyyI7TOxlf0x0R-4JZB8x7cw-ML-yOjF8QW8rHSrViZacf-AqD-eqg2ccvbYr5C9URAa_vTJI3k=s1600-rj", music.url)
        assertEquals(music.url.substringBefore('='), music.id)
        assertTrue(Fixtures.gen("1600x900@12").contentEquals(music.src))
        assertEquals(Sources.YOUTUBE, www.source)
        assertEquals("youtube", www.label)
        assertEquals("YOASOBI - トピック", www.title)
        assertEquals("https://yt3.googleusercontent.com/y6q758b0MjT6HIK2YIqUY0Vn3YanapJBTYIYEYHZmSqehYW_dpPGvrM2FhHCqiIqciQznTq7KJI=s900-c-k-c0x00ffffff-no-rj", www.url)
        assertEquals(listOf("https://music.youtube.com/channel/$topic", "https://www.youtube.com/channel/$topic"), http.calls.filter { "youtube.com/" in it })
    }

    @Test
    fun anOwnChannelGivesOneImageSharedWithTheChannelAvatar() {
        for (link in listOf("https://music.youtube.com/channel/UC368BL46Vr1Y2ZbyWXY-ebw", "https://music.youtube.com/@tsukiniterasarete")) {
            val o = art().offer(link) as Offered.Ok
            val m = o.offers.single()
            assertEquals("youtube music", m.label)
            assertEquals("月-Tsuki-", m.title)
            assertEquals("https://yt3.googleusercontent.com/0cNMCoNqAuBZzzxAwcjYYKMx_5KCtO0X1UYzzuVwaCP_Mf-EwDeYRZRebd-dvR3eJ_19szDpIQ=s1600-rj", m.url)
            assertEquals(ImageIds.of(Sources.YOUTUBE, avatar), m.id)
            assertTrue(Fixtures.gen("747x748@11").contentEquals(m.src))
        }
        assertEquals(emptyList<String>(), http.missing)
    }

    @Test
    fun aYouTubeMusicPageWithNoImageIsNotFoundAndAFailedLoadIsUnreachable() {
        assertEquals(PickLink.NOT_FOUND, refused(art().offer("https://music.youtube.com/channel/UCzzzzzzzzzzzzzzzzzzzzzz")))
        assertEquals(listOf("https://music.youtube.com/channel/UCzzzzzzzzzzzzzzzzzzzzzz"), http.calls)
        assertEquals(PickLink.UNREACHABLE, refused(art().offer("https://music.youtube.com/@nobody")))
        assertEquals(PickLink.UNSUPPORTED, refused(art().offer("https://music.youtube.com/watch?v=qwYhKlY4Tx0")))
        val down = ArtistArt({ _, _ -> throw IOException("offline") }, AwtCodec(), cache, { sources }, "0.1.0", { time.now }, time)
        assertEquals(PickLink.UNREACHABLE, refused(down.offer("https://music.youtube.com/@tsukiniterasarete")))
    }

    @Test
    fun theMusicSizeReplacesTheWholeSuffix() {
        assertEquals("https://yt3.googleusercontent.com/a=s1600-rj", YouTube.full("https://yt3.googleusercontent.com/a=w544-c-h544-k-c0x00ffffff-no-l90-rj"))
        assertEquals("https://yt3.googleusercontent.com/b=s1600-rj", YouTube.full("https://yt3.googleusercontent.com/b=w544-h544-p-l90-rj"))
        assertEquals("https://yt3.googleusercontent.com/c=s1600-rj", YouTube.full("https://yt3.googleusercontent.com/c"))
    }

    @Test
    fun aReplacedPickStoresNoBytes() {
        val p = pick()
        val next = pick(url = "https://example.org/b.jpg", source = Sources.LINK)
        val a = art()
        assertNull(a.store(p, Images.jpeg(Images.photo(900, 900), 92)))
        assertNull(cache.hit(p.cacheKey))
        assertNotNull(a.store(next, Images.jpeg(Images.photo(900, 900), 92)))
        assertEquals("https://example.org/b.jpg", cache.hit(next.cacheKey)!!.url)
    }
}
