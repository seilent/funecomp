package net.seilent.funecomp.proto

import net.seilent.funecomp.proto.gen.TokBoard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.lang.reflect.Modifier

class TokBoardTest {
    @Test
    fun boardTokensComeFromTheirFile() {
        assertEquals(1.5f, TokBoard.ap80.u)
        assertEquals(1.0f, TokBoard.v1.u)
        assertEquals("5,10,15,20,25,30,35,40,45,50,55,60,65,70,75,80,85,90,95,100", TokBoard.v1.settings_maxVolumeSteps)
        assertEquals(55f, TokBoard.ap80.settings_brightnessDefault)
        assertEquals(35f, TokBoard.v1.settings_brightnessDefault)
        assertSame(TokBoard.v1, TokBoard.of("v1"))
        assertSame(TokBoard.ap80, TokBoard.of("ap80"))
    }

    @Test
    fun anR1OnlyTokenIsABoardToken() {
        assertEquals(2.0f, TokBoard.r1.u)
        assertEquals(400f, TokBoard.r1.canvasH)
        assertEquals(320f, TokBoard.v1.canvasH)
        assertEquals(320f, TokBoard.ap80.canvasH)
        assertEquals(TokBoard.ap80.queueRowsTop, TokBoard.v1.queueRowsTop)
        assertEquals(120f, TokBoard.r1.queueRowsTop)
        assertEquals("0,1,5,10,15,20,25,30,35,40,45,50,55,60,65,70,75,80,85,90,95,100", TokBoard.r1.settings_powerOnSteps)
        assertEquals(5f, TokBoard.r1.settings_filterCount)
        assertSame(TokBoard.r1, TokBoard.of("r1"))
    }

    @Test
    fun theBoardHoldsEveryBoardTokenAndEachBoardLoads() {
        assertEquals(391, TokBoard.Board::class.java.declaredMethods.count { Modifier.isAbstract(it.modifiers) })
        assertEquals(0f, TokBoard.ap80.npNextNavCountLarge)
        assertEquals(0f, TokBoard.v1.npNextNavCountLarge)
        assertEquals(1f, TokBoard.r1.npNextNavCountLarge)
    }

    @Test
    fun theSeekBandSplitsBetweenTheAp80AndTheV1() {
        assertEquals(219.8f, TokBoard.ap80.npSeekBandBottom)
        assertEquals(235.49f, TokBoard.v1.npSeekBandBottom)
    }
}
