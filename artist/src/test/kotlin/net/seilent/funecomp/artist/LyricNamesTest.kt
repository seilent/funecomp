package net.seilent.funecomp.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricNamesTest {
    @Test
    fun everySampledTagSplitsAsTheCaptureRuleDid() {
        val tags = Tags.all()
        assertEquals(78, tags.size)
        for (t in tags) assertEquals(t.title, t.pair, LyricNames.split(t.title, t.artist))
        assertEquals(55, tags.count { it.pair != null })
    }

    @Test
    fun noPairWithoutASpacedSeparatorOrWithTheArtistFirst() {
        assertNull(LyricNames.split("Blue Journey - また傷に触れる（歌い分け）- 不知火フレア / 常闇トワ / 尾丸ポルカ", "Blue Journey"))
        assertNull(LyricNames.split("15歳/マーシャル・マキシマイザー-歌ってみた-", "Tsuzuri"))
        assertNull(LyricNames.split("ヒバナ -Reloaded-", "Leo/need"))
        assertNull(LyricNames.split("ブラック★ロックシューター / covered by カグラナナ", "Kagura Nana"))
        assertNull(LyricNames.split("1/6", "someone"))
    }

    @Test
    fun theRightPartIsTheSecondSegmentOnly() {
        assertEquals("ラプンツェル" to "n-buna", LyricNames.split("ラプンツェル - n-buna / 町田ちま(Cover)", "Machita Chima"))
        assertEquals("紗痲" to "煮ル果実", LyricNames.split("紗痲 / 煮ル果実 (cover) - Nqsi", "Nqsi"))
        assertEquals("渉り星" to "Islet", LyricNames.split("渉り星 / Islet feat.Sando Aoi×Yoru", "Islet"))
        assertEquals("ドライフラワー" to "優里", LyricNames.split("ドライフラワー／優里 covered by カグラナナ", "Kagura Nana"))
    }

    @Test
    fun aBareCoverWordOnTheRightGivesNoPair() {
        assertNull(LyricNames.split("Song / Cover", "Someone"))
        assertNull(LyricNames.split("Song / cover 000", "Someone"))
        assertNull(LyricNames.split("Song / カバー", "Someone"))
        assertNull(LyricNames.split("Song / 歌ってみた", "Someone"))
        assertEquals("Song" to "Coverlands", LyricNames.split("Song / Coverlands", "Someone"))
    }

    @Test
    fun aTailThatLeavesOnlyASeparatorIsUndone() {
        assertEquals("(cover)" to "(cover)", LyricNames.split("(cover) - (cover)", "Someone"))
        assertEquals("(Cover)" to "(Cover)", LyricNames.split("(Cover) / (Cover)", "Someone"))
    }

    @Test
    fun theLrclibFoldDropsMarksCaseAndApostrophes() {
        assertEquals("im a mess", LyricNames.fold("I'm a Mess!"))
        assertEquals("推し ごと", LyricNames.fold("推し★ごと"))
        assertEquals("rolling star", LyricNames.fold("  Rolling   (star) "))
    }
}

data class Tag(val artist: String, val title: String, val durationMs: Long, val pair: Pair<String, String>?, val attempt: Int, val first: String?)

object Tags {
    fun all(): List<Tag> = Fixtures.text("lyr-tags.tsv").lineSequence().filter { it.isNotBlank() }.map {
        val f = it.split('\t')
        Tag(f[0], f[1], f[2].toLong(), if (f[3].isEmpty()) null else f[3] to f[4], f[5].toInt(), f[6].ifEmpty { null })
    }.toList()
}
