package net.seilent.funecomp.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionTest {
    private fun letter(title: String, the: Boolean = false) = Section.letter(Section.key(title, the).letter)

    private fun sort(title: String) = Section.key(title).sort

    @Test
    fun funeosLetters() {
        val cases = listOf(
            Triple("abba", false, "a"), Triple("The Beatles", true, "b"), Triple("The Beatles", false, "t"),
            Triple("99 Luftballons", false, "#"), Triple("Ｚｅｒｏ", false, "z"), Triple("ヨアソビ", false, "や"),
            Triple("がっこう", false, "か"), Triple("ｶﾀｶﾅ", false, "か"), Triple("夜に駆ける", false, "漢"),
            Triple("안녕", false, "#"), Triple("Élan", false, "#"), Triple("", false, "#"),
        )
        for ((title, the, want) in cases) assertEquals(title, want, letter(title, the))
    }

    @Test
    fun funeosRankOrder() {
        assertTrue(Section.compare(sort("b"), sort("c")) < 0)
        assertTrue(Section.compare(sort("zz"), sort("あ")) < 0)
        assertTrue(Section.compare(sort("わ"), sort("漢")) < 0)
        assertTrue(Section.compare(sort("#1"), sort("a")) < 0)
    }

    @Test
    fun autoEqNames() {
        assertEquals("#", letter("1Custom SA02"))
        assertEquals("#", letter("64 Audio N8"))
        assertEquals("#", letter("écoute Audio TH1"))
        assertEquals("q", letter("qdc Anole VX"))
        assertEquals("r", letter("RØDE NTH-100"))
    }

    @Test
    fun funeosTrim() {
        assertEquals("a", letter("\tabc"))
        assertEquals("a", letter(" \u0001abc"))
        assertEquals("#", letter("　zz"))
        assertEquals(sort("abc"), sort(" abc\n"))
    }

    @Test
    fun equalKeysTieByRawNameThenIndex() {
        val names = listOf("abc", "b", "Abc", "abc", "#x")
        val order = Section.sorted(names) { it }
        assertEquals(listOf("#x", "Abc", "abc", "abc", "b"), order.map { it.first })
        assertEquals(listOf(0, 1, 1, 1, 2), order.map { it.second.letter })
        val pairs = Section.sorted(listOf("x" to 1, "x" to 2, "X" to 3)) { it.first }
        assertEquals(listOf(3, 1, 2), pairs.map { it.first.second })
    }

    @Test
    fun codePointOrder() {
        assertTrue(Section.compare("ｚ", "🎵") < 0)
        assertTrue(Section.compare("ab", "abc") < 0)
        assertEquals(0, Section.compare("ab", "ab"))
    }
}
