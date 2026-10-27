package net.seilent.funecomp.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KeepTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val utada = "b539e453-c4fe-47e3-8a07-8517eac74429"
    private val radiohead = "a74b1b7f-71a5-4011-9441-d0b5e4122711"
    private val credit = Credit("A", "CC BY 4.0", "https://www.deezer.com/artist/1")

    private val fixtures = Fixtures()
    private val denied = ArrayList<String>()
    private var open = false
    private val time = FakeTime()
    private var sources = Sources.COMMONS
    private val dir by lazy { File(tmp.root, "art") }
    private val cache by lazy { ArtCache(dir) }

    private val http = Http { url, h ->
        if (open) {
            fixtures.get(url, h)
        } else {
            denied += url
            throw AssertionError("request $url")
        }
    }

    private fun art() = ArtistArt(http, AwtCodec(), cache, { sources }, "0.1.0", { time.now }, time).also {
        fixtures.now = { time.now }
    }

    private fun keep(key: String = utada, name: String = "宇多田ヒカル") {
        cache.putHit(ArtCache.Entry(key, name, key, Sources.DEEZER, credit, "https://e/$key.jpg", time.now, "1"), Images.jpeg(Images.photo(900, 900), 92))
    }

    private fun files() = dir.listFiles()!!.map { it.name }.sorted()

    @Test
    fun anOffSourcePhotoIsShownAndNeverLookedUpOrMissed() {
        keep()
        val a = art()
        assertNotNull(a.render(cache.hit(utada)!!, 384, 480))
        val before = files()
        assertNull(a.get("宇多田ヒカル", utada, 384, 480))
        assertNull(a.refresh("宇多田ヒカル", utada, 384, 480))
        assertTrue(denied.toString(), denied.isEmpty())
        assertNull(cache.miss(utada))
        assertEquals(before, files())
        assertEquals(Sources.DEEZER, a.cachedEntry("宇多田ヒカル", utada)!!.source)
        assertEquals(listOf(utada), a.credits().map { it.key })
    }

    @Test
    fun everySourceOffStillShowsTheKeptPhoto() {
        keep()
        sources = Sources.NONE
        val a = art()
        assertNull(a.get("宇多田ヒカル", utada, 384, 480))
        assertNull(a.refresh("宇多田ヒカル", utada, 384, 480))
        assertNotNull(a.cachedEntry("宇多田ヒカル", utada))
        assertNotNull(cache.hit(utada))
        assertTrue(denied.toString(), denied.isEmpty())
    }

    @Test
    fun theSourceOnAgainServesTheKeptPhotoWithNoRequest() {
        keep()
        assertNull(art().get("宇多田ヒカル", utada, 384, 480))
        sources = Sources.COMMONS.copy(deezer = true)
        val got = art().get("宇多田ヒカル", utada, 384, 480)
        assertEquals(Sources.DEEZER, got!!.source)
        assertTrue(denied.toString(), denied.isEmpty())
    }

    @Test
    fun anotherArtistIsLookedUpOverTheSourcesThatAreOn() {
        keep(radiohead, "Radiohead")
        open = true
        val got = art().get("Utada Hikaru", null, 384, 480)
        assertEquals(Wikimedia.SOURCE, got!!.source)
        assertTrue(fixtures.calls.isNotEmpty())
    }

    @Test
    fun aMissBesideAKeptHitLeavesItsFiles() {
        keep()
        art().render(cache.hit(utada)!!, 256, 320)
        val before = files()
        cache.putMiss(utada, time.now, setOf(Wikimedia.SOURCE))
        assertNotNull(cache.hit(utada))
        assertEquals(setOf(Wikimedia.SOURCE), cache.miss(utada)!!.sources)
        assertTrue(files().containsAll(before))
    }

    @Test
    fun anOldMissMetaIsReadThenReplacedByTheMissFile() {
        cache.putMiss("k", 5, setOf(Wikimedia.SOURCE))
        val miss = dir.listFiles { f -> f.name.endsWith(".miss") }!!.single()
        val meta = File(dir, miss.name.removeSuffix(".miss") + ".meta")
        assertTrue(miss.renameTo(meta))
        assertEquals(5L, cache.miss("k")!!.at)
        cache.putMiss("k", 9, setOf(Wikimedia.SOURCE))
        assertFalse(meta.exists())
        assertEquals(9L, cache.miss("k")!!.at)
        assertTrue(File(dir, miss.name).isFile)
    }
}
