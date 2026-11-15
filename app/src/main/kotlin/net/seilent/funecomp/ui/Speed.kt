package net.seilent.funecomp.ui

val SPEED_STEPS = listOf(75, 100, 125, 150, 175, 200)

fun speedWord(pct: Int): String {
    val part = pct % 100
    val digits = if (part < 10) "0$part" else part.toString()
    val fraction = if (part == 0) "" else "." + digits.trimEnd('0')
    return (pct / 100).toString() + fraction
}
