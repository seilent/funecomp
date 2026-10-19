package net.seilent.funecomp.state

import net.seilent.funecomp.proto.Band
import net.seilent.funecomp.proto.Profile
import net.seilent.funecomp.proto.gen.Enums
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoEqTest {
    private fun text(name: String) = String(AutoEqTest::class.java.getResourceAsStream("/autoeq/$name")!!.readBytes(), Charsets.UTF_8)

    private fun ok(text: String, name: String = "x") = (AutoEq.parse(text, name) as AutoEqResult.Ok).eq

    private fun refused(text: String) = (AutoEq.parse(text, "x") as AutoEqResult.Refused).reason

    private fun filters(vararg lines: String) = "Preamp: -3.0 dB\n" + lines.mapIndexed { i, l -> "Filter ${i + 1}: $l" }.joinToString("\n")

    @Test
    fun hd650() {
        val eq = ok(text("hd650.txt"), "Sennheiser HD 650")
        assertEquals(-6.1, eq.preamp, 0.0)
        assertEquals(10, eq.filters.size)
        assertEquals(EqFilter(Enums.BandType.lowShelf, 105.0, 6.4, 0.7), eq.filters[0])
        assertEquals(EqFilter(Enums.BandType.peak, 8800.0, 5.1, 1.42), eq.filters[1])
        assertEquals(EqFilter(Enums.BandType.highShelf, 10000.0, -2.1, 0.7), eq.filters[5])
        assertEquals(emptyList<Clamp>(), eq.clamps)
    }

    @Test
    fun crinacle() {
        val eq = ok(text("sa02.txt"))
        assertEquals(-6.0, eq.preamp, 0.0)
        assertEquals(10, eq.filters.size)
        assertEquals(EqFilter(Enums.BandType.peak, 5939.0, -3.1, 6.0), eq.filters[7])
        assertEquals(emptyList<Clamp>(), eq.clamps)
    }

    @Test
    fun elevenFiltersRefused() {
        val lines = (1..11).map { "ON PK Fc ${it * 100} Hz Gain 1.0 dB Q 1.00" }.toTypedArray()
        assertEquals("11 filters, max 10", refused(filters(*lines)))
    }

    @Test
    fun offFiltersDropped() {
        val lines = (1..10).map { "ON PK Fc ${it * 100} Hz Gain 1.0 dB Q 1.00" } + "OFF PK Fc 0 Hz Gain 0.0 dB Q 0.00" + "OFF"
        val eq = ok(filters(*lines.toTypedArray()))
        assertEquals(10, eq.filters.size)
        assertEquals(1000.0, eq.filters.last().hz, 0.0)
    }

    @Test
    fun clampsListed() {
        val eq = ok("Preamp: 1.5 dB\n" + filters("ON PK Fc 1000 Hz Gain 14.0 dB Q 1.00", "ON LSC Fc 15 Hz Gain -30 dB Q 12", "ON HSC Fc 22000 Hz Gain 2 dB Q 0.05").substringAfter('\n'))
        assertEquals(12.0, eq.filters[0].db, 0.0)
        assertEquals(
            listOf(
                Clamp(AutoEq.PREAMP, AutoEq.FIELD_PREAMP, 1.5, 0.0),
                Clamp(1, AutoEq.FIELD_GAIN, 14.0, 12.0),
                Clamp(2, AutoEq.FIELD_FC, 15.0, 20.0),
                Clamp(2, AutoEq.FIELD_GAIN, -30.0, -24.0),
                Clamp(2, AutoEq.FIELD_Q, 12.0, 10.0),
                Clamp(3, AutoEq.FIELD_FC, 22000.0, 20000.0),
                Clamp(3, AutoEq.FIELD_Q, 0.05, 0.1),
            ),
            eq.clamps,
        )
    }

    @Test
    fun commaDecimalsAndCrlf() {
        val eq = ok("\uFEFFPreamp: -6,1 dB\r\nFilter 1: ON LSC Fc 105,5 Hz Gain 6,4 dB Q 0,70\r\nFilter 2: ON PK Fc 8800 Hz Gain -5.1 dB Q 1.42\r\n")
        assertEquals(-6.1, eq.preamp, 0.0)
        assertEquals(listOf(EqFilter(Enums.BandType.lowShelf, 105.5, 6.4, 0.7), EqFilter(Enums.BandType.peak, 8800.0, -5.1, 1.42)), eq.filters)
    }

    @Test
    fun refusals() {
        assertEquals("no filters", refused("GraphicEQ: 20 -1.0; 21 -1.2"))
        assertEquals("filter 1 type lp", refused(filters("ON LP Fc 100 Hz Q 0.71")))
        assertEquals("filter 1 no q", refused(filters("ON PK Fc 100 Hz Gain 1.0 dB")))
        assertEquals("file over 64 kib", refused("x".repeat(64 * 1024 + 1)))
    }

    @Test
    fun noPreampIsZero() {
        assertEquals(0.0, ok("Filter 1: ON PK Fc 100 Hz Gain 1 dB Q 1").preamp, 0.0)
    }

    @Test
    fun names() {
        assertEquals("Sennheiser HD 650", AutoEq.name("Sennheiser HD 650 ParametricEQ.txt"))
        assertEquals("Sennheiser HD 650", AutoEq.name("Sennheiser HD 650 GraphicEQ.TXT"))
        assertEquals("1Custom SA02", AutoEq.name("1Custom SA02"))
        assertTrue(AutoEq.validName("Sennheiser HD 650 (2020, worn earpads)"))
        assertTrue(AutoEq.validName("a".repeat(64)))
        assertFalse(AutoEq.validName("a".repeat(65)))
        assertFalse(AutoEq.validName("ア".repeat(22)))
        assertFalse(AutoEq.validName(""))
        assertFalse(AutoEq.validName("a/b"))
        assertFalse(AutoEq.validName(".hidden"))
        assertFalse(AutoEq.validName(" lead"))
        assertFalse(AutoEq.validName("tab\there"))
        assertFalse(AutoEq.validName("del\u007f"))
        assertFalse(AutoEq.validName("X ParametricEQ"))
        assertFalse(AutoEq.validName("Custom"))
        assertFalse(AutoEq.validName("FLAT"))
    }

    @Test
    fun namesFollowTheDeviceRule() {
        assertTrue(AutoEq.validName("\u3000HD 650"))
        assertTrue(AutoEq.validName("HD 650\u3000"))
        assertFalse(AutoEq.validName(" HD"))
        assertFalse(AutoEq.validName("HD "))
        assertFalse(AutoEq.validName("OFF"))
        assertTrue(AutoEq.validName("\uFF2Fff"))
        assertTrue(AutoEq.validName("\u00D6ff"))
        assertFalse(AutoEq.validName("a/b"))
        assertTrue(AutoEq.validName("ア".repeat(21) + "a"))
        assertFalse(AutoEq.validName("ア".repeat(21) + "ab"))
        assertEquals("sennheiser hd 650 \u00C9", AutoEq.foldKey("Sennheiser HD 650 \u00C9"))
    }

    @Test
    fun profileRoundsAsTheDevice() {
        val p = AutoEq.profile(ok(text("hd650.txt"), "Sennheiser HD 650"))
        assertEquals(
            Profile(
                -61,
                listOf(
                    Band(Enums.BandType.lowShelf, -1, 105000, 64, 70),
                    Band(Enums.BandType.peak, -1, 8800000, 51, 142),
                    Band(Enums.BandType.peak, -1, 118000, -31, 50),
                    Band(Enums.BandType.peak, -1, 37000, 7, 396),
                    Band(Enums.BandType.peak, -1, 3169000, -17, 389),
                    Band(Enums.BandType.highShelf, -1, 10000000, -21, 70),
                    Band(Enums.BandType.peak, -1, 1227000, -12, 253),
                    Band(Enums.BandType.peak, -1, 2055000, 12, 323),
                    Band(Enums.BandType.peak, -1, 587000, 4, 119),
                    Band(Enums.BandType.peak, -1, 5332000, -11, 575),
                ),
            ),
            p,
        )
    }

    @Test
    fun profileRoundsHalvesAwayFromZeroInDouble() {
        val p = AutoEq.profile(ok("Preamp: -6.25 dB\nFilter 1: ON PK Fc 1000.5 Hz Gain -2.25 dB Q 0.705\n"))
        assertEquals(Profile(-63, listOf(Band(Enums.BandType.peak, -1, 1000500, -23, 71))), p)
    }
}
