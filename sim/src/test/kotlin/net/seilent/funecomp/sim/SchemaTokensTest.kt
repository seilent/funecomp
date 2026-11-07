package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Settings
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.proto.gen.TokBoard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaTokensTest {
    private fun steps(s: String) = s.split(',')

    @Test
    fun saverDelayStepsAreFuneosTokensLine672UntilTheTokenRepin() {
        assertTrue(Tok::class.java.declaredFields.none { it.name == "settings_saverDelaySteps" })
        for (b in listOf("ap80", "v1", "r1")) {
            val rows = Device(Options(Board.of(b)), Library.synthetic()) { 0L }.rows.associateBy { it.key }
            assertEquals(b, listOf("10", "15", "30", "60", "120", "300"), rows.getValue("saverDelay").values)
        }
    }

    @Test
    fun stepRowsAreTheTokenLists() {
        for (b in listOf("ap80", "v1", "r1")) {
            val tok = TokBoard.of(b)
            val rows = Device(Options(Board.of(b)), Library.synthetic()) { 0L }.rows.associateBy { it.key }
            val want = mapOf(
                "volumeStep" to Tok.settings_volumeSteps,
                "maxVolume" to tok.settings_maxVolumeSteps,
                "powerOnScale" to tok.settings_powerOnSteps,
                "brightness" to Tok.settings_brightnessSteps,
                "screenOffSeconds" to Tok.settings_screenOffSteps,
                "idleShutdownMinutes" to Tok.settings_idleSteps,
                "sleep" to Tok.settings_sleepSteps,
                "crossfade" to Tok.settings_crossfadeSteps,
            )
            for ((key, list) in want) assertEquals("$b $key", steps(list), rows.getValue(key).values)
            assertEquals("$b maxVolume words", steps(tok.settings_maxVolumeSteps), rows.getValue("maxVolume").words)
            assertEquals(
                "$b powerOnScale words",
                steps(tok.settings_powerOnSteps).map { if (it.toInt() > 0) it else "LAST" },
                rows.getValue("powerOnScale").words,
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
