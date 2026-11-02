package net.seilent.funecomp

import net.seilent.funecomp.ui.CoverBlur
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverBlurTest {
    @Test
    fun theRadiusFollowsFuneosOnEveryBoard() {
        assertEquals("tokens.txt u 1.5, s 2.25", 2, CoverBlur.radius(CoverBlur.sigma(1.5f)))
        assertEquals("V1 u 1, s 1.5", 1, CoverBlur.radius(CoverBlur.sigma(1f)))
        assertEquals("R1 u 2, s 3", 3, CoverBlur.radius(CoverBlur.sigma(2f)))
        assertEquals("A105 u 3.0, s 4.5", 4, CoverBlur.radius(CoverBlur.sigma(3f)))
        assertEquals(2.25, CoverBlur.sigma(1.5f), 1e-9)
    }

    @Test
    fun theRadiusOnTheAppWindowsSigmas() {
        val want = listOf(4.79 to 4, 5.95 to 5, 5.16 to 5, 7.41 to 7, 7.53 to 7, 5.13 to 5, 10.16 to 10, 5.06 to 5, 4.22 to 4)
        for ((s, r) in want) assertEquals("s $s", r, CoverBlur.radius(s))
    }

    @Test
    fun aWidthAtTheExactMidpointTakesTheLowerWidth() {
        assertEquals(3, CoverBlur.width(4.0))
        assertEquals(5, CoverBlur.width(4.0001))
        assertEquals(1, CoverBlur.width(1.0))
        assertEquals(1, CoverBlur.width(2.0))
        assertEquals(3, CoverBlur.width(2.0001))
    }

    @Test
    fun theCropIsCentredToTheBoxShape() {
        assertArrayEquals(intArrayOf(232, 512), CoverBlur.crop(512, 512, 1079, 2372))
        assertArrayEquals(intArrayOf(512, 303), CoverBlur.crop(512, 512, 2411, 1427))
        assertArrayEquals(intArrayOf(512, 512), CoverBlur.crop(512, 512, 600, 600))
    }

    @Test
    fun theGridIsTheBoxAtAQuarterFloored() {
        assertArrayEquals(intArrayOf(269, 593), CoverBlur.grid(1079, 2372))
        assertArrayEquals(intArrayOf(602, 356), CoverBlur.grid(2411, 1427))
        assertArrayEquals(intArrayOf(1, 1), CoverBlur.grid(7, 7))
    }

    @Test
    fun aCropAtLeastTheGridIsAveragedElseScaled() {
        assertFalse("512 square into 1079 x 2372", CoverBlur.fits(512, 512, 269, 593))
        assertFalse("256 square into 1079 x 2372", CoverBlur.fits(256, 256, 269, 593))
        assertTrue("512 square into 411 x 640", CoverBlur.fits(512, 512, 102, 160))
        assertTrue("1024 square into 1079 x 2372", CoverBlur.fits(1024, 1024, 269, 593))
    }

    @Test
    fun theAreaAverageRoundsEachBoxOfTheCrop() {
        val w = 8
        val h = 4
        val src = IntArray(w * h) { i -> if (i % w < 4) 0x000000 else 0xFFFFFF }
        val out = CoverBlur.area(src, w, h, 4, 2)
        assertArrayEquals(intArrayOf(0x000000, 0x000000, 0xFFFFFF, 0xFFFFFF, 0x000000, 0x000000, 0xFFFFFF, 0xFFFFFF), out)
        val half = intArrayOf(0x000000, 0x000001, 0x000000, 0x000000, 0x000001, 0x000000, 0x000000, 0x000000)
        assertArrayEquals(intArrayOf(0x000001, 0x000000), CoverBlur.area(half, 4, 2, 2, 1))
    }

    @Test
    fun theAreaAverageTakesTheCentredCrop() {
        val src = IntArray(8 * 8) { i -> i / 8 }
        assertArrayEquals(intArrayOf(4, 4), CoverBlur.area(src, 8, 8, 2, 1))
    }

    @Test
    fun theBilinearScaleMatchesFuneosLinAxis() {
        val src = intArrayOf(0x000000, 0xFFFFFF)
        val out = CoverBlur.bilinear(src, 2, 0, 0, 2, 1, 4, 1)
        assertArrayEquals(intArrayOf(0x000000, 0x404040, 0xBFBFBF, 0xFFFFFF), out)
    }

    @Test
    fun theBoxPassesEqualFuneosValueForValue() {
        val lines = javaClass.getResource("/coverblur/parity.tsv")!!.readText().lines().filter { it.isNotEmpty() }
        assertTrue(lines[0].startsWith("#funeos\t83e846240"))
        val inputs = HashMap<String, Triple<Int, Int, IntArray>>()
        var outs = 0
        for (l in lines.filter { !it.startsWith("#") }) {
            val p = l.split('\t')
            val px = p[4].split(' ').map { it.toInt(16) }.toIntArray()
            if (p[0] == "in") {
                inputs[p[1]] = Triple(p[2].toInt(), p[3].toInt(), px)
                continue
            }
            val (w, h, src) = inputs.getValue(p[1])
            val g = src.copyOf()
            CoverBlur.blur(g, w, h, p[2].toInt())
            assertArrayEquals("${p[1]} radius ${p[2]}", px, g)
            outs++
        }
        assertEquals(18, outs)
    }

    @Test
    fun aCoverThatFitsTheGridIsAreaAveraged() {
        val src = IntArray(12 * 12) { i -> (i % 12 * 20) shl 16 or (i / 12 * 20) }
        val g = CoverBlur.cover(src, 12, 12, 32, 32, 0.1f)!!
        assertEquals(0, CoverBlur.radius(CoverBlur.sigma(0.1f)))
        val area = CoverBlur.area(src, 12, 12, 8, 8)
        assertArrayEquals(area, g.px)
        assertFalse(CoverBlur.bilinear(src, 12, 0, 0, 12, 12, 8, 8).contentEquals(area))
    }

    @Test
    fun aCoverIntoATallBoxTakesTheBilinearBranchAndBlurs() {
        val side = 64
        val src = IntArray(side * side) { i -> if (i % side < side / 2) 0x000000 else 0xFFFFFF }
        val g = CoverBlur.cover(src, side, side, 200, 400, 2f)
        assertNotNull(g)
        assertEquals(50, g!!.w)
        assertEquals(100, g.h)
        val mid = g.px[50 * 50 + 25] and 0xFF
        assertTrue("the split is blurred: $mid", mid in 60..195)
        assertEquals(0x000000, g.px[50 * 50] and 0xFFFFFF)
        assertEquals(0xFFFFFF, g.px[50 * 50 + 49] and 0xFFFFFF)
    }
}
