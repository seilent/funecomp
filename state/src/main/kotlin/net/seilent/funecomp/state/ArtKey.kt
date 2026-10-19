package net.seilent.funecomp.state

object ArtKey {
    internal fun lower(c: Int): Int = when {
        c in 'A'.code..'Z'.code -> c + 32
        c in 0xC0..0xDE && c != 0xD7 -> c + 32
        c in 0x391..0x3A9 && c != 0x3A2 -> c + 32
        c in 0x410..0x42F -> c + 32
        c in 0x400..0x40F -> c + 80
        else -> c
    }

    internal fun trim(s: String): String {
        var a = 0
        var b = s.length
        while (a < b && s[a] <= ' ') a++
        while (b > a && s[b - 1] <= ' ') b--
        return s.substring(a, b)
    }

    internal fun foldLower(s: String): String = StringBuilder(s.length).apply { s.codePoints().forEach { appendCodePoint(lower(it)) } }.toString()

    fun fold(name: String): String = foldLower(trim(name))
}
