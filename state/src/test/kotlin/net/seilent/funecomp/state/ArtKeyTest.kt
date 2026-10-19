package net.seilent.funecomp.state

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtKeyTest {
    private fun cp(vararg c: Int) = StringBuilder().apply { c.forEach { appendCodePoint(it) } }.toString()

    private fun utf8(vararg b: Int) = String(ByteArray(b.size) { b[it].toByte() }, Charsets.UTF_8)

    @Test
    fun theDeviceFoldTable() {
        val cases = listOf(
            "\u0000\u0009\u001f Gemma \n " to "gemma",
            "\u00a0Gemma\u00a0" to "\u00a0gemma\u00a0",
            "\u3000Gemma\u3000" to "\u3000gemma\u3000",
            "a b" to "a b",
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ" to "abcdefghijklmnopqrstuvwxyz",
            "@[`{" to "@[`{",
            cp(0xBF, 0xC0, 0xD6, 0xD7, 0xD8, 0xDE, 0xDF) to cp(0xBF, 0xE0, 0xF6, 0xD7, 0xF8, 0xFE, 0xDF),
            cp(0x390, 0x391, 0x3A1, 0x3A2, 0x3A3, 0x3A9, 0x3AA) to cp(0x390, 0x3B1, 0x3C1, 0x3A2, 0x3C3, 0x3C9, 0x3AA),
            cp(0x3FF, 0x400, 0x40F) to cp(0x3FF, 0x450, 0x45F),
            cp(0x410, 0x42F, 0x430) to cp(0x430, 0x44F, 0x430),
            "ß Ÿ Ā" to "ß Ÿ Ā",
            "ＢｏＡ" to "ＢｏＡ",
            "ﾄ ｱ むト" to "ﾄ ｱ むト",
            "Sigur Rós" to "sigur rós",
            cp(0x1F3B5, 'A'.code) to cp(0x1F3B5, 'a'.code),
            utf8(0x41, 0x80, 0x42) to "a\uFFFDb",
            utf8(0xE3, 0x81, 0x41) to "\uFFFDa",
            utf8(0xF8, 0x41) to "\uFFFDa",
            "\uFFFD" to "\uFFFD",
        )
        for ((input, want) in cases) assertEquals(input, want, ArtKey.fold(input))
    }
}
