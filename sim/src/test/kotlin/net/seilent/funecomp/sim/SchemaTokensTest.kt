package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Settings
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.proto.gen.TokBoard
import org.junit.Assert.assertEquals
import org.junit.Test

class SchemaTokensTest {
    private fun steps(s: String) = s.split(',')

    @Test
    fun stepRowsAreTheTokenLists() {
        for (b in listOf("ap80", "v1")) {
            val tok = TokBoard.of(b)
            val rows = Device(Options(Board.of(b)), Library.synthetic()) { 0L }.rows.associateBy { it.key }
            val want = mapOf(
                "volumeStep" to Tok.settings_volumeSteps,
                "maxVolume" to tok.settings_maxVolumeSteps,
                "powerOnVolume" to tok.settings_powerOnSteps,
                "brightness" to Tok.settings_brightnessSteps,
                "saverSeconds" to Tok.settings_saverSteps,
                "screenOffSeconds" to Tok.settings_screenOffSteps,
                "idleShutdownMinutes" to Tok.settings_idleSteps,
                "sleep" to Tok.settings_sleepSteps,
                "crossfade" to Tok.settings_crossfadeSteps,
            )
            for ((key, list) in want) assertEquals("$b $key", steps(list), rows.getValue(key).values)
            assertEquals("$b maxVolume words", steps(tok.settings_maxVolumeSteps), rows.getValue("maxVolume").words)
            assertEquals(
                "$b powerOnVolume words",
                steps(tok.settings_powerOnSteps).map { if (it.toInt() > 0) it else "LAST" },
                rows.getValue("powerOnVolume").words,
            )
        }
    }

    @Test
    fun maxPctSnapsToTheNearestBoardStep() {
        for ((b, pct, want) in listOf(Triple("v1", 7, "5"), Triple("v1", 3, "5"), Triple("ap80", 52, "50"), Triple("ap80", 7, "50"), Triple("ap80", 120, "100"))) {
            val frames = ArrayList<Pair<Int, ByteArray>>()
            Device(Options(Board.of(b), maxPct = pct), Library.synthetic()) { 0L }.greet(object : Out {
                override fun push(type: Int, payload: ByteArray) {
                    frames += type to payload
                }
            })
            val text = Settings.from(Node.decode("SETTINGS", frames.single { it.first == Msg.SETTINGS }.second)).text
            assertEquals("$b $pct", "maxVolume=$want", text.lines().single { it.startsWith("maxVolume=") })
        }
    }
}
