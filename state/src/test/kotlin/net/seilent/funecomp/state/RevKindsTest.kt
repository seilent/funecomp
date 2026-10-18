package net.seilent.funecomp.state

import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.Lib
import net.seilent.funecomp.proto.LibRow
import net.seilent.funecomp.proto.PageInfo
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RevKindsTest {
    private fun hello(snapRev: Long?) = HelloReply(1, 3, "v1", null, null, null, null, null, 16384, 0, 0, 0, 7, 1, snapRev)

    private fun lib(view: Int, libRev: Long, snapRev: Long?) = Lib(libRev, view, "", PageInfo(0, 1, false), listOf(LibRow("x", 1, 1, "x")), snapRev)

    @Test
    fun aLibRevChangeDropsOnlyTheListPages() {
        var d = DeviceState().withHello(hello(4)).withState(State(libRev = 7, snapRev = 4), 0)
        d = d.withLib(lib(Enums.View.albums, 7, 4)).withLib(lib(Enums.View.favorites, 7, 4))
        assertEquals(2, d.lib.pages.size)
        d = d.withState(State(libRev = 8, snapRev = 4), 0)
        assertEquals(setOf(LibKey(Enums.View.albums, "")), d.lib.pages.keys)
        assertEquals(8L, d.lib.rev)
        d = d.withLib(lib(Enums.View.favorites, 8, 4))
        assertEquals(2, d.lib.pages.size)
        d = d.withLib(lib(Enums.View.albums, 7, 4))
        assertEquals(2, d.lib.pages.size)
        d = d.withState(State(libRev = 9, snapRev = 5), 0)
        assertEquals(0, d.lib.pages.size)
        assertEquals(5L, d.lib.snap)
    }

    @Test
    fun requestsCarryTheRevOfTheirView() {
        val d = DeviceState().withHello(hello(4)).withState(State(libRev = 7, snapRev = 4), 0)
        assertNull(d.lib.libRev(Enums.View.albums))
        assertEquals(4L, d.lib.snapRev(Enums.View.albums))
        assertEquals(7L, d.lib.libRev(Enums.View.favorites))
        assertNull(d.lib.snapRev(Enums.View.playlists))
        assertEquals(4L, d.lib.dataRev)
        val toggled = d.withState(State(libRev = 8, snapRev = 4), 0)
        assertEquals(d.lib.askKey(Enums.View.albums, "", 0), toggled.lib.askKey(Enums.View.albums, "", 0))
        assertNotEquals(d.lib.askKey(Enums.View.favorites, "", 0), toggled.lib.askKey(Enums.View.favorites, "", 0))
        val old = DeviceState().withHello(hello(null)).withState(State(libRev = 7), 0)
        assertEquals(7L, old.lib.libRev(Enums.View.albums))
        assertNull(old.lib.snapRev(Enums.View.albums))
        assertEquals(7L, old.lib.dataRev)
    }
}
