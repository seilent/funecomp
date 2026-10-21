package net.seilent.funecomp

import net.seilent.funecomp.artist.LyricNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricNamesTest {
    @Test
    fun sampledTagsSplitOnTheDevice() {
        assertEquals("ドライフラワー" to "優里", LyricNames.split("ドライフラワー／優里 covered by カグラナナ", "Kagura Nana"))
        assertEquals("春の歌" to "スピッツ", LyricNames.split("春の歌 - スピッツ Covered by 理芽 / RIM", "RIM"))
        assertEquals("名前のない怪物" to "EGOIST", LyricNames.split("名前のない怪物 / EGOIST ﴾ Covered by つぐ ﴿", "Tsugu"))
        assertEquals("ラプンツェル" to "n-buna", LyricNames.split("ラプンツェル - n-buna / 町田ちま(Cover)", "Machita Chima"))
        assertEquals("推し★ごと" to "HoneyWorks", LyricNames.split("推し★ごと／HoneyWorks feat.かぴ", "Kapi"))
        assertEquals("ロミオとシンデレラ" to "doriko", LyricNames.split("ロミオとシンデレラ / doriko(covered by 犬山たまき)", "Inuyama Tamaki"))
        assertNull(LyricNames.split("ブラック★ロックシューター / covered by カグラナナ", "Kagura Nana"))
        assertNull(LyricNames.split("Nighthawk feat. たなか", "DUSTCELL"))
        assertNull(LyricNames.split("Song / 歌ってみた", "Someone"))
    }

    @Test
    fun coverTagsCleanOnTheDeviceAsOnTheJvm() {
        val rows = javaClass.getResourceAsStream("/lyc-clean.tsv")!!.use { String(it.readBytes(), Charsets.UTF_8) }
            .lineSequence().filter { it.isNotBlank() }.map { it.split('\t') }.toList()
        assertEquals(958, rows.size)
        for (r in rows) {
            val c = LyricNames.clean(r[1], r[0])
            assertEquals(r[1], listOf(r[2], r[3], r[4]), listOf(c.title, c.artist ?: "", c.tags.joinToString(" ")))
        }
    }

    @Test
    fun cleaningRulesRunOnTheDevice() {
        assertEquals(LyricNames.Clean("Undercover", "米津玄師", emptyList()), LyricNames.clean("Undercover / 米津玄師 (\uD835\uDDF0\uD835\uDDFC\uD835\uDE03\uD835\uDDF2\uD835\uDDFF) | yoei.", "yoei."))
        assertEquals(LyricNames.Clean("なんでもないや", null, listOf("RADWIMPS", "届け")), LyricNames.clean("なんでもないや カバー 羊羊｜フルVer. #RADWIMPS #届け", "meimei"))
        assertEquals(LyricNames.Clean("アンダーカバー", null, emptyList()), LyricNames.clean("アンダーカバー", "Someone"))
        assertEquals(LyricNames.Clean("Discover", null, emptyList()), LyricNames.clean("Discover", "Someone"))
        assertEquals(LyricNames.Clean("One Last Kiss", null, emptyList()), LyricNames.clean("One Last Kiss Cover. LOLUET", "LOLUET"))
        assertEquals(LyricNames.Clean("春", null, emptyList()), LyricNames.clean("春\u3000カバー\u3000羊羊", "meimei"))
        assertEquals(LyricNames.Clean("Song", null, emptyList()), LyricNames.clean("Song ／\u0085", "Someone"))
        for (sp in listOf(" ", "\u0085", "\u1680", "\u2028", "\u2029", "\u3000")) assertEquals(sp, LyricNames.Clean("Song", null, emptyList()), LyricNames.clean("music${sp}video | Song", "Someone"))
        assertEquals(LyricNames.artistKey("みきとP"), LyricNames.artistKey("みきとＰ"))
        assertEquals(LyricNames.artistKey("tuki"), LyricNames.artistKey("tuki."))
    }

    @Test
    fun theLrclibFoldRunsOnTheDevice() {
        assertEquals("im a mess", LyricNames.fold("I'm a Mess!"))
        assertEquals("推し ごと", LyricNames.fold("推し★ごと"))
        assertEquals("rolling star", LyricNames.fold("  Rolling　(star) "))
    }
}
