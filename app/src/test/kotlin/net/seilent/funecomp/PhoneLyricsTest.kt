package net.seilent.funecomp

import net.seilent.funecomp.artist.Lrclib
import net.seilent.funecomp.artist.LyricSources
import net.seilent.funecomp.artist.Sweep
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.state.Lrc
import net.seilent.funecomp.state.LyricsFetch
import net.seilent.funecomp.state.PhoneKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PhoneLyricsTest {
    private val tasks = ArrayList<() -> Unit>()
    private val asks = ArrayList<PhoneLyrics.Ask>()
    private var answer: (PhoneLyrics.Ask) -> PhoneLyrics.Found = { PhoneLyrics.Found.Miss }
    private var pokes = 0
    private val p = PhoneLyrics({ tasks += it; true }, { asks += it; answer(it) }, { pokes++ })
    private var t = 10_000L

    private val synced = PhoneLyrics.Found.Copy("[00:01.00]one\n[00:02.00]two\n", LyricSources.LRCLIB)
    private val plain = PhoneLyrics.Found.Copy("one\ntwo\n", LyricSources.NETEASE)

    private fun phone(title: String? = "Idol", artist: String? = "YOASOBI", durationMs: Long = 213_000, source: Int = Enums.Source.phone) =
        State(source = source, output = Enums.Output.phoneReceiver, track = 0, title = title, artist = artist, artists = listOfNotNull(artist), durationMs = durationMs)

    private val idol = PhoneKey("Idol", "YOASOBI")

    private fun settle(s: State = phone(), shown: Boolean = true) {
        p.see(s, t, shown)
        t += PhoneLyrics.SETTLE_MS
        assertNull(p.see(s, t, shown))
    }

    private fun drain() {
        while (tasks.isNotEmpty()) tasks.removeAt(0)()
    }

    @Test
    fun aKeyIsAskedOnceAfterItSettles() {
        assertEquals(PhoneLyrics.SETTLE_MS, p.see(phone(), t, true))
        t += 400
        assertEquals(PhoneLyrics.SETTLE_MS - 400, p.see(phone(), t, true))
        assertNull(p.settled)
        t += PhoneLyrics.SETTLE_MS - 400
        assertNull(p.see(phone(), t, true))
        assertEquals(idol, p.settled)
        assertEquals(PhoneLyrics.Kind.Pending, p.entry(idol)!!.kind)
        assertNull(p.entry(idol)!!.follow)
        p.see(phone(), t, true)
        drain()
        assertEquals(listOf(PhoneLyrics.Ask(idol, 213_000, listOf("YOASOBI"))), asks)
        p.see(phone(), t + 5000, true)
        drain()
        assertEquals(1, asks.size)
    }

    @Test
    fun aNewerKeyRestartsTheSettle() {
        p.see(phone(artist = "Old Artist"), t, true)
        t += 400
        assertEquals(PhoneLyrics.SETTLE_MS, p.see(phone(), t, true))
        t += PhoneLyrics.SETTLE_MS
        p.see(phone(), t, true)
        drain()
        assertEquals(listOf(idol), asks.map { it.key })
    }

    @Test
    fun anEmptyTitleOrArtistMakesNoKey() {
        for (s in listOf(phone(title = ""), phone(artist = ""), phone(title = null), phone(artist = " "), phone(source = Enums.Source.local))) {
            assertNull(p.see(s, t, true))
            t += 10_000
            assertNull(p.see(s, t, true))
            assertNull(p.settled)
        }
        drain()
        assertTrue(asks.isEmpty())
    }

    @Test
    fun nothingIsAskedWhileNotShown() {
        settle(shown = false)
        drain()
        assertTrue(asks.isEmpty())
        assertNull(p.entry(idol))
        p.see(phone(), t, true)
        drain()
        assertEquals(1, asks.size)
    }

    @Test
    fun aSyncedCopyFollowsAndAPlainOneDoesNot() {
        answer = { synced }
        settle()
        drain()
        val e = p.entry(idol)!!
        assertEquals(PhoneLyrics.Kind.Copy, e.kind)
        assertTrue(e.has)
        assertEquals(true, e.follow)
        assertEquals(listOf(Lrc.Line(1000, "one"), Lrc.Line(2000, "two")), e.copy!!.lines)
        assertEquals(java.util.zip.CRC32().apply { update(synced.text.toByteArray()) }.value, e.crc)
        assertTrue(pokes > 0)
        answer = { plain }
        val other = phone(title = "Other")
        settle(other)
        drain()
        val o = p.entry(PhoneKey("Other", "YOASOBI"))!!
        assertTrue(o.has)
        assertEquals(false, o.follow)
        assertEquals(listOf("one", "two"), o.copy!!.plain)
    }

    @Test
    fun aMissAndAnUnavailableDoNotFollow() {
        settle()
        drain()
        assertEquals(PhoneLyrics.Kind.Miss, p.entry(idol)!!.kind)
        assertEquals(false, p.entry(idol)!!.follow)
        assertFalse(p.entry(idol)!!.has)
        answer = { PhoneLyrics.Found.Unavailable }
        settle(phone(title = "Other"))
        drain()
        assertEquals(PhoneLyrics.Kind.Unavailable, p.entry(PhoneKey("Other", "YOASOBI"))!!.kind)
        assertEquals(false, p.entry(PhoneKey("Other", "YOASOBI"))!!.follow)
    }

    @Test
    fun aThrowingChainIsUnavailable() {
        answer = { throw IllegalStateException("x") }
        settle()
        drain()
        assertEquals(PhoneLyrics.Kind.Unavailable, p.entry(idol)!!.kind)
    }

    @Test
    fun aThrownErrorIsUnavailableLoggedAndAskedAgainOnRetry() {
        val logs = ArrayList<String>()
        val q = PhoneLyrics({ tasks += it; true }, { asks += it; throw AssertionError("boom") }, {}) { logs += it }
        q.see(phone(), t, true)
        q.see(phone(), t + PhoneLyrics.SETTLE_MS, true)
        drain()
        assertEquals(PhoneLyrics.Kind.Unavailable, q.entry(idol)!!.kind)
        assertTrue(logs.toString(), logs.any { "AssertionError: boom" in it })
        q.retry()
        q.see(phone(), t + PhoneLyrics.SETTLE_MS, true)
        drain()
        assertEquals(2, asks.size)
    }

    @Test
    fun aClearDropsAQueuedTaskThatNeverRanAndTheNextKeyIsAsked() {
        settle()
        assertEquals(1, tasks.size)
        tasks.clear()
        p.clear()
        settle(phone(title = "Other"))
        assertEquals(1, tasks.size)
        drain()
        assertEquals(listOf(PhoneKey("Other", "YOASOBI")), asks.map { it.key })
        assertEquals(PhoneLyrics.Kind.Miss, p.entry(PhoneKey("Other", "YOASOBI"))!!.kind)
    }

    @Test
    fun aCopyThatReturnsAfterItsSourceTurnsOffIsDroppedAndAskedAgain() {
        answer = {
            p.sources(LyricSources.LRCLIB)
            synced
        }
        settle()
        drain()
        assertNull(p.entry(idol))
        answer = { PhoneLyrics.Found.Miss }
        p.see(phone(), t, true)
        drain()
        assertEquals(2, asks.size)
        assertEquals(PhoneLyrics.Kind.Miss, p.entry(idol)!!.kind)
    }

    @Test
    fun aResultThatReturnsAfterAReorderOrAClearIsDropped() {
        answer = {
            p.sources(null)
            PhoneLyrics.Found.Miss
        }
        settle()
        drain()
        assertNull(p.entry(idol))
        answer = {
            p.clear()
            synced
        }
        p.see(phone(), t, true)
        drain()
        assertEquals(2, asks.size)
        assertNull(p.entry(idol))
    }

    @Test
    fun aCopyWithNothingParsedIsAMiss() {
        answer = { PhoneLyrics.Found.Copy("[ar:x]\n", LyricSources.LRCLIB) }
        settle()
        drain()
        assertEquals(PhoneLyrics.Kind.Miss, p.entry(idol)!!.kind)
    }

    @Test
    fun anUnavailableIsAskedAgainOnRetryOnSourcesAndOnSettlingAgain() {
        answer = { PhoneLyrics.Found.Unavailable }
        settle()
        drain()
        p.see(phone(), t, true)
        drain()
        assertEquals(1, asks.size)
        p.retry()
        p.see(phone(), t, true)
        drain()
        assertEquals(2, asks.size)
        p.sources(null)
        p.see(phone(), t, true)
        drain()
        assertEquals(3, asks.size)
        settle(phone(title = "Other"))
        drain()
        assertEquals(4, asks.size)
        settle()
        drain()
        assertEquals(5, asks.size)
    }

    @Test
    fun sourcesClearMissesAndTheCopiesOfASourceTurnedOff() {
        answer = { synced }
        settle()
        drain()
        answer = { plain }
        settle(phone(title = "Other"))
        drain()
        answer = { PhoneLyrics.Found.Miss }
        settle(phone(title = "Third"))
        drain()
        p.sources(null)
        assertNull(p.entry(PhoneKey("Third", "YOASOBI")))
        assertTrue(p.entry(idol)!!.has)
        p.sources(LyricSources.NETEASE)
        assertNull(p.entry(PhoneKey("Other", "YOASOBI")))
        assertTrue(p.entry(idol)!!.has)
        p.sources(LyricSources.LRCLIB)
        assertNull(p.entry(idol))
    }

    @Test
    fun aMissAtDurationZeroIsAskedOnceTheDurationArrivesAndOnceMoreOnAChange() {
        settle(phone(durationMs = 0))
        drain()
        assertEquals(0L, asks.single().durationMs)
        p.see(phone(durationMs = 0), t, true)
        drain()
        assertEquals(1, asks.size)
        p.see(phone(durationMs = 213_000), t, true)
        drain()
        assertEquals(listOf(0L, 213_000L), asks.map { it.durationMs })
        p.see(phone(durationMs = 213_000), t, true)
        drain()
        assertEquals(2, asks.size)
        p.see(phone(durationMs = 214_000), t, true)
        drain()
        p.see(phone(durationMs = 215_000), t, true)
        drain()
        assertEquals(listOf(0L, 213_000L, 214_000L), asks.map { it.durationMs })
    }

    @Test
    fun aStaleDurationMissIsAskedWhenTheRightOneArrives() {
        settle(phone(durationMs = 180_000))
        drain()
        p.see(phone(durationMs = 213_000), t, true)
        drain()
        assertEquals(listOf(180_000L, 213_000L), asks.map { it.durationMs })
    }

    @Test
    fun aCopyIsNeverAskedAgain() {
        answer = { synced }
        settle(phone(durationMs = 0))
        drain()
        p.see(phone(durationMs = 213_000), t, true)
        drain()
        assertEquals(1, asks.size)
    }

    @Test
    fun oneChainInFlightAndANewerKeyDropsAQueuedOlderOne() {
        settle()
        assertEquals(1, tasks.size)
        settle(phone(title = "Other"))
        assertEquals(1, tasks.size)
        assertNull(p.entry(idol))
        drain()
        assertEquals(listOf(PhoneKey("Other", "YOASOBI")), asks.map { it.key })
        assertEquals(PhoneLyrics.Kind.Miss, p.entry(PhoneKey("Other", "YOASOBI"))!!.kind)
    }

    @Test
    fun aRunningChainFinishesAndItsResultIsKept() {
        var during: (() -> Unit)? = null
        answer = {
            during?.invoke()
            synced
        }
        settle()
        during = {
            during = null
            settle(phone(title = "Other"))
        }
        drain()
        assertTrue(p.entry(idol)!!.has)
        assertEquals(listOf(idol, PhoneKey("Other", "YOASOBI")), asks.map { it.key })
    }

    @Test
    fun aRefusedRunIsUnavailable() {
        val q = PhoneLyrics({ false }, { asks += it; PhoneLyrics.Found.Miss }, {})
        q.see(phone(), t, true)
        q.see(phone(), t + PhoneLyrics.SETTLE_MS, true)
        assertEquals(PhoneLyrics.Kind.Unavailable, q.entry(idol)!!.kind)
    }

    @Test
    fun copiesAreBoundedAndOffsetsOutliveThem() {
        answer = { synced }
        p.offset(idol, 250)
        settle()
        drain()
        for (i in 0 until PhoneLyrics.COPIES) {
            settle(phone(title = "T$i"))
            drain()
        }
        assertNull(p.entry(idol))
        assertTrue(p.entry(PhoneKey("T0", "YOASOBI"))!!.has)
        assertEquals(250, p.offset(idol))
        for (i in 0 until PhoneLyrics.OFFSETS) p.offset(PhoneKey("O$i", "x"), i + 1)
        assertEquals(0, p.offset(idol))
        p.offset(PhoneKey("O1", "x"), 0)
        assertEquals(0, p.offset(PhoneKey("O1", "x")))
    }

    @Test
    fun leavingThePhoneTrackClearsTheSettledKey() {
        settle()
        assertEquals(idol, p.settled)
        p.see(phone(source = Enums.Source.local), t, true)
        assertNull(p.settled)
    }

    private val breakers = LyricSources.ORDER.associateWith { Sweep.Breaker({ 0L }, 3, 60_000) }
    private val queries = ArrayList<Pair<String, Lrclib.Query>>()

    private fun ask(
        durationMs: Long,
        sources: LyricSources = LyricSources(),
        lrclib: (Lrclib.Query) -> Lrclib.Found? = { null },
        netease: (Lrclib.Query) -> Lrclib.Found? = { null },
    ) = PhoneLyrics.ask(
        PhoneLyrics.Ask(idol, durationMs, listOf("YOASOBI")), sources,
        { queries += "lrclib" to it; lrclib(it) }, { queries += "netease" to it; netease(it) },
        { breakers.getValue(it).open }, { id, failed -> breakers.getValue(id).result(failed) },
    )

    @Test
    fun durationZeroSkipsNeteaseLeavesItsBreakerCountAndAsksLrclibWithNoDuration() {
        val down = { _: Lrclib.Query -> throw IOException("down") }
        repeat(2) { ask(213_000, netease = down) }
        queries.clear()
        assertEquals(PhoneLyrics.Found.Miss, ask(0))
        assertEquals(listOf("lrclib"), queries.map { it.first })
        assertNull(queries.single().second.durationMs)
        assertEquals("Idol", queries.single().second.track)
        assertNull(queries.single().second.album)
        assertFalse(breakers.getValue(LyricSources.NETEASE).open)
        ask(213_000, netease = down)
        assertTrue(breakers.getValue(LyricSources.NETEASE).open)
        queries.clear()
        assertEquals(PhoneLyrics.Found.Unavailable, ask(213_000))
        assertEquals(listOf("lrclib"), queries.map { it.first })
    }

    @Test
    fun bothSourcesAreAskedOnceTheDurationIsKnown() {
        assertEquals(PhoneLyrics.Found.Miss, ask(213_000))
        assertEquals(listOf("lrclib", "netease"), queries.map { it.first })
        assertEquals(213_000L, queries[1].second.durationMs)
    }

    @Test
    fun aFailureIsUnavailableAndSourcesOffOrAllOpenAskNothing() {
        assertEquals(PhoneLyrics.Found.Unavailable, ask(213_000, lrclib = { throw IOException("x") }))
        queries.clear()
        assertEquals(PhoneLyrics.Found.Unavailable, ask(213_000, LyricSources(lrclib = false, netease = false)))
        repeat(3) { breakers.values.forEach { it.result(true) } }
        assertEquals(PhoneLyrics.Found.Unavailable, ask(213_000))
        assertTrue(queries.isEmpty())
    }

    @Test
    fun aBodyOverTheCapIsAMissForThatSource() {
        val big = Lrclib.Found("[00:01.00]" + "a".repeat(Lrc.CAP), true)
        val small = Lrclib.Found("[00:01.00]ne\n", true, LyricSources.NETEASE)
        assertEquals(PhoneLyrics.Found.Copy(small.text, LyricSources.NETEASE), ask(213_000, lrclib = { big }, netease = { small }))
        assertEquals(PhoneLyrics.Found.Miss, ask(213_000, lrclib = { big }))
    }

    @Test
    fun theShowCarriesTheSettledKeyWithItsShiftedCopy() {
        assertEquals(LyricsFetch.Show.Off, p.show())
        answer = { synced }
        settle()
        assertEquals(LyricsFetch.Show.Phone(idol, null, null), p.show())
        drain()
        val c = (p.show() as LyricsFetch.Show.Phone).copy!!
        assertEquals(Enums.LyricsKind.synced, c.kind)
        assertEquals(listOf(1000L, 2000L), c.lines.map { it.ms })
        assertEquals(p.entry(idol)!!.crc, c.crc)
        p.offset(idol, 1500)
        assertEquals(listOf(0L, 500L), (p.show() as LyricsFetch.Show.Phone).copy!!.lines.map { it.ms })
        p.offset(idol, -500)
        assertEquals(listOf(1500L, 2500L), (p.show() as LyricsFetch.Show.Phone).copy!!.lines.map { it.ms })
        p.see(phone(title = "Next"), t, true)
        assertEquals(LyricsFetch.Show.Off, p.show())
    }

    @Test
    fun aPlainCopyShowsAsPlainWithNoFollowAndAMissShowsNothing() {
        answer = { plain }
        settle()
        drain()
        val show = p.show() as LyricsFetch.Show.Phone
        assertEquals(false, show.follow)
        assertEquals(Enums.LyricsKind.plain, show.copy!!.kind)
        assertEquals(listOf("one", "two"), show.copy!!.lines.map { it.text })
        assertNull(show.copy!!.lines.first().ms)
        val q = PhoneLyrics({ tasks += it; true }, { PhoneLyrics.Found.Miss }, {})
        q.see(phone(), t, true)
        t += PhoneLyrics.SETTLE_MS
        q.see(phone(), t, true)
        drain()
        assertEquals(LyricsFetch.Show.Phone(idol, false, null), q.show())
    }

    @Test
    fun clearResetsThePhoneMode() {
        p.mode = true
        p.clear()
        assertFalse(p.mode)
    }
}
