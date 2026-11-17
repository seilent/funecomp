package net.seilent.funecomp.sources

import net.seilent.funekit.artist.ArtBlocks
import net.seilent.funekit.artist.ArtCache
import net.seilent.funekit.artist.ArtPicks
import net.seilent.funekit.artist.ArtistArt
import net.seilent.funekit.artist.AwtCodec
import net.seilent.funekit.artist.Deezer
import net.seilent.funekit.artist.FakeTime
import net.seilent.funekit.artist.Fixtures
import net.seilent.funekit.artist.Images
import net.seilent.funekit.artist.Names
import net.seilent.funekit.artist.Response
import net.seilent.funekit.artist.Size
import net.seilent.funekit.artist.TEST_AGENT
import net.seilent.funekit.artist.Targets
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

    private val http = Fixtures()
    private val youtube = YouTubeArt {}
    private val time = FakeTime()
    private val cache by lazy { ArtCache(tmp.newFolder("art")) }
    private val blocks by lazy { ArtBlocks(tmp.root.resolve("artblock.tsv")) }
    private var sources = YouTubeArt.with(ArtSources.COMMONS, true)
    private var targets = Targets(Size(720, 1680))
    private var placeholders = Deezer.PLACEHOLDERS

    private val picks by lazy { ArtPicks(tmp.root.resolve("artpick.tsv")) }

    private fun art() = ArtistArt(http, AwtCodec(), cache, { sources }, TEST_AGENT, { time.now }, time, blocks = blocks, picks = picks, placeholders = placeholders, targets = { targets }, extensions = listOf(youtube))

    private fun serve(url: String, bytes: ByteArray) {
        http.queued.getOrPut(url) { ArrayDeque() } += Response(200, bytes)
    }

    private fun serve(url: String, spec: String) = serve(url, Fixtures.gen(spec))

    private fun stored(key: String): Size = Images.size(cache.source(key)!!).let { Size(it.first, it.second) }

    @Test
    fun youtubeTakesTheOriginalWhenItIsNotAboveTheTarget() {
        serve(yt(0), "1200x1200@5")
        assertEquals(YouTubeArt.SOURCE, art().get("コバソロ", kobasolo, 360, 480)!!.source)
        val e = cache.hit(kobasolo)!!
        assertEquals(yt(0), e.url)
        assertEquals(youtube.imageId(og), e.id)
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
        assertTrue(big.size > YouTubeArt.KEEP_BYTES)
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
        blocks.add(ArtBlocks.Block(kobasolo, kobasolo, YouTubeArt.SOURCE, youtube.imageId(og), "", "コバソロ", 1))
        serve(yt(0), "1200x1200@5")
        assertNull(art().get("コバソロ", kobasolo, 360, 480))
        assertFalse(http.calls.any { it.startsWith("https://yt3.googleusercontent.com/") })
    }

    private val pages = HashMap<String, Response>()
    private var info = ""

    init {
        http.answer = { u -> if (u.startsWith("https://commons.wikimedia.org/w/api.php")) Response(200, info.toByteArray()) else pages[u] }
    }

    private fun page(url: String, body: String) {
        pages[url] = Response(200, body.toByteArray())
    }

    private fun hit(key: String, source: String, url: String, spec: String, mbid: String? = null): ArtCache.Entry {
        val src = Fixtures.gen(spec)
        cache.putHit(ArtCache.Entry(key, name, mbid, source, null, url, 5, youtube.imageId(url), ArtCache.sha256(src)), src)
        return cache.hit(key)!!
    }

    @Test
    fun theUpgradeTriesTheSameImageLargerFirst() {
        val e = hit(kobasolo, YouTubeArt.SOURCE, og, "900x900@5", kobasolo)
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
    fun anUpgradeNeverUndoesAChangeMadeWhileItDownloaded() {
        val e = hit(kobasolo, YouTubeArt.SOURCE, og, "900x900@5", kobasolo)
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
    fun anUpgradeTakesTheSameImageLargerThatStillDoesNotFillTheDevice() {
        targets = Targets(Size(720, 1280))
        val e = hit(kobasolo, YouTubeArt.SOURCE, og, "900x900@5", kobasolo)
        serve(yt(0), "1080x1080@5")
        val a = art()
        assertTrue(a.upgradable(e))
        assertTrue(a.upgrade(e, 720, 1280).replaced)
        val n = cache.hit(kobasolo)!!
        assertEquals(yt(0), n.url)
        assertEquals(1080 to 1080, n.w to n.h)
        assertEquals(listOf(yt(0)), http.calls)
        assertTrue(http.missing.isEmpty())
    }

    @Test
    fun aChannelAvatarAboveTheFloorIsTakenAndRenderedAtTheCallSize() {
        targets = Targets(Size(720, 1280))
        sources = YouTubeArt.with(ArtSources.COMMONS.copy(order = ArtSources.ORDER), true)
        val tag = "UCartsmallAvatarCase0001"
        page(YouTube.channelUrl(tag), "<meta property=\"og:image\" content=\"https://yt3.googleusercontent.com/ytc/artsmall=s900-c-k-c0x00ffffff-no-rj\">")
        val before = http.answer
        http.answer = { u -> if (u.startsWith("https://yt3.googleusercontent.com/ytc/artsmall")) Response(200, Fixtures.gen("900x900@5")) else before(u) }
        val a = art().get(tag, null, 720, 1280)!!
        assertEquals(YouTubeArt.SOURCE, a.source)
        assertEquals(720 to 1280, a.w to a.h)
        assertEquals(720 to 1280, Images.size(a.jpeg))
        assertEquals(tag, cache.hit("name:${Names.fold(tag)}")!!.channel)
    }
}
