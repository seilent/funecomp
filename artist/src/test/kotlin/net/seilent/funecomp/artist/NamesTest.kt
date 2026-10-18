package net.seilent.funecomp.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NamesTest {
    @Test
    fun aTaggedJoinGivesTheWholeNameThenEachPart() {
        assertEquals(listOf("空星きらめ/Sorahoshi Kirame", "空星きらめ", "Sorahoshi Kirame"), Names.variants("空星きらめ/Sorahoshi Kirame【にじさんじ】"))
        assertEquals(listOf("Ichiko Aoba"), Names.variants("Ichiko Aoba [Live]"))
        assertEquals(listOf("Ichiko Aoba"), Names.variants("Ichiko Aoba 【公式】 [Live]"))
    }

    @Test
    fun plainNamesAndRealBracketsStayAsTheyAre() {
        for (n in listOf("Utada Hikaru", "[Alexandros]", "【推しの子】", "AC/DC", "Sound Horizon (Revo)", "(K)NoW_NAME", "Pentatonix", "Jack x Jill", "Ximena Sariñana", "ずっと真夜中でいいのに。")) {
            assertEquals(n, emptyList<String>(), Names.variants(n))
        }
    }

    @Test
    fun oneTrailingChannelSuffixIsDroppedAfterTheTagTail() {
        assertEquals(listOf("DAZBEE"), Names.variants("DAZBEE official"))
        assertEquals(listOf("DAZBEE"), Names.variants("DAZBEE  OFFICIAL "))
        for (s in listOf("Official", "channel", "Channel", "公式", "チャンネル")) assertEquals(s, listOf("Aoi"), Names.variants("Aoi $s"))
        assertEquals(listOf("宇多田ヒカル"), Names.variants("宇多田ヒカル/公式"))
        assertEquals(listOf("ダズビー"), Names.variants("ダズビー\u3000公式"))
        assertEquals(listOf("ダズビー"), Names.variants("ダズビー\u3000\u3000Official"))
        assertEquals(listOf("DAZBEE official", "DAZBEE"), Names.variants("DAZBEE official【公式】"))
        assertEquals(listOf("YOASOBI/ヨルシカ", "YOASOBI", "ヨルシカ"), Names.variants("YOASOBI/ヨルシカ official"))
        assertEquals(listOf("DAZBEE official"), Names.variants("DAZBEE official official"))
    }

    @Test
    fun aChannelWordAsAPrefixOrInnerWordStays() {
        for (n in listOf("Official髭男dism", "Official", "official DAZBEE", "Ch. Aoi", "Aoi channel X", "Aoi ch.", "Aoi ch", "Aoi Ch.", "Aoi CH.", "Aoich.", "公式", "/公式", "DAZBEEofficial", "Aoi Chan")) {
            assertEquals(n, emptyList<String>(), Names.variants(n))
        }
    }

    @Test
    fun crossJoinsSplitOnlyBetweenNonLatinParts() {
        assertEquals(listOf("花譜", "可不"), Names.variants("花譜×可不"))
        assertEquals(listOf("花譜", "可不"), Names.variants("花譜 x 可不"))
        assertEquals(listOf("YOASOBI", "ヨルシカ"), Names.variants("YOASOBI/ヨルシカ"))
        assertEquals(emptyList<String>(), Names.variants("Ado × Eve"))
    }

    @Test
    fun kanaFoldsHiraganaToKatakanaOnlyInKana() {
        val equal = listOf(
            listOf("ひかる", "ヒカル", "ﾋｶﾙ"),
            listOf("ｶﾞ", "が", "ガ"),
            listOf("ゝ", "ヽ"),
            listOf("ゞ", "ヾ"),
            listOf("\u308F\u3099", "ヷ"),
        )
        for (g in equal) {
            for (n in g) assertEquals(n, Names.kana(g[0]), Names.kana(n))
        }
        assertEquals("ラーメン", Names.kana("らーめん"))
        assertEquals("radiohead 宇多田", Names.kana("Radiohead 宇多田"))
        assertNotEquals(Names.fold("ひかる"), Names.fold("ヒカル"))
        assertNotEquals(Names.fold("ゝ"), Names.fold("ヽ"))
        assertNotEquals(Names.fold("\u308F\u3099"), Names.fold("ヷ"))
        assertEquals("ヒカル", Names.fold("ﾋｶﾙ"))
        assertEquals("radiohead 宇多田", Names.fold("Radiohead 宇多田"))
    }
}
