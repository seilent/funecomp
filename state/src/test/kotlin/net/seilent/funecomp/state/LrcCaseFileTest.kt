package net.seilent.funecomp.state

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class LrcCaseFileTest {
    private val file = File("../app/src/androidTest/resources/lrc-cases.tsv")

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun u(s: String) = s.toByteArray(Charsets.UTF_8)

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private val inputs: List<ByteArray> = listOf(
        u("[ar:Someone]\n[ti:Song]\n[00:01.50]one\r\n[00:03.00][00:10.25]chorus  \n[00:05.123]five\n[01:02]bare\n[00:07:40]colon\nno stamp\n[00:08.00]\n"),
        b(0xEF, 0xBB, 0xBF) + u("[00:01.00]café\n"),
        "[00:01.00]café crème\n".toByteArray(Charsets.ISO_8859_1),
        u("[00:01.00]旅の歌\n[00:02.00]夜に駆ける\n[00:03.00]사랑해\n[00:04.00]Привет\n"),
        u("[00:01.00]\ud83c\udfa4\ud83c\udfb6 emoji\n[00:02.00]\ud83d\udc69\u200d\ud83d\udc69\u200d\ud83d\udc67 joined\n"),
        u("[00:01.00]e\u0301 combining\n[00:02.00]\u05e9\u05dc\u05d5\u05dd rtl\n"),
        u("[00:01.00] \u3000全角\u3000\t\n[00:02.00]\u00a0nbsp\u00a0\n"),
        u("\u3000plain\u3000\n \n歌詞\n"),
        u("[00:01.00]") + b(0xC0, 0x80) + u(" overlong\n"),
        u("[00:01.00]") + b(0xED, 0xA0, 0x80) + u(" surrogate\n"),
        u("[00:01.00]") + b(0xF4, 0x90, 0x80, 0x80) + u(" past max\n"),
        u("[00:01.00]") + b(0xEF, 0xBF, 0xBD) + u(" replacement\n"),
        u("[00:01.00]") + b(0xE3, 0x81) + u(" short\n"),
        u("[00:01.00]") + b(0xFF) + u(" ff\n"),
        "café\u0000tail\n".toByteArray(Charsets.ISO_8859_1),
        u("[offset:+500]\n[00:02.00]あ\n[00:00.20]い\n"),
        u("[ti:曲]\n[00:01.00]ひとつ\nはぐれ\n"),
    )

    private val cuts = listOf("a".repeat(Lrc.LINE_MAX - 1) + "あtail", "あ".repeat(2000), "\ud83c\udfa4".repeat(1100), "a".repeat(Lrc.LINE_MAX - 3) + "あx")

    private fun expected(): String = buildString {
        for (i in inputs) {
            val p = Lrc.parse(i)
            append("parse\t").append(hex(i)).append('\t').append(p.have.name).append('\t')
            append(p.lines.joinToString(";") { "${it.ms}:${hex(u(it.text))}" }).append('\t')
            append(p.plain.joinToString(";") { hex(u(it)) }).append('\n')
        }
        for (c in cuts) append("cut\t").append(hex(u(c))).append('\t').append(hex(u(Lrc.cut(c)))).append('\n')
    }

    @Test
    fun theDeviceCaseFileMatchesTheJvm() {
        val want = expected()
        if (System.getenv("LRC_CASES_WRITE") == "1") file.writeText(want)
        assertEquals("rerun with LRC_CASES_WRITE=1 to rewrite ${file.path}", want, file.readText())
    }
}
