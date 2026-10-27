package net.seilent.funecomp.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesTest {
    private val old = "commons,theaudiodb,fanarttv,discogs,youtube,deezer"
    private val v1 = mapOf<String, Any?>("theaudiodb" to false, "discogs" to false, "fanarttv" to false, "fanarttvKey" to "", "deezer" to false, "youtube" to false, "order" to old)

    @Test
    fun everySourceIsOnByDefaultInTheMeasuredOrder() {
        val s = Sources()
        assertEquals(listOf("theaudiodb", "fanarttv", "youtube", "discogs", "commons", "deezer"), Sources.ORDER)
        assertEquals(Sources.ORDER, s.order)
        assertEquals(setOf("commons", "theaudiodb", "discogs", "youtube", "deezer"), s.enabled)
        assertEquals(listOf("theaudiodb", "youtube", "discogs", "commons", "deezer"), s.ordered)
        assertEquals(listOf("theaudiodb", "fanarttv", "youtube", "discogs", "commons", "deezer"), s.copy(fanartTvKey = "k").ordered)
    }

    @Test
    fun noneIsEveryToggleOffAndCommonsTurnsOff() {
        assertEquals(emptySet<String>(), Sources.NONE.enabled)
        assertEquals(Sources.NONE.copy(commons = true), Sources.COMMONS)
        assertEquals(setOf("theaudiodb", "discogs", "youtube", "deezer"), Sources(commons = false).enabled)
        assertEquals(listOf("theaudiodb", "youtube", "discogs", "deezer"), Sources(commons = false).ordered)
    }

    @Test
    fun commonsIsCommonsOnlyInTheOldOrder() {
        assertEquals(setOf("commons"), Sources.COMMONS.enabled)
        assertEquals(old.split(','), Sources.COMMONS.order)
        assertEquals(setOf("commons", "fanarttv"), Sources.COMMONS.copy(fanartTv = true, fanartTvKey = "k").enabled)
        assertEquals(setOf("commons"), Sources.COMMONS.copy(fanartTvKey = "k").enabled)
    }

    @Test
    fun aStoredOrderKeepsKnownIdsAndAppendsTheRest() {
        assertEquals(Sources.ORDER, Sources.order(null))
        assertEquals(listOf("deezer", "theaudiodb", "fanarttv", "youtube", "discogs", "commons"), Sources.order("deezer"))
    }

    @Test
    fun anOldFullWriteIsReadAsUnset() {
        val e = Sources.upgrade(v1)
        assertTrue(e.write)
        assertEquals(setOf("theaudiodb", "discogs", "fanarttv", "deezer", "youtube", "order"), e.remove)
    }

    @Test
    fun aTrueValueStays() {
        assertEquals(setOf("discogs", "fanarttv", "deezer", "youtube", "order"), Sources.upgrade(v1 + ("theaudiodb" to true)).remove)
    }

    @Test
    fun aStoredCommonsOffStays() {
        assertFalse("commons" in Sources.upgrade(v1 + ("commons" to false)).remove)
    }

    @Test
    fun aCustomOrderStays() {
        assertFalse("order" in Sources.upgrade(v1 + ("order" to "deezer,commons")).remove)
    }

    @Test
    fun theKeyStaysAndAKeyedFanartOffIsAChoice() {
        val e = Sources.upgrade(v1 + ("fanarttvKey" to "abc"))
        assertFalse("fanarttvKey" in e.remove)
        assertFalse("fanarttv" in e.remove)
        assertTrue("fanarttv" in Sources.upgrade(v1 + ("fanarttvKey" to "  ")).remove)
    }

    @Test
    fun aFreshInstallOnlyWritesTheRevision() {
        val e = Sources.upgrade(emptyMap())
        assertTrue(e.write)
        assertEquals(emptySet<String>(), e.remove)
    }

    @Test
    fun anUpgradedStoreIsLeftAlone() {
        val e = Sources.upgrade(v1 + ("defaults" to 2))
        assertFalse(e.write)
        assertEquals(emptySet<String>(), e.remove)
    }
}
