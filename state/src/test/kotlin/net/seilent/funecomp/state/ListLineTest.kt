package net.seilent.funecomp.state

import net.seilent.funecomp.proto.Live
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListLineTest {
    private val ready = Enums.Card.ready
    private val music = listOf(Enums.View.albums, Enums.View.artists, Enums.View.songs, Enums.View.folders)

    private fun live(text: String) = DeviceState().reduce(Msg.LIVE, Node.decode("LIVE", Live(1, text).encode()), 0)

    @Test
    fun theCardLineReadsOutAndError() {
        assertEquals("no card", ListLine.card(Enums.Card.out))
        assertEquals("card error", ListLine.card(Enums.Card.error))
        assertNull(ListLine.card(Enums.Card.busy))
        assertNull(ListLine.card(ready))
        assertNull(ListLine.card(null))
    }

    @Test
    fun scanningIsTheRescanLiveWord() {
        assertTrue(ListLine.scanning(live("rescan=SCANNING\n")))
        assertFalse(ListLine.scanning(live("rescan=12 SONGS\n")))
        assertFalse(ListLine.scanning(DeviceState()))
    }

    @Test
    fun anEmptyLibraryNamesEachPivot() {
        assertEquals("no albums", ListLine.empty(ready, false, Enums.View.albums, 0, 0))
        assertEquals("no artists", ListLine.empty(ready, false, Enums.View.artists, 0, 0))
        assertEquals("no songs", ListLine.empty(ready, false, Enums.View.songs, 0, 0))
        assertEquals("no folders", ListLine.empty(ready, false, Enums.View.folders, 0, 0))
        assertEquals("no genres", ListLine.empty(ready, false, Enums.View.genres, 0, 0))
    }

    @Test
    fun aPivotWithRowsOrALibraryWithSongsShowsNoLine() {
        for (v in music) {
            assertNull(ListLine.empty(ready, false, v, 3, 12))
            assertNull(ListLine.empty(ready, false, v, 0, 12))
        }
        assertNull(ListLine.empty(ready, false, Enums.View.genres, 2, 12))
    }

    @Test
    fun genresWithNoGenreRowsSaysSoWithSongs() {
        assertEquals("no genres", ListLine.empty(ready, false, Enums.View.genres, 0, 12))
        assertEquals("no genres", ListLine.empty(ready, false, Enums.View.genres, 0, null))
        assertEquals("no genres", ListLine.empty(ready, true, Enums.View.genres, 0, 12))
    }

    @Test
    fun aFirstScanShowsNoEmptyLine() {
        for (v in music + Enums.View.genres) {
            assertNull(ListLine.empty(ready, true, v, 0, 0))
            assertNull(ListLine.empty(ready, true, v, 0, null))
        }
    }

    @Test
    fun noAnswerYetOrNoLibraryShowsNoLine() {
        for (v in music + Enums.View.genres) {
            assertNull(ListLine.empty(ready, false, v, null, 0))
            assertNull(ListLine.empty(ready, false, v, null, null))
        }
        for (v in music) assertNull(ListLine.empty(ready, false, v, 0, null))
    }

    @Test
    fun playlistsNeverShowTheEmptyLine() {
        assertNull(ListLine.empty(ready, false, Enums.View.playlists, 0, 0))
        assertNull(ListLine.empty(ready, false, Enums.View.playlists, 0, 12))
    }

    @Test
    fun onlyAReadyCardShowsTheEmptyLine() {
        for (c in listOf(Enums.Card.out, Enums.Card.error, Enums.Card.busy, null)) assertNull(ListLine.empty(c, false, Enums.View.albums, 0, 0))
    }

    @Test
    fun theQueueSaysNothingHereOnlyEmptyWithAReadyCard() {
        assertEquals("nothing here", ListLine.queue(ready, 0))
        assertNull(ListLine.queue(ready, 3))
        assertNull(ListLine.queue(Enums.Card.busy, 0))
        assertNull(ListLine.queue(Enums.Card.out, 0))
        assertNull(ListLine.queue(null, 0))
        assertNull(ListLine.queue(ready, null))
    }
}
