package net.seilent.funecomp.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetEaseTextTest {
    private val stamp = Regex("\\[([0-9]+):([0-9]+)(?:([.:])([0-9]+))?\\]")
    private val device = Regex("\\[([0-9]{1,4}):([0-5][0-9])\\.([0-9]{1,3})\\]")

    private fun bodies() = Fixtures.text("urls.tsv").lineSequence().filter { it.startsWith("POST ${NetEase.LYRIC} ") }
        .map { it.substringAfter('\t') }.map { it to J.parse(Fixtures.text(it))["lrc"]["lyric"].str }.filter { it.second != null }.toList()

    private fun lines(vararg l: String) = l.joinToString("\n")

    @Test
    fun colonStampsComeOutWithDotFractionsTheDeviceParses() {
        val colon = bodies().filter { (_, raw) -> stamp.findAll(raw!!).any { it.groupValues[3] == ":" } }
        assertEquals(7, colon.size)
        for ((name, raw) in colon) {
            val got = NetEase.text(raw!!) ?: continue
            assertTrue(name, got.synced)
            for (l in got.text.split("\n")) {
                for (m in stamp.findAll(l)) {
                    assertEquals(name, ".", m.groupValues[3])
                    assertTrue("$name $l", device.matches(m.value))
                }
            }
        }
    }

    @Test
    fun everyStampOnALineIsRewritten() {
        val got = NetEase.text(lines("[00:01:00][01:30:5]a", "[00:02:123]b", "[00:03.00]c", "[00:04.00]d", "[00:05.00]e [x:y]"))!!
        assertEquals(lines("[00:01.00][01:30.5]a", "[00:02.123]b", "[00:03.00]c", "[00:04.00]d", "[00:05.00]e [x:y]"), got.text)
    }

    @Test
    fun theLeadingCreditBlockIsDropped() {
        val got = NetEase.text(
            lines("[00:00.00] 作詞/作曲/編曲 : n1", "[00:00.10]", "[00:00.20] 原唱：n2", "[00:00.30]Composer:n3", "[00:01.00]a", "[00:02.00]编曲 : kept", "[00:03.00]c", "[00:04.00]d", "[00:05.00]e"),
        )!!
        assertTrue(got.synced)
        assertEquals(lines("[00:01.00]a", "[00:02.00]编曲 : kept", "[00:03.00]c", "[00:04.00]d", "[00:05.00]e"), got.text)
    }

    @Test
    fun aLabelOutsideTheSetEndsTheBlock() {
        val got = NetEase.text(lines("[00:00.00]作词 : n1", "[00:00.50]歌词 : x", "[00:01.00]a", "[00:02.00]b", "[00:03.00]c", "[00:04.00]d"))!!
        assertEquals("[00:00.50]歌词 : x", got.text.split("\n").first())
        assertTrue(got.synced)
    }

    @Test
    fun theCapturedCreditLabelsAreDropped() {
        val (name, raw) = bodies().single { it.first == "ne-l-2106445969.json" }
        val got = NetEase.text(raw!!)!!
        assertTrue(name, raw.contains("作詞/作曲/編曲") || raw.contains("原唱"))
        val first = got.text.split("\n").first()
        assertFalse(first, first.contains("作詞") || first.contains("原唱"))
    }

    @Test
    fun syncedNeedsFiveStampedLinesOverFiveSeconds() {
        assertTrue(NetEase.text(lines("[00:01.00]a", "[00:02.00]b", "[00:03.00]c", "[00:04.00]d", "[00:05.00]e"))!!.synced)
        val sameSecond = NetEase.text(lines("[00:01.00]a", "[00:01.20]b", "[00:02.00]c", "[00:03.00]d", "[00:04.00]e", "[00:04.50]f"))!!
        assertFalse(sameSecond.synced)
        assertEquals(lines("a", "b", "c", "d", "e", "f"), sameSecond.text)
        val emptyLines = NetEase.text(lines("[00:01.00]a", "[00:02.00]", "[00:03.00]", "[00:04.00]b", "[00:05.00]c", "[00:06.00]d"))!!
        assertFalse(emptyLines.synced)
        assertEquals("netease", emptyLines.source)
    }

    @Test
    fun onlyCreditsOrBlanksIsAMiss() {
        assertNull(NetEase.text(lines("[00:00.00]作词 : n1", "", "[00:01.00]")))
        assertNull(NetEase.text(""))
        assertNull(NetEase.text(lines("[00:01.00]a", "[00:02.00]纯音乐，x", "[00:03.00]b", "[00:04.00]c", "[00:05.00]d")))
    }

    @Test
    fun carriageReturnsAreDropped() {
        assertEquals(lines("[00:01.00]a", "[00:02.00]b", "[00:03.00]c", "[00:04.00]d", "[00:05.00]e"), NetEase.text("[00:01.00]a\r\n[00:02.00]b\r\n[00:03.00]c\r\n[00:04.00]d\r\n[00:05.00]e")!!.text)
    }
}
