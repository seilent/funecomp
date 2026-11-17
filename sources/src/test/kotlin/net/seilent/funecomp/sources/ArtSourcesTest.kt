package net.seilent.funecomp.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtSourcesTest {
    private val old = "commons,theaudiodb,fanarttv,discogs,youtube,deezer"
    private val v1 = mapOf<String, Any?>("theaudiodb" to false, "discogs" to false, "fanarttv" to false, "fanarttvKey" to "", "deezer" to false, "youtube" to false, "order" to old)

    @Test
    fun everySourceIsOnByDefaultInTheMeasuredOrder() {
        val s = ArtSources.DEFAULT
        assertEquals(listOf("theaudiodb", "fanarttv", "youtube", "discogs", "commons", "deezer"), ArtSources.ORDER)
        assertEquals(ArtSources.ORDER, s.order)
        assertEquals(setOf("commons", "theaudiodb", "discogs", "youtube", "deezer"), s.enabled)
        assertEquals(listOf("theaudiodb", "youtube", "discogs", "commons", "deezer"), s.ordered)
        assertEquals(listOf("theaudiodb", "fanarttv", "youtube", "discogs", "commons", "deezer"), s.copy(fanartTvKey = "k").ordered)
    }

    @Test
    fun noneIsEveryToggleOffAndCommonsTurnsOff() {
        assertEquals(emptySet<String>(), ArtSources.NONE.enabled)
        assertEquals(ArtSources.NONE.copy(commons = true), ArtSources.COMMONS)
        assertEquals(setOf("theaudiodb", "discogs", "youtube", "deezer"), ArtSources.DEFAULT.copy(commons = false).enabled)
        assertEquals(listOf("theaudiodb", "youtube", "discogs", "deezer"), ArtSources.DEFAULT.copy(commons = false).ordered)
    }

    @Test
    fun commonsIsCommonsOnlyInTheOldOrder() {
        assertEquals(setOf("commons"), ArtSources.COMMONS.enabled)
        assertEquals(old.split(','), ArtSources.COMMONS.order)
        assertEquals(setOf("commons", "fanarttv"), ArtSources.COMMONS.copy(fanartTv = true, fanartTvKey = "k").enabled)
        assertEquals(setOf("commons"), ArtSources.COMMONS.copy(fanartTvKey = "k").enabled)
        assertEquals(setOf("commons", "youtube"), YouTubeArt.with(ArtSources.COMMONS, true).enabled)
    }

    @Test
    fun aStoredOrderKeepsKnownIdsAndAppendsTheRest() {
        assertEquals(ArtSources.ORDER, ArtSources.order(null))
        assertEquals(listOf("deezer", "theaudiodb", "fanarttv", "youtube", "discogs", "commons"), ArtSources.order("deezer"))
    }

    @Test
    fun anOldFullWriteIsReadAsUnset() {
        val e = ArtSources.upgrade(v1)
        assertTrue(e.write)
        assertEquals(setOf("theaudiodb", "discogs", "fanarttv", "deezer", "youtube", "order"), e.remove)
    }

    @Test
    fun aTrueValueStays() {
        assertEquals(setOf("discogs", "fanarttv", "deezer", "youtube", "order"), ArtSources.upgrade(v1 + ("theaudiodb" to true)).remove)
    }

    @Test
    fun aStoredCommonsOffStays() {
        assertFalse("commons" in ArtSources.upgrade(v1 + ("commons" to false)).remove)
    }

    @Test
    fun aCustomOrderStays() {
        assertFalse("order" in ArtSources.upgrade(v1 + ("order" to "deezer,commons")).remove)
    }

    @Test
    fun anUnknownIdInAStoredOrderIsDroppedAndTheRestKeepTheirPlaces() {
        assertEquals(listOf("deezer", "discogs", "theaudiodb", "fanarttv", "youtube", "commons"), ArtSources.order("deezer,bogus,discogs"))
    }

    @Test
    fun theKeyStaysAndAKeyedFanartOffIsAChoice() {
        val e = ArtSources.upgrade(v1 + ("fanarttvKey" to "abc"))
        assertFalse("fanarttvKey" in e.remove)
        assertFalse("fanarttv" in e.remove)
        assertTrue("fanarttv" in ArtSources.upgrade(v1 + ("fanarttvKey" to "  ")).remove)
    }

    @Test
    fun aFreshInstallOnlyWritesTheRevision() {
        val e = ArtSources.upgrade(emptyMap())
        assertTrue(e.write)
        assertEquals(emptySet<String>(), e.remove)
    }

    @Test
    fun anUpgradedStoreIsLeftAlone() {
        val e = ArtSources.upgrade(v1 + ("defaults" to 2))
        assertFalse(e.write)
        assertEquals(emptySet<String>(), e.remove)
    }
}
