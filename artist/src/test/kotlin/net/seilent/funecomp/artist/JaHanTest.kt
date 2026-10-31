package net.seilent.funecomp.artist

import net.seilent.funecomp.artist.gen.JaHan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JaHanTest {
    private fun cp(s: String) = s.codePointAt(0)

    @Test
    fun spotChecks() {
        for (s in listOf("視", "頰", "塡", "剝", "噓", "𠮟", "髙", "﨑", "你", "愛", "仝")) assertTrue(s, NeMix.japanese(cp(s)))
        for (s in listOf("视", "爱", "她", "哪", "词", "𠀀")) assertFalse(s, NeMix.japanese(cp(s)))
        assertFalse(NeMix.japanese(cp("々")))
        assertFalse(NeMix.japanese(cp("〇")))
    }

    @Test
    fun theTableHoldsEveryKanjiOfCp932AndJisX0213() {
        val n = (0..0x3FFFF).count(NeMix::japanese)
        assertEquals(10135, JaHan.SIZE)
        assertEquals(JaHan.SIZE, n)
        assertTrue((0..0x3FFFF).filter(NeMix::japanese).all(NeMix::han))
    }

    @Test
    fun theRangesRoundTrip() {
        val cps = (0..0x3FFFF).filter(NeMix::japanese)
        val out = ArrayList<String>()
        var i = 0
        while (i < cps.size) {
            var j = i
            while (j + 1 < cps.size && cps[j + 1] == cps[j] + 1) j++
            out += if (i == j) cps[i].toString(16) else "${cps[i].toString(16)}-${cps[j].toString(16)}"
            i = j + 1
        }
        assertEquals(JaHan.RANGES, out.joinToString(","))
    }
}
