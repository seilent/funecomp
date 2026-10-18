package net.seilent.funecomp.artist

import net.seilent.funecomp.state.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoEqListTest {
    private val text = AutoEqListTest::class.java.getResourceAsStream("/autoeq/index.md")!!.use { String(it.readBytes(), Charsets.UTF_8) }
    private val entries = AutoEqIndex.parse(text)
    private val list = AutoEqList.build(entries)

    private fun names(q: String) = list.filter(q).map { it.entry.name }

    private fun synthetic(vararg lines: String) = AutoEqList.build(AutoEqIndex.parse(lines.joinToString("\n")))

    @Test
    fun fullIndex() {
        assertEquals(6033, list.rows.size)
        assertEquals(23, entries.map { it.source }.toSet().size)
        assertEquals(733, entries.count { it.source == "oratory1990" })
        assertEquals(6033, list.filter("").size)
        assertEquals(6033, list.filter("   ").size)
    }

    @Test
    fun pathsDecode() {
        val ears = entries.first { it.path.startsWith("crinacle/EARS%20+%20711") }
        assertEquals("crinacle", ears.source)
        assertEquals("EARS + 711 over-ear", ears.rig)
        val ecoute = entries.first { it.path.contains("%C3%A9coute") }
        assertEquals("écoute Audio TH1 (ANC off, Harman curve)", ecoute.name)
        assertEquals("Kuulokenurkka", ecoute.source)
        assertEquals("over-ear", ecoute.rig)
        assertEquals("${AutoEqIndex.BASE}/${ecoute.path}/%C3%A9coute%20Audio%20TH1%20(ANC%20off,%20Harman%20curve)%20ParametricEQ.txt", ecoute.url)
        assertEquals("Hi End Portable", entries.first { it.path.startsWith("Hi%20End%20Portable/") }.source)
        val moller = AutoEqIndex.parse("- [Möller One](./M%C3%B6ller/over-ear/M%C3%B6ller%20One)").single()
        assertEquals("Möller", moller.source)
        assertEquals("Ted's Squig Hoard", AutoEqIndex.decode("Ted's%20Squig%20Hoard"))
        assertEquals("100%", AutoEqIndex.decode("100%"))
        assertEquals("%zz", AutoEqIndex.decode("%zz"))
    }

    @Test
    fun accentsFold() {
        assertEquals(listOf("Vision Ears PHöNIX"), names("phonix"))
        assertEquals(5, names("erlkonig").size)
        assertTrue(names("erlkonig").all { "Erlkönig" in it })
        assertEquals(listOf("RØDE NTH-100", "RØDE NTH-100 (front dampening removed)", "RØDE NTH-100M"), names("rode"))
        assertEquals(3, names("ÉCOUTE").size)
        assertEquals(3, names("ecoute").size)
        assertEquals(11, names("headset").size)
        assertTrue(names("headset").any { it.endsWith("Gaming Headset\u200B") })
        assertEquals(listOf("Zero Head\u200Bset"), synthetic("- [Zero Head\u200Bset](./Filk/over-ear/Zero%20Head%E2%80%8Bset)").filter("headset").map { it.entry.name })
    }

    @Test
    fun sourceWordsMatchRigWordsDoNot() {
        assertEquals(listOf("Sennheiser HD 650"), names("oratory1990 hd 650"))
        assertEquals(6, names("HD 650").size)
        assertEquals(names("hd 650"), names("HD 650"))
        assertEquals(733, list.filter("oratory1990").size)
        assertEquals(0, list.filter("gras").count { "gras" !in AutoEqList.fold(it.entry.name) })
        assertEquals(emptyList<String>(), names("zzqx"))
        assertEquals(emptyList<String>(), names("oratory1990 zzqx"))
    }

    @Test
    fun noCap() {
        assertTrue(list.filter("sennheiser").size > 50)
        assertEquals(248, list.filter("sennheiser").size)
    }

    @Test
    fun funeosSectionOrder() {
        list.rows.forEach { assertEquals(it.entry.name, Section.key(it.entry.name).letter, it.letter) }
        list.rows.zipWithNext().forEach { (a, b) ->
            val c = Section.compare(Section.key(a.entry.name).sort, Section.key(b.entry.name).sort)
            assertTrue("${a.entry.name} before ${b.entry.name}", c < 0 || c == 0 && Section.compare(a.entry.name, b.entry.name) <= 0)
        }
        assertEquals("#", Section.letter(list.rows.first().letter))
        assertEquals("1Custom SA02", list.rows.first { it.entry.name.startsWith("1") }.entry.name)
        assertTrue(list.rows.indexOfFirst { it.entry.name.startsWith("1Custom") } < list.rows.indexOfFirst { it.entry.name.startsWith("écoute") })
        assertEquals("z", Section.letter(list.rows.last().letter))
    }

    @Test
    fun itemsHaveDividersForLettersWithRows() {
        val items = list.items("rode")
        assertEquals(listOf("div:r", "RØDE NTH-100", "RØDE NTH-100 (front dampening removed)", "RØDE NTH-100M"), items.map { (it as? AutoEqList.Hit)?.row?.entry?.name ?: it.key })
        val all = list.items("")
        val dividers = all.filterIsInstance<AutoEqList.Divider>().map { Section.letter(it.letter) }
        assertEquals(dividers.distinct(), dividers)
        assertEquals(list.rows.map { it.letter }.distinct().map { Section.letter(it) }, dividers)
        assertEquals(6033 + dividers.size, all.size)
        assertEquals(all.size, all.map { it.key }.toSet().size)
        val hd = list.items("oratory1990 hd 6").filterIsInstance<AutoEqList.Divider>().map { Section.letter(it.letter) }
        assertTrue(hd.isNotEmpty() && hd.size < dividers.size)
    }
}
