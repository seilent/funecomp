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
        const val motion_pivotStrip = 380f
        const val motion_pivotLabel = 200f

        val entries = listOf(
            Entry("motion_pivotStrip", "motion_pivotStrip", motion_pivotStrip, "P1", "zt5", "836c8fbc4"),
            Entry("motion_pivotLabel", "motion_pivotLabel", motion_pivotLabel, "P1", "zt5", "836c8fbc4"),
        )
    }

    object Vol {
        const val motion_volumePressOff = 33f

        val entries = listOf(
            Entry("motion_volumePressOff", "motion_volumePressOff", motion_volumePressOff, "V4", "zvol", "frames ff3b9095"),
        )
    }

    object Track {
        const val motion_trackHide = 500f
        const val motion_trackShow = 400f

        val entries = listOf(
            Entry("motion_trackHide", "motion_trackHide", motion_trackHide, "T9", "zt9", "2cc18f094"),
            Entry("motion_trackShow", "motion_trackShow", motion_trackShow, "T9", "zt9", "2cc18f094"),
        )
    }

    val entries get() = Nav.entries + Pivot.entries + Vol.entries + Track.entries
}
