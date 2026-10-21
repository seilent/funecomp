package net.seilent.funecomp.artist

import net.seilent.funecomp.artist.Lrclib.Found
import net.seilent.funecomp.artist.Lrclib.Query
import net.seilent.funecomp.artist.LyricChain.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class LyricChainTest {
    private val q = Query("t", "a", null, 200_000)
    private val asked = ArrayList<String>()
    private val lrSynced = Found("[00:01.00]l", true)
    private val lrPlain = Found("l", false)
    private val neSynced = Found("[00:01.00]n", true, LyricSources.NETEASE)
    private val nePlain = Found("n", false, LyricSources.NETEASE)

    private fun chain(lr: () -> Found?, ne: () -> Found?, sources: LyricSources = LyricSources(), skip: (String) -> Boolean = { false }) =
        LyricChain(sources, { asked += LyricSources.LRCLIB; lr() }, { asked += LyricSources.NETEASE; ne() }, skip)

    @Test
    fun defaultsAreBothOnLrclibFirst() {
        assertEquals(LyricSources(true, true, listOf("lrclib", "netease")), LyricSources())
        assertEquals(listOf("netease", "lrclib"), LyricSources.order("netease"))
        assertEquals(listOf("lrclib", "netease"), LyricSources.order(null))
        assertEquals(listOf("netease", "lrclib"), LyricSources().moved("netease", -1).order)
        assertEquals(LyricSources(), LyricSources().moved("lrclib", -1))
        assertEquals(listOf("netease"), LyricSources(lrclib = false).ordered)
    }

    @Test
    fun lrclibSyncedWinsAndNeteaseIsNotAsked() {
        val c = chain({ lrSynced }, { neSynced }).get(q)
        assertEquals(lrSynced, c.found)
        assertEquals(listOf("lrclib"), asked)
        assertEquals(mapOf("lrclib" to Outcome.Answered, "netease" to Outcome.NotAsked), c.outcome)
        assertEquals("lrclib", c.found!!.source)
    }

    @Test
    fun lrclibPlainThenNeteaseSyncedGivesNetease() {
        val c = chain({ lrPlain }, { neSynced }).get(q)
        assertEquals(neSynced, c.found)
        assertEquals("netease", c.found!!.source)
        assertEquals(mapOf("lrclib" to Outcome.Answered, "netease" to Outcome.Answered), c.outcome)
    }

    @Test
    fun bothPlainGivesLrclibPlain() {
        assertEquals(lrPlain, chain({ lrPlain }, { nePlain }).get(q).found)
    }

    @Test
    fun reversedOrderAsksNeteaseFirst() {
        val c = chain({ lrSynced }, { neSynced }, LyricSources(order = listOf("netease", "lrclib"))).get(q)
        assertEquals(neSynced, c.found)
        assertEquals(listOf("netease"), asked)
        assertEquals(Outcome.NotAsked, c.outcome["lrclib"])
    }

    @Test
    fun aFailingSourceIsFailedAndTheChainGoesOn() {
        val e = IOException("down")
        val c = chain({ lrPlain }, { throw e }).get(q)
        assertEquals(lrPlain, c.found)
        assertEquals(Outcome.Failed(e), c.outcome["netease"])
        assertEquals(Outcome.Answered, c.outcome["lrclib"])
    }

    @Test
    fun aSkippedSourceIsNotCalled() {
        val c = chain({ lrSynced }, { neSynced }, skip = { it == "lrclib" }).get(q)
        assertEquals(neSynced, c.found)
        assertEquals(listOf("netease"), asked)
        assertEquals(mapOf("lrclib" to Outcome.Skipped, "netease" to Outcome.Answered), c.outcome)
    }

    @Test
    fun anOffSourceHasNoOutcome() {
        val c = chain({ lrSynced }, { neSynced }, LyricSources(lrclib = false)).get(q)
        assertEquals(mapOf("netease" to Outcome.Answered), c.outcome)
        assertEquals(listOf("netease"), asked)
    }

    @Test
    fun aMissFromBothIsNullWithBothAnswered() {
        val c = chain({ null }, { null }).get(q)
        assertEquals(null, c.found)
        assertEquals(mapOf("lrclib" to Outcome.Answered, "netease" to Outcome.Answered), c.outcome)
    }

    @Test
    fun aFileErrorIsRethrownAtOnce() {
        val e = LyricsFileException("write x")
        val thrown = assertThrows(LyricsFileException::class.java) { chain({ throw e }, { neSynced }).get(q) }
        assertSame(e, thrown)
        assertEquals(listOf("lrclib"), asked)
    }
}
