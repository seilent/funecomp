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
                listOf("type_homeItem_style", "type_homeItem_style", "Light", "M1, M4", "zhome"),
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
        assertEquals(9, Held.entries.size)
        assertEquals(setOf("Entry", "Home", "Np"), Held::class.java.declaredClasses.map { it.simpleName }.toSet())
        assertEquals(Held.entries.size, Held.entries.map { "${it.branch} ${it.name}" }.toSet().size)
    }
}
