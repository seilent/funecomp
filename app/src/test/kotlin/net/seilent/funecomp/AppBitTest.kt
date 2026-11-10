package net.seilent.funecomp

import net.seilent.funecomp.state.DeviceState
import org.junit.Assert.assertEquals
import org.junit.Test

class AppBitTest {
    @Test
    fun theStateWordIsTheAppBit() {
        assertEquals(Apps.APP_BIT, DeviceState.APP)
    }
}
