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

    val entries get() = Nav.entries + Pivot.entries + Vol.entries
}
