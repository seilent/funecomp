package net.seilent.funecomp.ui

object Held {
    const val PIN = "6e3302724"

    class Entry(val name: String, val token: String, val value: Any, val row: String, val branch: String)

    object Home {
        const val type_homeItem_style = "Light"

        val entries = listOf(
            Entry("type_homeItem_style", "type_homeItem_style", type_homeItem_style, "M1, M4", "zhome"),
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
