package net.seilent.funecomp

import net.seilent.funecomp.ui.Shape
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.volExitY
import net.seilent.funecomp.ui.volLabelY
import net.seilent.funecomp.ui.volMinusY
import net.seilent.funecomp.ui.volNumberY
import net.seilent.funecomp.ui.volPlusY
import net.seilent.funecomp.ui.volRingY
import net.seilent.funecomp.ui.volTextY
import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeGettersTest {
    private fun Ui.ys() = listOf(volPlusY, volNumberY, volRingY, volMinusY, volLabelY, volExitY, volTextY)

    private val v1 = listOf(56.5f, 64.6f, 159.4f, 263.6f, 271.2f, 6.6f, 290.8f)

    @Test
    fun everyYIsTheV1TokenAtTheV1CanvasHeight() {
        for (ui in listOf(Ui(3f, 320f, 320f, Shape.SN), Ui(3f, 361.6f, 320f, Shape.SW))) {
            ui.ys().zip(v1).forEachIndexed { i, (got, want) -> assertEquals("${ui.shape} $i", want, got, 1e-3f) }
        }
    }

    @Test
    fun everyYScalesWithTheWindowHeightOnEveryShape() {
        for (ui in listOf(Ui(3f, 272f, 635f, Shape.P), Ui(3f, 320f, 480f, Shape.SN), Ui(3f, 320f, 341f, Shape.SN), Ui(3f, 635f, 272f, Shape.L))) {
            ui.ys().zip(v1).forEachIndexed { i, (got, want) -> assertEquals("${ui.shape} ${ui.h} $i", want * ui.h / 320f, got, 1e-3f) }
        }
    }

    @Test
    fun portraitMeetsShortNarrowAtH480() {
        val p = Ui.of(960f, 1440f)
        val sn = Ui.of(960f, 1439.7f)
        assertEquals(Shape.P, p.shape)
        assertEquals(Shape.SN, sn.shape)
        p.ys().zip(sn.ys()).forEachIndexed { i, (a, b) -> assertEquals("$i", a, b, 0.2f) }
    }
}
