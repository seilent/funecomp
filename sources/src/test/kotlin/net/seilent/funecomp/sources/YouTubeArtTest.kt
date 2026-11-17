package net.seilent.funecomp.sources

import net.seilent.funekit.artist.Api
import net.seilent.funekit.artist.ArtExtension
import net.seilent.funekit.artist.AwtCodec
import net.seilent.funekit.artist.Candidate
import net.seilent.funekit.artist.Credit
import net.seilent.funekit.artist.FakeTime
import net.seilent.funekit.artist.Fixtures
import net.seilent.funekit.artist.Hosts
import net.seilent.funekit.artist.MusicBrainz
import net.seilent.funekit.artist.Offered
import net.seilent.funekit.artist.TEST_AGENT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeArtTest {
    private val kobasolo = "4981caf4-856a-4a2f-bdf1-3cfd65c6a532"
    private val http = Fixtures()
    private val time = FakeTime()
    private val host = object : ArtExtension.Host {
        override val api = Api(http, TEST_AGENT.artUserAgent, Hosts(time, YouTubeArt {}.limits))
        override val mb = MusicBrainz(api)
        override val codec = AwtCodec()

        override fun image(source: String, url: String, id: String, credit: Credit?, title: String, label: String): Offered = error("no image")
    }

    init {
        http.now = { time.now }
    }

    @Test
    fun theSourceAndItsSections() {
        val y = YouTubeArt {}
        assertEquals("youtube", y.source)
        assertEquals(listOf("youtube", "youtube search"), y.sections)
        assertEquals(listOf("youtube", "youtubeImages"), y.limits.map { it.key })
        assertFalse(y.identify("UC5CwaMl1eIgY8h02uZw7u8A"))
        assertTrue(y.identify("Suisei Channel"))
    }

    @Test
    fun noArtistGivesNoCandidate() {
        assertEquals(emptyList<Candidate>(), YouTubeArt {}.candidates(kobasolo, null, host).toList())
        assertEquals(emptyList<String>(), http.calls)
    }

    @Test
    fun candidatesAndTheAvatarCarryTheirImageId() {
        val y = YouTubeArt {}
        val c = y.candidates(kobasolo, host.mb.artist(kobasolo), host).first()
        assertEquals(YouTubeArt.SOURCE, c.source)
        assertEquals("https://yt3.googleusercontent.com/ytc/AIdro_l2Ct30OyvxG3NA-y_3rn79eJG0sygrJ66vTBxQb0J71xo", c.id)
        assertEquals(c.url.substringBefore('='), c.id)
        val r = y.resolve("UC3Ruo_5doyu514PesWGvCAg", emptySet(), HashMap(), host)!!
        val avatar = r.avatar!!
        assertEquals("UC3Ruo_5doyu514PesWGvCAg", r.tag)
        assertEquals(avatar.url.substringBefore('='), avatar.id)
        assertTrue(avatar.url.contains("=s900"))
        assertTrue("missing ${http.missing}", http.missing.isEmpty())
    }
}
