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

    private fun art() = ArtistArt(http, AwtCodec(), cache, { sources }, "0.1.0", { time.now }, time, blocks = blocks, placeholders = placeholders, targets = { targets })

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

    private fun commons(vararg files: Triple<String, Int, Int>): List<Candidate> {
        http.answer = { u ->
            if (!u.startsWith("https://commons.wikimedia.org/w/api.php")) null else Response(200, files.joinToString(",", "{\"query\":{\"pages\":{", "}}}") { (f, w, h) ->
                "\"$f\":{\"title\":\"File:$f\",\"imageinfo\":[{\"width\":$w,\"height\":$h,\"mime\":\"${kinds[f]?.first ?: "image/jpeg"}\",\"size\":${kinds[f]?.second ?: 100000}," +
                    "\"thumburl\":\"https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/$f/1280px-$f\"," +
                    "\"url\":\"https://upload.wikimedia.org/wikipedia/commons/a/ab/$f\",\"descriptionurl\":\"https://commons.wikimedia.org/wiki/File:$f\",\"extmetadata\":{}}]}"
            }.toByteArray())
        }
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
