package net.seilent.funecomp.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AllOffTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val utada = "b539e453-c4fe-47e3-8a07-8517eac74429"
    private val link = "https://example.org/pick.jpg"

    private val fixtures = Fixtures()
    private val denied = ArrayList<String>()
    private val served = ArrayList<String>()
    private var open = false
    private val time = FakeTime()
    private var sources = Sources.NONE
    private val cache by lazy { ArtCache(File(tmp.root, "art")) }
    private val picks by lazy { ArtPicks(File(tmp.root, "artpick.tsv")) }

    private val http = Http { url, h ->
        when {
            open -> fixtures.get(url, h)
            url == link -> {
                served += url
                Response(200, Images.jpeg(Images.photo(900, 900), 92))
            }
            else -> {
                denied += url
                throw AssertionError("request $url")
            }
        }
    }

    private fun art() = ArtistArt(http, AwtCodec(), cache, { sources }, "0.1.0", { time.now }, time, picks = picks).also {
        fixtures.now = { time.now }
    }

    private fun pick(name: String, url: String, kind: String, source: String, mbid: String? = null): ArtPicks.Pick {
        val (k, k2) = ArtPicks.keys(name, null, false)
        return ArtPicks.Pick(k, k2, name, kind, source, url, ImageIds.of(source, url), "", null, mbid, time.now++).also { picks.add(it) }
    }

    @Test
    fun noneIsEveryToggleOff() {
        assertTrue(Sources.NONE.enabled.isEmpty())
        assertFalse(Sources.NONE.anyOn)
        assertEquals(setOf(Wikimedia.SOURCE), Sources.COMMONS.enabled)
        assertTrue(Sources.NONE.copy(fanartTv = true).enabled.isEmpty())
    }

    @Test
    fun everyPathMakesNoRequestAndWritesNoMiss() {
        val a = art()
        a.beginMark()
        assertNull(a.get("Utada Hikaru", null, 384, 480))
        assertNull(a.get("宇多田ヒカル", utada, 384, 480))
        assertNull(a.refresh("Utada Hikaru", null, 384, 480))
        assertNull(a.previewArtist("宇多田ヒカル", utada, 384, 480))
        val c = Collected()
        a.browse("Utada Hikaru", utada, 53, c, Inline)
        assertTrue(c.order.isEmpty())
        assertTrue(c.all().isEmpty())
        assertEquals(Long.MAX_VALUE, a.mark().until)
        assertNull(cache.miss(utada))
        assertNull(cache.miss("name:${Names.fold("Utada Hikaru")}"))
        assertNull(cache.mbid("Utada Hikaru"))
        assertTrue(denied.toString(), denied.isEmpty())
    }

    @Test
    fun aSourceTurnedOnLooksTheArtistUp() {
        assertNull(art().get("Utada Hikaru", null, 384, 480))
        open = true
        sources = Sources.COMMONS
        assertEquals(Wikimedia.SOURCE, art().get("Utada Hikaru", null, 384, 480)!!.source)
        assertTrue(denied.isEmpty())
    }

    @Test
    fun anArtistPickWaitsForASourceThenItsMbidIsLookedUp() {
        pick("TSUKI", utada, ArtPicks.ARTIST, Sources.MUSICBRAINZ, utada)
        assertNull(art().get("TSUKI", null, 384, 480))
        assertNull(art().refresh("TSUKI", null, 384, 480))
        assertTrue(denied.toString(), denied.isEmpty())
        assertEquals(ArtPicks.ARTIST, picks.of(null, "TSUKI")!!.kind)
        open = true
        sources = Sources.COMMONS
        val a = art().get("TSUKI", null, 384, 480)!!
        assertEquals(utada, a.mbid)
        assertEquals(Wikimedia.SOURCE, a.source)
        assertTrue(fixtures.calls.contains("https://musicbrainz.org/ws/2/artist/$utada?inc=url-rels&fmt=json"))
        assertTrue(fixtures.calls.none { it.contains("query=") })
    }

    @Test
    fun aPastedMusicBrainzLinkIsOfferedWithNoRead() {
        val o = (art().offer("https://musicbrainz.org/artist/$utada") as Offered.Ok).offers.single()
        assertEquals(ArtPicks.ARTIST, o.kind)
        assertEquals(Sources.MUSICBRAINZ, o.source)
        assertEquals(utada, o.mbid)
        assertEquals(utada, o.id)
        assertEquals(utada, o.title)
        assertEquals("https://musicbrainz.org/artist/$utada", o.url)
        assertTrue(denied.toString(), denied.isEmpty())
    }

    @Test
    fun anImagePickStillShowsWithItsOneFetch() {
        pick("TSUKI", link, ArtPicks.IMAGE, Sources.LINK)
        val a = art().get("TSUKI", null, 240, 320)
        assertNotNull(a)
        assertEquals(Sources.LINK, a!!.source)
        assertEquals(listOf(link), served)
        assertNotNull(art().get("TSUKI", null, 240, 320))
        assertNotNull(art().refresh("TSUKI", null, 360, 480))
        assertNotNull(art().cachedEntry("TSUKI", null))
        assertEquals(listOf(link), served)
        assertTrue(denied.toString(), denied.isEmpty())
    }
}
