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
    fun everyCoverTagCleansAsThePortDid() {
        val rows = CleanRows.all()
        assertEquals(958, rows.size)
        for (r in rows) assertEquals(r.title, r.clean, LyricNames.clean(r.title, r.artist))
    }

    private fun clean(title: String, artist: String = "Someone") = LyricNames.clean(title, artist)

    private fun c(title: String, artist: String? = null, vararg tags: String) = LyricNames.Clean(title, artist, tags.toList())

    @Test
    fun coverWordsAndMarkersAreCut() {
        assertEquals(c("なんでもないや", null, "RADWIMPS", "届け"), clean("なんでもないや カバー 羊羊｜フルVer. #RADWIMPS #届け", "meimei"))
        assertEquals(c("GLAMOROUS SKY", null, "NANA", "中島美嘉"), clean("GLAMOROUS SKYカバー 羊羊｜フルVer. #NANA #中島美嘉", "meimei"))
        assertEquals(c("千本桜"), clean("千本桜を歌ってみた"))
        assertEquals(c("One Last Kiss"), clean("One Last Kiss Cover. LOLUET", "LOLUET"))
        assertEquals(c("KING"), clean("KING cover /鹿乃", "Kano"))
        assertEquals(c("RAINBOW GIRL"), clean("RAINBOW GIRL cover by 石狩あかり", "Ishikari Akari"))
        assertEquals(c("少女レイ", "みきとＰ"), clean("少女レイ / みきとＰ cover 000", "000"))
        assertEquals(c("イカサマダンス", "HACHI × aMatsuka"), clean("イカサマダンス / HACHI × aMatsuka cover"))
        assertEquals(c("春"), clean("春\u3000カバー\u3000羊羊", "meimei"))
        assertEquals(c("glow"), clean("「glow」歌ってみた"))
    }

    @Test
    fun latinWordsEndingInCoverAndKatakanaCoverStay() {
        for (t in listOf("アンダーカバー", "Discover", "Undercover", "Take Cover", "Hello Cover Story", "VTuber cover", "MTV size")) assertEquals(c(t), clean(t))
        assertEquals(c("カバー"), clean("カバー"))
    }

    @Test
    fun mathLettersAreReadAsPlainLetters() {
        assertEquals(c("Undercover", "米津玄師"), clean("Undercover / 米津玄師 (\uD835\uDDF0\uD835\uDDFC\uD835\uDE03\uD835\uDDF2\uD835\uDDFF) | yoei.", "yoei."))
    }

    @Test
    fun onlyATrailingRunOfHashtagsCounts() {
        for (t in listOf("#超絶かわいい/HoneyWorks", "花譜 #68 「メルの黄昏」")) assertEquals(c(t), clean(t))
        assertEquals(c("ヰ世界情緒 #58「ETERNAL」"), clean("ヰ世界情緒 #58「ETERNAL」", "Isekaijoucho"))
        assertEquals(c("生活に落ちる", "CIEL"), clean("生活に落ちる / CIEL #12", "CIEL"))
        assertEquals(c("Song"), clean("Song #１２"))
        assertEquals(c("花", null, "内なる花", "見つかりますように"), clean("花 カバー 羊羊｜フルVer. #内なる花 #見つかりますように", "meimei"))
        assertEquals(c("星間飛行", null, "ランカリー", "中島愛", "マクロス"), clean("星間飛行 カバー 羊羊｜フルVer. #ランカリー #中島愛 #マクロス", "meimei"))
        assertEquals(c("Song", null, "A", "B", "C"), clean("Song #A #A #B #C #D"))
    }

    @Test
    fun theBarKeepsTheFirstPartThatIsNotAMarkerOrTheArtist() {
        assertEquals(c("あの夏のいつかは"), clean("歌ってみた | あの夏のいつかは covered by 山神カルタ", "Yamagami Karuta"))
        assertEquals(c("口笛で愛は歌えない"), clean("DAZBEE (ダズビー) | ‘口笛で愛は歌えない’ M/V", "DAZBEE official"))
        assertEquals(c("Awake Now", "雄之助"), clean("〖 歌ってみた 〗 Awake Now / 雄之助 Covered by るる〖 オリジナルMV 〗", "Ruru"))
    }

    @Test
    fun aRightPartThatCleansToNothingIsNoArtist() {
        assertEquals(c("Song"), clean("Song ／\u0085"))
    }

    @Test
    fun aMarkerPartMatchesWithAnySpaceBetweenItsWords() {
        for (sp in listOf(" ", "\u0085", "\u1680", "\u2028", "\u2029", "\u3000")) assertEquals(sp, c("Song"), clean("music${sp}video | Song"))
    }

    @Test
    fun theArtistKeyFoldsWidthCaseAndMarks() {
        assertEquals(LyricNames.artistKey("tuki"), LyricNames.artistKey("tuki."))
        assertEquals(LyricNames.artistKey("みきとＰ"), LyricNames.artistKey("みきとP"))
        assertEquals("bish", LyricNames.artistKey("BiSH"))
        assertEquals("backnumber", LyricNames.artistKey("Back number"))
    }

    @Test
    fun theLrclibFoldDropsMarksCaseAndApostrophes() {
        assertEquals("im a mess", LyricNames.fold("I'm a Mess!"))
        assertEquals("推し ごと", LyricNames.fold("推し★ごと"))
        assertEquals("rolling star", LyricNames.fold("  Rolling   (star) "))
    }
}

data class Tag(val artist: String, val title: String, val durationMs: Long, val pair: Pair<String, String>?, val attempt: Int, val first: String?, val cleaned: Int)

object Tags {
    fun all(): List<Tag> = Fixtures.text("lyr-tags.tsv").lineSequence().filter { it.isNotBlank() }.map {
        val f = it.split('\t')
        Tag(f[0], f[1], f[2].toLong(), if (f[3].isEmpty()) null else f[3] to f[4], f[5].toInt(), f[6].ifEmpty { null }, f[7].toInt())
    }.toList()
}

data class CleanRow(val artist: String, val title: String, val durationMs: Long, val clean: LyricNames.Clean)

object CleanRows {
    fun all(): List<CleanRow> = Fixtures.text("lyc-tags.tsv").lineSequence().filter { it.isNotBlank() }.map {
        val f = it.split('\t')
        CleanRow(f[0], f[1], f[2].toLong(), LyricNames.Clean(f[3], f[4].ifEmpty { null }, if (f[5].isEmpty()) emptyList() else f[5].split(' ')))
    }.toList()
}
