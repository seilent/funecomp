package net.seilent.funecomp

import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.sim.Track
import net.seilent.funecomp.state.LibKey
import net.seilent.funecomp.ui.folderStart
import net.seilent.funecomp.ui.isTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderPlayTest {
    private fun track(id: Long, title: String, folder: String, file: String) = Track(id, "A", listOf("A"), "X", "A", title, 200_000, folder, file)

    @Test
    fun songTapInAFolderWithSubfoldersStartsThatSong() {
        val lib = Library(
            listOf(
                track(11, "Deep", "Root/Beta", "01.flac"),
                track(12, "Deeper", "Root/alpha", "01.flac"),
                track(19, "Deepest", "Root/alpha/x", "01.flac"),
                track(13, "Zed", "Root", "a.flac"),
                track(20, "Bee", "Root", "ab.flac"),
                track(14, "Alpha", "Root", "B.flac"),
                track(15, "Cat", "Root", "c.flac"),
                track(16, "Dog", "Root", "C.flac"),
                track(17, "Fox", "Root", "áa.flac"),
                track(18, "Echo", "Root", "Áz.flac"),
            ),
        )
        assertEquals(listOf(19L, 12, 11, 13, 20, 14, 16, 15, 18, 17), lib.tracksOf(Enums.View.folders, "Root"))
        val c = SimControl("ap80", lib)
        c.libGet(Enums.View.folders, "Root", 0)
        val list = c.device.lib.pages.getValue(LibKey(Enums.View.folders, "Root"))
        assertEquals(
            listOf("alpha", "Beta", "Zed", "Bee", "Alpha", "Dog", "Cat", "Echo", "Fox"),
            (0 until list.total.toInt()).map { list.rows.getValue(it.toLong()).name },
        )
        for ((row, id) in listOf(2 to 13L, 3 to 20L, 4 to 14L, 5 to 16L, 6 to 15L, 7 to 18L, 8 to 17L)) {
            val r = list.rows.getValue(row.toLong())
            assertEquals(true, r.isTrack())
            c.play(Enums.View.folders, "Root", list.folderStart(row))
            assertEquals(id, c.device.playback.state.track)
        }
    }
}
