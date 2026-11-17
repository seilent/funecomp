package net.seilent.funecomp.sources

import net.seilent.funecomp.sim.Jpeg
import net.seilent.funekit.artist.Art
import net.seilent.funekit.artist.ArtCache
import net.seilent.funekit.artist.ArtistArt
import net.seilent.funekit.artist.AwtCodec
import net.seilent.funekit.artist.FakeTime
import net.seilent.funekit.artist.Fixtures
import net.seilent.funekit.artist.Images
import net.seilent.funekit.artist.TEST_AGENT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LookupTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val kobasolo = "4981caf4-856a-4a2f-bdf1-3cfd65c6a532"

    private val http = Fixtures()
    private val time = FakeTime()
    private var sources = ArtSources.COMMONS
    private val codec = AwtCodec()
    private val cache by lazy { ArtCache(tmp.newFolder("art")) }

    private fun art() = ArtistArt(http, codec, cache, { sources }, TEST_AGENT, { time.now }, time, extensions = listOf(YouTubeArt {})).also {
        http.now = { time.now }
    }

    private fun checkJpeg(a: Art, w: Int, h: Int) {
        assertEquals(w to h, Images.size(a.jpeg))
        assertTrue("${a.jpeg.size} bytes", a.jpeg.size <= 262_144)
        assertTrue(Jpeg.baseline(a.jpeg, w, h))
    }

    @Test
    fun youtubeChannelAvatarFromTheMusicBrainzRel() {
        assertNull(art().get("コバソロ", kobasolo, 384, 480))
        assertTrue(http.hosts().none { it == "www.youtube.com" })
        sources = YouTubeArt.with(ArtSources.COMMONS, true)
        val a = art().get("コバソロ", kobasolo, 384, 480)!!
        assertEquals(YouTubeArt.SOURCE, a.source)
        checkJpeg(a, 384, 480)
        assertTrue(http.calls.contains("https://www.youtube.com/channel/UCDbQblY1XASbgqOXmy6FOFQ"))
        assertTrue(http.calls.last().startsWith("https://yt3.googleusercontent.com/ytc/AIdro_l2Ct30OyvxG3NA"))
        assertEquals(
            "https://yt3.googleusercontent.com/ytc/AIdro_l2Ct30OyvxG3NA-y_3rn79eJG0sygrJ66vTBxQb0J71xo=s900-c-k-c0x00ffffff-no-rj",
            YouTube.ogImage(Fixtures.text("yt-kobasolo-head.html")),
        )
        assertTrue(http.missing.isEmpty())
    }
}
