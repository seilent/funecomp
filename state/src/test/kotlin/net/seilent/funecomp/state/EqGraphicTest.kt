package net.seilent.funecomp.state

import net.seilent.funecomp.proto.Eq
import net.seilent.funecomp.proto.EqName
import net.seilent.funecomp.proto.FrameReader
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.PageInfo
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
    fun graphicPagesFillBothSidesOfTheMirror() {
        val p = pages("eq_graphic")
        assertEquals(2, p.size)
        val first = DeviceState().withEq(p[0])
        assertFalse(first.eq.getValue(EqSlots.HEADPHONES).complete)
        val d = first.withEq(p[1])
        val m = d.eq.getValue(EqSlots.HEADPHONES)
        assertEquals("custom", m.key)
        assertEquals(listOf("off", "custom", "autoeq/HD 600.txt"), m.names.map { it.key })
        assertEquals(3L, m.total)
        assertTrue(m.complete)
        assertEquals(-30, m.ints!!.preamp10)
        assertEquals(Enums.EqMode.graphic, m.mode)
        assertEquals("custom", m.graphicKey)
        assertEquals(4L, m.graphicTotal)
        assertEquals("/data/funeos/eq/graphic/Rock.ini", m.graphicNames[2].key)
        assertTrue(m.graphicComplete)
        assertEquals(setOf(EqSlots.HEADPHONES), d.eq.keys)
    }

    @Test
    fun aSecondRunReplacesTheGraphicNames() {
        val p = pages("eq_graphic")
        val d = p.fold(p.fold(DeviceState()) { s, e -> s.withEq(e) }) { s, e -> s.withEq(e) }
        assertEquals(4, d.eq.getValue(EqSlots.HEADPHONES).graphicNames.size)
    }

    @Test
    fun aParametricPageKeepsTheGraphicNamesUntilTheGraphicPageReplacesThem() {
        val p = pages("eq_graphic")
        val rock = "/data/funeos/eq/graphic/Rock.ini"
        assertEquals("Rock", EqSlots.presetText(DeviceState().withEq(p[0].copy(graphicKey = rock)).eq.getValue(EqSlots.HEADPHONES)))
        val d = listOf(p[0], p[1], p[0].copy(graphicKey = rock)).fold(DeviceState()) { s, e -> s.withEq(e) }
        val m = d.eq.getValue(EqSlots.HEADPHONES)
        assertEquals(listOf("off", "flat", rock, "custom"), m.graphicNames.map { it.key })
        assertEquals(4L, m.graphicTotal)
        assertEquals("Rock", EqSlots.presetText(m))
        assertEquals("graphic Rock", EqSlots.value(m))
    }

    @Test
    fun aGraphicRunStartsOverAtItsFirstOffset() {
        val page = Eq(Enums.EqOutput.wired, "off", emptyList(), null, PageInfo(0, 0, false), mode = Enums.EqMode.graphic, graphicKey = "flat", graphicNames = listOf(EqName("off", "OFF"), EqName("flat", "FLAT")), graphicOffset = 0, graphicTotal = 2)
        val d = DeviceState().withEq(page).withEq(page)
        assertEquals(listOf("off", "flat"), d.eq.getValue(EqSlots.HEADPHONES).graphicNames.map { it.key })
    }

    @Test
    fun aSlotPageKeysByItsSlot() {
        val d = DeviceState().withEq(pages("eq_slot").single())
        assertEquals(setOf("line"), d.eq.keys)
        assertEquals("custom", d.eq.getValue("line").key)
    }

    @Test
    fun outputPagesLandOnTheUserSlots() {
        val wired = Eq(Enums.EqOutput.wired, "off", emptyList(), null, PageInfo(0, 0, false))
        val d = DeviceState().withEq(wired).withEq(wired.copy(output = Enums.EqOutput.bt, key = "custom"))
        assertEquals("off", d.eq.getValue(EqSlots.HEADPHONES).key)
        assertEquals("custom", d.eq.getValue(EqSlots.BLUETOOTH).key)
    }

    @Test
    fun aStagePageKeepsItsKeyWithTheStageOffAndANewTargetReplacesTheOld() {
        val page = Eq(null, "/data/funeos/eq/HD 650.txt", emptyList(), null, PageInfo(0, 0, false), slot = EqSlots.EAR_BLUETOOTH, on = false, target = 3, headset = "WH-1000XM4")
        val d = DeviceState().withEq(page)
        val m = d.eq.getValue(EqSlots.EAR_BLUETOOTH)
        assertEquals(false, m.on)
        assertEquals("/data/funeos/eq/HD 650.txt", m.key)
        assertEquals("WH-1000XM4", m.headset)
        assertEquals("off", EqSlots.value(m))
        assertEquals(4L, d.withEq(page.copy(target = 4)).eq.getValue(EqSlots.EAR_BLUETOOTH).target)
    }
}
