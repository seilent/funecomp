package net.seilent.funecomp.ui

object Held {
    class Entry(val name: String, val token: String, val value: Any, val row: String, val branch: String, val pin: String)

    object Nav {
        const val motion_turnstileOut = 250f
        const val motion_turnstileIn = 350f
        const val motion_turnstileOutAngle = 50f
        const val motion_turnstileInAngle = -80f

        val entries = listOf(
            Entry("motion_turnstileOut", "motion_turnstileOut", motion_turnstileOut, "M1, M2, M3, M4", "zmove", "836c8fbc4"),
            Entry("motion_turnstileIn", "motion_turnstileIn", motion_turnstileIn, "M1, M2, M3, M4", "zmove", "836c8fbc4"),
            Entry("motion_turnstileOutAngle", "motion_turnstileOutAngle", motion_turnstileOutAngle, "M1, M2, M3, M4", "zmove", "836c8fbc4"),
            Entry("motion_turnstileInAngle", "motion_turnstileInAngle", motion_turnstileInAngle, "M1, M2, M3, M4", "zmove", "836c8fbc4"),
        )
    }

    object Qp {
        const val type_quickplayHeader_size = 34f
        const val type_quickplayHeader_alpha = 0.6f
        const val qpSectionGap = 70f

        val entries = listOf(
            Entry("type_quickplayHeader_size", "type_quickplayHeader_size", type_quickplayHeader_size, "W3", "zrows", "836c8fbc4"),
            Entry("type_quickplayHeader_alpha", "type_quickplayHeader_alpha", type_quickplayHeader_alpha, "W3", "zrows", "836c8fbc4"),
            Entry("qpSectionGap", "qpSectionGap", qpSectionGap, "W3", "zrows", "836c8fbc4"),
        )
    }

    object Pivot {
        const val motion_pivotOut = 300f
        const val motion_pivotStrip = 380f
        const val motion_pivotLabel = 200f
        const val motion_pivotRows = 380f
        const val motion_pivotRowStagger = 22f

        val entries = listOf(
            Entry("motion_pivotOut", "motion_pivotOut", motion_pivotOut, "P1", "zt5", "836c8fbc4"),
            Entry("motion_pivotStrip", "motion_pivotStrip", motion_pivotStrip, "P1", "zt5", "836c8fbc4"),
            Entry("motion_pivotLabel", "motion_pivotLabel", motion_pivotLabel, "P1", "zt5", "836c8fbc4"),
            Entry("motion_pivotRows", "motion_pivotRows", motion_pivotRows, "P1", "zt5", "836c8fbc4"),
            Entry("motion_pivotRowStagger", "motion_pivotRowStagger", motion_pivotRowStagger, "P1", "zt5", "836c8fbc4"),
        )
    }

    object Rest {
        const val homeFirstBaseline = 96.4f
        const val homeRingY = 87.4f
        const val homeRingHitBottom = 111.7f

        val entries = listOf(
            Entry("homeFirstBaseline", "homeFirstBaseline", homeFirstBaseline, "R1", "zhome2", "836c8fbc4"),
            Entry("homeRingY", "homeRingY", homeRingY, "R1", "zhome2", "836c8fbc4"),
            Entry("homeRingHitBottom", "homeRingHitBottom", homeRingHitBottom, "R1", "zhome2", "836c8fbc4"),
        )
    }

    object Jump {
        const val type_jumpExit_size = 11f
        const val jumpGap = 4f
        const val jumpTop = 16f

        val entries = listOf(
            Entry("type_jumpExit_size", "type_jumpExit_size", type_jumpExit_size, "T3", "ztoast", "836c8fbc4"),
            Entry("jumpGap", "jumpGap", jumpGap, "T3", "ztoast", "836c8fbc4"),
            Entry("jumpTop", "jumpTop", jumpTop, "T3", "ztoast", "836c8fbc4"),
        )
    }

    object Vol {
        const val vol_skip = 42.1f
        const val vol_label = 8.8f
        const val motion_volumePressOff = 33f
        const val motion_volumeTouchClose = 100f

        val entries = listOf(
            Entry("vol_skip", "vol_skip", vol_skip, "V1", "zvol", "frames ff3b9095"),
            Entry("vol_label", "vol_label", vol_label, "V1", "zvol", "frames ff3b9095"),
            Entry("motion_volumePressOff", "motion_volumePressOff", motion_volumePressOff, "V4", "zvol", "frames ff3b9095"),
            Entry("motion_volumeTouchClose", "motion_volumeTouchClose", motion_volumeTouchClose, "V8", "zvol", "frames ff3b9095"),
        )
    }

    val entries get() = Nav.entries + Qp.entries + Pivot.entries + Rest.entries + Jump.entries + Vol.entries
}
