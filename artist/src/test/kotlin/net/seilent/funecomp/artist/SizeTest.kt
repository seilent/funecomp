package net.seilent.funecomp.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SizeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val kobasolo = "4981caf4-856a-4a2f-bdf1-3cfd65c6a532"
    private val og = "https://yt3.googleusercontent.com/ytc/AIdro_l2Ct30OyvxG3NA-y_3rn79eJG0sygrJ66vTBxQb0J71xo=s900-c-k-c0x00ffffff-no-rj"
    private fun yt(side: Int) = og.replace("=s900", "=s$side")

    private val name = "Sztest"
    private val dzSearch = "https://api.deezer.com/search/artist?q=$name"
    private val mbSearch = "https://musicbrainz.org/ws/2/artist/?query=$name&fmt=json&limit=10"
    private fun dz(hash: String, side: Int = 1000) = "https://cdn-images.dzcdn.net/images/artist/$hash/${side}x$side-000000-80-0-0.jpg"
    private val one = "11111111111111111111111111111111"
    private val two = "22222222222222222222222222222222"

    private val http = Fixtures()
    private val time = FakeTime()
    private val cache by lazy { ArtCache(tmp.newFolder("art")) }
    private val blocks by lazy { ArtBlocks(tmp.root.resolve("artblock.tsv")) }
    private var sources = Sources.NONE.copy(youtube = true)
    private var targets = Targets(Size(720, 1680))
    private var placeholders = Deezer.PLACEHOLDERS

    private val picks by lazy { ArtPicks(tmp.root.resolve("artpick.tsv")) }

    private fun art() = ArtistArt(http, AwtCodec(), cache, { sources }, "0.1.0", { time.now }, time, blocks = blocks, picks = picks, placeholders = placeholders, targets = { targets })

    private fun serve(url: String, bytes: ByteArray) {
        http.queued.getOrPut(url) { ArrayDeque() } += Response(200, bytes)
    }

    private fun serve(url: String, spec: String) = serve(url, Fixtures.gen(spec))

    private fun stored(key: String): Size = Images.size(cache.source(key)!!).let { Size(it.first, it.second) }

    private fun deezer(vararg hashes: String) {
        sources = Sources.NONE.copy(deezer = true)
        http.queued.getOrPut(mbSearch) { ArrayDeque() } += Response(404, ByteArray(0))
        val data = hashes.joinToString(",") { "{\"name\":\"$name\",\"nb_fan\":1,\"picture_medium\":\"${dz(it)}\",\"picture_xl\":\"${dz(it)}\"}" }
        http.queued.getOrPut(dzSearch) { ArrayDeque() } += Response(200, "{\"data\":[$data]}".toByteArray())
    }

    @Test
    fun theScoreIsTheWorstCropRatioOverBothPhoneOrientations() {
        val t = Targets(Size(720, 1680))
        assertEquals(listOf(Size(720, 1680), Size(1680, 720)), t.sizes)
        assertEquals(1680, t.side)
        assertEquals(1.0, t.score(1680, 1680), 1e-9)
        assertEquals(385.0 / 720, t.score(900, 900), 1e-9)
        assertEquals(642.0 / 720, t.score(3000, 1500), 1e-9)
        assertEquals(Double.MAX_VALUE, Targets.NONE.score(10, 10), 0.0)
        assertEquals(0.0, t.score(0, 0), 0.0)
        assertEquals(2520, t.width(6000, 4000))
        assertEquals(1680, t.width(1500, 2000))
        assertEquals(Size(480, 800), Size.parse("480x800"))
        assertNull(Size.parse("0x800"))
        assertNull(Size.parse("bad"))
    }

    @Test
    fun youtubeTakesTheOriginalWhenItIsNotAboveTheTarget() {
        serve(yt(0), "1200x1200@5")
        assertEquals(Sources.YOUTUBE, art().get("コバソロ", kobasolo, 360, 480)!!.source)
        val e = cache.hit(kobasolo)!!
        assertEquals(yt(0), e.url)
        assertEquals(ImageIds.of(Sources.YOUTUBE, og), e.id)
        assertEquals(Size(1200, 1200), stored(kobasolo))
        assertFalse(og in http.calls)
        assertFalse(yt(1680) in http.calls)
    }

    @Test
    fun aYoutubeOriginalAboveTheTargetWithinTheByteCapIsKept() {
        serve(yt(0), "3000x3000@5")
        art().get("コバソロ", kobasolo, 360, 480)!!
        assertEquals(yt(0), cache.hit(kobasolo)!!.url)
        assertEquals(Size(3000, 3000), stored(kobasolo))
        assertFalse(yt(1680) in http.calls)
    }

    @Test
    fun aYoutubeOriginalOverTheByteCapIsAskedAtTheTargetSide() {
        val big = Images.jpeg(Images.noise(2200, 2200), 100)
        assertTrue(big.size > ArtistArt.KEEP_BYTES)
        serve(yt(0), big)
        serve(yt(1680), "1680x1680@5")
        art().get("コバソロ", kobasolo, 360, 480)!!
        assertEquals(yt(1680), cache.hit(kobasolo)!!.url)
        assertEquals(Size(1680, 1680), stored(kobasolo))
        assertTrue(http.calls.indexOf(yt(0)) < http.calls.indexOf(yt(1680)))
        assertFalse(http.calls.any { it.contains("=s2048") })
    }

    @Test
    fun aFailedYoutubeOriginalFallsBackToTheServedForm() {
        http.queued[yt(0)] = ArrayDeque(listOf(Response(404, ByteArray(0))))
        art().get("コバソロ", kobasolo, 360, 480)!!
        assertEquals(og, cache.hit(kobasolo)!!.url)
        assertTrue(http.missing.isEmpty())
    }

    @Test
    fun withNoTargetTheServedFormsAreAsked() {
        targets = Targets.NONE
        art().get("コバソロ", kobasolo, 360, 480)!!
        assertEquals(og, cache.hit(kobasolo)!!.url)
        assertFalse(yt(0) in http.calls)
        assertTrue(http.missing.isEmpty())
    }

    @Test
    fun aBlockMadeOnTheServedUrlBlocksTheLargerForm() {
        blocks.add(ArtBlocks.Block(kobasolo, kobasolo, Sources.YOUTUBE, ImageIds.of(Sources.YOUTUBE, og), "", "コバソロ", 1))
        serve(yt(0), "1200x1200@5")
        assertNull(art().get("コバソロ", kobasolo, 360, 480))
        assertFalse(http.calls.any { it.startsWith("https://yt3.googleusercontent.com/") })
    }

    @Test
    fun aBlockMadeOnTheServedDeezerUrlBlocksTheLargerForm() {
        deezer(one)
        blocks.add(ArtBlocks.Block("name:sztest", null, Sources.DEEZER, ImageIds.of(Sources.DEEZER, dz(one)), "", name, 1))
        assertNull(art().get(name, null, 360, 480))
        assertFalse(http.calls.any { it.startsWith("https://cdn-images.dzcdn.net/") })
    }

    @Test
    fun deezerAsksTheLargerFormOfTheSameHashAfterTheXl() {
        deezer(one)
        serve(dz(one), "1000x1000@5")
        serve(dz(one, 1680), "1680x1680@5")
        art().get(name, null, 360, 480)!!
        val e = cache.hit("name:sztest")!!
        assertEquals(dz(one, 1680), e.url)
        assertEquals(one, e.id)
        assertEquals(Size(1680, 1680), stored("name:sztest"))
        assertTrue(http.calls.indexOf(dz(one)) < http.calls.indexOf(dz(one, 1680)))
    }

    @Test
    fun aDeezerLargerFormUnder1500IsLeftAsTheXl() {
        deezer(one)
        serve(dz(one), "1000x1000@5")
        serve(dz(one, 1680), "1200x1200@5")
        art().get(name, null, 360, 480)!!
        assertEquals(dz(one), cache.hit("name:sztest")!!.url)
        assertEquals(Size(1000, 1000), stored("name:sztest"))
    }

    @Test
    fun aDeezerLargerFormIsAskedAtLeastAt1500SoASmallerPhoneCanTellARealOriginal() {
        targets = Targets(Size(720, 1440))
        deezer(one)
        serve(dz(one), "1000x1000@5")
        serve(dz(one, 1500), "1500x1500@5")
        art().get(name, null, 360, 480)!!
        assertEquals(dz(one, 1500), cache.hit("name:sztest")!!.url)
        assertFalse(dz(one, 1440) in http.calls)
    }

    @Test
    fun aDeezerLargerFormIsAskedAtTheFlip7Side() {
        deezer(one)
        serve(dz(one), "1000x1000@5")
        serve(dz(one, 1680), "1680x1680@5")
        art().get(name, null, 360, 480)!!
        assertEquals(dz(one, 1680), cache.hit("name:sztest")!!.url)
        assertFalse(dz(one, 1500) in http.calls)
    }

    @Test
    fun aDeezerLargerFormAt1500IsTaken() {
        deezer(one)
        serve(dz(one), "1000x1000@5")
        serve(dz(one, 1680), "1500x1500@5")
        art().get(name, null, 360, 480)!!
        assertEquals(Size(1500, 1500), stored("name:sztest"))
    }

    @Test
    fun aDeezerLargerFormNoLargerThanTheXlKeepsTheXl() {
        deezer(one)
        serve(dz(one), "1000x1000@5")
        serve(dz(one, 1680), "1000x1000@6")
        art().get(name, null, 360, 480)!!
        assertEquals(dz(one), cache.hit("name:sztest")!!.url)
    }

    @Test
    fun aMissingDeezerLargerFormKeepsTheXl() {
        deezer(one)
        serve(dz(one), "1000x1000@5")
        http.queued[dz(one, 1680)] = ArrayDeque(listOf(Response(403, ByteArray(0))))
        art().get(name, null, 360, 480)!!
        assertEquals(dz(one), cache.hit("name:sztest")!!.url)
    }

    @Test
    fun aDeezerXlPlaceholderNeverAsksTheLargerForm() {
        val silhouette = Fixtures.gen("1000x1000@3")
        placeholders = setOf(ArtCache.sha256(silhouette))
        deezer(one, two)
        serve(dz(one), silhouette)
        serve(dz(two), "1000x1000@5")
        serve(dz(two, 1680), "1680x1680@5")
        art().get(name, null, 360, 480)!!
        assertEquals(two, cache.hit("name:sztest")!!.id)
        assertFalse(dz(one, 1680) in http.calls)
    }

    @Test
    fun aDeezerLargerFormThatIsAPlaceholderSkipsTheCandidate() {
        val big = Fixtures.gen("1680x1680@3")
        placeholders = setOf(ArtCache.sha256(big))
        deezer(one, two)
        serve(dz(one), "1000x1000@5")
        serve(dz(one, 1680), big)
        serve(dz(two), "1000x1000@6")
        serve(dz(two, 1680), "1680x1680@6")
        art().get(name, null, 360, 480)!!
        assertEquals(two, cache.hit("name:sztest")!!.id)
    }

    private var kinds = mapOf<String, Pair<String, Long>>()
    private val pages = HashMap<String, Response>()
    private var info = ""

    init {
        http.answer = { u -> if (u.startsWith("https://commons.wikimedia.org/w/api.php")) Response(200, info.toByteArray()) else pages[u] }
    }

    private fun page(url: String, body: String) {
        pages[url] = Response(200, body.toByteArray())
    }

    private fun thumb(f: String, side: Int = 1280) = "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/$f/${side}px-$f"

    private fun infoOf(vararg files: Triple<String, Int, Int>) {
        info = files.joinToString(",", "{\"query\":{\"pages\":{", "}}}") { (f, w, h) ->
            "\"$f\":{\"title\":\"File:$f\",\"imageinfo\":[{\"width\":$w,\"height\":$h,\"thumbwidth\":1280,\"thumbheight\":${1280L * h / w},\"mime\":\"${kinds[f]?.first ?: "image/jpeg"}\",\"size\":${kinds[f]?.second ?: 100000}," +
                "\"thumburl\":\"${thumb(f)}\",\"url\":\"https://upload.wikimedia.org/wikipedia/commons/a/ab/$f\",\"descriptionurl\":\"https://commons.wikimedia.org/wiki/File:$f\",\"extmetadata\":{}}]}"
        }
    }

    private val artist = "8d8d8d8d-0000-4000-8000-00000000a512"

    private fun mbArtist(vararg rels: Pair<String, String>) = page(
        "https://musicbrainz.org/ws/2/artist/$artist?inc=url-rels&fmt=json",
        rels.joinToString(",", "{\"name\":\"$name\",\"relations\":[", "]}") { (t, u) -> "{\"type\":\"$t\",\"url\":{\"resource\":\"$u\"}}" },
    )

    private fun discogs(vararg images: Triple<String, Int, Int>) {
        mbArtist("discogs" to "https://www.discogs.com/artist/77")
        page("https://api.discogs.com/artists/77", images.joinToString(",", "{\"images\":[", "]}") { (u, w, h) -> "{\"type\":\"${if (u in primary) "primary" else "secondary"}\",\"uri\":\"$u\",\"width\":$w,\"height\":$h}" })
    }

    private var primary = setOf<String>()

    private fun dc(n: Int) = "https://i.discogs.com/k$n/rs:fit/g:sm/q:90/h:600/w:600/" +
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("s3://discogs-images/A-77-$n.jpeg".toByteArray()) + ".jpeg"

    @Test
    fun theLargestDiscogsImageIsTriedFirstWithinTheSource() {
        sources = Sources.NONE.copy(discogs = true)
        discogs(Triple(dc(1), 400, 400), Triple(dc(2), 600, 600))
        serve(dc(1), "400x400@5")
        serve(dc(2), "600x600@5")
        art().get(name, artist, 240, 320)!!
        val e = cache.hit(artist)!!
        assertEquals(dc(2), e.url)
        assertEquals(600 to 600, e.w to e.h)
        assertFalse(dc(1) in http.calls)
    }

    @Test
    fun theDiscogsPrimaryImageStaysFirstAndSizeRanksWithinEachType() {
        sources = Sources.NONE.copy(discogs = true)
        primary = setOf(dc(3))
        discogs(Triple(dc(1), 400, 400), Triple(dc(2), 600, 600), Triple(dc(3), 300, 400))
        serve(dc(3), "300x400@5")
        art().get(name, artist, 240, 320)!!
        assertEquals(dc(3), cache.hit(artist)!!.url)
        assertFalse(http.calls.any { it == dc(1) || it == dc(2) })
    }

    @Test
    fun aRescanFindsAPlaceholderStoredAtTheLargerForm() {
        deezer(one)
        val xl = Fixtures.gen("1000x1000@5")
        serve(dz(one), xl)
        serve(dz(one, 1680), "1680x1680@5")
        art().get(name, null, 360, 480)!!
        assertEquals(ArtCache.sha256(xl), cache.hit("name:sztest")!!.base)
        placeholders = setOf(ArtCache.sha256(xl))
        assertEquals(1, art().scanPlaceholders())
        assertNull(cache.hit("name:sztest"))
    }

    @Test
    fun withNoTargetDiscogsKeepsItsOrder() {
        targets = Targets.NONE
        sources = Sources.NONE.copy(discogs = true)
        discogs(Triple(dc(1), 400, 400), Triple(dc(2), 600, 600))
        serve(dc(1), "400x400@5")
        art().get(name, artist, 240, 320)!!
        assertEquals(dc(1), cache.hit(artist)!!.url)
        assertFalse(dc(2) in http.calls)
    }

    @Test
    fun aLargerDiscogsImageThatDoesNotFitMovesOnToTheNext() {
        sources = Sources.NONE.copy(discogs = true)
        discogs(Triple(dc(1), 400, 400), Triple(dc(2), 600, 600))
        serve(dc(1), "400x400@5")
        serve(dc(2), "200x200@5")
        art().get(name, artist, 240, 320)!!
        assertEquals(dc(1), cache.hit(artist)!!.url)
    }

    @Test
    fun aBlockedLargerImageIsSkipped() {
        sources = Sources.NONE.copy(discogs = true)
        discogs(Triple(dc(1), 400, 400), Triple(dc(2), 600, 600))
        blocks.add(ArtBlocks.Block(artist, artist, Sources.DISCOGS, ImageIds.of(Sources.DISCOGS, dc(2)), "", name, 1))
        serve(dc(1), "400x400@5")
        art().get(name, artist, 240, 320)!!
        assertEquals(dc(1), cache.hit(artist)!!.url)
        assertFalse(dc(2) in http.calls)
    }

    @Test
    fun aLargerImageOfALaterSourceNeverWins() {
        sources = Sources.NONE.copy(discogs = true, deezer = true)
        discogs(Triple(dc(1), 400, 400))
        serve(dc(1), "400x400@5")
        http.queued.getOrPut(dzSearch) { ArrayDeque() } += Response(200, "{\"data\":[{\"name\":\"$name\",\"nb_fan\":1,\"picture_xl\":\"${dz(one)}\"}]}".toByteArray())
        serve(dz(one), "1000x1000@5")
        assertEquals(Sources.DISCOGS, art().get(name, artist, 240, 320)!!.source)
        assertFalse(dz(one) in http.calls)
    }

    @Test
    fun commonsRanksByTheOriginalSizeNotTheServedThumb() {
        mbArtist("image" to "https://commons.wikimedia.org/wiki/File:Small.jpg", "image" to "https://commons.wikimedia.org/wiki/File:Big.jpg")
        infoOf(Triple("Small.jpg", 1300, 1733), Triple("Big.jpg", 3000, 4000))
        serve(thumb("Small.jpg"), "1280x1706@5")
        serve(thumb("Big.jpg", 1920), "1920x2560@5")
        art().get(name, artist, 240, 320)!!
        val e = cache.hit(artist)!!
        assertEquals(thumb("Big.jpg", 1920), e.url)
        assertEquals("File:Big.jpg", e.id)
        assertFalse(http.calls.any { it.startsWith("https://upload.wikimedia.org/") && it.contains("Small.jpg") })
    }

    @Test
    fun aCommonsLargerUrlThatFailsFallsBackToThe1280Thumb() {
        mbArtist("image" to "https://commons.wikimedia.org/wiki/File:Big.jpg")
        infoOf(Triple("Big.jpg", 3000, 4000))
        http.queued[thumb("Big.jpg", 1920)] = ArrayDeque(listOf(Response(404, ByteArray(0))))
        serve(thumb("Big.jpg"), "1280x1706@5")
        art().get(name, artist, 240, 320)!!
        assertEquals(thumb("Big.jpg"), cache.hit(artist)!!.url)
    }

    @Test
    fun variantsNeverRunWhenThePlainNameFits() {
        val tagged = "$name【ch】"
        sources = Sources.NONE.copy(deezer = true)
        http.queued.getOrPut("https://musicbrainz.org/ws/2/artist/?query=${Api.enc(tagged)}&fmt=json&limit=10") { ArrayDeque() } += Response(404, ByteArray(0))
        http.queued.getOrPut("https://api.deezer.com/search/artist?q=${Api.enc(tagged)}") { ArrayDeque() } +=
            Response(200, "{\"data\":[{\"name\":\"$tagged\",\"nb_fan\":1,\"picture_xl\":\"${dz(one)}\"}]}".toByteArray())
        serve(dz(one), "1000x1000@5")
        assertEquals(Sources.DEEZER, art().get(tagged, null, 240, 320)!!.source)
        assertFalse(http.calls.any { it == dzSearch || it == mbSearch })
    }

    @Test
    fun deezerKeepsTheSameNameRankingWhenALaterEntryIsLarger() {
        sources = Sources.NONE.copy(deezer = true)
        http.queued.getOrPut(mbSearch) { ArrayDeque() } += Response(404, ByteArray(0))
        http.queued.getOrPut(dzSearch) { ArrayDeque() } += Response(
            200,
            ("{\"data\":[{\"name\":\"SZTEST\",\"nb_fan\":900,\"picture_xl\":\"${dz(two)}\"}," +
                "{\"name\":\"$name\",\"nb_fan\":1,\"picture_xl\":\"${dz(one)}\"}]}").toByteArray(),
        )
        serve(dz(one), "1000x1000@5")
        serve(dz(two), "1000x1000@6")
        serve(dz(two, 1680), "1800x1800@6")
        art().get(name, null, 240, 320)!!
        assertEquals(one, cache.hit("name:sztest")!!.id)
        assertFalse(dz(two) in http.calls)
    }

    @Test
    fun anOldEntryWithNoSizeIsReadOnceAndRewritten() {
        val src = Fixtures.gen("900x900@5")
        cache.putHit(ArtCache.Entry("name:sztest", name, null, Sources.DEEZER, null, dz(one), 5, one, ArtCache.sha256(src)), src)
        val e = cache.hit("name:sztest")!!
        assertEquals(0 to 0, e.w to e.h)
        assertEquals(Size(900, 900), art().size(e))
        assertEquals(900 to 900, cache.hit("name:sztest")!!.let { it.w to it.h })
    }

    private fun searches(vararg found: String) {
        sources = Sources.NONE.copy(deezer = true)
        http.queued.getOrPut(mbSearch) { ArrayDeque() } += Response(404, ByteArray(0))
        for (h in found) http.queued.getOrPut(dzSearch) { ArrayDeque() } += Response(200, "{\"data\":[{\"name\":\"$name\",\"nb_fan\":1,\"picture_xl\":\"${dz(h)}\"}]}".toByteArray())
    }

    @Test
    fun aLargerDeviceKeepsTheSmallHitAndGetsItUpscaled() {
        targets = Targets.NONE
        val silhouette = Fixtures.gen("1000x1000@3")
        placeholders = setOf(ArtCache.sha256(silhouette))
        searches(one, two)
        serve(dz(one), "300x400@5")
        serve(dz(two), silhouette)
        val key = "name:sztest"
        assertEquals(240 to 320, Images.size(art().get(name, null, 240, 320)!!.jpeg))
        val big = art().get(name, null, 480, 800)
        assertEquals(480 to 800, big?.let { Images.size(it.jpeg) })
        assertTrue(cache.hasSource(key))
        assertEquals(Size(300, 400), stored(key))
        assertTrue(cache.rendered(key, 240, 320) != null)
        assertTrue(cache.rendered(key, 480, 800) != null)
        assertNull(cache.miss(key))
        assertTrue(blocks.drops().isEmpty())
    }

    @Test
    fun aHitThatCannotRenderIsNeverReplacedByAMissOrADropRecord() {
        targets = Targets.NONE
        val silhouette = Fixtures.gen("1000x1000@3")
        placeholders = setOf(ArtCache.sha256(silhouette))
        searches(two)
        serve(dz(two), silhouette)
        val key = "name:sztest"
        val bad = "not an image".toByteArray()
        cache.putHit(ArtCache.Entry(key, name, null, Sources.DEEZER, null, dz(one), 5, one, ArtCache.sha256(bad)), bad)
        assertNull(art().get(name, null, 480, 800))
        assertTrue(cache.hasSource(key))
        assertNull(cache.miss(key))
        assertTrue(blocks.drops().isEmpty())
        val calls = http.calls.size
        assertNull(art().get(name, null, 480, 800))
        assertEquals(calls, http.calls.size)
        time.now += ArtistArt.TTL_MS
        searches(two)
        serve(dz(two), silhouette)
        assertNull(art().get(name, null, 480, 800))
        assertTrue(http.calls.size > calls)
        assertTrue(cache.hasSource(key))
    }

    @Test
    fun aNewCandidateIsNeverAcceptedUpscaled() {
        targets = Targets.NONE
        searches(one)
        serve(dz(one), "300x400@5")
        assertNull(art().get(name, null, 480, 800))
        assertTrue(cache.miss("name:sztest") != null)
    }

    @Test
    fun aLinkPickIsNotFailedForSizeOnALargerDevice() {
        val url = "https://e.example/pick.jpg"
        val (k, k2) = ArtPicks.keys(name, null, false)
        picks.add(ArtPicks.Pick(k, k2, name, ArtPicks.IMAGE, Sources.LINK, url, url, "", null, null, 1))
        serve(url, "360x480@5")
        val a = art().get(name, null, 480, 800)
        assertEquals(480 to 800, a?.let { Images.size(it.jpeg) })
        assertNull(picks.list().single().failedAt)
    }

    @Test
    fun aGalleryPickIsNotMissingForSizeOnALargerDevice() {
        val g = GalleryStore(tmp.newFolder("pickimg"))
        val src = Fixtures.gen("360x480@5")
        val sha = ArtCache.sha256(src)
        g.put(src)
        val (k, k2) = ArtPicks.keys(name, null, false)
        picks.add(ArtPicks.Pick(k, k2, name, ArtPicks.IMAGE, Sources.GALLERY, "", GalleryStore.ID_PREFIX + sha, sha, null, null, 1))
        val logs = ArrayList<String>()
        val a = ArtistArt(http, AwtCodec(), cache, { sources }, "0.1.0", { time.now }, time, blocks = blocks, picks = picks, gallery = g, log = { logs += it }, targets = { targets })
            .get(name, null, 480, 800)
        assertEquals(480 to 800, a?.let { Images.size(it.jpeg) })
        assertTrue(logs.isEmpty())
    }

    @Test
    fun aPickBelow360x480IsStillRefused() {
        assertEquals(PickLink.TOO_SMALL, art().check(Fixtures.gen("300x400@5")))
    }

    private fun hit(key: String, source: String, url: String, spec: String, mbid: String? = null): ArtCache.Entry {
        val src = Fixtures.gen(spec)
        cache.putHit(ArtCache.Entry(key, name, mbid, source, null, url, 5, ImageIds.of(source, url), ArtCache.sha256(src)), src)
        return cache.hit(key)!!
    }

    @Test
    fun theUpgradeTriesTheSameImageLargerFirst() {
        val e = hit(kobasolo, Sources.YOUTUBE, og, "900x900@5", kobasolo)
        serve(yt(0), "3000x3000@5")
        val a = art()
        assertTrue(a.upgradable(e))
        assertEquals(listOf(kobasolo), a.upgrades().map { it.key })
        assertTrue(a.upgrade(e, 240, 320).replaced)
        val n = cache.hit(kobasolo)!!
        assertEquals(yt(0), n.url)
        assertEquals(e.id, n.id)
        assertEquals(3000 to 3000, n.w to n.h)
        assertTrue(cache.rendered(kobasolo, 240, 320) != null)
        assertEquals(listOf(yt(0)), http.calls)
        assertFalse(a.upgradable(n))
    }

    @Test
    fun theUpgradeTakesAnotherImageOfTheSameSourceThatFitsTheTarget() {
        sources = Sources.NONE.copy(discogs = true)
        discogs(Triple(dc(1), 400, 400), Triple(dc(2), 600, 600), Triple(dc(3), 1700, 1700))
        val e = hit(artist, Sources.DISCOGS, dc(1), "400x400@5", artist)
        serve(dc(3), "1700x1700@5")
        assertTrue(art().upgrade(e, 240, 320).replaced)
        assertEquals(dc(3), cache.hit(artist)!!.url)
        assertFalse(dc(2) in http.calls)
    }

    @Test
    fun anUpgradeNeverUndoesAChangeMadeWhileItDownloaded() {
        val e = hit(kobasolo, Sources.YOUTUBE, og, "900x900@5", kobasolo)
        val before = http.answer
        http.answer = { u ->
            if (u == yt(0)) {
                cache.drop(kobasolo)
                Response(200, Fixtures.gen("1600x1600@5"))
            } else {
                before(u)
            }
        }
        assertFalse(art().upgrade(e, 240, 320).replaced)
        assertNull(cache.hit(kobasolo))
    }

    @Test
    fun anUpgradeRecordIsReplacedByTheNextUpgradeAndDroppedByABlockOrADrop() {
        val f = tmp.root.resolve("up.tsv")
        val r = ArtBlocks(f)
        r.upgrades("tcp:B")
        r.upgraded("k1", "One", null, 1, "tcp:A")
        r.upgraded("k1", "One", null, 2, null)
        r.upgraded("k2", "Two", null, 1, null)
        r.upgraded("k3", "Three", null, 1, null)
        r.save()
        val back = ArtBlocks(f)
        assertEquals(listOf("k1" to 2L, "k2" to 1L, "k3" to 1L), back.upgrades("tcp:A").map { it.key to it.at })
        back.upgradeDone(back.upgrades("tcp:A").first(), "tcp:A")
        assertEquals(listOf("k2", "k3"), back.upgrades("tcp:A").map { it.key })
        back.add(ArtBlocks.Block("k2", null, Sources.DEEZER, "x", "", "Two", 5))
        back.drop("k3", null, "Three", 5)
        assertEquals(listOf("k1"), back.upgrades("tcp:B").map { it.key })
        back.upgradeDone(back.upgrades("tcp:B").single(), "tcp:B")
        assertTrue(back.upgrades("tcp:C").isEmpty())
    }

    @Test
    fun anUpgradeRecordKeepsItsOriginAndIsDroppedOnceEveryKnownDeviceHasIt() {
        val f = tmp.root.resolve("up2.tsv")
        val r = ArtBlocks(f)
        r.upgrades("tcp:A")
        r.upgrades("tcp:B")
        r.upgraded("k1", "One", null, 1, null, net.seilent.funecomp.proto.gen.Enums.ImgOrigin.phone)
        r.save()
        val back = ArtBlocks(f)
        val u = back.upgrades("tcp:A").single()
        assertEquals(net.seilent.funecomp.proto.gen.Enums.ImgOrigin.phone, u.origin)
        back.upgradeDone(u, "tcp:A")
        assertEquals(listOf("k1"), back.upgrades("tcp:B").map { it.key })
        back.upgradeDone(back.upgrades("tcp:B").single(), "tcp:B")
        back.save()
        assertFalse(f.readText().contains("upgrade\tk1"))
    }

    @Test
    fun aDifferentImageBelowTheTargetNeverReplacesTheHit() {
        sources = Sources.NONE.copy(theAudioDb = true)
        val thumb = "https://r2.theaudiodb.com/images/media/artist/thumb/sz.jpg"
        val fanart = "https://r2.theaudiodb.com/images/media/artist/fanart/sz.jpg"
        page("https://www.theaudiodb.com/api/v1/json/123/artist-mb.php?i=$artist", "{\"artists\":[{\"strArtistThumb\":\"$thumb\",\"strArtistFanart\":\"$fanart\"}]}")
        val e = hit(artist, Sources.THE_AUDIO_DB, thumb, "700x700@5", artist)
        serve(fanart, "1280x720@5")
        assertFalse(art().upgrade(e, 240, 320).replaced)
        assertEquals(thumb, cache.hit(artist)!!.url)
        assertFalse(fanart in http.calls)
    }

    @Test
    fun aLargerDifferentDiscogsImageBelowTheTargetNeverReplacesTheHit() {
        sources = Sources.NONE.copy(discogs = true)
        discogs(Triple(dc(1), 400, 400), Triple(dc(2), 1700, 1700))
        val e = hit(artist, Sources.DISCOGS, dc(1), "400x400@5", artist)
        serve(dc(2), "900x900@5")
        assertFalse(art().upgrade(e, 240, 320).replaced)
        assertEquals(dc(1), cache.hit(artist)!!.url)
        assertTrue(dc(2) in http.calls)
    }

    @Test
    fun aSmallerOrEqualCandidateNeverReplacesTheHit() {
        sources = Sources.NONE.copy(discogs = true)
        discogs(Triple(dc(1), 600, 600), Triple(dc(2), 1700, 1700), Triple(dc(3), 500, 500))
        val e = hit(artist, Sources.DISCOGS, dc(1), "600x600@5", artist)
        serve(dc(2), "450x450@5")
        serve(dc(3), "600x600@6")
        assertFalse(art().upgrade(e, 240, 320).replaced)
        assertEquals(dc(1), cache.hit(artist)!!.url)
    }

    @Test
    fun theUpgradeNeverTakesAnotherSource() {
        sources = Sources.NONE.copy(discogs = true, deezer = true)
        discogs(Triple(dc(1), 400, 400))
        val e = hit(artist, Sources.DISCOGS, dc(1), "400x400@5", artist)
        serve(dz(one), "1000x1000@5")
        assertFalse(art().upgrade(e, 240, 320).replaced)
        assertEquals(dc(1), cache.hit(artist)!!.url)
        assertFalse(http.calls.any { it.contains("deezer") })
    }

    @Test
    fun aDeezerUpgradeIsOnlyTheSameHashLarger() {
        deezer(one, two)
        val e = hit("name:sztest", Sources.DEEZER, dz(one), "1000x1000@5")
        http.queued[dz(one, 1680)] = ArrayDeque(listOf(Response(403, ByteArray(0))))
        serve(dz(two), "1000x1000@6")
        serve(dz(two, 1680), "1800x1800@6")
        assertFalse(art().upgrade(e, 240, 320).replaced)
        assertEquals(one, cache.hit("name:sztest")!!.id)
        assertFalse(dzSearch in http.calls)
    }

    @Test
    fun aBlockedLargerCandidateIsNotTakenByTheUpgrade() {
        sources = Sources.NONE.copy(discogs = true)
        discogs(Triple(dc(1), 400, 400), Triple(dc(2), 1700, 1700))
        val e = hit(artist, Sources.DISCOGS, dc(1), "400x400@5", artist)
        blocks.add(ArtBlocks.Block(artist, artist, Sources.DISCOGS, ImageIds.of(Sources.DISCOGS, dc(2)), "", name, 1))
        serve(dc(2), "1700x1700@5")
        assertFalse(art().upgrade(e, 240, 320).replaced)
        assertFalse(dc(2) in http.calls)
    }

    @Test
    fun aDoneRecordHoldsUntilTheTargetsGrow() {
        sources = Sources.NONE.copy(discogs = true)
        discogs(Triple(dc(1), 400, 400))
        val e = hit(artist, Sources.DISCOGS, dc(1), "400x400@5", artist)
        val a = art()
        assertFalse(a.upgrade(e, 240, 320).replaced)
        val done = cache.hit(artist)!!
        assertFalse(a.upgradable(done))
        assertTrue(a.upgrades().isEmpty())
        val calls = http.calls.size
        assertFalse(a.upgrade(done, 240, 320).replaced)
        assertEquals(calls, http.calls.size)
        targets = Targets(Targets.grow(Size(720, 1680), Size(360, 400)))
        assertFalse(a.upgradable(done))
        targets = Targets(Size(720, 1680), listOf(Size(480, 800)))
        assertTrue(a.upgradable(done))
        assertFalse(a.upgrade(done, 240, 320).replaced)
        val again = cache.hit(artist)!!
        targets = Targets(Size(720, 1680), listOf(Size(480, 800), Size(240, 320)))
        assertFalse(a.upgradable(again))
        targets = Targets(Size(720, 1680), listOf(Size(600, 800)))
        assertTrue(a.upgradable(again))
    }

    @Test
    fun aPhotoThatCoversTheTargetsOrAPickIsNotUpgraded() {
        val big = hit("name:sztest", Sources.DEEZER, dz(one, 1680), "1680x1680@5")
        assertFalse(art().upgradable(big))
        val p = hit(ArtPicks.PREFIX + "name:x", Sources.LINK, "https://e.example/p.jpg", "400x400@5")
        assertFalse(art().upgradable(p))
        targets = Targets.NONE
        assertFalse(art().upgradable(hit("name:y", Sources.DEEZER, dz(two), "400x400@5")))
    }

    @Test
    fun theSweepMarkChangesWithTheTargets() {
        val a = art()
        a.beginMark()
        val m = a.mark()
        assertTrue(a.holds(m))
        targets = Targets(Size(720, 1680), listOf(Size(480, 800)))
        assertFalse(a.holds(m))
    }

    @Test
    fun theTargetsKeepTheLargestWindowEverSeenAndTheLargestBackdropPerBoard() {
        assertEquals(Size(720, 1680), Targets.phone(1080, 2520))
        assertEquals(Size(720, 1680), Targets.phone(2520, 1080))
        val main = Targets.phone(1080, 2520)
        val cover = Targets.phone(948, 748)
        assertEquals(main, Targets.grow(main, cover))
        assertEquals(main, Targets.grow(cover, main))
        assertEquals(main, Targets.grow(null, main))
        val d = Targets.devices("v1=240x320,ap80=360x480,bad,x=0x1")
        assertEquals(mapOf("v1" to Size(240, 320), "ap80" to Size(360, 480)), d)
        assertEquals("ap80=360x480,v1=240x320", Targets.devices(d))
        val t = Targets.of("720x1680", "v1=240x320,r1=480x800")
        assertEquals(listOf(Size(480, 800), Size(240, 320)), t.devices)
        assertEquals(Size(720, 1680), t.phone)
    }

    @Test
    fun thePhoneDecodeKeepsTheCropAtTheWindowWhenTheSourceIsLarger() {
        val flip = Size(1080, 2520)
        assertEquals(3361, Backdrop.side(Size(3840, 2880), flip, 2048, 4096))
        assertEquals(3781, Backdrop.side(Size(6000, 4000), flip, 2048, 4096))
        assertEquals(4096, Backdrop.side(Size(12000, 3000), flip, 2048, 4096))
        assertEquals(1680, Backdrop.side(Size(1680, 1680), flip, 2048, 4096))
        assertEquals(900, Backdrop.side(Size(900, 900), flip, 2048, 4096))
        assertEquals(4096, Backdrop.side(Size(1080, 20000), flip, 2048, 4096))
        assertEquals(2048, Backdrop.side(Size(3840, 2880), Size(720, 1280), 2048, 4096))
        assertEquals(2048, Backdrop.side(Size(4000, 4000), Size(360, 640), 2048, 4096))
    }

    private fun commons(vararg files: Triple<String, Int, Int>): List<Candidate> {
        infoOf(*files)
        val a = MbArtist("x", "x", null, files.map { Rel("image", "https://commons.wikimedia.org/wiki/File:${it.first}", false) })
        return Wikimedia(Api(http, "ua", RateLimiter(0) { 0 }, {})).candidates(a, 360, 480, targets = targets)
    }

    @Test
    fun commonsAsksTheStandardStepThatCoversTheTargetCappedByTheOriginal() {
        val c = commons(Triple("Wide.jpg", 6000, 4000), Triple("Mid.jpg", 2400, 3200), Triple("Small.jpg", 1500, 2000), Triple("Tiny.jpg", 900, 1200)).associateBy { it.id }
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Wide.jpg/3840px-Wide.jpg", c["File:Wide.jpg"]!!.url)
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Wide.jpg/1280px-Wide.jpg", c["File:Wide.jpg"]!!.alt)
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Mid.jpg/1920px-Mid.jpg", c["File:Mid.jpg"]!!.url)
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/a/ab/Small.jpg", c["File:Small.jpg"]!!.url)
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Tiny.jpg/1280px-Tiny.jpg", c["File:Tiny.jpg"]!!.url)
        assertNull(c["File:Tiny.jpg"]!!.alt)
        assertEquals(6000 to 4000, c["File:Wide.jpg"]!!.let { it.w to it.h })
    }

    @Test
    fun commonsTakesTheOriginalOnlyForAJpegOrASmallPngElseTheLargestThumbBelowItsWidth() {
        kinds = mapOf("Scan.tif" to ("image/tiff" to 9_000_000L), "Big.png" to ("image/png" to 9_000_000L), "Small.png" to ("image/png" to 900_000L), "Wide.tif" to ("image/tiff" to 30_000_000L))
        val c = commons(Triple("Scan.tif", 1500, 2000), Triple("Big.png", 1500, 2000), Triple("Small.png", 1500, 2000), Triple("Wide.tif", 3000, 2000)).associateBy { it.id }
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Scan.tif/1280px-Scan.tif", c["File:Scan.tif"]!!.url)
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Big.png/1280px-Big.png", c["File:Big.png"]!!.url)
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/a/ab/Small.png", c["File:Small.png"]!!.url)
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Wide.tif/1920px-Wide.tif", c["File:Wide.tif"]!!.url)
    }

    @Test
    fun commonsWithNoTargetKeepsThe1280Thumb() {
        targets = Targets.NONE
        val c = commons(Triple("Wide.jpg", 6000, 4000)).single()
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Wide.jpg/1280px-Wide.jpg", c.url)
        assertNull(c.alt)
    }
}
