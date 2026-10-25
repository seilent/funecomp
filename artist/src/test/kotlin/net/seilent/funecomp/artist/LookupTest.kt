package net.seilent.funecomp.artist

import net.seilent.funecomp.sim.Jpeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class LookupTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val utada = "b539e453-c4fe-47e3-8a07-8517eac74429"
    private val radiohead = "a74b1b7f-71a5-4011-9441-d0b5e4122711"
    private val kobasolo = "4981caf4-856a-4a2f-bdf1-3cfd65c6a532"
    private val ado = "e134b52f-2e9e-4734-9bc3-bea9648d1fa1"
    private val optInHosts = setOf("www.theaudiodb.com", "api.discogs.com", "webservice.fanart.tv", "api.deezer.com", "www.youtube.com")

    private val http = Fixtures()
    private val time = FakeTime()
    private var sources = Sources.NONE
    private val codec = AwtCodec()
    private val cache by lazy { ArtCache(tmp.newFolder("art")) }

    private fun art() = ArtistArt(http, codec, cache, { sources }, "0.1.0", { time.now }, time).also {
        http.now = { time.now }
    }

    private fun mb(): MusicBrainz {
        http.now = { time.now }
        return MusicBrainz(Api(http, "funecomp/0.1.0 (github@seilent.net)", RateLimiter(1000) { time.now }, time.sleep))
    }

    private fun checkJpeg(a: Art, w: Int, h: Int) {
        assertEquals(w to h, Images.size(a.jpeg))
        assertTrue("${a.jpeg.size} bytes", a.jpeg.size <= 262_144)
        assertTrue(Jpeg.baseline(a.jpeg, w, h))
    }

    @Test
    fun jsonParsesNestingEscapesAndNumbers() {
        val j = J.parse("""{"a":[1,-2.5e1,true,null,"xé\n"],"b":{"c":"\"q\""},"d":12345678901}""")
        assertEquals(1L, j["a"][0].long)
        assertEquals(-25L, j["a"][1].long)
        assertEquals(true, j["a"][2].bool)
        assertFalse(j["a"][3].exists)
        assertEquals("xé\n", j["a"][4].str)
        assertEquals("\"q\"", j["b"]["c"].str)
        assertEquals(12345678901L, j["d"].long)
        assertFalse(j["zz"]["y"][3].exists)
    }

    @Test
    fun jsonUnicodeEscapesAndSurrogatePairs() {
        assertEquals("é", J.parse("\"\\u00e9\"").str)
        assertEquals("É", J.parse("\"\\u00C9\"").str)
        assertEquals("\uD83C\uDFB5", J.parse("\"\\ud83c\\udfb5\"").str)
        assertEquals(0x1F3B5, J.parse("\"\\ud83c\\udfb5\"").str!!.codePointAt(0))
        assertEquals("宇多田", J.parse("\"\\u5b87\\u591a\\u7530\"").str)
    }

    @Test
    fun jsonRejectsMalformedInput() {
        val bad = listOf(
            "", "{", "{\"a\":}", "[1,]", "[1 2]", "{\"a\" 1}", "\"abc", "tru", "nul", "-", "1 2",
            "\"\\u-001\"", "\"\\u+041\"", "\"\\u12\"", "\"\\u12g4\"", "\"\\u１２３４\"", "\"\\x\"",
        )
        for (b in bad) {
            try {
                J.parse(b)
                throw AssertionError("parsed $b")
            } catch (e: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun jsonDepthIsCapped() {
        val ok = "[".repeat(J.MAX_DEPTH) + "]".repeat(J.MAX_DEPTH)
        assertTrue(J.parse(ok).exists)
        for (deep in listOf("[".repeat(J.MAX_DEPTH + 1) + "]".repeat(J.MAX_DEPTH + 1), "[".repeat(100_000), "{\"a\":".repeat(100_000))) {
            try {
                J.parse(deep)
                throw AssertionError("parsed depth ${deep.length}")
            } catch (e: IllegalArgumentException) {
            }
        }
        val api = Api({ _, _ -> Response(200, "[".repeat(100_000).toByteArray()) }, "ua", RateLimiter(0) { 0 }, {})
        try {
            api.json("https://x/")
            throw AssertionError("no IOException")
        } catch (e: IOException) {
        }
    }

    @Test
    fun redirectsAndClientErrorsGiveNoResultAndHttpIsUpgraded() {
        val seen = ArrayList<String>()
        var code = 301
        val api = Api({ u, _ ->
            seen += u
            Response(code, ByteArray(0))
        }, "ua", RateLimiter(0) { 0 }, {})
        assertNull(api.get("http://example.org/a.jpg"))
        assertEquals("https://example.org/a.jpg", seen.single())
        code = 404
        assertNull(api.get("https://example.org/b"))
        for (c in listOf(502, 401, 403)) {
            code = c
            try {
                api.get("https://example.org/c")
                throw AssertionError("no IOException for $c")
            } catch (e: IOException) {
            }
        }
    }

    @Test
    fun aFanartKeyChangeRetriesAMiss() {
        sources = Sources.NONE.copy(fanartTv = true, fanartTvKey = "one")
        assertNull(art().get("Ado", ado, 384, 480))
        val n = http.calls.size
        assertNull(art().get("Ado", ado, 384, 480))
        assertEquals(n, http.calls.size)
        sources = Sources.NONE.copy(fanartTv = true, fanartTvKey = "two")
        assertNull(art().get("Ado", ado, 384, 480))
        assertTrue(http.calls.size > n)
    }

    @Test
    fun forbiddenIsNotCachedAsAMiss() {
        sources = Sources.NONE.copy(fanartTv = true, fanartTvKey = "bad")
        val n = http.calls.size
        val api = "https://webservice.fanart.tv/v3/music/$ado?api_key=bad"
        http.queued[api] = ArrayDeque(listOf(Response(403, ByteArray(0))))
        try {
            art().get("Ado", ado, 384, 480)
            throw AssertionError("no IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("403"))
        }
        assertTrue(http.calls.size > n)
        assertNull(cache.miss(ado))
    }

    @Test
    fun longRetryAfterFailsInsteadOfBlocking() {
        val m = mb()
        val url = "https://musicbrainz.org/ws/2/artist/?query=Ado&fmt=json&limit=10"
        http.queued[url] = ArrayDeque(listOf(Response(429, ByteArray(0), mapOf("retry-after" to "3600"))))
        try {
            m.search("Ado")
            throw AssertionError("no IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("retry after"))
        }
        assertTrue(time.sleeps.all { it <= 60_000 })
    }

    @Test
    fun oneSourceErrorDoesNotEndTheChain() {
        sources = Sources.NONE.copy(theAudioDb = true, discogs = true)
        val adb = "https://www.theaudiodb.com/api/v1/json/123/artist-mb.php?i=$radiohead"
        http.queued[adb] = ArrayDeque(listOf(Response(500, ByteArray(0))))
        assertEquals(Sources.DISCOGS, art().get("Radiohead", radiohead, 384, 480)!!.source)

        cache.clear()
        sources = Sources.NONE.copy(theAudioDb = true)
        http.queued[adb] = ArrayDeque(listOf(Response(500, ByteArray(0))))
        try {
            art().get("Radiohead", radiohead, 384, 480)
            throw AssertionError("no IOException")
        } catch (e: IOException) {
        }
        assertNull(cache.miss(radiohead))
        assertEquals(Sources.THE_AUDIO_DB, art().get("Radiohead", radiohead, 384, 480)!!.source)
    }

    @Test
    fun corruptCacheMetaIsIgnored() {
        val dir = tmp.newFolder("bad")
        val c = ArtCache(dir)
        c.putMiss("k", 5, setOf("commons"))
        dir.listFiles()!!.filter { it.name.endsWith(".meta") }.forEach { it.writeText("state=miss\nat=zz\n") }
        assertEquals(0L, c.miss("k")!!.at)
        dir.listFiles()!!.filter { it.name.endsWith(".meta") }.forEach { it.writeText("state=hit\\u12") }
        assertNull(c.hit("k"))
        assertNull(c.miss("k"))
    }

    @Test
    fun romajiFindsTheKanjiArtistByAlias() {
        val m = mb()
        assertEquals(utada, m.search("Utada Hikaru"))
        assertEquals(utada, m.search("宇多田ヒカル"))
        assertEquals(Names.fold("Utada Hikaru"), Names.fold("  utada  HIKARU "))
        assertEquals("utada", Names.fold("Ｕｔａｄａ"))
        assertTrue(http.calls.all { it.contains("?query=") && !it.contains("artist%3A") })
        assertTrue(http.agents.all { it == "funecomp/0.1.0 (github@seilent.net)" })
    }

    @Test
    fun searchAcceptsOnlyAnExactNameOrAlias() {
        val m = mb()
        assertNull(m.search("Radiohead feat. Thom Yorke"))
        assertEquals(radiohead, m.search("Radiohead"))
        assertEquals(ado, m.search("Ado"))
        assertEquals(kobasolo, m.search("コバソロ"))
        assertTrue(http.missing.isEmpty())
    }

    @Test
    fun musicBrainzIsLimitedToOneRequestPerSecondAndBacksOffOn503() {
        val m = mb()
        val url = "https://musicbrainz.org/ws/2/artist/?query=Radiohead&fmt=json&limit=10"
        http.queued[url] = ArrayDeque(listOf(Response(503, Fixtures.load("mb-503.json")), Response(503, Fixtures.load("mb-503.json"))))
        assertEquals(radiohead, m.search("Radiohead"))
        assertEquals(utada, m.search("Utada Hikaru"))
        assertEquals(ado, m.search("Ado"))
        val at = http.times.map { it.first }
        assertEquals(5, at.size)
        assertTrue("first backoff ${at[1] - at[0]}", at[1] - at[0] >= 2000)
        assertTrue("second backoff ${at[2] - at[1]}", at[2] - at[1] >= 4000)
        at.zipWithNext().forEach { (a, b) -> assertTrue("gap ${b - a}", b - a >= 1000) }

        val busy = mb()
        http.queued[url] = ArrayDeque(List(4) { Response(503, Fixtures.load("mb-503.json")) })
        try {
            busy.search("Radiohead")
            throw AssertionError("no IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("busy"))
        }
    }

    @Test
    fun retryAfterHeaderWins() {
        val m = mb()
        val url = "https://musicbrainz.org/ws/2/artist/?query=Ado&fmt=json&limit=10"
        http.queued[url] = ArrayDeque(listOf(Response(429, ByteArray(0), mapOf("retry-after" to "7"))))
        assertEquals(ado, m.search("Ado"))
        assertTrue(http.times[1].first - http.times[0].first >= 7000)
    }

    @Test
    fun freeChainGivesTheP18PhotoWithItsCredit() {
        val a = art().get("Utada Hikaru", null, 384, 480)
        assertNotNull(a)
        a!!
        assertEquals(Wikimedia.SOURCE, a.source)
        assertEquals(utada, a.mbid)
        assertEquals(Credit("Goldnrush Podcast", "CC BY 4.0", "https://commons.wikimedia.org/wiki/File:Hikaru_Utada_at_Spotify_Tokyo.png", "https://creativecommons.org/licenses/by/4.0"), a.credit)
        checkJpeg(a, 384, 480)
        assertTrue(http.hosts().none { it in optInHosts })
        assertTrue(http.calls.any { it.startsWith("https://ja.wikipedia.org/") })
        assertTrue(http.missing.isEmpty())

        val n = http.calls.size
        val lookup = art()
        assertNotNull(lookup.cached("Utada Hikaru", null))
        assertNotNull(lookup.cached("宇多田ヒカル", utada))
        assertNull(lookup.cached("Radiohead", null))
        val e = lookup.cachedEntry("宇多田ヒカル", utada)!!
        assertEquals(utada, e.key)
        assertEquals(e.at, lookup.cachedEntry("Utada Hikaru", null)!!.at)
        assertNull(lookup.cachedEntry("Radiohead", null))
        assertEquals(listOf("Utada Hikaru" to a.credit), lookup.credits().map { it.name to it.credit })
        assertEquals(n, http.calls.size)
    }

    @Test
    fun wideCompositeIsSkippedAndOptInSourcesStayOff() {
        assertNull(art().get("Radiohead", null, 384, 480))
        assertTrue(http.calls.none { it.contains("RadioheadO2211125") && it.contains("upload.wikimedia.org") })
        assertTrue(http.calls.none { it.contains("thumb.wikimedia.org") })
        assertTrue(http.hosts().none { it in optInHosts })
    }

    @Test
    fun optInSourcesRunOnlyWhenEnabled() {
        sources = Sources.NONE.copy(theAudioDb = true)
        val a = art().get("Radiohead", radiohead, 384, 480)!!
        assertEquals(Sources.THE_AUDIO_DB, a.source)
        assertNull(a.credit)
        checkJpeg(a, 384, 480)
        assertTrue(http.hosts().none { it in optInHosts - "www.theaudiodb.com" })
        assertTrue(http.calls.any { it.startsWith("https://r2.theaudiodb.com/images/media/artist/thumb/") })

        cache.clear()
        http.calls.clear()
        sources = Sources.NONE.copy(discogs = true)
        assertEquals(Sources.DISCOGS, art().get("Radiohead", radiohead, 384, 480)!!.source)
        assertTrue(http.calls.contains("https://api.discogs.com/artists/3840"))

        cache.clear()
        sources = Sources.NONE.copy(deezer = true)
        assertEquals(Sources.DEEZER, art().get("Radiohead", radiohead, 384, 480)!!.source)

        cache.clear()
        http.calls.clear()
        sources = Sources.NONE.copy(fanartTv = true)
        assertNull(art().get("Radiohead", radiohead, 384, 480))
        assertTrue(http.hosts().none { it == "webservice.fanart.tv" })
        assertTrue(http.missing.isEmpty())
    }

    @Test
    fun youtubeChannelAvatarFromTheMusicBrainzRel() {
        assertNull(art().get("コバソロ", kobasolo, 384, 480))
        assertTrue(http.hosts().none { it == "www.youtube.com" })
        sources = Sources.NONE.copy(youtube = true)
        val a = art().get("コバソロ", kobasolo, 384, 480)!!
        assertEquals(Sources.YOUTUBE, a.source)
        checkJpeg(a, 384, 480)
        assertTrue(http.calls.contains("https://www.youtube.com/channel/UCDbQblY1XASbgqOXmy6FOFQ"))
        assertTrue(http.calls.last().startsWith("https://yt3.googleusercontent.com/ytc/AIdro_l2Ct30OyvxG3NA"))
        assertEquals(
            "https://yt3.googleusercontent.com/ytc/AIdro_l2Ct30OyvxG3NA-y_3rn79eJG0sygrJ66vTBxQb0J71xo=s900-c-k-c0x00ffffff-no-rj",
            YouTube.ogImage(Fixtures.text("yt-kobasolo-head.html")),
        )
        assertTrue(http.missing.isEmpty())
    }

    @Test
    fun nothingFoundIsCachedUntilASourceIsEnabledOrItExpires() {
        assertNull(art().get("Ado", null, 384, 480))
        val n = http.calls.size
        assertNull(art().get("Ado", null, 384, 480))
        assertEquals(n, http.calls.size)
        assertNull(art().get("ado", ado, 384, 480))
        assertEquals(n, http.calls.size)

        sources = Sources.NONE.copy(youtube = true)
        val a = art().get("Ado", null, 384, 480)
        assertTrue(http.calls.size > n)
        assertTrue(http.calls.contains("https://www.youtube.com/channel/UCln9P4Qm3-EAY4aiEPmRwEA"))
        assertNull(a)

        sources = Sources.NONE
        val m = http.calls.size
        assertNull(art().get("Ado", null, 384, 480))
        assertEquals(m, http.calls.size)
        time.now += ArtistArt.TTL_MS
        assertNull(art().get("Ado", null, 384, 480))
        assertTrue(http.calls.size > m)
    }

    @Test
    fun aSweepMarkEndsAtTheFirstMissExpiryOrASourceChange() {
        val a = art()
        val t = time.now
        assertNull(a.get("Ado", null, 384, 480))
        time.now += 1000
        a.beginMark()
        assertNull(a.get("Ado", null, 384, 480))
        val m = a.mark()
        assertTrue(a.holds(m))
        time.now = t + ArtistArt.TTL_MS - 1
        assertTrue(a.holds(m))
        time.now = t + ArtistArt.TTL_MS
        assertFalse(a.holds(m))
        a.beginMark()
        val n = a.mark()
        assertTrue(a.holds(n))
        sources = Sources.NONE.copy(youtube = true)
        assertFalse(a.holds(n))
    }

    @Test
    fun hitIsServedFromCacheForBothBoardsAndDroppedWhenItsSourceIsOff() {
        sources = Sources.NONE.copy(theAudioDb = true)
        val first = art().get("Radiohead", radiohead, 384, 480)!!
        val n = http.calls.size
        val again = art().get("Radiohead", radiohead, 384, 480)!!
        assertTrue(first.jpeg.contentEquals(again.jpeg))
        val v1 = art().get("Radiohead", radiohead, 256, 320)!!
        checkJpeg(v1, 256, 320)
        assertEquals(n, http.calls.size)
        assertEquals(listOf("Radiohead"), cache.entries().map { it.name })

        sources = Sources.NONE
        assertNull(art().get("Radiohead", radiohead, 384, 480))
        assertTrue(http.calls.size > n)
        assertTrue(cache.entries().isEmpty())
    }

    @Test
    fun transientErrorsAreNotCachedAsMisses() {
        val url = "https://musicbrainz.org/ws/2/artist/$utada?inc=url-rels&fmt=json"
        http.queued[url] = ArrayDeque(listOf(Response(500, ByteArray(0))))
        try {
            art().get("宇多田ヒカル", utada, 384, 480)
            throw AssertionError("no IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("500"))
        }
        assertNull(cache.miss(utada))
        assertNotNull(art().get("宇多田ヒカル", utada, 384, 480))
    }

    @Test
    fun mbidFromStateSkipsTheSearch() {
        art().get("宇多田ヒカル", utada.uppercase(), 384, 480)
        assertTrue(http.calls.none { it.contains("?query=") })
    }

    @Test
    fun outputFitsBothBoardsUnder256kB() {
        for ((w, h) in listOf(360 to 480, 240 to 320)) {
            codec.qualities.clear()
            val jpeg = Backdrop.render(codec, Images.png(Images.noise(2000, 1500)), w, h)!!
            assertEquals(w to h, Images.size(jpeg))
            assertTrue("${jpeg.size}", jpeg.size <= Backdrop.MAX_BYTES)
            assertTrue(Jpeg.baseline(jpeg, w, h))
        }
        val asked = ArrayList<Int>()
        val big = object : Picture {
            override val w = 1000
            override val h = 1000
            override fun face() = null
            override fun jpeg(crop: Rect, w: Int, h: Int, quality: Int): ByteArray {
                asked += quality
                return ByteArray(if (quality > 70) 300_000 else 200_000)
            }
        }
        assertEquals(200_000, Backdrop.render(big, 384, 480)!!.size)
        assertEquals(listOf(90, 85, 80, 75, 70), asked)
        assertNull(Backdrop.render(codec, Images.jpeg(Images.photo(300, 300), 90), 384, 480))
        assertNull(Backdrop.render(codec, byteArrayOf(1, 2, 3), 384, 480))
    }

    @Test
    fun theRenderIsCentredAcrossWhereverTheFaceIs() {
        val src = Images.photo(1600, 900)
        val seen = ArrayList<Rect>()
        val c = object : Codec {
            override fun decode(bytes: ByteArray, maxSide: Int): Picture {
                val p = AwtCodec { _, _ -> 1400.0 to 300.0 }.decode(bytes, maxSide)!!
                return object : Picture by p {
                    override fun jpeg(crop: Rect, w: Int, h: Int, quality: Int): ByteArray {
                        seen += crop
                        return p.jpeg(crop, w, h, quality)
                    }
                }
            }
        }
        Backdrop.render(c, Images.jpeg(src, 90), 384, 480)!!
        assertEquals(Rect(440, 0, 720, 900), seen.first())
    }

    @Test
    fun commonsFileNamesAndCreditText() {
        assertEquals("Utada Hikaru 2018 (3).jpg", Wikimedia.commonsFile("https://commons.wikimedia.org/wiki/File:Utada_Hikaru_2018_(3).jpg"))
        assertNull(Wikimedia.commonsFile("https://www.generasia.com/w/images/c/cd/Utada_Hikaru_(2008).jpg"))
        assertEquals("a & b", Wikimedia.text("<a href=\"x\">a</a> &amp;\n b"))
        assertEquals("é é a b", Wikimedia.text("&#233; &#xE9; a&nbsp;b"))
        assertEquals("&bogus; &#99999999;", Wikimedia.text("&bogus; &#99999999;"))
        assertEquals("ja", Wikimedia.lang("JP"))
        assertEquals("en", Wikimedia.lang(null))
    }

    @Test
    fun aTaggedJoinFindsTheArtistByItsCleanedNameAndCachesItUnderTheOriginal() {
        val name = "宇多田ヒカル/Utada Hikaru【公式】"
        val a = art().get(name, null, 240, 320)
        assertTrue("missing ${http.missing}", http.missing.isEmpty())
        checkJpeg(a!!, 240, 320)
        assertEquals(name, a.name)
        assertEquals(utada, a.mbid)
        val n = http.calls.size
        assertTrue(art().get(name, null, 240, 320) != null)
        assertEquals(n, http.calls.size)
        assertEquals(listOf(name), cache.entries().map { it.name })
        assertTrue(art().cachedEntry(name, null) != null)
    }

    @Test
    fun theTagMbidWinsOverNameVariants() {
        assertTrue(art().get("宇多田ヒカル/Utada Hikaru【公式】", utada, 240, 320) != null)
        assertTrue(http.calls.none { it.contains("query=") })
    }

    @Test
    fun everyCleanedNameIsTriedBeforeAMissIsCached() {
        sources = Sources.NONE.copy(deezer = true)
        val name = "空星きらめ/Sorahoshi Kirame【にじさんじ】"
        assertNull(art().get(name, null, 240, 320))
        assertTrue("missing ${http.missing}", http.missing.isEmpty())
        for (q in listOf("空星きらめ/Sorahoshi Kirame【にじさんじ】", "空星きらめ/Sorahoshi Kirame", "空星きらめ", "Sorahoshi Kirame")) {
            assertTrue(q, http.calls.any { it == "https://api.deezer.com/search/artist?q=${Api.enc(q)}" })
        }
        val n = http.calls.size
        assertNull(art().get(name, null, 240, 320))
        assertEquals(n, http.calls.size)
        assertTrue(cache.miss("name:${Names.fold(name)}")!!.cleaned)
    }

    @Test
    fun aMissFromBeforeTheCleanerIsLookedUpOnceMore() {
        sources = Sources.NONE.copy(deezer = true)
        val name = "空星きらめ/Sorahoshi Kirame【にじさんじ】"
        val key = "name:${Names.fold(name)}"
        cache.putMiss(key, time.now, sources.enabled)
        val f = java.io.File(tmp.root, "art").listFiles { x -> x.name.endsWith(".meta") }!!.single()
        f.writeText(f.readText().lines().filter { !it.startsWith("cleaned") }.joinToString("\n"))
        assertTrue(!cache.miss(key)!!.cleaned)
        assertNull(art().get(name, null, 240, 320))
        assertTrue(http.calls.any { it.contains("q=Sorahoshi") })
        assertTrue(cache.miss(key)!!.cleaned)
    }

    @Test
    fun aTopScoreSortNameInTagOrderIsAccepted() {
        assertEquals("2339cd33-4563-4c2a-b60d-4c074a67abff", mb().search("Tenjin Kotone"))
        assertTrue("missing ${http.missing}", http.missing.isEmpty())
    }

    @Test
    fun aSortNameMatchBelowTheTopOrUnder100IsRejected() {
        val url = "https://musicbrainz.org/ws/2/artist/?query=Mori%20Calliope&fmt=json&limit=10"
        fun reply(vararg a: Triple<String, String, Int>) = Response(200, a.joinToString(",", "{\"artists\":[", "]}") { (id, sort, score) ->
            "{\"id\":\"$id\",\"name\":\"x$score\",\"sort-name\":\"$sort\",\"score\":$score}"
        }.toByteArray())
        val a = "11111111-1111-1111-1111-111111111111"
        val b = "22222222-2222-2222-2222-222222222222"
        http.queued[url] = ArrayDeque(listOf(
            reply(Triple(a, "Other, Name", 100), Triple(b, "Mori, Calliope", 100)),
            reply(Triple(a, "Mori, Calliope", 90)),
            reply(Triple(a, "Mori, Calliope", 100)),
        ))
        assertNull(mb().search("Mori Calliope"))
        assertNull(mb().search("Mori Calliope"))
        assertEquals(a, mb().search("Mori Calliope"))
    }

    @Test
    fun aHiraganaNameFindsItsKatakanaOnlyArtist() {
        assertEquals("6ac7acd1-2720-4733-9f98-cc5163bdce21", mb().search("むと"))
        assertTrue("missing ${http.missing}", http.missing.isEmpty())
    }

    private fun kanaReply(vararg a: Pair<String, String>) = Response(200, a.joinToString(",", "{\"artists\":[", "]}") { (id, name) ->
        "{\"id\":\"$id\",\"name\":\"$name\",\"sort-name\":\"Sato\",\"score\":100}"
    }.toByteArray())

    @Test
    fun anExactNameWinsOverAKanaNamesakeRankedAbove() {
        val a = "11111111-1111-1111-1111-111111111111"
        val b = "22222222-2222-2222-2222-222222222222"
        http.queued["https://musicbrainz.org/ws/2/artist/?query=${Api.enc("さとう")}&fmt=json&limit=10"] = ArrayDeque(listOf(kanaReply(a to "サトウ", b to "さとう")))
        assertEquals(b, mb().search("さとう"))
    }

    @Test
    fun deezerTakesTheSameCaseNameFirstThenFans() {
        http.now = { time.now }
        val dz = Deezer(Api(http, "ua", RateLimiter(0) { time.now }, time.sleep))
        fun order(q: String) = dz.candidates(q).map { ImageIds.of(Sources.DEEZER, it.url) }
        val title = "26cc4f7884f4a1122ee11c72fcbff31d"
        val upper = "7d0d00dbfa6f87119195f6edb1a83170"
        assertEquals(listOf(upper, title), order("SHIHO"))
        assertEquals(listOf(title, upper), order("Shiho"))
        assertEquals(listOf(title, upper), order("shiho"))
        assertTrue("missing ${http.missing}", http.missing.isEmpty())
    }

    @Test
    fun musicBrainzTakesTheSameCaseNameAmongFoldedMatches() {
        val a = "11111111-1111-1111-1111-111111111111"
        val b = "22222222-2222-2222-2222-222222222222"
        for (q in listOf("SHIHO", "Shiho", "shiho")) {
            http.queued["https://musicbrainz.org/ws/2/artist/?query=${Api.enc(q)}&fmt=json&limit=10"] =
                ArrayDeque(listOf(kanaReply(a to "SHIHO", b to "Shiho")))
        }
        assertEquals(a, mb().search("SHIHO"))
        assertEquals(b, mb().search("Shiho"))
        assertEquals(a, mb().search("shiho"))
    }

    @Test
    fun twoKanaOnlyMatchesGiveNoMbid() {
        val a = "11111111-1111-1111-1111-111111111111"
        val b = "22222222-2222-2222-2222-222222222222"
        http.queued["https://musicbrainz.org/ws/2/artist/?query=${Api.enc("さとう")}&fmt=json&limit=10"] = ArrayDeque(listOf(kanaReply(a to "サトウ", b to "ｻﾄｳ")))
        assertNull(mb().search("さとう"))
    }

    @Test
    fun aTopSortNameMatchWinsOverASingleKanaMatch() {
        val a = "11111111-1111-1111-1111-111111111111"
        val b = "22222222-2222-2222-2222-222222222222"
        val body = "{\"artists\":[{\"id\":\"$a\",\"name\":\"x\",\"sort-name\":\"さとう, たろう\",\"score\":100},{\"id\":\"$b\",\"name\":\"サトウ タロウ\",\"sort-name\":\"y\",\"score\":90}]}"
        http.queued["https://musicbrainz.org/ws/2/artist/?query=${Api.enc("さとう たろう")}&fmt=json&limit=10"] = ArrayDeque(listOf(Response(200, body.toByteArray())))
        assertEquals(a, mb().search("さとう たろう"))
    }

    @Test
    fun kanaChangesOnlySearchesWithNoExactMatch() {
        val prefix = "https://musicbrainz.org/ws/2/artist/?query="
        fun old(all: List<J>, q: String): String? {
            val want = Names.fold(q)
            val top = all.firstOrNull()?.takeIf { it["score"].long == 100L && Names.fold(it["sort-name"].str?.replace(", ", " ")) == want }
            val folded = all.filter { a ->
                Names.fold(a["name"].str) == want || Names.fold(a["sort-name"].str) == want || a["aliases"].list.any { Names.fold(it["name"].str) == want }
            }
            val same = folded.firstOrNull { a -> a["name"].str?.trim() == q.trim() || a["sort-name"].str?.trim() == q.trim() || a["aliases"].list.any { it["name"].str?.trim() == q.trim() } }
            return (same ?: folded.firstOrNull() ?: top)?.get("id")?.str
        }
        val rows = Fixtures.text("urls.tsv").lines().filter { it.startsWith(prefix) }.map { line ->
            val q = java.net.URLDecoder.decode(line.removePrefix(prefix).substringBefore('&'), "UTF-8").replace(Regex("\\\\(.)"), "$1")
            Triple(q, old(J.parse(Fixtures.text(line.substringAfter('\t')))["artists"].list, q), mb().search(q))
        }
        assertTrue("missing ${http.missing}", http.missing.isEmpty())
        assertEquals(44, rows.size)
        assertEquals(
            listOf(Triple("むと", null, "6ac7acd1-2720-4733-9f98-cc5163bdce21"), Triple("アイミョン", null, "908d9ac2-5187-4b9e-b281-601c6afb0791")),
            rows.filter { it.second != it.third },
        )
        val homographs = mapOf(
            "さくら" to "224292b6-7684-4a98-aed7-a67d61c91840",
            "サクラ" to "28e26449-27e4-4081-a6ba-42fb9f5b9028",
            "ひとみ" to "3dca9aac-3d04-4505-bba1-885422108e83",
            "ヒトミ" to "3d40b48a-7e19-4313-904e-b104598a8acf",
            "ゆず" to "d796574f-db13-41d2-9dd7-82a55c5bd71f",
            "ユズ" to "2e3b1726-6521-40c3-99b5-bb22a3f2f6e1",
            "あいみょん" to "908d9ac2-5187-4b9e-b281-601c6afb0791",
            "みゆき" to "fa88b2a6-27d3-48b8-bbd2-4793ac8a79e0",
            "ミユキ" to "2dfcdf4c-9d2d-4fc4-9d93-739141375271",
            "ヨルシカ" to "dfc6a151-3792-4695-8fda-f64723eaa788",
        )
        for ((q, id) in homographs) {
            val r = rows.single { it.first == q }
            assertEquals(q, id, r.second)
            assertEquals(q, id, r.third)
        }
    }

    @Test
    fun aChannelSuffixedNameFindsTheArtistByItsBareName() {
        val dazbee = "10623c33-205c-49f4-b8a0-55a105dd4fa3"
        sources = Sources.NONE.copy(deezer = true)
        val a = art().get("DAZBEE official", null, 240, 320)
        assertTrue("missing ${http.missing}", http.missing.isEmpty())
        assertEquals(dazbee, a!!.mbid)
        assertEquals("DAZBEE official", a.name)
        assertNotNull(art().cachedEntry("DAZBEE official", null))
    }

    @Test
    fun aMissCleanedUnderTheOlderRulesIsLookedUpOnceMore() {
        sources = Sources.NONE.copy(deezer = true)
        val name = "DAZBEE official"
        val key = "name:${Names.fold(name)}"
        cache.putMiss(key, time.now, sources.enabled)
        val f = java.io.File(tmp.root, "art").listFiles { x -> x.name.endsWith(".meta") }!!.single()
        f.writeText(f.readText().lines().map { if (it.startsWith("cleaned")) "cleaned=true" else it }.joinToString("\n"))
        assertFalse(cache.miss(key)!!.cleaned)
        assertNotNull(art().get(name, null, 240, 320))
        assertTrue(http.calls.any { it.contains("query=DAZBEE&") })
    }

    @Test
    fun theChannelRuleStripsOnlySuffixedNamesInEveryFixture() {
        val names = HashSet<String>()
        for (line in Fixtures.text("urls.tsv").lines().filter { it.endsWith(".json") }) {
            val url = line.substringBefore('\t')
            val body = J.parse(Fixtures.text(line.substringAfter('\t')))
            when {
                url.startsWith("https://musicbrainz.org/ws/2/artist/?query=") -> {
                    names += java.net.URLDecoder.decode(url.substringAfter("query=").substringBefore('&'), "UTF-8").replace(Regex("\\\\(.)"), "$1")
                    for (a in body["artists"].list) {
                        names += listOfNotNull(a["name"].str, a["sort-name"].str) + a["aliases"].list.mapNotNull { it["name"].str }
                    }
                }
                url.startsWith("https://api.deezer.com/search/artist?q=") -> {
                    names += java.net.URLDecoder.decode(url.substringAfter("q="), "UTF-8")
                    names += body["data"].list.mapNotNull { it["name"].str }
                }
            }
        }
        assertTrue(names.size > 600)
        val stripped = names.filter { Names.bare(it) != null }
        assertTrue("Aoi ch." in names)
        assertEquals(setOf("Bruce Channel", "DAZBEE official", "Fatz Official", "Iris Official", "NÄO Official", "NÄO Official Channel", "Suisei Channel"), stripped.toSet())
        assertEquals(listOf("Bruce", "DAZBEE", "Fatz", "Iris", "NÄO", "NÄO Official", "Suisei"), stripped.mapNotNull { Names.bare(it) }.sorted())
    }
}
