package net.seilent.funecomp.state

import net.seilent.funecomp.proto.LibRow
import net.seilent.funecomp.proto.gen.Enums
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListGoneTest {
    private val ready = Enums.Card.ready
    private val lists = Enums.View.playlists

    private fun root(n: Int, read: Int = n, key: (Int) -> String = { "/Playlists/$it.m3u8" }) =
        LibList(n.toLong(), (0 until read).associate { it.toLong() to LibRow("list $it", 3, null, key(it)) })

    @Test
    fun aCompleteRootWithTheKeyIsNotGone() {
        assertEquals(false, ListGone.gone(lists, "/Playlists/1.m3u8", ready, root(3)))
        assertEquals(false, ListGone.gone(lists, "road", ready, LibList(1, mapOf(0L to LibRow("road", 3, null, null)))))
    }

    @Test
    fun aCompleteRootWithoutTheKeyIsGone() {
        assertEquals(true, ListGone.gone(lists, "/Playlists/9.m3u8", ready, root(3)))
        assertEquals(true, ListGone.gone(lists, "/Playlists/0.m3u8", ready, LibList(0)))
    }

    @Test
    fun anIncompleteRootDecidesNothing() {
        assertNull(ListGone.gone(lists, "/Playlists/100.m3u8", ready, root(101, 100)))
        assertNull(ListGone.gone(lists, "/Playlists/0.m3u8", ready, LibList(3, mapOf(1L to LibRow("list 1", 3, null, "/Playlists/1.m3u8")))))
        assertNull(ListGone.gone(lists, "/Playlists/0.m3u8", ready, null))
        assertEquals(false, ListGone.gone(lists, "/Playlists/100.m3u8", ready, root(101)))
    }

    @Test
    fun aCardNotReadyDecidesNothing() {
        for (c in listOf(Enums.Card.busy, Enums.Card.out, Enums.Card.error, null)) assertNull(ListGone.gone(lists, "/Playlists/9.m3u8", c, root(3)))
    }

    @Test
    fun favoritesAreNeverGone() {
        assertEquals(false, ListGone.gone(Enums.View.favorites, "", ready, root(3)))
        assertEquals(false, ListGone.gone(Enums.View.favorites, "", Enums.Card.out, null))
    }
}
