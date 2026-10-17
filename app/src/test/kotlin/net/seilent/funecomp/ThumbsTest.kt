package net.seilent.funecomp

import net.seilent.funecomp.art.Thumbs
import net.seilent.funecomp.art.Thumbs.Done
import net.seilent.funecomp.art.Thumbs.Got
import net.seilent.funecomp.art.Thumbs.Want
import net.seilent.funecomp.proto.gen.Err
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThumbsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val jpeg = byteArrayOf(1, 2, 3, 4)

    private fun thumbs(rev: Long = 3) = Thumbs(tmp.root).also { it.rev(rev) }

    @Test
    fun buckets() {
        assertEquals(256, Thumbs.bucket(174))
        assertEquals(256, Thumbs.bucket(256))
        assertEquals(512, Thumbs.bucket(257))
        assertEquals(512, Thumbs.bucket(1200))
    }

    @Test
    fun imageIsIndexedAndSurvivesARestart() {
        val t = thumbs()
        val w = Want(Thumbs.NOW_PLAYING, 101, 512, "Bad Mode")
        assertNull(t.want(w))
        assertEquals(w, t.next())
        val d = t.done(w, Got.Image(0xabcdefL, jpeg)) as Done.Ready
        assertArrayEquals(jpeg, d.file.readBytes())
        assertEquals("00abcdef.jpg", d.file.name)
        val again = thumbs()
        assertEquals(d.file, again.want(w))
        assertNull(again.next())
    }

    @Test
    fun albumCrcIsSentAsHaveForANewTrack() {
        val t = thumbs()
        t.want(Want(0, 101, 512, "Bad Mode"))
        t.done(t.next()!!, Got.Image(77, jpeg))
        val other = Want(0, 102, 512, "Bad Mode")
        t.want(other)
        assertEquals(77L, t.have(t.next()!!))
        assertTrue(t.done(other, Got.Have(77)) is Done.Ready)
        assertTrue(t.cached(102, 512) != null)
        assertNull(t.have(Want(0, 103, 512, "Other")))
        assertNull(t.have(Want(0, 103, 256, "Bad Mode")))
    }

    @Test
    fun notFoundIsCachedForTheSession() {
        val t = thumbs()
        val w = Want(1, 101, 256, null)
        t.want(w)
        assertEquals(Done.Miss, t.done(t.next()!!, Got.Failed(Err.notFound)))
        assertNull(t.want(w))
        assertNull(t.next())
        t.session()
        t.want(w)
        assertEquals(w, t.next())
    }

    @Test
    fun busyRetriesThreeTimes() {
        val t = thumbs()
        val w = Want(1, 101, 256, null)
        repeat(3) { assertEquals(Thumbs.BUSY_RETRY_MS, (t.done(w, Got.Failed(Err.busy)) as Done.Retry).afterMs) }
        assertEquals(Done.Dropped, t.done(w, Got.Failed(Err.busy)))
        assertEquals(Done.Dropped, t.done(w, Got.Failed(null)))
    }

    @Test
    fun aNewerWantForTheSameSlotSupersedes() {
        val t = thumbs()
        val a = Want(Thumbs.TILE, 101, 256, null)
        val b = Want(Thumbs.TILE, 102, 256, null)
        val np = Want(Thumbs.NOW_PLAYING, 103, 512, null)
        t.want(a)
        t.want(b)
        t.want(np)
        assertEquals(np, t.next())
        assertEquals(b, t.next())
        assertNull(t.next())
        t.want(a)
        val inFlight = t.next()!!
        assertFalse(t.superseded(inFlight))
        t.want(b)
        assertTrue(t.superseded(inFlight))
    }

    @Test
    fun libRevChangeStartsANewIndex() {
        val t = thumbs(3)
        val w = Want(0, 101, 512, null)
        t.want(w)
        t.done(t.next()!!, Got.Image(5, jpeg))
        t.rev(4)
        assertNull(t.cached(101, 512))
        assertFalse(tmp.root.resolve("index-3.tsv").exists())
        assertTrue(tmp.root.resolve("00000005.jpg").exists())
        assertNull(Thumbs(tmp.root).want(w))
    }
}
