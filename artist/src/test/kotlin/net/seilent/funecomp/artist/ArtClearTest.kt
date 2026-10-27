package net.seilent.funecomp.artist

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArtClearTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val utada = "b539e453-c4fe-47e3-8a07-8517eac74429"
    private val radiohead = "a74b1b7f-71a5-4011-9441-d0b5e4122711"
    private val ado = "e134b52f-2e9e-4734-9bc3-bea9648d1fa1"
    private val pick = ArtPicks.PREFIX + "name:tsuki"

    private val http = Fixtures()
    private val time = FakeTime()
    private var sources = Sources.COMMONS.copy(theAudioDb = true, deezer = true)
    private val dir by lazy { File(tmp.root, "art") }
    private val cache by lazy { ArtCache(dir) }
    private val blocksFile by lazy { File(tmp.root, "artblock.tsv") }
    private val picksFile by lazy { File(tmp.root, "artpick.tsv") }

    private fun art() = ArtistArt(http, AwtCodec(), cache, { sources }, "0.1.0", { time.now }, time, blocks = ArtBlocks(blocksFile), picks = ArtPicks(picksFile)).also {
        http.now = { time.now }
    }

    private fun hit(key: String, name: String, source: String) =
        cache.putHit(ArtCache.Entry(key, name, key.takeIf { !it.contains(':') }, source, null, "https://e/$name.jpg", time.now, name), Images.jpeg(Images.photo(900, 900), 92))

    private fun seed(): ArtistArt {
        hit(utada, "宇多田ヒカル", Sources.DEEZER)
        hit(radiohead, "Radiohead", Sources.THE_AUDIO_DB)
        hit(pick, "TSUKI", Sources.DEEZER)
        cache.putMiss(ado, time.now, setOf(Sources.DEEZER, Sources.THE_AUDIO_DB))
        cache.putMbid("Ado", ado, time.now)
        cache.putBare("Ado Official", ado, time.now)
        val a = art()
        a.render(cache.hit(utada)!!, 256, 320)
        a.render(cache.hit(pick)!!, 256, 320)
        a.blocks.add(ArtBlocks.Block("name:x", null, Sources.DEEZER, "1", "", "x", 1))
        a.blocks.save()
        a.picks.add(ArtPicks.Pick("name:tsuki", null, "TSUKI", ArtPicks.IMAGE, Sources.DEEZER, "https://e/TSUKI.jpg", "TSUKI", "", null, null, 1))
        a.picks.save()
        return a
    }

    private fun files() = dir.listFiles()!!.map { it.name }.toSet()

    @Test
    fun aSourceClearRemovesOnlyItsPhotosAndItsNameFromMisses() {
        val a = seed()
        val blocks = blocksFile.readBytes()
        val picks = picksFile.readBytes()
        val pickFiles = files().filter { it.startsWith(hashOf(pick)) }.toSet()
        assertEquals(1, a.usage()[Sources.DEEZER]?.photos)
        assertTrue(a.usage().getValue(Sources.DEEZER).bytes > 0)
        val gone = a.clearSource(Sources.DEEZER)
        assertEquals(listOf(utada), gone.map { it.key })
        assertNull(cache.hit(utada))
        assertTrue(files().none { it.startsWith(hashOf(utada)) })
        assertNotNull(cache.hit(radiohead))
        assertNotNull(cache.hit(pick))
        assertTrue(files().containsAll(pickFiles))
        assertEquals(setOf(Sources.THE_AUDIO_DB), cache.miss(ado)!!.sources)
        assertEquals(ado, cache.mbid("Ado")!!.mbid)
        assertNull(a.usage()[Sources.DEEZER])
        assertArrayEquals(blocks, blocksFile.readBytes())
        assertArrayEquals(picks, picksFile.readBytes())
    }

    @Test
    fun aWholeClearLeavesOnlyThePickEntries() {
        val a = seed()
        val blocks = blocksFile.readBytes()
        val picks = picksFile.readBytes()
        val pickFiles = files().filter { it.startsWith(hashOf(pick)) }.toSet()
        assertEquals(2, a.usage()[null]?.photos)
        val gone = a.clearAll()
        assertEquals(setOf(utada, radiohead), gone.map { it.key }.toSet())
        assertEquals(pickFiles, files())
        assertNull(a.usage()[null])
        assertNotNull(cache.hit(pick))
        assertNull(cache.miss(ado))
        assertNull(cache.mbid("Ado"))
        assertNull(cache.bare("Ado Official"))
        assertArrayEquals(blocks, blocksFile.readBytes())
        assertArrayEquals(picks, picksFile.readBytes())
    }

    @Test
    fun aClearedSourceThatStaysOnLooksTheArtistUpAgain() {
        sources = Sources.COMMONS.copy(theAudioDb = true)
        assertEquals(Sources.THE_AUDIO_DB, art().get("Radiohead", radiohead, 384, 480)!!.source)
        val n = http.calls.size
        assertNotNull(art().get("Radiohead", radiohead, 384, 480))
        assertEquals(n, http.calls.size)
        art().clearSource(Sources.THE_AUDIO_DB)
        assertEquals(Sources.THE_AUDIO_DB, art().get("Radiohead", radiohead, 384, 480)!!.source)
        assertTrue(http.calls.size > n)
    }

    @Test
    fun aMissAsksTheClearedSourceAgainOnlyWhileItIsOn() {
        sources = Sources.COMMONS.copy(deezer = true)
        cache.putMiss(radiohead, time.now, sources.enabled)
        assertNull(art().get("Radiohead", radiohead, 384, 480))
        assertTrue(http.calls.isEmpty())
        art().clearSource(Sources.DEEZER)
        sources = Sources.COMMONS
        assertNull(art().get("Radiohead", radiohead, 384, 480))
        assertTrue(http.calls.isEmpty())
        sources = Sources.COMMONS.copy(deezer = true)
        assertEquals(Sources.DEEZER, art().get("Radiohead", radiohead, 384, 480)!!.source)
        assertTrue(http.calls.isNotEmpty())
    }

    @Test
    fun aClearRecordReachesEveryDeviceAndANewClearReachesThemAgain() {
        val r = ArtBlocks(blocksFile)
        r.cleared(utada, utada, "宇多田ヒカル", 1)
        r.applied(r.drops().single(), "tcp:A")
        assertTrue(r.pending("tcp:A").isEmpty())
        assertEquals(listOf(utada), r.pending("tcp:B").map { it.key })
        r.cleared(utada, utada, "宇多田ヒカル", 2)
        assertEquals(listOf(2L), r.pending("tcp:A").map { it.at })
        assertEquals(1, r.drops().size)
    }

    @Test
    fun aClearRecordExpiresNinetyDaysAfterItWasMade() {
        val day = 24L * 60 * 60 * 1000
        val r = ArtBlocks(blocksFile)
        r.cleared(utada, utada, "宇多田ヒカル", day)
        r.cleared(radiohead, radiohead, "Radiohead", 2 * day)
        r.save()
        assertEquals(listOf(utada, radiohead), ArtBlocks(blocksFile, { 90 * day }).drops().map { it.key })
        assertEquals(listOf(radiohead), ArtBlocks(blocksFile, { 91 * day }).drops().map { it.key })
    }

    @Test
    fun aClearLeavesAPendingDropThatIsNotAClearAsItIs() {
        val r = ArtBlocks(blocksFile)
        r.drop(utada, utada, "宇多田ヒカル", 1)
        r.applied(r.drops().single(), "tcp:A")
        r.cleared(utada, utada, "宇多田ヒカル", 2)
        assertEquals(listOf(ArtBlocks.Drop(utada, utada, "宇多田ヒカル", 1, setOf("tcp:A"))), r.drops())
    }

    @Test
    fun blockAndOtherDropRecordsNeverExpire() {
        val day = 24L * 60 * 60 * 1000
        val r = ArtBlocks(blocksFile)
        r.add(ArtBlocks.Block(utada, utada, Sources.DEEZER, "1", "", "宇多田ヒカル", day))
        r.drop(radiohead, radiohead, "Radiohead", day)
        r.save()
        val back = ArtBlocks(blocksFile, { 400 * day })
        assertEquals(listOf(radiohead), back.drops().map { it.key })
        assertEquals(setOf(utada, radiohead), back.pending("tcp:new").map { it.key }.toSet())
    }

    private fun hashOf(key: String) = java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
}
