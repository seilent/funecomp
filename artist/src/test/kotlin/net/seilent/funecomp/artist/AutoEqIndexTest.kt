package net.seilent.funecomp.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class AutoEqIndexTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val time = FakeTime()

    private fun index(http: Http, dir: File = tmp.root) =
        AutoEqIndex(dir, http, "0.1.0", clock = { time.now }, sleep = time.sleep, mono = { time.now })

    @Test
    fun searchAndProfile() {
        val f = Fixtures()
        val ix = index(f)
        val hits = ix.search("hd 650")
        assertEquals(6, hits.size)
        assertEquals("Sennheiser HD 650", hits[0].name)
        assertEquals("Sennheiser HD 650 (2020, worn earpads)", hits[2].name)
        assertEquals(
            "${AutoEqIndex.BASE}/crinacle/GRAS%2043AG-7%20over-ear/Sennheiser%20HD%20650%20(2020,%20worn%20earpads)/Sennheiser%20HD%20650%20(2020,%20worn%20earpads)%20ParametricEQ.txt",
            hits[2].url,
        )
        assertTrue(ix.profile(hits[0])!!.startsWith("Preamp: -6.1 dB"))
        assertEquals(listOf("SA02", "SA03", "SA05").map { "1Custom $it" }, ix.search("1custom").map { it.name })
        assertEquals(emptyList<String>(), f.missing)
        assertTrue(f.agents.all { it == "funecomp/0.1.0 (https://github.com/seilent/funecomp)" })
    }

    @Test
    fun indexCachedThirtyDays() {
        val f = Fixtures()
        index(f).search("hd 650")
        File(tmp.root, AutoEqIndex.INDEX).setLastModified(time.now)
        time.now += AutoEqIndex.MAX_AGE_MS - 1
        assertEquals(6, index(f).search("hd 650").size)
        assertEquals(1, f.calls.size)
        time.now += 1
        index(f).search("hd 650")
        assertEquals(2, f.calls.size)
    }

    @Test
    fun staleIndexUsedWhenOffline() {
        index(Fixtures()).search("hd")
        time.now += AutoEqIndex.MAX_AGE_MS * 2
        val down = Http { url, _ -> throw IOException("offline $url") }
        assertEquals(6, index(down).search("hd 650").size)
    }

    @Test(expected = IOException::class)
    fun noIndexOfflineThrows() {
        index(Http { url, _ -> throw IOException("offline $url") }).search("hd 650")
    }

    @Test
    fun blankQueryMakesNoCalls() {
        val f = Fixtures()
        assertEquals(emptyList<AutoEqIndex.Entry>(), index(f).search("  "))
        assertEquals(emptyList<String>(), f.calls)
    }
}
