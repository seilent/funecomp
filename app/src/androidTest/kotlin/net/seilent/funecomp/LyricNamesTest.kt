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
    fun theLrclibFoldRunsOnTheDevice() {
        assertEquals("im a mess", LyricNames.fold("I'm a Mess!"))
        assertEquals("推し ごと", LyricNames.fold("推し★ごと"))
        assertEquals("rolling star", LyricNames.fold("  Rolling　(star) "))
    }
}
