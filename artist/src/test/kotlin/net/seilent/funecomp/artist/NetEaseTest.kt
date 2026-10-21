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
import java.security.MessageDigest
import kotlin.random.Random

class NetEaseTest {
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

    private fun netease(f: Fixtures, on: Boolean = true) = NetEase(tmp.root, f, "0.1.0", { on }, { now }, time.sleep, { time.now }, Random(1)).also { used += f }

    private class Tag(val title: String, val artist: String, val albumArtist: String, val durationMs: Long) {
        val query get() = Query(title, artist, null, durationMs, deviceSplit(artist, albumArtist))
    }

    private companion object {
        fun deviceSplit(text: String, albumArtist: String): List<String> {
            val t = text.trim()
            if (t.isEmpty()) return emptyList()
            if (albumArtist.isNotEmpty() && t.lowercase() == albumArtist.lowercase()) return listOf(t)
            return t.replace(" / ", "; ").replace(", ", "; ").split("; ").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    private val tags = Fixtures.text("ne-tags.tsv").lineSequence().filter { it.isNotBlank() }.map { it.split('\t') }.map { Tag(it[0], it[1], it[2], it[3].toLong()) }.toList()
    private val expect = Fixtures.text("ne-expect.tsv").lineSequence().filter { it.isNotBlank() }.map { it.split('\t') }.toList()

    private fun tag(title: String) = tags.single { it.title == title }

    private fun search(q: String) = "POST ${NetEase.SEARCH} s=${Api.formEnc(q)}&type=1&limit=10&offset=0"

    private fun lyric(id: Long) = "POST ${NetEase.LYRIC} id=$id&lv=1&tv=-1"

    private fun picked(calls: List<String>) = calls.lastOrNull { it.startsWith("POST ${NetEase.LYRIC} ") }?.substringAfter("id=")?.substringBefore('&')

    private fun h12(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

    private fun file(q: Query, ext: String) = tmp.root.resolve("${q.key}.ne.$ext")

    private fun songs(vararg s: Pair<String, Long>) = Response(200, "{\"result\":{\"songs\":[${s.withIndex().joinToString(",") { (i, x) -> "{\"id\":${i + 1},\"name\":\"${x.first}\",\"alia\":[],\"tns\":[],\"ar\":[],\"dt\":${x.second}}" }}]},\"code\":200}".toByteArray())

    private fun lyrics(text: String) = Response(200, "{\"lrc\":{\"lyric\":\"${text.replace("\n", "\\n")}\"},\"code\":200}".toByteArray())

    private val five = (1..5).joinToString("\n") { "[00:0$it.00]x$it" }

    private val stub = Query("Alpha", "Beta", null, 200_000)

    private fun stubbed(f: Fixtures, dt: Long) {
        f.queued[search("Alpha Beta")] = ArrayDeque(listOf(songs("Alpha" to dt)))
        f.queued[lyric(1)] = ArrayDeque(listOf(lyrics(five)))
    }

    @Test
    fun theSampledTracksGetTheirMeasuredPicks() {
        val f = Fixtures()
        val n = netease(f)
        assertEquals(240, tags.size)
        assertEquals(tags.map { it.title to it.artist }, expect.map { it[0] to it[1] })
        for ((t, e) in tags.zip(expect)) {
            val before = f.calls.size
            val got = n.get(t.query)
            val made = f.calls.drop(before)
            assertTrue(t.title, made.size in 1..3)
            assertEquals(t.title, e[2], picked(made) ?: "none")
            assertEquals(t.title, e[3], if (got == null) "miss" else if (got.synced) "synced" else "plain")
            assertEquals(t.title, e[4].toInt(), got?.text?.split("\n")?.size ?: 0)
            assertEquals(t.title, e[5], got?.text?.split("\n")?.first()?.let(::h12) ?: "")
            if (got != null) assertEquals("netease", got.source)
        }
        assertEquals(125, expect.count { it[3] == "synced" })
        assertEquals(139, expect.count { it[2] != "none" })
        assertEquals(0, expect.count { it[3] == "plain" })
        assertTrue(f.calls.all { it.startsWith("POST https://music.163.com/api/") })
        val calls = f.calls.size
        for (t in tags) n.get(t.query)
        assertEquals(calls, f.calls.size)
    }

    @Test
    fun requestShapeAndHeaders() {
        val f = Fixtures()
        val t = tag("God knows...／涼宮ハルヒ (CV.平野 綾)")
        assertTrue(netease(f).get(t.query)!!.synced)
        assertEquals(listOf(search("God knows... 涼宮ハルヒ (CV.平野 綾)"), lyric(4944530)), f.calls)
        val headers = mapOf("User-Agent" to "funecomp/0.1.0 (https://github.com/seilent/funecomp)", "Referer" to "https://music.163.com/", "Content-Type" to "application/x-www-form-urlencoded")
        assertEquals(listOf(headers, headers), f.sent)
    }

    @Test
    fun godKnowsPicksTheKnownWrongSong() {
        val f = Fixtures()
        netease(f).get(tag("God knows...／涼宮ハルヒ (CV.平野 綾)").query)
        assertEquals("4944530", picked(f.calls))
    }

    @Test
    fun nectarPicksTheRemixWithNoLyricAndTriesNoOther() {
        val f = Fixtures()
        assertNull(netease(f).get(tag("Nectar / Covered by 紲凪いろ").query))
        assertEquals(1, f.calls.count { it.startsWith("POST ${NetEase.LYRIC} ") })
        assertEquals("2039350779", picked(f.calls))
    }

    @Test
    fun theUnsureSuiseiPickIsPinned() {
        val f = Fixtures()
        assertTrue(netease(f).get(tag("✦.* 水星 - tofubeats feat.オノマトペ大臣(Cover) ┊ 天宮こころ×不破湊").query)!!.synced)
        assertEquals("1915879440", picked(f.calls))
    }

    @Test
    fun aTitleOnlySearchRunsWhenNoFirstResultIsWithinFiveSecondsAndKeepsOnlyNewIds() {
        val f = Fixtures()
        f.queued[search("Alpha Beta")] = ArrayDeque(listOf(songs("Other" to 205_001)))
        f.queued[search("Alpha")] = ArrayDeque(listOf(songs("Alpha" to 199_000)))
        assertNull(netease(f).get(stub))
        assertEquals(listOf(search("Alpha Beta"), search("Alpha")), f.calls)
        tmp.root.listFiles()?.forEach { it.delete() }
        val g = Fixtures()
        g.queued[search("Alpha Beta")] = ArrayDeque(listOf(songs("Other" to 205_000)))
        assertNull(netease(g).get(stub.copy(durationMs = 200_001)))
        assertEquals(listOf(search("Alpha Beta")), g.calls)
    }

    @Test
    fun secondSearchIdsAreAppendedAfterTheFirst() {
        val f = Fixtures()
        f.queued[search("Alpha Beta")] = ArrayDeque(listOf(songs("Other" to 100_000)))
        f.queued[search("Alpha")] = ArrayDeque(listOf(songs("Other" to 100_000, "Alpha" to 201_000)))
        f.queued[lyric(2)] = ArrayDeque(listOf(lyrics(five)))
        assertTrue(netease(f).get(stub)!!.synced)
        assertEquals(lyric(2), f.calls.last())
    }

    @Test
    fun durationWithinThreeSecondsInclusive() {
        for ((dt, ok) in listOf(203_000L to true, 197_000L to true, 203_001L to false, 196_999L to false)) {
            tmp.root.listFiles()?.forEach { it.delete() }
            val f = Fixtures()
            stubbed(f, dt)
            assertEquals("$dt", ok, netease(f).get(stub) != null)
            assertEquals("$dt", if (ok) 2 else 1, f.calls.size)
        }
    }

    @Test
    fun coreMatchesAsMeasured() {
        assertEquals("song", NetEase.core("Song ver.2023remaster"))
        assertEquals("songver", NetEase.core("Song ver"))
        assertEquals("dis", NetEase.core("Discover"))
        assertEquals("songx", NetEase.core("Song (TV size】 x"))
        assertEquals("songx", NetEase.core("Song【A) x"))
        assertEquals("song", NetEase.core("Song - Artist"))
        assertEquals("songartist", NetEase.core("Ｓｏｎｇ／Artist"))
        assertEquals("song", NetEase.core("Ｓｏｎｇ｜Artist"))
        assertEquals("song", NetEase.core("Song 歌ってみた"))
        assertEquals("songmvextra", NetEase.core("Song MV extra"))
        assertEquals("song", NetEase.core("Song MV"))
        assertEquals("ab", NetEase.core("a/b"))
        assertEquals("", NetEase.core("(cover)"))
    }

    @Test
    fun aNameAliasOrTranslationMatches() {
        val f = Fixtures()
        f.queued[search("Alpha Beta")] = ArrayDeque(listOf(Response(200, "{\"result\":{\"songs\":[{\"id\":7,\"name\":\"X\",\"alia\":[\"Y\"],\"tns\":[\"ALPHA\"],\"dt\":200000}]},\"code\":200}".toByteArray())))
        f.queued[lyric(7)] = ArrayDeque(listOf(lyrics(five)))
        assertTrue(netease(f).get(stub)!!.synced)
    }

    @Test
    fun requestsAreOneAndAHalfSecondsApart() {
        val f = Fixtures()
        f.now = { time.now }
        val n = netease(f)
        for (t in tags.take(10)) n.get(t.query)
        val at = f.times.map { it.first }
        assertTrue(at.size > 10)
        assertTrue(at.zipWithNext().all { (a, b) -> b - a >= 1500 })
    }

    @Test
    fun noDurationMakesNoCall() {
        val f = Fixtures()
        assertNull(netease(f).get(stub.copy(durationMs = null)))
        assertEquals(emptyList<String>(), f.calls)
    }

    @Test
    fun sourceOffMakesNoCall() {
        val f = Fixtures()
        assertNull(netease(f, on = false).get(tags[0].query))
        assertEquals(emptyList<String>(), f.calls)
    }

    @Test
    fun aBodyCodeOtherThan200Throws() {
        val f = Fixtures()
        f.queued[search("Alpha Beta")] = ArrayDeque(listOf(Response(200, "{\"code\":500}".toByteArray())))
        val e = assertThrows(IOException::class.java) { netease(f).get(stub) }
        assertEquals("netease code 500", e.message)
        assertFalse(file(stub, "miss").exists())
    }

    @Test
    fun aSyncedHitIsCachedWithTheRuleLine() {
        val f = Fixtures()
        stubbed(f, 200_000)
        val got = netease(f).get(stub)!!
        assertEquals("rule=1\n${got.text}", file(stub, "lrc").readText())
        assertEquals(setOf("${stub.key}.ne.lrc"), tmp.root.list()!!.toSet())
        assertEquals(got, netease(f).get(stub))
        assertEquals(2, f.calls.size)
        assertFalse(tmp.root.resolve("${stub.key}.lrc").exists())
    }

    @Test
    fun aHitUnderAnotherRuleIsLookedUpAgain() {
        file(stub, "lrc").writeText("rule=0\n[00:01.00]old")
        val f = Fixtures()
        stubbed(f, 200_000)
        assertEquals(five, netease(f).get(stub)!!.text)
        assertEquals(2, f.calls.size)
        assertEquals("rule=1\n$five", file(stub, "lrc").readText())
    }

    @Test
    fun aStaleRuleHitThatNowMissesIsDropped() {
        file(stub, "lrc").writeText("rule=0\n[00:01.00]old")
        val f = Fixtures()
        f.queued[search("Alpha Beta")] = ArrayDeque(listOf(songs("Other" to 200_000)))
        assertNull(netease(f).get(stub))
        assertEquals(setOf("${stub.key}.ne.miss"), tmp.root.list()!!.toSet())
    }

    @Test
    fun aMissIsKeptForSevenDaysUnderTheSameRule() {
        val f = Fixtures()
        f.queued[search("Alpha Beta")] = ArrayDeque(List(3) { songs("Other" to 200_000) })
        assertNull(netease(f).get(stub))
        assertEquals("$wall\nrule=1", file(stub, "miss").readText())
        now += Lrclib.MISS_MS - 1
        assertNull(netease(f).get(stub))
        assertEquals(1, f.calls.size)
        now += 1
        assertNull(netease(f).get(stub))
        assertEquals(2, f.calls.size)
        file(stub, "miss").writeText("$now\nrule=0")
        assertNull(netease(f).get(stub))
        assertEquals(3, f.calls.size)
    }

    @Test
    fun aPlainHitWritesTxtAndMiss() {
        val f = Fixtures()
        f.queued[search("Alpha Beta")] = ArrayDeque(listOf(songs("Alpha" to 200_000)))
        f.queued[lyric(1)] = ArrayDeque(listOf(lyrics("[00:01.00]a\n[00:01.50]b\nc")))
        val got = netease(f).get(stub)!!
        assertEquals(Lrclib.Found("a\nb\nc", false, "netease"), got)
        assertEquals("rule=1\na\nb\nc", file(stub, "txt").readText())
        assertEquals("$wall\nrule=1", file(stub, "miss").readText())
        assertEquals(got, netease(f).get(stub))
        assertEquals(2, f.calls.size)
    }

    @Test
    fun noLyricUncollectedAndInstrumentalAreMisses() {
        for (body in listOf("{\"nolyric\":true,\"code\":200}", "{\"uncollected\":true,\"code\":200}", "{\"lrc\":{\"lyric\":\"[00:01.00]纯音乐，x\"},\"code\":200}", "{\"lrc\":{\"lyric\":\"\"},\"code\":200}")) {
            tmp.root.listFiles()?.forEach { it.delete() }
            val f = Fixtures()
            f.queued[search("Alpha Beta")] = ArrayDeque(listOf(songs("Alpha" to 200_000)))
            f.queued[lyric(1)] = ArrayDeque(listOf(Response(200, body.toByteArray())))
            assertNull(body, netease(f).get(stub))
            assertTrue(file(stub, "miss").isFile)
        }
    }

    @Test
    fun theArtistIsTheSplitPartElseTheFirstDeviceArtist() {
        val f = Fixtures()
        f.queued[search("Alpha Beta")] = ArrayDeque(listOf(songs("Other" to 200_000)))
        netease(f).get(Query("Alpha", "Beta, Gamma", null, 200_000, listOf("Beta", "Gamma")))
        assertEquals(search("Alpha Beta"), f.calls.first())
        tmp.root.listFiles()?.forEach { it.delete() }
        val g = Fixtures()
        g.queued[search("Alpha Earth, Wind & Fire")] = ArrayDeque(listOf(songs("Other" to 200_000)))
        netease(g).get(Query("Alpha", "Earth, Wind & Fire", null, 200_000, listOf("Earth, Wind & Fire")))
        assertEquals(search("Alpha Earth, Wind & Fire"), g.calls.first())
        tmp.root.listFiles()?.forEach { it.delete() }
        val h = Fixtures()
        h.queued[search("Alpha 歌 / い")] = ArrayDeque(listOf(songs("Other" to 200_000)))
        netease(h).get(Query("Alpha", "歌 / い", null, 200_000))
        assertEquals(search("Alpha 歌 / い"), h.calls.first())
    }

    @Test
    fun theFormBodyEncodesAsMeasured() {
        val f = Fixtures()
        f.queued[search("DECO*27 ~x")] = ArrayDeque(listOf(songs("Other" to 200_000)))
        netease(f).get(Query("DECO*27", "~x", null, 200_000))
        assertEquals("POST ${NetEase.SEARCH} s=DECO%2A27+~x&type=1&limit=10&offset=0", f.calls.first())
    }
}
