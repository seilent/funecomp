package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.Lib
import net.seilent.funecomp.proto.LibRow
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibArtistSimTest {
    private class Sink : Out {
        val frames = ArrayList<Pair<Int, ByteArray>>()
        override fun push(type: Int, payload: ByteArray) {
            frames += type to payload
        }
    }

    private val base = Library.synthetic()
    private val listed = base.tracks.takeLast(4).map { it.id }
    private val lib = Library(base.tracks, playlists = listOf(Playlist("mix", "/Playlists/mix.m3u8", listed)))
    private val joined = lib.tracks.associate { it.id to it.artists.joinToString(", ") }
    private val noBit = Groups.all and Groups.mask("trackArtist").inv()

    private fun device(minor: Int = Proto.minor) = Device(Options(Board.of("v1"), minor = minor), lib) { 1000L }

    private fun link(d: Device, features: Long = Groups.all) = Sink().also { d.linked(it, features) }

    private fun rows(d: Device, out: Sink, view: Int, parent: String = ""): List<LibRow> {
        out.frames.clear()
        assertTrue(d.request(Msg.LIB_GET, Node.decode("LIB_GET", Req.libGet(view, parent, 0, 100, d.lib.rev)), out) is Answer.Ok)
        return out.frames.filter { it.first == Msg.LIB }.flatMap { Lib.from(Node.decode("LIB", it.second)).rows }
    }

    @Test
    fun songRowsOfEveryViewCarryTheJoinedArtistsForABit39Peer() {
        val d = device()
        val out = link(d)
        val songs = rows(d, out, Enums.View.songs)
        assertEquals(lib.tracks.map { joined.getValue(it.id) }, songs.map { it.artist })
        assertEquals("Kendrick Lamar, SZA", songs.single { it.name == "All The Stars" }.artist)
        val album = rows(d, out, Enums.View.albums).first()
        for (parent in listOf(Enums.View.albumTracks to album.key!!, Enums.View.playlists to "/Playlists/mix.m3u8", Enums.View.genres to "Rock")) {
            val r = rows(d, out, parent.first, parent.second)
            assertTrue(parent.toString(), r.isNotEmpty())
            assertEquals(parent.toString(), r.map { joined.getValue(it.first!!) }, r.map { it.artist })
        }
        assertTrue(d.request(Msg.ACTION, Node.decode("ACTION", Req.action(Enums.ActionCmd.favorite, listed[0])), out) is Answer.Ok)
        assertEquals(listOf(joined.getValue(listed[0])), rows(d, out, Enums.View.favorites).map { it.artist })
    }

    @Test
    fun albumRowsCarryTheAlbumArtistAndPlaylistRowsNone() {
        val d = device()
        val out = link(d)
        assertEquals(lib.tracks.groupBy { it.album to it.albumArtist }.keys.map { it.second }, rows(d, out, Enums.View.albums).map { it.artist })
        assertEquals("Various Artists", rows(d, out, Enums.View.albums).single { it.name == "Black Panther" }.artist)
        assertEquals(listOf<String?>(null), rows(d, out, Enums.View.playlists).map { it.artist })
    }

    @Test
    fun aPeerWithoutTheBitGetsNoArtist() {
        val d = device()
        assertTrue(rows(d, link(d, noBit), Enums.View.songs).all { it.artist == null })
    }

    @Test
    fun aMinor17SimServesNoArtist() {
        val d = device(17)
        assertEquals(0L, d.helloReply().features and Groups.mask("trackArtist"))
        assertEquals(Groups.mask("trackArtist"), device().helloReply().features and Groups.mask("trackArtist"))
        assertTrue(rows(d, link(d), Enums.View.songs).all { it.artist == null })
    }
}
