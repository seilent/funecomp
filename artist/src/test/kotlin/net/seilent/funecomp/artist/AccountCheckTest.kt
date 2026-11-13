package net.seilent.funecomp.artist

import net.seilent.funecomp.proto.PlayEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.time.ZoneId

class AccountCheckTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val base = 1_800_000_000L
    private var now = base * 1000
    private val nowS get() = now / 1000
    private val u = base - 2 * 86400
    private val time = FakeTime()
    private var zone: ZoneId = ZoneId.of("Asia/Tokyo")
    private var stop: () -> Boolean = { false }
    private val logs = ArrayList<String>()
    private val both = Accounts(true, "", "", "me", "sk1", true, "tok", "me")
    private var accounts = both
    private val app = AppPair("a".repeat(32), "appsecret")
    private var net = Net()
    private var dir = File(".")
    private var log = 0xA0L

    private inner class Net : Http {
        val lfm = ArrayList<Played>()
        val lb = ArrayList<Played>()
        val gets = ArrayList<String>()
        val posts = ArrayList<Pair<String, String>>()
        val script = HashMap<String, ArrayDeque<() -> Response>>()
        var answer: (String, Map<String, String>) -> Response? = { _, _ -> null }
        var readsDown = false
        var age: Long? = null

        fun on(key: String, vararg r: () -> Response) {
            script.getOrPut(key) { ArrayDeque() }.addAll(r)
        }

        override fun get(url: String, headers: Map<String, String>): Response {
            if (readsDown) throw IOException("offline")
            gets += url
            val host = hostOf(url)
            script[host]?.removeFirstOrNull()?.let { return it() }
            val q = query(url)
            answer(host, q)?.let { return it }
            val h = age?.let { mapOf("cache-control" to "max-age=$it") } ?: emptyMap()
            if (host == LFM) {
                val from = q.getValue("from").toLong()
                val to = q.getValue("to").toLong()
                val all = lfm.filter { it.s >= from && it.s < to }.sortedByDescending { it.s }
                return Response(200, lfmBody(all.take(q.getValue("limit").toInt()), all.size).toByteArray(), h)
            }
            val max = q.getValue("max_ts").toLong()
            return Response(200, lbBody(lb.filter { it.s < max }.sortedByDescending { it.s }.take(q.getValue("count").toInt())).toByteArray(), h)
        }

        override fun post(url: String, headers: Map<String, String>, body: ByteArray): Response {
            val host = hostOf(url)
            posts += host to String(body)
            script["POST $host"]?.removeFirstOrNull()?.let { return it() }
            return Response(200, (if (host == LFM) "{\"scrobbles\":{\"@attr\":{\"accepted\":1,\"ignored\":0}}}" else "{\"status\":\"ok\"}").toByteArray())
        }
    }

    companion object {
        const val LFM = "ws.audioscrobbler.com"
        const val LB = "api.listenbrainz.org"
        val SERVICES = listOf(Accounts.LAST_FM, Accounts.LISTEN_BRAINZ)

        fun hostOf(url: String) = url.substringAfter("://").substringBefore('/')

        fun query(url: String) = url.substringAfter('?', "").split('&').filter { '=' in it }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }

        fun track(p: Played) = "{\"artist\":{\"mbid\":\"\",\"#text\":\"${p.artist}\"},\"streamable\":\"0\",\"mbid\":\"\",\"album\":{\"mbid\":\"\",\"#text\":\"\"},\"name\":\"${p.title}\",\"url\":\"\",\"date\":{\"uts\":\"${p.s}\",\"#text\":\"\"}}"

        fun playing(p: Played) = "{\"artist\":{\"mbid\":\"\",\"#text\":\"${p.artist}\"},\"name\":\"${p.title}\",\"@attr\":{\"nowplaying\":\"true\"}}"

        fun lfmBody(page: List<Played>, total: Int, now: Played? = null) =
            "{\"recenttracks\":{\"track\":[${(listOfNotNull(now?.let { playing(it) }) + page.map { track(it) }).joinToString(",")}],\"@attr\":{\"user\":\"me\",\"totalPages\":\"1\",\"page\":\"1\",\"perPage\":\"200\",\"total\":\"$total\"}}}"

        fun lbBody(page: List<Played>) = "{\"payload\":{\"count\":${page.size},\"latest_listen_ts\":0,\"oldest_listen_ts\":0,\"user_id\":\"me\",\"listens\":[" +
            page.joinToString(",") { "{\"inserted_at\":0,\"listened_at\":${it.s},\"recording_msid\":\"m\",\"user_name\":\"me\",\"track_metadata\":{\"artist_name\":\"${it.artist}\",\"track_name\":\"${it.title}\",\"additional_info\":{}}}" } +
            "]}}"
    }

    private fun each(services: List<String> = SERVICES, block: (String) -> Unit) {
        for (svc in services) {
            accounts = if (svc == Accounts.LAST_FM) both.copy(listenBrainz = false) else both.copy(lastFm = false)
            net = Net()
            dir = tmp.newFolder("$svc${log++}")
            logs.clear()
            now = base * 1000
            zone = ZoneId.of("Asia/Tokyo")
            stop = { false }
            block(svc)
        }
    }

    private fun scrobbler() = Scrobbler(dir, net, { accounts }, app, "0.1.0", { now }, time.sleep, { time.now }, zone = { zone }, stop = { stop() }) { logs += it }

    private fun records(from: Long = u - 7 * 86400, extra: Map<String, String> = emptyMap()) {
        for (s in SERVICES) File(dir, "own.$s").writeText("from\t$from\n${extra[s].orEmpty()}c\t0\n")
    }

    private fun play(at: Long, title: String, z: Int? = 32400, artist: String = "Artist", exposed: Boolean = true) =
        PlayEntry(0, at + (z ?: 0), 200_000, 240_000, 7, title, artist, listOf(artist), zoneS = z, exposed = exposed.takeIf { it })

    private fun store(s: Scrobbler, vararg plays: PlayEntry) {
        s.addLog(++log, plays.mapIndexed { i, p -> p.copy(seq = i + 1L) }, zone)
    }

    private fun account(svc: String) = if (svc == Accounts.LAST_FM) net.lfm else net.lb

    private fun host(svc: String) = if (svc == Accounts.LAST_FM) LFM else LB

    private fun posts(svc: String) = net.posts.filter { it.first == host(svc) && "playing_now" !in it.second }

    private fun sent(svc: String): List<String> = posts(svc).flatMap { (_, b) ->
        if (svc == Accounts.LAST_FM) {
            query("?$b").filterKeys { it.startsWith("track[") }.entries.sortedBy { it.key.removePrefix("track[").removeSuffix("]").toInt() }.map { it.value }
        } else {
            Regex("\"track_name\":\"([^\"]+)\"").findAll(b).map { it.groupValues[1] }.toList()
        }
    }

    private fun reads(svc: String) = net.gets.filter { hostOf(it) == host(svc) }

    private fun retry(svc: String): () -> Response =
        if (svc == Accounts.LAST_FM) ({ Response(200, "{\"error\":8,\"message\":\"Operation failed\"}".toByteArray()) }) else ({ Response(503, ByteArray(0)) })

    private fun tracks(n: Int, at: Long, gap: Long = 300, z: Int? = 32400, from: Int = 1) = (0 until n).map { play(at + it * gap, "Song ${from + it}", z) }

    private fun onAccount(svc: String, n: Int, at: Long, gap: Long = 300, from: Int = 1) {
        account(svc) += (0 until n).map { Played(at + it * gap, "Artist", "Song ${from + it}") }
    }

    @Test
    fun aMatchedListenIsNotSentTheCursorPassesItAndItsEntryIsRecorded() = each { svc ->
        records()
        val s = scrobbler()
        account(svc) += Played(u + 1, "Artist", "Song 1")
        store(s, *tracks(2, u).toTypedArray())
        s.flush()
        assertEquals(listOf("Song 2"), sent(svc))
        assertEquals(0, s.pending())
        assertTrue(File(dir, "own.$svc").readLines().contains("e\t1\t${u + 1}\tArtist\tSong 1"))
        assertTrue(logs.contains("scrobble $svc check 1 of 2 on the account"))
    }

    @Test
    fun anAllMatchedWindowMovesTheCursorWithNoCallAndAnUnexposedListenGoesAlone() = each { svc ->
        records()
        val s = scrobbler()
        onAccount(svc, 2, u)
        store(s, *tracks(2, u).toTypedArray())
        s.flush()
        assertTrue(posts(svc).isEmpty())
        assertEquals(0, s.pending())
        onAccount(svc, 2, u + 86400, from = 3)
        store(s, play(u + 86400, "Song 3"), play(u + 86400 + 300, "Song 4"), play(u + 86400 + 600, "Song 5", exposed = false))
        s.flush()
        assertEquals(listOf("Song 5"), sent(svc))
        assertEquals(0, s.pending())
    }

    @Test
    fun aWindowWithNoExposedListenReadsNothing() = each { svc ->
        records()
        val s = scrobbler()
        store(s, play(u, "Song 1", exposed = false))
        s.flush()
        assertTrue(reads(svc).isEmpty())
        assertEquals(listOf("Song 1"), sent(svc))
    }

    @Test
    fun theExactPassMatchesAtOneSecondAndNotAtTwo() = each { svc ->
        records()
        val s = scrobbler()
        account(svc) += listOf(Played(u + 1, "Artist", "Song 1"), Played(u + 302, "Artist", "Song 2"))
        store(s, *tracks(2, u).toTypedArray())
        s.flush()
        assertEquals(listOf("Song 2"), sent(svc))
    }

    @Test
    fun oneEqualFieldMatchesExactlyButNeverUnderAShift() = each { svc ->
        records()
        val s = scrobbler()
        val far = u + 3 * 3600
        account(svc) += listOf(Played(u, "Other", "Song 1"), Played(far + 32400, "Other", "Song 2"), Played(far + 300 + 32400, "Other", "Song 3"))
        store(s, play(u, "Song 1"), play(far, "Song 2"), play(far + 300, "Song 3"))
        s.flush()
        assertEquals(listOf("Song 2", "Song 3"), sent(svc))
    }

    @Test
    fun anOwnSendAtOrBelowTheCursorMatchesExactly() = each { svc ->
        records()
        val s = scrobbler()
        store(s, play(u, "Song 1", exposed = false))
        s.flush()
        store(s, play(u, "Song 1"))
        s.flush()
        assertEquals(listOf("Song 1"), sent(svc))
        assertEquals(0, s.pending())
    }

    @Test
    fun theLinesOfARetriedPostDoNotMatchAndTheNextFlushSendsTheListen() = each { svc ->
        records()
        val s = scrobbler()
        net.on("POST ${host(svc)}", if (svc == Accounts.LAST_FM) ({ Response(200, "{\"error\":11,\"message\":\"x\"}".toByteArray()) }) else ({ Response(503, ByteArray(0)) }))
        store(s, play(u, "Song 1"))
        assertEquals(Sent.RETRY, s.flush()[svc])
        assertEquals(1, s.pending())
        s.flush()
        assertEquals(listOf("Song 1", "Song 1"), sent(svc))
        assertEquals(0, s.pending())
    }

    @Test
    fun aPostAnsweredWithIoLeavesItsLinesInTheRecord() = each { svc ->
        records()
        val s = scrobbler()
        net.on("POST ${host(svc)}", { throw IOException("offline") })
        store(s, play(u, "Song 1", exposed = false))
        s.flush()
        assertEquals(1, s.pending())
        assertTrue(File(dir, "own.$svc").readLines().any { it.startsWith("e\t1\t$u\t") })
    }

    @Test
    fun aUtcReadInTokyoIsSkippedAndAnEightHourShiftIsSent() = each { svc ->
        records()
        val s = scrobbler()
        onAccount(svc, 3, u + 32400)
        store(s, *tracks(3, u).toTypedArray())
        s.flush()
        assertTrue(sent(svc).isEmpty())
        assertEquals(0, s.pending())
        onAccount(svc, 3, u + 86400 + 28800, from = 4)
        store(s, *tracks(3, u + 86400, from = 4).toTypedArray())
        s.flush()
        assertEquals(listOf("Song 4", "Song 5", "Song 6"), sent(svc))
    }

    @Test
    fun aTravelerMatchesTheUtcReadAndTheReadInThePhonesZoneButNotTheOffsetNow() = each { svc ->
        records()
        zone = ZoneId.of("Europe/Berlin")
        val s = scrobbler()
        onAccount(svc, 3, u + 32400)
        onAccount(svc, 3, u + 86400 + 28800, from = 4)
        onAccount(svc, 3, u + 86400 + 43200 + 3600, from = 7)
        store(s, *(tracks(3, u) + tracks(3, u + 86400, from = 4) + tracks(3, u + 86400 + 43200, from = 7)).toTypedArray())
        s.flush()
        assertEquals(listOf("Song 7", "Song 8", "Song 9"), sent(svc))
    }

    @Test
    fun aWinterOffsetKeptPastTheMarchTransitionMatchesTheSummerRead() = each { svc ->
        now = (base + 90 * 86400) * 1000
        val at = nowS - 2 * 86400
        records(at - 7 * 86400)
        zone = ZoneId.of("Europe/Berlin")
        val s = scrobbler()
        onAccount(svc, 3, at - 3600)
        store(s, *tracks(3, at, z = 3600).toTypedArray())
        s.flush()
        assertTrue(sent(svc).isEmpty())
        assertEquals(0, s.pending())
    }

    @Test
    fun anAlbumReplayedAfterAnEarlierPlayOnTheAccountIsSent() = each { svc ->
        records()
        val s = scrobbler()
        onAccount(svc, 5, u - 2700)
        store(s, *tracks(5, u).toTypedArray())
        s.flush()
        assertEquals((1..5).map { "Song $it" }, sent(svc))
    }

    private fun loops(from: Int, to: Int, at: Long) = (from until to).flatMap { k ->
        listOf(play(at + k * 450L, "Song A", -18000), play(at + k * 450L + 200, "Song B", -18000))
    }

    private fun loopsOnAccount(svc: String, from: Int, to: Int, at: Long) {
        account(svc) += (from until to).flatMap { k -> listOf(Played(at + k * 450L, "Artist", "Song A"), Played(at + k * 450L + 200, "Artist", "Song B")) }
    }

    @Test
    fun anAlignedRepeatAllIsSentPastTheMatchedLoopsAcrossAYield() = each { svc ->
        records()
        zone = ZoneId.of("America/New_York")
        val s = scrobbler()
        loopsOnAccount(svc, 0, 48, u)
        store(s, *loops(0, 64, u).toTypedArray())
        val size = if (svc == Accounts.LAST_FM) 50L else 100L
        var yielded = false
        stop = { !yielded && s.queue.cursor(svc) >= size && run { yielded = true; true } }
        assertEquals(Sent.YIELD, s.flush()[svc])
        assertEquals(if (svc == Accounts.LAST_FM) 0 else 4, sent(svc).size)
        s.flush()
        assertEquals(32, sent(svc).size)
        assertEquals(0, s.pending())
    }

    @Test
    fun theLaterLoopsOfAnAlignedRepeatAllAreSentWhenTheEarlierOnesNeverArrived() = each { svc ->
        records()
        zone = ZoneId.of("America/New_York")
        val s = scrobbler()
        loopsOnAccount(svc, 0, 48, u)
        store(s, *loops(48, 64, u).toTypedArray())
        s.flush()
        assertEquals(32, sent(svc).size)
    }

    @Test
    fun tracksMatchedEarlierAndReplayedFiveHoursLaterAreSent() = each { svc ->
        records()
        zone = ZoneId.of("America/New_York")
        val s = scrobbler()
        onAccount(svc, 3, u)
        store(s, *tracks(3, u, z = -18000).toTypedArray())
        s.flush()
        assertTrue(sent(svc).isEmpty())
        store(s, *tracks(3, u + 18000, z = -18000).toTypedArray())
        s.flush()
        assertEquals((1..3).map { "Song $it" }, sent(svc))
    }

    @Test
    fun aLonePlayTakesNoShift() = each { svc ->
        records()
        val s = scrobbler()
        onAccount(svc, 1, u + 32400)
        store(s, play(u, "Song 1"))
        s.flush()
        assertEquals(listOf("Song 1"), sent(svc))
    }

    @Test
    fun aClusterWithAnExactMatchTakesNoShift() = each { svc ->
        records()
        val s = scrobbler()
        account(svc) += Played(u, "Artist", "Song 1")
        onAccount(svc, 2, u + 300 + 32400, from = 2)
        store(s, *tracks(3, u).toTypedArray())
        s.flush()
        assertEquals(listOf("Song 2", "Song 3"), sent(svc))
    }

    @Test
    fun oneShiftedMatchOfTwoCountedListensIsNotEnough() = each { svc ->
        records()
        val s = scrobbler()
        onAccount(svc, 1, u + 32400)
        store(s, *tracks(2, u).toTypedArray())
        s.flush()
        assertEquals(listOf("Song 1", "Song 2"), sent(svc))
    }

    @Test
    fun aBaseRangeLeftUncoveredAfterTheCapTakesNoShift() = each { svc ->
        records()
        val s = scrobbler()
        onAccount(svc, 2, u + 32400)
        account(svc) += (1..1200).map { Played(u + 100, "Artist", "Filler $it") }
        store(s, *tracks(2, u).toTypedArray())
        s.flush()
        assertEquals(listOf("Song 1", "Song 2"), sent(svc))
        assertTrue(logs.contains("scrobble $svc check 2 unchecked after 3 windows"))
    }

    @Test
    fun aTieBetweenTheKindsTakesNeither() = each { svc ->
        records()
        zone = ZoneId.of("Europe/Berlin")
        val s = scrobbler()
        onAccount(svc, 2, u + 32400)
        onAccount(svc, 2, u + 600 + 28800, from = 3)
        store(s, *tracks(4, u).toTypedArray())
        s.flush()
        assertEquals((1..4).map { "Song $it" }, sent(svc))
    }

    @Test
    fun aSummerOffsetReadInALondonJanuaryIsOneCandidateAndSkipped() = each { svc ->
        records()
        zone = ZoneId.of("Europe/London")
        val s = scrobbler()
        onAccount(svc, 3, u + 3600)
        store(s, *tracks(3, u, z = 3600).toTypedArray())
        s.flush()
        assertTrue(sent(svc).isEmpty())
        assertEquals(0, s.pending())
    }

    @Test
    fun anOwnSendAtTheShiftKeepsTheListens() = each { svc ->
        val lines = (0 until 2).joinToString("") { "e\t${900 + it}\t${u + it * 300 + 32400}\tArtist\tSong ${it + 1}\n" }
        records(extra = mapOf(svc to lines))
        val s = scrobbler()
        onAccount(svc, 2, u + 32400)
        store(s, *tracks(2, u).toTypedArray())
        s.flush()
        assertEquals(listOf("Song 1", "Song 2"), sent(svc))
    }

    @Test
    fun aListenBrainzEntryEqualToALastFmSendKeepsTheListens() = each(listOf(Accounts.LISTEN_BRAINZ)) { svc ->
        val lines = (0 until 2).joinToString("") { "e\t${900 + it}\t${u + it * 300 + 32400}\tArtist\tSong ${it + 1}\n" }
        records(extra = mapOf(Accounts.LAST_FM to lines))
        val s = scrobbler()
        onAccount(svc, 2, u + 32400)
        store(s, *tracks(2, u).toTypedArray())
        s.flush()
        assertEquals(listOf("Song 1", "Song 2"), sent(svc))
    }

    @Test
    fun noRecordFileTakesNoShift() = each { svc ->
        val s = scrobbler()
        onAccount(svc, 3, u + 32400)
        store(s, *tracks(3, u).toTypedArray())
        s.flush()
        assertEquals((1..3).map { "Song $it" }, sent(svc))
        assertTrue(File(dir, "own.$svc").readLines().first().startsWith("from\t$nowS"))
    }

    @Test
    fun aShiftRangeBelowTheOtherServicesFromTakesNoShift() = each(listOf(Accounts.LISTEN_BRAINZ)) { svc ->
        records()
        File(dir, "own.${Accounts.LAST_FM}").writeText("from\t${u + 86400}\nc\t0\n")
        val s = scrobbler()
        onAccount(svc, 3, u + 32400)
        store(s, *tracks(3, u).toTypedArray())
        s.flush()
        assertEquals((1..3).map { "Song $it" }, sent(svc))
    }

    @Test
    fun aShiftRangeBelowFromTakesNoShift() = each { svc ->
        records(u + 10 * 3600)
        val s = scrobbler()
        onAccount(svc, 3, u + 32400)
        store(s, *tracks(3, u).toTypedArray())
        s.flush()
        assertEquals((1..3).map { "Song $it" }, sent(svc))
    }

    @Test
    fun aPruneRaisesFromSoARangeBetweenTheOldAndNewFromTakesNoShift() = each(listOf(Accounts.LISTEN_BRAINZ)) { svc ->
        val old = (0 until 10).joinToString("") { "e\t${800 + it}\t${nowS - 16 * 86400 + it}\tArtist\tOld $it\n" }
        records(nowS - 20 * 86400, mapOf(svc to old))
        val s = scrobbler()
        store(s, play(nowS - 86400, "Other", exposed = false))
        s.flush()
        assertEquals("from\t${nowS - OwnRecord.FLOOR_S}", File(dir, "own.$svc").readLines().first())
        val at = nowS - 15 * 86400
        onAccount(svc, 3, at + 32400)
        store(s, *tracks(3, at).toTypedArray())
        s.flush()
        assertEquals(listOf("Other", "Song 1", "Song 2", "Song 3"), sent(svc))
    }

    @Test
    fun aRecordBehindTheSentCursorAtLoadTakesNoShift() = each { svc ->
        records()
        scrobbler().let { s0 -> store(s0, *(1..3).map { play(u - 86400 + it * 60, "Old $it", exposed = false) }.toTypedArray()) }
        File(dir, "sent.$svc").writeText("3")
        val s = scrobbler()
        onAccount(svc, 3, u + 32400)
        store(s, *tracks(3, u).toTypedArray())
        s.flush()
        assertEquals((1..3).map { "Song $it" }, sent(svc))
        assertTrue(File(dir, "own.$svc").readLines().first().startsWith("from\t$nowS"))
    }

    @Test
    fun aFailedRecordWriteTurnsTheShiftOffAndStillMovesTheCursor() = each { svc ->
        records()
        File(dir, "own.$svc").setWritable(false)
        val s = scrobbler()
        store(s, play(u - 86400, "Other", exposed = false))
        s.flush()
        assertEquals(0, s.pending())
        assertTrue(logs.contains("scrobble $svc record: write failed, shift off until a write"))
        onAccount(svc, 3, u + 32400)
        store(s, *tracks(3, u).toTypedArray())
        s.flush()
        assertEquals(listOf("Other", "Song 1", "Song 2", "Song 3"), sent(svc))
    }

    @Test
    fun twoClustersThreeHoursApartGiveTwoBaseReads() = each { svc ->
        records()
        val s = scrobbler()
        store(s, play(u, "Song 1"), play(u + 3 * 3600, "Song 2"))
        s.flush()
        assertEquals(2, reads(svc).size)
        if (svc == Accounts.LAST_FM) {
            assertEquals(listOf("${u - 60}" to "${u + 61}", "${u + 3 * 3600 - 60}" to "${u + 3 * 3600 + 61}"), reads(svc).map { query(it).let { q -> q.getValue("from") to q.getValue("to") } })
        } else {
            assertEquals(listOf("${u + 61}", "${u + 3 * 3600 + 61}"), reads(svc).map { query(it).getValue("max_ts") })
        }
    }

    @Test
    fun anEntryReadTwiceIsClaimedOnce() = each(listOf(Accounts.LISTEN_BRAINZ)) { svc ->
        records()
        val s = scrobbler()
        account(svc) += Played(u + 1, "Artist", "Song 1")
        store(s, play(u, "Song 1"), play(u + 2, "Song 1"), play(u + 3 * 3600, "Song 2"))
        s.flush()
        assertEquals(2, reads(svc).size)
        assertEquals(listOf("Song 1", "Song 2"), sent(svc))
    }

    @Test
    fun lastFmPagesOnToAtTheOldestPlusOneAndKeepsEqualSeconds() = each(listOf(Accounts.LAST_FM)) { svc ->
        records()
        val s = scrobbler()
        account(svc) += (1..199).map { Played(u + 30, "Artist", "Filler $it") }
        account(svc) += listOf(Played(u, "Artist", "Other"), Played(u, "Artist", "Song 1"))
        store(s, play(u, "Song 1"))
        s.flush()
        assertTrue(sent(svc).isEmpty())
        assertEquals(listOf("${u + 61}", "${u + 1}"), reads(svc).map { query(it).getValue("to") })
        assertTrue(reads(svc).none { "page=" in it })
        assertTrue(logs.none { "check off" in it })
    }

    @Test
    fun listenBrainzAsksOneHundredFourHundredAndOneThousandBelowTheOldestPlusOne() = each(listOf(Accounts.LISTEN_BRAINZ)) { svc ->
        records()
        val s = scrobbler()
        account(svc) += (-60L..60L).flatMap { d -> (1..10).map { Played(u + d, "Artist", "Filler $d $it") } }
        account(svc) += Played(u, "Artist", "Song 1")
        store(s, play(u, "Song 1"))
        s.flush()
        assertTrue(sent(svc).isEmpty())
        assertEquals(listOf("100", "400", "1000"), reads(svc).map { query(it).getValue("count") })
        assertEquals(listOf("${u + 61}", "${u + 52}", "${u + 13}"), reads(svc).map { query(it).getValue("max_ts") })
    }

    @Test
    fun lastFmReadsNothingForListensOverFourteenDays() = each(listOf(Accounts.LAST_FM)) { svc ->
        records()
        val s = scrobbler()
        store(s, play(nowS - 15 * 86400, "Song 1"))
        s.flush()
        assertTrue(reads(svc).isEmpty())
        assertTrue(sent(svc).isEmpty())
        assertEquals(0, s.pending())
    }

    @Test
    fun thePlayingTrackIsSkippedAndASingleTrackObjectIsRead() = each(listOf(Accounts.LAST_FM)) { svc ->
        records()
        val s = scrobbler()
        net.on(LFM, { Response(200, lfmBody(listOf(Played(u, "Artist", "Song 1")), 1, Played(0, "Artist", "Song 2")).toByteArray()) })
        store(s, play(u, "Song 1"), play(u + 300, "Song 2"))
        s.flush()
        assertEquals(listOf("Song 2"), sent(svc))
        net.on(LFM, { Response(200, "{\"recenttracks\":{\"track\":${track(Played(u + 86400, "Artist", "Song 3"))},\"@attr\":{\"total\":\"1\"}}}".toByteArray()) })
        store(s, play(u + 86400, "Song 3"))
        s.flush()
        assertEquals(listOf("Song 2"), sent(svc))
    }

    @Test
    fun userNamesWithASpaceAreEncodedInTheReadUrls() = each { svc ->
        accounts = accounts.copy(lastFmUser = "my user", listenBrainzUser = "my user")
        records()
        val s = scrobbler()
        store(s, play(u, "Song 1"))
        s.flush()
        val url = reads(svc).single()
        assertTrue(url, if (svc == Accounts.LAST_FM) "user=my%20user&" in url else "/1/user/my%20user/listens?" in url)
    }

    @Test
    fun aReadIsReusedInsideItsMaxAgeAndReadAgainAfter() = each { svc ->
        records()
        net.age = 600
        val s = scrobbler()
        store(s, play(u, "Song 1"))
        s.flush()
        assertEquals(1, reads(svc).size)
        now += 300_000
        store(s, play(u, "Song 1"))
        s.flush()
        assertEquals(1, reads(svc).size)
        now += 301_000
        store(s, play(u, "Song 1"))
        s.flush()
        assertEquals(2, reads(svc).size)
    }

    @Test
    fun aRangeOverTheCapCutsResumesAndGoesUncheckedInTheThirdWindow() = each { svc ->
        records()
        val s = scrobbler()
        account(svc) += (1..1200).map { Played(u + 30, "Artist", "Filler $it") }
        store(s, play(u - 7200, "Song 0", exposed = false), play(u, "Song 1"))
        s.flush()
        assertEquals(listOf(listOf("Song 0"), listOf("Song 1")), posts(svc).map { (_, b) -> if (svc == Accounts.LAST_FM) query("?$b").filterKeys { it.startsWith("track[") }.values.toList() else Regex("\"track_name\":\"([^\"]+)\"").findAll(b).map { it.groupValues[1] }.toList() })
        assertEquals(15, reads(svc).size)
        assertEquals(1, logs.count { it == "scrobble $svc check 1 unchecked after 3 windows" })
        assertEquals(0, s.pending())
    }

    @Test
    fun aReadRetrySendsThePartBeforeTheFailingClusterAndWaits() = each { svc ->
        records()
        val s = scrobbler()
        net.on(host(svc), retry(svc))
        store(s, play(u - 7200, "Song 0", exposed = false), play(u, "Song 1"))
        assertEquals(Sent.RETRY, s.flush()[svc])
        assertEquals(listOf("Song 0"), sent(svc))
        assertEquals(1, s.pending())
        assertTrue(logs.none { "check off" in it })
    }

    private fun failing(svc: String, at: Long, fail: () -> Response = retry(svc)) {
        net.answer = { _, q ->
            val top = (q["to"] ?: q.getValue("max_ts")).toLong()
            if (top > at - 120 && top <= at + 120) fail() else null
        }
    }

    @Test
    fun threeCountedRetriesOverAnHourSendTheFailingClusterAndKeepACoveredClustersMatches() = each { svc ->
        records()
        val s = scrobbler()
        failing(svc, u)
        onAccount(svc, 1, u + 3 * 3600, from = 2)
        store(s, play(u, "Song 1"), play(u + 3 * 3600, "Song 2"))
        assertEquals(Sent.RETRY, s.flush()[svc])
        now += 1800_000
        assertEquals(Sent.RETRY, s.flush()[svc])
        now += 1801_000
        s.flush()
        assertEquals(listOf("Song 1"), sent(svc))
        assertEquals(0, s.pending())
        assertTrue(logs.contains("scrobble $svc check 1 unchecked after read retries"))
    }

    @Test
    fun aFlushThatThrowsEndsTheCheckSoTheNextOneReadsAgain() = each { svc ->
        records()
        val s = scrobbler()
        net.on("POST ${host(svc)}", { throw IllegalStateException("post") })
        store(s, play(u, "Song 1"))
        assertThrows(IllegalStateException::class.java) { s.flush() }
        onAccount(svc, 1, u)
        s.flush()
        assertEquals(1, posts(svc).size)
        assertEquals(0, s.pending())
    }

    @Test
    fun aReadTimeoutAfterConnectOnThreeFlushesOverAnHourSendsTheClusterUnchecked() = each { svc ->
        records()
        val s = scrobbler()
        failing(svc, u) { throw HttpTimeout() }
        store(s, play(u, "Song 1"))
        assertEquals(Sent.RETRY, s.flush()[svc])
        now += 1800_000
        assertEquals(Sent.RETRY, s.flush()[svc])
        now += 1801_000
        s.flush()
        assertEquals(listOf("Song 1"), sent(svc))
        assertEquals(0, s.pending())
        assertTrue(logs.contains("scrobble $svc check 1 unchecked after read retries"))
    }

    @Test
    fun threeCountedRetriesWithinAnHourKeepWaiting() = each { svc ->
        records()
        val s = scrobbler()
        failing(svc, u)
        store(s, play(u, "Song 1"))
        repeat(3) {
            assertEquals(Sent.RETRY, s.flush()[svc])
            now += 1200_000
        }
        assertTrue(sent(svc).isEmpty())
        assertEquals(1, s.pending())
    }

    @Test
    fun theRetryCountSurvivesANewScrobbler() = each { svc ->
        records()
        failing(svc, u)
        store(scrobbler(), play(u, "Song 1"))
        scrobbler().flush()
        now += 1800_000
        scrobbler().flush()
        now += 1801_000
        val s = scrobbler()
        s.flush()
        assertEquals(listOf("Song 1"), sent(svc))
        assertEquals(0, s.pending())
    }

    @Test
    fun ioOnThreeFlushesOverTwoHoursCountsNothingAndTheWindowIsCheckedOnceOnline() = each { svc ->
        records()
        val s = scrobbler()
        onAccount(svc, 1, u)
        store(s, play(u, "Song 1"))
        net.readsDown = true
        repeat(3) {
            assertEquals(Sent.RETRY, s.flush()[svc])
            now += 3600_000
        }
        net.readsDown = false
        s.flush()
        assertTrue(sent(svc).isEmpty())
        assertEquals(0, s.pending())
    }

    @Test
    fun aPermanentReadFailureSendsUncheckedAndTurnsTheCheckOffForTheSession() {
        val cases = listOf<Pair<String, () -> Response>>(
            Accounts.LAST_FM to { Response(404, "{\"message\":\"User not found\",\"error\":6}".toByteArray()) },
            Accounts.LISTEN_BRAINZ to { Response(404, "{\"code\":404,\"error\":\"Cannot find user\"}".toByteArray()) },
            Accounts.LAST_FM to { Response(200, "{}".toByteArray()) },
            Accounts.LISTEN_BRAINZ to { Response(200, "{\"payload\":{}}".toByteArray()) },
            Accounts.LAST_FM to { Response(200, lfmBody(listOf(Played(u - 10, "Artist", "X"), Played(u + 10, "Artist", "Y")), 2).toByteArray()) },
            Accounts.LISTEN_BRAINZ to { Response(200, lbBody(listOf(Played(u - 10, "Artist", "X"), Played(u + 10, "Artist", "Y"))).toByteArray()) },
        )
        val why = listOf("error 6", "http 404", "shape", "shape", "order", "order")
        for ((i, c) in cases.withIndex()) {
            each(listOf(c.first)) { svc ->
                records()
                val s = scrobbler()
                net.on(host(svc), c.second)
                onAccount(svc, 3, u + 86400)
                store(s, play(u, "Song 1"))
                s.flush()
                assertEquals(listOf("Song 1"), sent(svc))
                assertEquals(listOf("scrobble $svc check off this session: ${why[i]}"), logs.filter { "check off" in it })
                store(s, *tracks(3, u + 86400).toTypedArray())
                s.flush()
                assertEquals(1, reads(svc).size)
                assertEquals(4, sent(svc).size)
                s.session()
                store(s, play(u + 86400, "Song 1"))
                s.flush()
                assertEquals(2, reads(svc).size)
                assertEquals(4, sent(svc).size)
            }
        }
    }

    @Test
    fun noUserNameSendsUnchecked() = each { svc ->
        accounts = accounts.copy(lastFmUser = "", listenBrainzUser = "")
        records()
        val s = scrobbler()
        onAccount(svc, 1, u)
        store(s, play(u, "Song 1"))
        s.flush()
        assertTrue(reads(svc).isEmpty())
        assertEquals(listOf("Song 1"), sent(svc))
        assertTrue(logs.contains("scrobble $svc check off this session: no user"))
        assertFalse(logs.any { "on the account" in it })
    }
}
