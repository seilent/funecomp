package net.seilent.funecomp

import net.seilent.funecomp.art.Forward
import net.seilent.funecomp.artist.ArtPusher
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookArtTest {
    private val st = State(source = Enums.Source.local, track = 401, title = "Chapter 3", artist = "Natsume Soseki", artists = listOf("Natsume Soseki"), mbids = listOf(""), album = "Kokoro")

    @Test
    fun aBookHasNoPlayingArtistsToPushOrFetch() {
        assertEquals(emptyList<Pair<String, String?>>(), Forward.playingArtists(st.copy(book = true)))
        assertTrue(ArtPusher.order(st).isNotEmpty())
        assertEquals(ArtPusher.order(st), Forward.playingArtists(st))
        assertEquals(ArtPusher.order(st), Forward.playingArtists(st.copy(book = false)))
    }
}
