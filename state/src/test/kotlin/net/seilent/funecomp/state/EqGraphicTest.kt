package net.seilent.funecomp.state

import net.seilent.funecomp.proto.Eq
import net.seilent.funecomp.proto.FrameReader
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.gen.Enums
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EqGraphicTest {
    private fun pages(name: String): List<Eq> {
        val hex = File("../third_party/funeos/vectors.txt").readLines().filter { it.startsWith("hex $name ") }.joinToString("") { it.substringAfter("hex $name ") }
        val b = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return FrameReader().feed(b, 0, b.size).map { Eq.from(Node.decode("EQ", it.payload)) }
    }

    @Test
    fun graphicPagesFillTheParametricMirror() {
        val p = pages("eq_graphic")
        assertEquals(2, p.size)
        val first = DeviceState().withEq(p[0])
        assertFalse(first.eq.getValue(Enums.EqOutput.wired).complete)
        val d = first.withEq(p[1])
        val m = d.eq.getValue(Enums.EqOutput.wired)
        assertEquals("custom", m.key)
        assertEquals(listOf("off", "custom", "autoeq/HD 600.txt"), m.names.map { it.key })
        assertEquals(3L, m.total)
        assertTrue(m.complete)
        assertEquals(-30, m.ints!!.preamp10)
        assertEquals(setOf(Enums.EqOutput.wired), d.eq.keys)
    }

    @Test
    fun aSlotPageWithNoOutputKeysAsBefore() {
        val d = DeviceState().withEq(pages("eq_slot").single())
        assertEquals(setOf(-1), d.eq.keys)
        assertEquals("custom", d.eq.getValue(-1).key)
    }
}
