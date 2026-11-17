package net.seilent.funecomp.sources

import net.seilent.funekit.artist.ArtBlocks
import net.seilent.funekit.artist.ArtCache
import net.seilent.funekit.artist.ArtistArt
import net.seilent.funekit.artist.AwtCodec
import net.seilent.funekit.artist.FakeTime
import net.seilent.funekit.artist.Fixtures
import net.seilent.funekit.artist.ImageIds
import net.seilent.funekit.artist.Images
import net.seilent.funekit.artist.Response
import net.seilent.funekit.artist.TEST_AGENT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BlockTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val http = Fixtures()
    private val time = FakeTime()
    private var sources = ArtSources.COMMONS
    private var online = true
    private val cache by lazy { ArtCache(tmp.newFolder("art")) }
    private val blocks by lazy { ArtBlocks(java.io.File(tmp.root, "artblock.tsv")) }

    private fun art() = ArtistArt(http, AwtCodec(), cache, { sources }, TEST_AGENT, { time.now }, time, { online }, blocks, extensions = listOf(YouTubeArt {})).also {
        http.now = { time.now }
    }

    private fun block(e: ArtCache.Entry) = blocks.add(ArtBlocks.Block(e.key, e.mbid, e.source, ImageIds.of(e), cache.sha(e), e.name, time.now++))

    private fun shown(name: String, mbid: String?) = art().cachedEntry(name, mbid)!!

    @Test
    fun aYouTubeImageIdIsTheUrlBeforeItsSize() {
        val youtube = YouTubeArt {}
        assertEquals(
            youtube.imageId("https://yt3.googleusercontent.com/ytc/AIdro_l2Ct30OyvxG3NA=s900-c-k-c0x00ffffff-no-rj"),
            youtube.imageId("https://yt3.googleusercontent.com/ytc/AIdro_l2Ct30OyvxG3NA=s176-c-k"),
        )
    }

    @Test
    fun aYouTubeBlockFetchesTheNextChannelOnlyThen() {
        val id = "33333333-3333-3333-3333-333333333333"
        val ch = listOf("https://www.youtube.com/channel/UCaaaa", "https://www.youtube.com/channel/UCbbbb")
        val avatar = { k: String -> "https://yt3.googleusercontent.com/ytc/$k=s900-c-k-c0x00ffffff-no-rj" }
        val rels = ch.joinToString(",") { "{\"type\":\"youtube\",\"ended\":false,\"url\":{\"resource\":\"$it\"}}" }
        http.queued["https://musicbrainz.org/ws/2/artist/$id?inc=url-rels&fmt=json"] =
            ArrayDeque(List(2) { Response(200, "{\"id\":\"$id\",\"name\":\"Two\",\"relations\":[$rels]}".toByteArray()) })
        ch.zip(listOf("AAAA", "BBBB")).forEachIndexed { i, (url, k) ->
            http.queued[url] = ArrayDeque(List(2) { Response(200, "<meta property=\"og:image\" content=\"${avatar(k)}\">".toByteArray()) })
            http.queued[avatar(k)] = ArrayDeque(List(2) { Response(200, Images.jpeg(Images.photo(1000, 1000, i + 3L), 92)) })
        }
        sources = YouTubeArt.with(ArtSources.COMMONS, true)
        assertEquals(YouTubeArt.SOURCE, art().get("Two", id, 240, 320)!!.source)
        val first = shown("Two", id)
        assertEquals(avatar("AAAA").substringBefore('='), ImageIds.of(first))
        assertEquals(1, http.calls.count { it == ch[0] })
        assertEquals(0, http.calls.count { it == ch[1] })
        block(first)
        assertNotNull(art().get("Two", id, 240, 320))
        assertEquals(avatar("BBBB").substringBefore('='), ImageIds.of(shown("Two", id)))
        assertEquals(1, http.calls.count { it == ch[1] })
        assertTrue("missing ${http.missing}", http.missing.isEmpty())
    }
}

