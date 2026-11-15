package net.seilent.funecomp

import net.seilent.funecomp.ui.SPEED_STEPS
import net.seilent.funecomp.ui.speedWord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class SpeedWordTest {
    private val saved = Locale.getDefault()

    @Before
    fun setUp() {
        Locale.setDefault(Locale.GERMANY)
    }

    @After
    fun tearDown() {
        Locale.setDefault(saved)
    }

    @Test
    fun eachStepReadsWithAPointAndNoTrailingZero() {
        assertEquals(listOf("0.75", "1", "1.25", "1.5", "1.75", "2"), SPEED_STEPS.map { speedWord(it) })
        assertEquals("1.1", speedWord(110))
        assertEquals("1.05", speedWord(105))
    }
}
