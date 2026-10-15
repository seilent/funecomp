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
    private fun track(id: Long, title: String, folder: String) = Track(id, "A", listOf("A"), "X", "A", title, 200_000, folder)

    @Test
    fun songTapInAFolderWithASubfolderStartsThatSong() {
        val lib = Library(listOf(track(11, "Deep", "Root/Sub"), track(12, "Beta", "Root"), track(13, "Alpha", "Root")))
        val c = SimControl("ap80", lib)
        c.libGet(Enums.View.folders, "Root", 0)
        val list = c.device.lib.pages.getValue(LibKey(Enums.View.folders, "Root"))
        assertEquals(listOf("Sub", "Alpha", "Beta"), (0 until list.total.toInt()).map { list.rows.getValue(it.toLong()).name })
        for ((row, id) in listOf(1 to 13L, 2 to 12L)) {
            val r = list.rows.getValue(row.toLong())
            assertEquals(true, r.isTrack())
            c.play(Enums.View.folders, "Root", list.folderStart(row))
            assertEquals(id, c.device.playback.state.track)
        }
    }
}
