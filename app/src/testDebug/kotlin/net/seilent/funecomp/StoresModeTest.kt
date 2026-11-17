package net.seilent.funecomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StoresModeTest {
    @Test
    fun liveReadsAnUpgradedInstall() {
        assertEquals(StoresMode.Mode.LIVE, StoresMode.of("live", mapOf("defaults" to 2, "deezer" to false)))
    }

    @Test
    fun liveRefusesAStoreBelowTheDefaultsRevision() {
        assertThrows(IllegalArgumentException::class.java) { StoresMode.of("live", mapOf("defaults" to 1)) }
        assertThrows(IllegalArgumentException::class.java) { StoresMode.of("live", emptyMap<String, Any>()) }
    }

    @Test
    fun goldenRefusesANonEmptyScratchMap() {
        assertEquals(StoresMode.Mode.GOLDEN, StoresMode.of("golden", emptyMap<String, Any>()))
        assertThrows(IllegalArgumentException::class.java) { StoresMode.of("golden", mapOf("defaults" to 2)) }
    }

    @Test
    fun anyOtherArgumentFails() {
        for (arg in listOf(null, "", "LIVE", "gold")) assertThrows(arg.toString(), IllegalArgumentException::class.java) { StoresMode.of(arg, emptyMap<String, Any>()) }
    }
}
