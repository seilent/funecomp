package net.seilent.funecomp.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NeMixTest {
    private val file = File("../app/src/androidTest/resources/nemix-cases.tsv")

    private fun unesc(s: String) = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                append(
                    when (s[i + 1]) {
                        't' -> '\t'
                        'n' -> '\n'
                        'r' -> '\r'
                        else -> s[i + 1]
                    },
                )
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }

    private fun reason(v: NeMix.Verdict) = when (v) {
        NeMix.Verdict.Ok -> "ok"
        is NeMix.Verdict.Reject -> v.reason
    }

    @Test
    fun everyCaseOfTheSharedTableHoldsOnTheJvm() {
        val rows = file.readLines().filter { it.isNotBlank() }.map { it.split('\t') }
        assertTrue(rows.size >= 50)
        for (r in rows) assertEquals(r[0], r[2], reason(NeMix.check(unesc(r[1]))))
    }

    @Test
    fun aRejectCarriesItsCounts() {
        val v = NeMix.check(listOf("あめが ふる", "そらを みる", "ほしが ひかる", "花が 视る").joinToString("\n")) as NeMix.Verdict.Reject
        assertEquals(NeMix.HAN, v.reason)
        assertEquals(NeMix.Counts(cjk = 4, kana = 4, chinese = 0, han = 1, groups = 0, groupLines = 0, katakana = 0, outside = 1), v.counts)
    }

    @Test
    fun theFirstMatchingRuleIsTheReason() {
        val lines = listOf("あめが ふる") + List(6) { "雨(あめ)が 视る" } + List(4) { "视线时间" }
        assertEquals(NeMix.TRANSLATION, reason(NeMix.check(lines.joinToString("\n"))))
        assertEquals(NeMix.FURIGANA, reason(NeMix.check(lines.dropLast(4).joinToString("\n"))))
    }

    @Test
    fun katakanaGroupsAreCountedButNeverReject() {
        val c = NeMix.counts(List(8) { "孤独(コドク)の" }.joinToString("\n"))
        assertEquals(8, c.katakana)
        assertEquals(0, c.groups)
    }

    @Test
    fun theFixtureBodiesAreAllOk() {
        val bodies = Fixtures.text("urls.tsv").lineSequence().filter { it.startsWith("POST ${NetEase.LYRIC} ") }.map { it.substringAfter('\t') }
            .mapNotNull { f -> J.parse(Fixtures.text(f))["lrc"]["lyric"].str?.let { f to it } }.toList()
        assertTrue(bodies.size > 100)
        for ((f, raw) in bodies) {
            val got = NetEase.text(raw) ?: continue
            assertEquals(f, NeMix.Verdict.Ok, NeMix.check(got.text))
        }
    }

    @Test
    fun theJapaneseCheckTurnsAwayEveryOtherLanguage() {
        val japanese = listOf("あめが ふる", "そらを みる", "ほしが ひかる").joinToString("\n")
        assertEquals("ok", reason(NeMix.check(japanese, japanese = true)))
        for (other in listOf("视线时间\n爱这们给\n经过时间", "love you\nunder the sky\nsee the stars", "あめ")) {
            assertEquals(other, "ok", reason(NeMix.check(other)))
            assertEquals(other, NeMix.LANG, reason(NeMix.check(other, japanese = true)))
        }
    }
}
