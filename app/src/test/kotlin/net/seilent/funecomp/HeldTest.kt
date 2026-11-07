package net.seilent.funecomp

import net.seilent.funecomp.ui.Held
import org.junit.Assert.assertEquals
import org.junit.Test

class HeldTest {
    private fun rows(e: List<Held.Entry>) = e.map { listOf(it.name, it.token, it.value, it.row, it.branch) }

    @Test
    fun theHeldValuesKeepTheirOldPin() {
        assertEquals("6e3302724", Held.PIN)
        assertEquals(
            listOf(
                listOf("tileCellH", "tileCellH", 77f, "H3, H6a", "zhub"),
                listOf("tileRowPitch", "tileRowPitch", 89f, "H3, H6a", "zhub"),
                listOf("shortArtTop", "npArtTop", 124f, "H7", "zhub"),
            ),
            rows(Held.Hub.entries),
        )
        assertEquals(
            listOf(
                listOf("homeTextX", "homeTextX", 70f, "M1, M4", "zhome"),
                listOf("homeFirstBaseline", "homeFirstBaseline", 48f, "M1, M4", "zhome"),
                listOf("homeSubtitleX", "homeSubtitleX", 10f, "M1, M4", "zhome"),
                listOf("homeSubtitleDrop", "homeSubtitleDrop", 8f, "M1, M4", "zhome"),
                listOf("type_homeItem_size", "type_homeItem_size", 34f, "M1, M4", "zhome"),
                listOf("type_homeSubtitle_size", "type_homeSubtitle_size", 8f, "M1, M4", "zhome"),
                listOf("type_homeItem_style", "type_homeItem_style", "Light", "M1, M4", "zhome"),
                listOf("homeRingSize", "homeRingSize", 28.6f, "M2", "zhome"),
                listOf("homeRingX", "homeRingX", 50f, "M2", "zhome"),
                listOf("homeRingY", "homeRingY", 42.9f, "M2", "zhome"),
                listOf("homeRingHitBottom", "homeRingHitBottom", 64f, "M2", "zhome"),
                listOf("homeBarX", "homeBarX", 62f, "M3", "zhome"),
                listOf("homeBarTop", "homeBarTop", 23.8f, "M3", "zhome"),
                listOf("homeBarH", "homeBarH", 37.4f, "M3", "zhome"),
            ),
            rows(Held.Home.entries),
        )
        assertEquals(
            listOf(
                listOf("npArtTop", "npArtTop", 124f, "N4", "znp"),
                listOf("npRing", "npRing", 32f, "N5", "znp"),
                listOf("npSeekBandX0", "npSeekBandX0", 8f, "N7", "znp"),
                listOf("npProgressY", "npProgressY", 200f, "N7", "znp"),
                listOf("npSeekBandBottom", "npSeekBandBottom", 219.8f, "N7", "znp"),
                listOf("npFromBaseline", "npFromBaseline", 66f, "N8", "znp"),
                listOf("type_npCount_size", "type_npCount_size", 13f, "N8", "znp"),
                listOf("npNextIndent", "npNextIndent", 40f, "N8", "znp"),
            ),
            rows(Held.Np.entries),
        )
        assertEquals(25, Held.entries.size)
        assertEquals(Held.entries.size, Held.entries.map { "${it.branch} ${it.name}" }.toSet().size)
    }
}
