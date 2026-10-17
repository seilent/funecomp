package net.seilent.funecomp.artist

import org.junit.Assert.assertEquals
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
    fun crossJoinsSplitOnlyBetweenNonLatinParts() {
        assertEquals(listOf("花譜", "可不"), Names.variants("花譜×可不"))
        assertEquals(listOf("花譜", "可不"), Names.variants("花譜 x 可不"))
        assertEquals(listOf("YOASOBI", "ヨルシカ"), Names.variants("YOASOBI/ヨルシカ"))
        assertEquals(emptyList<String>(), Names.variants("Ado × Eve"))
    }
}
