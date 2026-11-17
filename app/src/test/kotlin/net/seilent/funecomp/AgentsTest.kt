package net.seilent.funecomp

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentsTest {
    @Test
    fun funecompsAgentKeepsTheReleaseStrings() {
        val a = Agents.of("0.3.0")
        assertEquals("funecomp/0.3.0 (https://github.com/seilent/funecomp)", a.userAgent)
        assertEquals("funecomp/0.3.0 (github@seilent.net)", a.artUserAgent)
        assertEquals("funeOS", a.player)
        assertEquals("funecomp", a.client)
        assertEquals("0.3.0", a.version)
    }
}
