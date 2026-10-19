package net.seilent.funecomp.proto

import net.seilent.funecomp.proto.gen.TokBoard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

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
}
