package net.seilent.funecomp.sources

import net.seilent.funekit.artist.ArtCache
import net.seilent.funekit.artist.ArtPicks
import net.seilent.funekit.artist.ArtistArt
import net.seilent.funekit.artist.AwtCodec
import net.seilent.funekit.artist.FakeTime
import net.seilent.funekit.artist.Fixtures
import net.seilent.funekit.artist.Offered
import net.seilent.funekit.artist.PickLink
import net.seilent.funekit.artist.Response
import net.seilent.funekit.artist.TEST_AGENT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class PickTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val avatar = "https://yt3.googleusercontent.com/0cNMCoNqAuBZzzxAwcjYYKMx_5KCtO0X1UYzzuVwaCP_Mf-EwDeYRZRebd-dvR3eJ_19szDpIQ=s900-c-k-c0x00ffffff-no-rj"
    private val channel = "https://www.youtube.com/@tsukiniterasarete"

    private val http = Fixtures()
    private val youtube = YouTubeArt {}
    private val time = FakeTime()
    private var sources = ArtSources.COMMONS
    private var dir = "art"
    private val cache get() = ArtCache(File(tmp.root, dir))
    private val picks by lazy { ArtPicks(File(tmp.root, "artpick.tsv")) }

    private fun art() = ArtistArt(http, AwtCodec(), cache, { sources }, TEST_AGENT, { time.now }, time, picks = picks, extensions = listOf(youtube)).also {
        http.now = { time.now }
    }

    @Test
    fun theYouTubeLinkRows() {
        val yt = "https://www.youtube.com/"
        val ytm = "https://music.youtube.com/"
        val table = mapOf(
            "https://www.youtube.com/@tsukiniterasarete" to YouTubeArt.Channel(yt + "@tsukiniterasarete"),
            "  https://m.youtube.com/@tsukiniterasarete/videos?x=1 " to YouTubeArt.Channel(yt + "@tsukiniterasarete"),
            "http://youtube.com/channel/UCLpqcg79IhmfrSrEC1dvNBw" to YouTubeArt.Channel(yt + "channel/UCLpqcg79IhmfrSrEC1dvNBw"),
            "https://www.youtube.com/user/kobasolo" to YouTubeArt.Channel(yt + "user/kobasolo"),
            "https://www.youtube.com/c/kobasolo/featured" to YouTubeArt.Channel(yt + "c/kobasolo"),
            "https://www.youtube.com/@月" to YouTubeArt.Channel(yt + "@月"),
            "https://www.youtube.com/channel/UCshort" to PickLink.Unsupported,
            "https://www.youtube.com/watch?v=abc" to PickLink.Unsupported,
            "https://youtu.be/@tsukiniterasarete" to PickLink.Unsupported,
            "https://music.youtube.com/channel/UC368BL46Vr1Y2ZbyWXY-ebw" to YouTubeArt.Music(ytm + "channel/UC368BL46Vr1Y2ZbyWXY-ebw", yt + "channel/UC368BL46Vr1Y2ZbyWXY-ebw"),
            "http://music.youtube.com/channel/UC368BL46Vr1Y2ZbyWXY-ebw/releases?si=x" to YouTubeArt.Music(ytm + "channel/UC368BL46Vr1Y2ZbyWXY-ebw", yt + "channel/UC368BL46Vr1Y2ZbyWXY-ebw"),
            "https://music.youtube.com/channel/UC368BL46Vr1Y2ZbyWXY-ebw?si=abc" to YouTubeArt.Music(ytm + "channel/UC368BL46Vr1Y2ZbyWXY-ebw", yt + "channel/UC368BL46Vr1Y2ZbyWXY-ebw"),
            "https://music.youtube.com/browse/UC368BL46Vr1Y2ZbyWXY-ebw" to YouTubeArt.Music(ytm + "channel/UC368BL46Vr1Y2ZbyWXY-ebw", yt + "channel/UC368BL46Vr1Y2ZbyWXY-ebw"),
            "https://music.youtube.com/@tsukiniterasarete" to YouTubeArt.Music(ytm + "@tsukiniterasarete", yt + "@tsukiniterasarete"),
            "https://music.youtube.com/@月" to YouTubeArt.Music(ytm + "@月", yt + "@月"),
            "https://music.youtube.com/watch?v=qwYhKlY4Tx0" to PickLink.Unsupported,
            "https://music.youtube.com/playlist?list=OLAK5uy_lIP2DkJ3PNan7s1VU1PurL-tKQCDkJBv0" to PickLink.Unsupported,
            "https://music.youtube.com/browse/MPREb_aaaaaaaaaaa" to PickLink.Unsupported,
            "https://music.youtube.com/browse/VLPLaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" to PickLink.Unsupported,
            "https://music.youtube.com/search?q=tsuki" to PickLink.Unsupported,
            "https://music.youtube.com/explore" to PickLink.Unsupported,
            "https://music.youtube.com/" to PickLink.Unsupported,
            "https://music.youtube.com/channel/UCshort" to PickLink.Unsupported,
        )
        for ((text, want) in table) assertEquals(text, want, PickLink.parse(text, listOf(youtube)))
    }

    private fun ok(o: Offered) = (o as? Offered.Ok)?.offers?.single() ?: error("refused ${(o as Offered.Refused).reason}")

    private fun refused(o: Offered) = (o as Offered.Refused).reason

    @Test
    fun aChannelLinkOffersTheChannelAvatar() {
        val y = ok(art().offer(channel))
        assertEquals(ArtPicks.IMAGE, y.kind)
        assertEquals(YouTubeArt.SOURCE, y.source)
        assertEquals("月-Tsuki-", y.title)
        assertEquals(avatar, y.url)
        assertEquals(avatar.substringBefore('='), y.id)
        assertNotNull(y.src)
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
        assertEquals(YouTubeArt.SOURCE, music.source)
        assertEquals("youtube music", music.label)
        assertEquals("YOASOBI", music.title)
        assertEquals("https://yt3.googleusercontent.com/8BbiNyyI7TOxlf0x0R-4JZB8x7cw-ML-yOjF8QW8rHSrViZacf-AqD-eqg2ccvbYr5C9URAa_vTJI3k=s1600-rj", music.url)
        assertEquals(music.url.substringBefore('='), music.id)
        assertTrue(Fixtures.gen("1600x900@12").contentEquals(music.src))
        assertEquals(YouTubeArt.SOURCE, www.source)
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
            assertEquals(youtube.imageId(avatar), m.id)
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
        val down = ArtistArt({ _, _ -> throw IOException("offline") }, AwtCodec(), cache, { sources }, TEST_AGENT, { time.now }, time, extensions = listOf(youtube))
        assertEquals(PickLink.UNREACHABLE, refused(down.offer("https://music.youtube.com/@tsukiniterasarete")))
    }

    @Test
    fun theMusicSizeReplacesTheWholeSuffix() {
        assertEquals("https://yt3.googleusercontent.com/a=s1600-rj", YouTube.full("https://yt3.googleusercontent.com/a=w544-c-h544-k-c0x00ffffff-no-l90-rj"))
        assertEquals("https://yt3.googleusercontent.com/b=s1600-rj", YouTube.full("https://yt3.googleusercontent.com/b=w544-h544-p-l90-rj"))
        assertEquals("https://yt3.googleusercontent.com/c=s1600-rj", YouTube.full("https://yt3.googleusercontent.com/c"))
    }
}
