package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.Lib
import net.seilent.funecomp.proto.LibRow
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistSimTest {
    private class Sink : Out {
        val frames = ArrayList<Pair<Int, ByteArray>>()
        override fun push(type: Int, payload: ByteArray) {
            frames += type to payload
        }

        fun of(type: Int) = frames.filter { it.first == type }.map { Node.decode(Msg.info.getValue(type).name, it.second) }
    }

    private val base = Library.synthetic()
    private val road = base.tracks.take(6).map { it.id }
    private val night = base.tracks.drop(10).take(3).map { it.id }
    private val roadKey = "/Playlists/road.m3u8"
    private val nightKey = "/Playlists/night.m3u8"
    private val lib = Library(base.tracks, playlists = listOf(Playlist("road", roadKey, road), Playlist("night", nightKey, night)))
    private val d = Device(Options(Board.of("v1")), lib) { 1000L }

    private fun req(type: Int, payload: ByteArray, out: Out = Sink()) = d.request(type, Node.decode(Msg.info.getValue(type).name, payload), out)

    private fun read(view: Int, parent: String, rev: Long? = lib.rev): Pair<Answer, List<LibRow>> {
        val out = Sink()
        val a = req(Msg.LIB_GET, Req.libGet(view, parent, 0, 100, rev), out)
        return a to out.of(Msg.LIB).flatMap { Lib.from(it).rows }
    }

    private fun rows(view: Int, parent: String) = read(view, parent).also { assertTrue(it.first is Answer.Ok) }.second

    private fun code(a: Answer) = (a as Answer.Fail).code

    private fun state() = State.from(Node.decode("STATE", d.state()))

    private fun play(view: Int, parent: String, start: Long): Answer = req(Msg.PLAY, Req.play(view, parent, lib.rev, start, false))

    private fun title(id: Long) = lib.byId.getValue(id).title

    private fun change(f: Library.() -> Unit): State {
        val out = Sink()
        d.lists(out, f)
        return State.from(out.of(Msg.STATE).single())
    }

    @Test
    fun theRootListsEachPlaylistWithItsCountFirstTrackAndSource() {
        val root = rows(Enums.View.playlists, "")
        assertEquals(listOf("road", "night"), root.map { it.name })
        assertEquals(listOf(6L, 3L), root.map { it.count })
        assertEquals(listOf(road[0], night[0]), root.map { it.first })
        assertEquals(listOf(roadKey, nightKey), root.map { it.key })
    }

    @Test
    fun aSourceListsItsTracksAsSongRows() {
        val rows = rows(Enums.View.playlists, roadKey)
        assertEquals(road.map { title(it) }, rows.map { it.name })
        assertEquals(road, rows.map { it.first })
        assertEquals(road.map { java.lang.Long.toUnsignedString(it) }, rows.map { it.key })
        assertTrue(rows.all { it.count == 1L })
    }

    @Test
    fun aRenameMovesTheRevAndTheOldSourceAnswersStaleRev() {
        val before = lib.rev
        val s = change { rename(roadKey, "/Playlists/trip.m3u8", "trip") }
        assertEquals(before + 1, lib.rev)
        assertEquals(lib.rev, s.libRev)
        assertEquals(Err.staleRev, code(read(Enums.View.playlists, roadKey).first))
        assertEquals(Err.staleRev, code(play(Enums.View.playlists, roadKey, 0)))
        val root = rows(Enums.View.playlists, "")
        assertEquals(listOf("trip", "night"), root.map { it.name })
        assertEquals(6L, root[0].count)
        assertEquals(road.map { title(it) }, rows(Enums.View.playlists, "/Playlists/trip.m3u8").map { it.name })
    }

    @Test
    fun aHideMovesTheRevAndAShowBringsTheListBack() {
        val before = lib.rev
        assertEquals(before + 1, change { hide(roadKey) }.libRev)
        assertEquals(Err.staleRev, code(read(Enums.View.playlists, roadKey).first))
        assertEquals(Err.staleRev, code(play(Enums.View.playlists, roadKey, 0)))
        assertEquals(listOf("night"), rows(Enums.View.playlists, "").map { it.name })
        assertEquals(before + 2, change { hide(roadKey, false) }.libRev)
        assertEquals(listOf("road", "night"), rows(Enums.View.playlists, "").map { it.name })
        assertEquals(road, rows(Enums.View.playlists, roadKey).map { it.first })
    }

    @Test
    fun aDeleteMovesTheRevAndTheSourceAnswersStaleRev() {
        val before = lib.rev
        assertEquals(before + 1, change { delete(nightKey) }.libRev)
        assertEquals(Err.staleRev, code(read(Enums.View.playlists, nightKey).first))
        assertEquals(Err.staleRev, code(play(Enums.View.playlists, nightKey, 0)))
        assertEquals(listOf("road"), rows(Enums.View.playlists, "").map { it.name })
    }

    @Test
    fun anEditMovesTheRevAndTheRowsFollow() {
        val before = lib.rev
        assertEquals(before + 1, change { edit(roadKey, road - road[2]) }.libRev)
        assertEquals((road - road[2]).map { title(it) }, rows(Enums.View.playlists, roadKey).map { it.name })
        assertEquals(5L, rows(Enums.View.playlists, "")[0].count)
        assertTrue(play(Enums.View.playlists, roadKey, 4) is Answer.Ok)
        assertEquals(title(road[5]), state().title)
    }

    @Test
    fun aRequestAtTheOldRevAnswersStaleRev() {
        val before = lib.rev
        change { hide(nightKey) }
        assertEquals(Err.staleRev, code(read(Enums.View.playlists, "", before).first))
        assertEquals(Err.staleRev, code(read(Enums.View.playlists, roadKey, before).first))
        assertEquals(Err.staleRev, code(req(Msg.PLAY, Req.play(Enums.View.playlists, roadKey, before, 0, false))))
    }

    @Test
    fun aPlayFromARowQueuesTheListAtThatRow() {
        assertTrue(play(Enums.View.playlists, roadKey, 3) is Answer.Ok)
        val s = state()
        assertEquals(title(road[3]), s.title)
        assertEquals(6L, s.slots)
        assertEquals(3L, s.slot)
        assertEquals(Err.badValue, code(play(Enums.View.playlists, "", 0)))
    }

    @Test
    fun favoritesListNewestFirst() {
        val (a, b) = base.tracks[2].id to base.tracks[7].id
        for (id in listOf(a, b)) assertTrue(req(Msg.ACTION, Req.action(Enums.ActionCmd.favorite, id)) is Answer.Ok)
        assertEquals(listOf(b, a), rows(Enums.View.favorites, "").map { it.first })
        assertTrue(play(Enums.View.favorites, "", 0) is Answer.Ok)
        assertEquals(title(b), state().title)
    }
}
