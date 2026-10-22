package net.seilent.funecomp.artist

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaceholderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val http = Fixtures()
    private val time = FakeTime()
    private val cache by lazy { ArtCache(tmp.newFolder("art")) }
    private val blocks by lazy { ArtBlocks(tmp.root.resolve("artblock.tsv")) }
    private val silhouette = Fixtures.gen("1000x1000@3")
    private val photo = Fixtures.gen("1000x1000")
    private val set = setOf(ArtCache.sha256(silhouette))
    private val name = "Phtest"
    private val key = "name:phtest"
    private val search = "https://api.deezer.com/search/artist?q=$name"
    private val mbSearch = "https://musicbrainz.org/ws/2/artist/?query=$name&fmt=json&limit=10"
    private fun pic(h: String) = "https://cdn-images.dzcdn.net/images/artist/$h/1000x1000-000000-80-0-0.jpg"
    private val first = pic("11111111111111111111111111111111")
    private val second = pic("22222222222222222222222222222222")
    private val empty = pic("d41d8cd98f00b204e9800998ecf8427e")

    @After
    fun everyUrlHasAFixture() = assertEquals(emptyList<String>(), http.missing)

    private fun art() = ArtistArt(http, AwtCodec(), cache, { Sources.NONE.copy(deezer = true) }, "0.1.0", { time.now }, time, blocks = blocks, placeholders = set)

    private fun found(vararg urls: String): Response =
        Response(200, "{\"data\":[${urls.joinToString(",") { "{\"name\":\"$name\",\"nb_fan\":1,\"picture_medium\":\"$it\",\"picture_xl\":\"$it\"}" }}]}".toByteArray())

    private fun serve(vararg urls: Pair<String, ByteArray>) {
        http.queued.getOrPut(mbSearch) { ArrayDeque() } += Response(404, ByteArray(0))
        http.queued.getOrPut(search) { ArrayDeque() } += found(*urls.map { it.first }.toTypedArray())
        for ((u, b) in urls) http.queued.getOrPut(u) { ArrayDeque() } += Response(200, b)
    }

    private fun hit(source: String, sha: String, bytes: ByteArray) =
        cache.putHit(ArtCache.Entry(key, name, null, source, null, first, time.now, "id", sha), bytes)

    @Test
    fun theThreeMeasuredSilhouettesArePinned() {
        assertEquals(
            setOf(
                "0d659e802c2d9c13e8cf4e0fab3f85b176173a649b60d9ddc03af2397fb43275",
                "bd8dae144dc585a7eb090e2071fe386bbe0df6ebcc47ee0d96d5ec5c23274530",
                "5caf39f825879e7982065e4402d7dde41be7ffcee984a0e1d273a7efb2b78f00",
            ),
            Deezer.PLACEHOLDERS,
        )
    }

    @Test
    fun noOtherMeasuredDeezerImageIsAPlaceholder() {
        val other = Fixtures.text("deezer-sha.tsv").lines().filter { it.isNotBlank() }
        assertEquals(571, other.size)
        assertTrue(other.all { it.matches(Regex("[0-9a-f]{64}")) })
        assertTrue(other.none { it in Deezer.PLACEHOLDERS })
    }

    @Test
    fun theEmptyHashUrlIsNeverFetched() {
        serve(empty to photo, second to photo)
        assertEquals(Sources.DEEZER, art().get(name, null, 240, 320)!!.source)
        assertTrue(http.calls.none { it == empty })
        assertTrue(second in http.calls)
    }

    @Test
    fun aPlaceholderIsSkippedAndTheNextCandidateWins() {
        serve(first to silhouette, second to photo)
        val a = art().get(name, null, 240, 320)!!
        assertEquals(Sources.DEEZER, a.source)
        assertEquals(second, cache.hit(key)!!.url)
        assertEquals(ArtCache.sha256(photo), cache.hit(key)!!.sha)
        assertTrue(blocks.drops().isEmpty())
    }

    @Test
    fun aMissAfterASkippedPlaceholderWritesOneDrop() {
        serve(first to silhouette)
        assertNull(art().get(name, null, 240, 320))
        assertNull(cache.hit(key))
        assertEquals(listOf(Triple(key, null, name)), blocks.drops().map { Triple(it.key, it.mbid, it.name) })
        assertTrue(blocks.records().isEmpty())
        assertTrue("drop\t$key" in tmp.root.resolve("artblock.tsv").readText())
        cache.drop(key)
        time.now += 1000
        serve(first to silhouette)
        assertNull(art().get(name, null, 240, 320))
        assertEquals(1, blocks.drops().size)
    }

    @Test
    fun aPlainMissWritesNoDrop() {
        serve()
        assertNull(art().get(name, null, 240, 320))
        assertTrue(blocks.drops().isEmpty())
    }

    @Test
    fun theStartupScanDropsCachedPlaceholdersOnce() {
        hit(Sources.DEEZER, "", silhouette)
        cache.putHit(ArtCache.Entry("name:other", "Other", null, Sources.DEEZER, null, second, time.now, "id2", ArtCache.sha256(photo)), photo)
        cache.putHit(ArtCache.Entry("name:third", "Third", null, Wikimedia.SOURCE, null, second, time.now, "id3", ArtCache.sha256(silhouette)), silhouette)
        val a = art()
        assertEquals(1, a.scanPlaceholders())
        assertNull(cache.hit(key))
        assertTrue(cache.hit("name:other") != null)
        assertTrue(cache.hit("name:third") != null)
        assertEquals(listOf(ArtBlocks.Drop(key, null, name, time.now)), blocks.drops())
        hit(Sources.DEEZER, ArtCache.sha256(silhouette), silhouette)
        assertEquals(0, a.scanPlaceholders())
        assertEquals(1, blocks.drops().size)
        assertEquals(1, art().scanPlaceholders())
        assertNull(cache.hit(key))
        assertEquals(1, blocks.drops().size)
    }

    @Test
    fun aCachedPlaceholderIsDroppedAtGetTimeWithNoRecord() {
        hit(Sources.DEEZER, ArtCache.sha256(silhouette), silhouette)
        serve(second to photo)
        assertEquals(second, art().get(name, null, 240, 320)!!.let { cache.hit(key)!!.url })
        assertTrue(blocks.drops().isEmpty())
    }

    @Test
    fun aPlaceholderPickIsNotAnImage() {
        http.queued[first] = ArrayDeque(listOf(Response(200, silhouette)))
        val r = art().take(Tile(Sources.DEEZER, name, photo, first, "dz", null))
        assertEquals(PickLink.NOT_IMAGE, (r as Offered.Refused).reason)
        http.queued[second] = ArrayDeque(listOf(Response(200, photo)))
        assertTrue(art().take(Tile(Sources.DEEZER, name, photo, second, "dz", null)) is Offered.Ok)
    }

    @Test
    fun dropsStayOffTheBlockedListAndRoundTrip() {
        assertTrue(blocks.drop(key, null, name, 5))
        assertFalse(blocks.drop(key, "x", name, 6))
        assertTrue(blocks.records().isEmpty())
        assertEquals(listOf<ArtBlocks.Record>(ArtBlocks.Drop(key, null, name, 5)), blocks.pending("tcp:A"))
        assertNull(blocks.covering(false, name, setOf(key), null, 1, "tcp:A"))
        blocks.addPush(name, null)
        blocks.applied(blocks.drops().single(), "tcp:A")
        assertEquals(1, blocks.pushes("tcp:A").size)
        assertTrue(blocks.pending("tcp:A").isEmpty())
        assertFalse(blocks.drop(key, null, name, 7))
        blocks.save()
        val again = ArtBlocks(tmp.root.resolve("artblock.tsv"))
        assertEquals(listOf(ArtBlocks.Drop(key, null, name, 5, setOf("tcp:A"))), again.drops())
        assertTrue(again.records().isEmpty())
    }
}
