package net.seilent.funecomp.ui

object Held {
    const val PIN = "6e3302724"

    class Entry(val name: String, val token: String, val value: Any, val row: String, val branch: String)

    object Home {
        const val homeTextX = 70f
        const val homeFirstBaseline = 48f
        const val homeSubtitleX = 10f
        const val homeSubtitleDrop = 8f
        const val type_homeItem_size = 34f
        const val type_homeSubtitle_size = 8f
        const val type_homeItem_style = "Light"
        const val homeRingSize = 28.6f
        const val homeRingX = 50f
        const val homeRingY = 42.9f
        const val homeRingHitBottom = 64f
        const val homeBarX = 62f
        const val homeBarTop = 23.8f

        val entries = listOf(
            Entry("homeTextX", "homeTextX", homeTextX, "M1, M4", "zhome"),
            Entry("homeFirstBaseline", "homeFirstBaseline", homeFirstBaseline, "M1, M4", "zhome"),
            Entry("homeSubtitleX", "homeSubtitleX", homeSubtitleX, "M1, M4", "zhome"),
            Entry("homeSubtitleDrop", "homeSubtitleDrop", homeSubtitleDrop, "M1, M4", "zhome"),
            Entry("type_homeItem_size", "type_homeItem_size", type_homeItem_size, "M1, M4", "zhome"),
            Entry("type_homeSubtitle_size", "type_homeSubtitle_size", type_homeSubtitle_size, "M1, M4", "zhome"),
            Entry("type_homeItem_style", "type_homeItem_style", type_homeItem_style, "M1, M4", "zhome"),
            Entry("homeRingSize", "homeRingSize", homeRingSize, "M2", "zhome"),
            Entry("homeRingX", "homeRingX", homeRingX, "M2", "zhome"),
            Entry("homeRingY", "homeRingY", homeRingY, "M2", "zhome"),
            Entry("homeRingHitBottom", "homeRingHitBottom", homeRingHitBottom, "M2", "zhome"),
            Entry("homeBarX", "homeBarX", homeBarX, "M3", "zhome"),
            Entry("homeBarTop", "homeBarTop", homeBarTop, "M3", "zhome"),
        )
    }

    object Np {
        const val npArtTop = 124f
        const val npRing = 32f
        const val npSeekBandX0 = 8f
        const val npProgressY = 200f
        const val npSeekBandBottom = 219.8f
        const val npFromBaseline = 66f
        const val type_npCount_size = 13f
        const val npNextIndent = 40f

        val entries = listOf(
            Entry("npArtTop", "npArtTop", npArtTop, "N4", "znp"),
            Entry("npRing", "npRing", npRing, "N5", "znp"),
            Entry("npSeekBandX0", "npSeekBandX0", npSeekBandX0, "N7", "znp"),
            Entry("npProgressY", "npProgressY", npProgressY, "N7", "znp"),
            Entry("npSeekBandBottom", "npSeekBandBottom", npSeekBandBottom, "N7", "znp"),
            Entry("npFromBaseline", "npFromBaseline", npFromBaseline, "N8", "znp"),
            Entry("type_npCount_size", "type_npCount_size", type_npCount_size, "N8", "znp"),
            Entry("npNextIndent", "npNextIndent", npNextIndent, "N8", "znp"),
        )
    }

    val entries get() = Home.entries + Np.entries
}
