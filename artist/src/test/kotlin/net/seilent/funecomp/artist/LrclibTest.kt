package net.seilent.funecomp.artist

import net.seilent.funecomp.artist.Lrclib.Query
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun lrclib(f: Http, on: Boolean = true) = Lrclib(tmp.root, f, "0.1.0", { on }, { now }, time.sleep, { time.now }, Random(1))

    private val airbag = Query("Airbag", "Radiohead", "OK Computer", 284_200)

    @Test
    fun syncedLyricsWithAlbumAndDuration() {
        val f = Fixtures()
        val text = lrclib(f).get(airbag)!!
        assertTrue(text.startsWith("[00:24.24]lr-1486d85ac411-019"))
        assertEquals(listOf("https://lrclib.net/api/get?track_name=Airbag&artist_name=Radiohead&album_name=OK%20Computer&duration=284"), f.calls)
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
    }

    @Test
    fun busyHonoursRetryAfterWithJitter() {
        val f = Fixtures()
        val url = "https://lrclib.net/api/get?track_name=Airbag&artist_name=Radiohead&album_name=OK%20Computer&duration=284"
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
        f.queued["https://lrclib.net/api/get?track_name=Airbag&artist_name=Radiohead&album_name=OK%20Computer&duration=284"] = ArrayDeque(listOf(Response(401, ByteArray(0))))
        lrclib(f).get(airbag)
    }

    @Test
    fun toggleOffMakesNoCalls() {
        val f = Fixtures()
        assertNull(lrclib(f, on = false).get(airbag))
        assertEquals(emptyList<String>(), f.calls)
    }
}
