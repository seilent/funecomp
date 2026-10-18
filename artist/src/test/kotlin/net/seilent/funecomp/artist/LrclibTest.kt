package net.seilent.funecomp.artist

import net.seilent.funecomp.artist.Lrclib.Query
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import kotlin.random.Random

class LrclibTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val time = FakeTime()
    private val wall = 1_800_000_000_000L
    private var now = wall

    private val used = ArrayList<Fixtures>()

    @After
    fun everyUrlHasAFixture() {
        for (f in used) assertEquals(emptyList<String>(), f.missing)
    }

    private fun lrclib(f: Fixtures, on: Boolean = true) = Lrclib(tmp.root, f, "0.1.0", { on }, { now }, time.sleep, { time.now }, Random(1)).also { used += f }

    private val airbag = Query("Airbag", "Radiohead", "OK Computer", 284_200)

    @Test
    fun syncedLyricsWithDurationAndNoAlbum() {
        val f = Fixtures()
        val text = lrclib(f).get(airbag)!!
        assertTrue(text.startsWith("[00:24.24]lr-1486d85ac411-019"))
        assertEquals(listOf("https://lrclib.net/api/get?track_name=Airbag&artist_name=Radiohead&duration=284"), f.calls)
        assertEquals(listOf<String?>("funecomp/0.1.0 (https://github.com/seilent/funecomp)"), f.agents)
        assertEquals(text, lrclib(f).get(airbag))
        assertEquals(1, f.calls.size)
    }

    @Test
    fun noAlbumOrDuration() {
        val f = Fixtures()
        assertTrue(lrclib(f).get(Query("Airbag", "Radiohead", null, null))!!.startsWith("["))
        assertEquals(emptyList<String>(), f.missing)
    }

    @Test
    fun plainOnlyAndInstrumentalAreMisses() {
        val f = Fixtures()
        assertNull(lrclib(f).get(Query("Radio (Demo Taped Remix)", "Sylvan Esso, Demo Taped", "Demo Taped Remixes", 183_000)))
        assertNull(lrclib(f).get(Query("Durga (Instrumental)", "Instrumental", "Instrumental", 721_000)))
        assertEquals(emptyList<String>(), f.missing)
        assertNull(lrclib(f).get(Query("Durga (Instrumental)", "Instrumental", "Instrumental", 721_000)))
        assertEquals(2, f.calls.size)
        assertTrue(f.calls.none { "album_name" in it })
    }

    @Test
    fun notFoundIsAMissForSevenDays() {
        val f = Fixtures()
        val q = Query("Nope Not A Song", "Nobody Here", null, 100_000)
        assertNull(lrclib(f).get(q))
        now += Lrclib.MISS_MS - 1
        assertNull(lrclib(f).get(q))
        assertEquals(1, f.calls.size)
        now += 1
        assertNull(lrclib(f).get(q))
        assertEquals(2, f.calls.size)
        assertEquals("${wall + Lrclib.MISS_MS}\ncleaned=1", tmp.root.resolve("${q.key}.miss").readText())
    }

    @Test
    fun busyHonoursRetryAfterWithJitter() {
        val f = Fixtures()
        val url = "https://lrclib.net/api/get?track_name=Airbag&artist_name=Radiohead&duration=284"
        f.queued[url] = ArrayDeque(listOf(Response(503, ByteArray(0), mapOf("retry-after" to "1")), Response(429, ByteArray(0), mapOf("retry-after" to "2"))))
        assertTrue(lrclib(f).get(airbag)!!.startsWith("["))
        assertEquals(3, f.calls.size)
        val waits = time.sleeps.filter { it > 999 }
        assertEquals(2, waits.size)
        assertTrue(waits[0] in 1000..1300)
        assertTrue(waits[1] in 2000..2600)
    }

    @Test(expected = IOException::class)
    fun unauthorizedThrows() {
        val f = Fixtures()
        f.queued["https://lrclib.net/api/get?track_name=Airbag&artist_name=Radiohead&duration=284"] = ArrayDeque(listOf(Response(401, ByteArray(0))))
        lrclib(f).get(airbag)
    }

    @Test
    fun toggleOffMakesNoCalls() {
        val f = Fixtures()
        assertNull(lrclib(f, on = false).get(airbag))
        assertEquals(emptyList<String>(), f.calls)
    }

    @Test
    fun theSampledTagsGetTheirCapturedLyrics() {
        val f = Fixtures()
        val l = lrclib(f)
        var hits = 0
        for (t in Tags.all()) {
            val before = f.calls.size
            val got = l.get(Query(t.title, t.artist, "MIU", t.durationMs))
            assertEquals(t.title, t.first, got?.lineSequence()?.first())
            val made = f.calls.size - before
            assertEquals(t.title, if (t.attempt == 1 || t.pair == null) 1 else 2, made)
            if (got != null) hits++
        }
        assertEquals(20, hits)
        assertEquals(15, Tags.all().count { it.attempt == 2 })
        assertEquals(emptyList<String>(), f.missing)
        assertTrue(f.calls.none { "album_name" in it })
        val calls = f.calls.size
        for (t in Tags.all()) l.get(Query(t.title, t.artist, "MIU", t.durationMs))
        assertEquals(calls, f.calls.size)
    }

    @Test
    fun anAlbumThatLrclibDoesNotKnowIsNotSent() {
        val f = Fixtures()
        assertTrue(lrclib(f).get(Query("Reckoner", "Radiohead", "MIU", 290_100))!!.startsWith("[00:46.24] lr-e93fbf01611f-004"))
        assertEquals(listOf("https://lrclib.net/api/get?track_name=Reckoner&artist_name=Radiohead&duration=290"), f.calls)
        assertEquals(emptyList<String>(), f.missing)
    }

    private val rainy = Query("rainy lady / しぐれうい", "Shigure Ui", "MIU", 254_630)
    private val rainyTag = url("rainy lady / しぐれうい", "Shigure Ui", 255)
    private val rainySplit = url("rainy lady", "しぐれうい", 255)

    private fun url(track: String, artist: String, s: Int) = "https://lrclib.net/api/get?track_name=${Api.enc(track)}&artist_name=${Api.enc(artist)}&duration=$s"

    private fun stub(track: String, duration: Double, synced: String? = "[00:01.00] line", instrumental: Boolean = false, plain: String? = null): Response {
        val body = "{\"trackName\":\"$track\",\"duration\":$duration,\"instrumental\":$instrumental,\"plainLyrics\":${plain?.let { "\"$it\"" } ?: "null"},\"syncedLyrics\":${synced?.let { "\"$it\"" } ?: "null"}}"
        return Response(200, body.toByteArray())
    }

    @Test
    fun aSplitHitNeedsTheSameTrackNameAndDurationWithinTwoSeconds() {
        val cases = listOf(
            stub("rainy days", 255.0) to false,
            stub("Rainy Lady!", 255.0) to true,
            stub("rainy lady", 257.0) to true,
            stub("rainy lady", 253.0) to true,
            stub("rainy lady", 257.01) to false,
            stub("rainy lady", 252.9) to false,
        )
        for ((r, ok) in cases) {
            tmp.root.listFiles()?.forEach { it.delete() }
            val f = Fixtures()
            f.queued[rainySplit] = ArrayDeque(listOf(r))
            assertEquals(r.text, if (ok) "[00:01.00] line" else null, lrclib(f).get(rainy))
            assertEquals(listOf(rainyTag, rainySplit), f.calls)
            assertEquals(emptyList<String>(), f.missing)
        }
    }

    @Test
    fun plainOnlyAsTaggedStillTriesTheSplit() {
        val f = Fixtures()
        f.queued[rainyTag] = ArrayDeque(listOf(stub("rainy lady / しぐれうい", 255.0, synced = null, plain = "words")))
        assertTrue(lrclib(f).get(rainy)!!.startsWith("[00:14.79] lr-819fd91fdc4b-004"))
        assertEquals(listOf(rainyTag, rainySplit), f.calls)
        assertEquals(emptyList<String>(), f.missing)
    }

    @Test
    fun instrumentalAsTaggedEndsTheLookup() {
        val f = Fixtures()
        f.queued[rainyTag] = ArrayDeque(listOf(stub("rainy lady / しぐれうい", 255.0, synced = null, instrumental = true)))
        assertNull(lrclib(f).get(rainy))
        assertEquals(listOf(rainyTag), f.calls)
        assertTrue(tmp.root.resolve("${rainy.key}.miss").isFile)
    }

    @Test
    fun noSplitWithoutADuration() {
        val f = Fixtures()
        f.queued["https://lrclib.net/api/get?track_name=${Api.enc(rainy.track)}&artist_name=Shigure%20Ui"] = ArrayDeque(listOf(Response(404, ByteArray(0))))
        assertNull(lrclib(f).get(rainy.copy(durationMs = null)))
        assertEquals(1, f.calls.size)
    }

    @Test
    fun aSplitHitIsStoredUnderTheTaggedKey() {
        val f = Fixtures()
        val text = lrclib(f).get(rainy)!!
        assertEquals(text, tmp.root.resolve("${rainy.key}.lrc").readText())
        assertEquals(text, lrclib(f).get(rainy))
        assertEquals(2, f.calls.size)
    }

    @Test
    fun aCleanedMissIsNotFetchedAgainForSevenDays() {
        val f = Fixtures()
        val q = Query("フラジール - ぬゆり covered by 明透", "ASU", "MIU", 275_701)
        assertNull(lrclib(f).get(q))
        assertEquals(2, f.calls.size)
        assertEquals("$wall\ncleaned=1", tmp.root.resolve("${q.key}.miss").readText())
        now += Lrclib.MISS_MS - 1
        assertNull(lrclib(f).get(q))
        assertEquals(2, f.calls.size)
        now += 1
        assertNull(lrclib(f).get(q))
        assertEquals(4, f.calls.size)
        assertEquals(emptyList<String>(), f.missing)
    }

    @Test
    fun anOldMissIsFetchedOnceMoreAndRewritten() {
        val f = Fixtures()
        tmp.root.resolve("${rainy.key}.miss").writeText("$wall")
        assertTrue(lrclib(f).get(rainy)!!.startsWith("[00:14.79]"))
        assertEquals(2, f.calls.size)
        val q = Query("フラジール - ぬゆり covered by 明透", "ASU", "MIU", 275_701)
        val miss = tmp.root.resolve("${q.key}.miss")
        miss.writeText("$wall")
        assertNull(lrclib(f).get(q))
        assertEquals(4, f.calls.size)
        assertEquals("$wall\ncleaned=1", miss.readText())
        assertNull(lrclib(f).get(q))
        assertEquals(4, f.calls.size)
    }

    @Test
    fun anIoErrorInTheSplitLeavesNoMiss() {
        val f = Fixtures()
        f.queued[rainySplit] = ArrayDeque(listOf(Response(401, ByteArray(0))))
        assertThrows(IOException::class.java) { lrclib(f).get(rainy) }
        assertFalse(tmp.root.resolve("${rainy.key}.miss").exists())
        assertTrue(lrclib(f).get(rainy)!!.startsWith("[00:14.79]"))
    }

    @Test
    fun sixTriesWhenBusy() {
        val f = Fixtures()
        val busy = Response(503, ByteArray(0), mapOf("retry-after" to "1"))
        f.queued[airbagUrl] = ArrayDeque(List(5) { busy })
        assertTrue(lrclib(f).get(airbag)!!.startsWith("["))
        assertEquals(6, f.calls.size)
        f.queued[reckonerUrl] = ArrayDeque(List(6) { busy })
        val e = assertThrows(IOException::class.java) { lrclib(f).get(Query("Reckoner", "Radiohead", "MIU", 290_100)) }
        assertTrue(e.message!!.startsWith("busy after 6 tries"))
    }

    private val airbagUrl = "https://lrclib.net/api/get?track_name=Airbag&artist_name=Radiohead&duration=284"
    private val reckonerUrl = "https://lrclib.net/api/get?track_name=Reckoner&artist_name=Radiohead&duration=290"
}
