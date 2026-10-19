package net.seilent.funecomp

import net.seilent.funecomp.artist.PickLink
import net.seilent.funecomp.artist.YouTube
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PickLinkTest {
    private val yt = "https://www.youtube.com/"

    @Test
    fun theLinkTableParsesOnTheDevice() {
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
            "https://musicbrainz.org/artist/91352441-9F79-48c2-81ad-ccdd1e46915a/relationships" to PickLink.MusicBrainz("91352441-9f79-48c2-81ad-ccdd1e46915a"),
            "https://beta.musicbrainz.org/artist/91352441-9f79-48c2-81ad-ccdd1e46915a" to PickLink.MusicBrainz("91352441-9f79-48c2-81ad-ccdd1e46915a"),
            "https://commons.wikimedia.org/wiki/File:Hikaru_Utada_at_Spotify_Tokyo.png" to PickLink.Commons("Hikaru Utada at Spotify Tokyo.png"),
            "https://upload.wikimedia.org/wikipedia/commons/thumb/b/b3/Hikaru_Utada_at_Spotify_Tokyo.png/800px-Hikaru_Utada_at_Spotify_Tokyo.png" to PickLink.Commons("Hikaru Utada at Spotify Tokyo.png"),
            "https://open.spotify.com/artist/2yJbiseBZFvrCK5GueLnk7" to PickLink.Unsupported,
            "https://x.com/tsukiuk" to PickLink.Unsupported,
            "http://example.org/a.jpg" to PickLink.Direct("https://example.org/a.jpg"),
            "TSUKI" to PickLink.Unsupported,
            "https://exa mple.org/a.jpg" to PickLink.Unsupported,
        )
        for ((text, want) in table) assertEquals(text, want, PickLink.parse(text))
    }

    @Test
    fun theChannelPageParsersRunOnTheDevice() {
        val head = "<html><head><meta property=\"og:title\" content=\"月-Tsuki-\"><meta property=\"og:image\" content=\"https://yt3.googleusercontent.com/a=s900-c-k-c0x00ffffff-no-rj\"></head></html>"
        assertEquals("月-Tsuki-", YouTube.ogTitle(head))
        assertEquals("https://yt3.googleusercontent.com/a=s900-c-k-c0x00ffffff-no-rj", YouTube.ogImage(head))
        assertEquals("https://yt3.googleusercontent.com/a=s176-c-k-c0x00ffffff-no-rj", YouTube.sized(YouTube.ogImage(head)!!, 176))
    }

    @Test
    fun theSearchParserRunsOnTheDevice() {
        val html = "<script nonce=\"x\">var ytInitialData = {\"contents\":{\"a\":[{\"channelRenderer\":{\"channelId\":\"UC368BL46Vr1Y2ZbyWXY-ebw\",\"title\":{\"simpleText\":\"月-Tsuki-\"}," +
            "\"thumbnail\":{\"thumbnails\":[{\"url\":\"//yt3.ggpht.com/k=s88-c\"},{\"url\":\"//yt3.ggpht.com/k=s176-c\"}]}}}]}};</script>"
        val c = YouTube.channels(html)!!.single()
        assertEquals("UC368BL46Vr1Y2ZbyWXY-ebw", c.id)
        assertEquals("月-Tsuki-", c.title)
        assertEquals("https://yt3.ggpht.com/k=s176-c", c.thumb)
        assertNull(YouTube.channels("<html>consent</html>"))
    }
}
