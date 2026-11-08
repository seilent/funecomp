package net.seilent.funecomp.state

object LrcCases {
    const val T1 = "[00:12.00]<00:12.00>Hello <00:12.50>world<00:13.10>\n"
    const val T1_TAIL = "[00:12.00]<00:12.00>a \u0001\n"
    const val T1_END = "[00:01.00]a <00:01.50>\u0001\n"
    const val T1_CLAMP = "[00:12.00]<00:12.50>a <00:12.40>\n"
    const val T1_BARE = "[00:12.00]<00:12.00>\n"
    const val T1B = "[00:12.00] <00:12.04> wwww <00:12.20> ttt <00:12.97> rrrrr\n"
    const val T2_SQUARE = "[00:12.00]Hello [00:12.50]world\n"
    const val T2_SPACES = "[00:12.00]a  <00:12.20>  b\n"
    const val T2_CJK = "[00:12:34]あ[00:12:56]い[00:13:00]\n"
    const val T2_MIXED = "[00:12.00]a <00:12.20>b [00:12.40]c\n"
    const val T2_CONTROL = "[00:12.00]a\u0001<00:12.20>\u0001b\n"
    const val T3_SPACE = "[00:01.00] [00:02.00]x\n"
    const val T3_CONTROL = "[00:01.00]\u0001[00:02.00]x\n"
    const val T3_ANGLE = "[00:12.00]<00:12.00>[00:12.30]la\n"
    const val T3_CHORUS = "[00:01.00] [Chorus] la\n"
    const val T3_ID = "[ti:x] [00:01.00]la\n"
    const val T4 = "[00:03.00][00:10.25]<00:03.40>la <00:03.80>la\n"
    const val T4_ORDER = "[00:10.25][00:03.00]<00:03.40>la\n"
    const val T5 = "[offset:+500]\n[00:02.00]<00:02.00>a <00:02.30>b\n"
    const val T6_HEART = "[00:12.00]<3 you <00:12.50>x\n"
    const val T6_NESTED = "[00:12.00]<<00:12.50>00:13.00>x\n"
    val T6_KEPT = listOf(
        "a <00:61.00> b", "a <00:12.00] b", "x [x2] y", "<b>x", "a [Chorus] b", "a <3", "a <00:12.50",
        "a <3 b", "a <b> c", "a [x2] b", "a <00:12.50 b", "a [00:12.50 b",
    )
    const val T6_PLAIN = "a <00:12.50>b\n"
    const val T6_LOOSE = "say <00:01.00>hi\n"
    const val T8_T1 = "[00:12.00]Hello world\n"
    const val T8_T1B = "[00:12.00]wwww ttt rrrrr\n"
    const val T8_TIMED = "[00:01.00]<00:01.00>one <00:01.40>two\n[00:03.00]three [00:03.50]four<00:04.00>\n"
    const val T8_CLEAN = "[00:01.00]one two\n[00:03.00]three four\n"

    private val stamps = "[0:01]".repeat(1000)
    private val runs = (0 until 1000).joinToString("") { "<0:01>" + "a".repeat(if (it < 536) 58 else 57) } + "<0:02>"
    val B1 = stamps + "<0:01>x ".repeat(500)
    val B2 = stamps + "<0:01><0:02>x ".repeat(500)
    val B3 = "[0:01]$runs"
    val B4 = "[0:01]<0:02>\n$B3"

    fun timed(t: String) = "[00:12.00]$t\n"

    val inputs = listOf(
        T1, T1_TAIL, T1_END, T1_CLAMP, T1_BARE, T1B, T2_SQUARE, T2_SPACES, T2_CJK, T2_MIXED, T2_CONTROL, T3_SPACE, T3_CONTROL,
        T3_ANGLE, T3_CHORUS, T3_ID, T4, T4_ORDER, T5, T6_HEART, T6_NESTED, T6_PLAIN, T6_LOOSE, T8_T1, T8_T1B, T8_TIMED, T8_CLEAN,
        B1, B2, B3, B4,
    ) + T6_KEPT.map(::timed)
}
