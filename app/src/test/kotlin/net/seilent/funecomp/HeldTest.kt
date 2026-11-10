package net.seilent.funecomp

import net.seilent.funecomp.ui.Held
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HeldTest {
    private fun rows(e: List<Held.Entry>) = e.map { listOf(it.name, it.token, it.value, it.row, it.branch, it.pin) }

    @Test
    fun theHeldValuesKeepTheirOldPin() {
        assertEquals(
            listOf(
                listOf("motion_turnstileOut", "motion_turnstileOut", 250f, "M1, M2, M3, M4", "zmove", "836c8fbc4"),
                listOf("motion_turnstileIn", "motion_turnstileIn", 350f, "M1, M2, M3, M4", "zmove", "836c8fbc4"),
                listOf("motion_turnstileOutAngle", "motion_turnstileOutAngle", 50f, "M1, M2, M3, M4", "zmove", "836c8fbc4"),
                listOf("motion_turnstileInAngle", "motion_turnstileInAngle", -80f, "M1, M2, M3, M4", "zmove", "836c8fbc4"),
            ),
            rows(Held.Nav.entries),
        )
        assertEquals(
            listOf(
                listOf("motion_pivotOut", "motion_pivotOut", 300f, "P1", "zt5", "836c8fbc4"),
                listOf("motion_pivotStrip", "motion_pivotStrip", 380f, "P1", "zt5", "836c8fbc4"),
                listOf("motion_pivotLabel", "motion_pivotLabel", 200f, "P1", "zt5", "836c8fbc4"),
                listOf("motion_pivotRows", "motion_pivotRows", 380f, "P1", "zt5", "836c8fbc4"),
                listOf("motion_pivotRowStagger", "motion_pivotRowStagger", 22f, "P1", "zt5", "836c8fbc4"),
            ),
            rows(Held.Pivot.entries),
        )
        assertEquals(
            listOf(
                listOf("vol_skip", "vol_skip", 42.1f, "V1", "zvol", "frames ff3b9095"),
                listOf("vol_label", "vol_label", 8.8f, "V1", "zvol", "frames ff3b9095"),
                listOf("motion_volumePressOff", "motion_volumePressOff", 33f, "V4", "zvol", "frames ff3b9095"),
                listOf("motion_volumeTouchClose", "motion_volumeTouchClose", 100f, "V8", "zvol", "frames ff3b9095"),
            ),
            rows(Held.Vol.entries),
        )
        assertEquals(13, Held.entries.size)
        assertEquals(setOf("Entry", "Nav", "Pivot", "Vol"), Held::class.java.declaredClasses.map { it.simpleName }.toSet())
        assertEquals(Held.entries.size, Held.entries.map { "${it.branch} ${it.name}" }.toSet().size)
    }

    @Test
    fun noSourceButHeldReadsAHeldNameFromTheTokens() {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }.firstOrNull { File(it, "settings.gradle.kts").isFile }
        assertNotNull("no settings.gradle.kts above ${System.getProperty("user.dir")}", root)
        val names = Held.entries.joinToString("|") { it.token }
        val owner = "(?:Tok|LayoutTok|TokBoard\\.(?:ap80|v1|r1|of\\([^)]*\\)))"
        val reads = listOf(
            Regex("(?<![A-Za-z_.])$owner(?:\\.|::)($names)\\b"),
            Regex("^import net\\.seilent\\.funecomp\\.proto\\.gen\\.(?:Tok|TokBoard)\\.(?:\\w+\\.)?(?:\\*|($names)\\b)"),
            Regex("\\bwith\\($owner\\)|(?<![A-Za-z_.])$owner\\.(?:run|apply)\\s*\\{"),
        )
        val alias = Regex("\\b(?:val|var)\\s+(\\w+)\\s*(?::[^=]*)?=\\s*$owner(?![A-Za-z_.(])")
        val sources = listOf("app/src/main", "app/src/test").flatMap { File(root, it).walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }
        assertTrue("no sources under $root", sources.any { it.name == "Held.kt" } && sources.any { it.name == "HeldTest.kt" })
        val hits = sources.filter { it.name != "Held.kt" }.flatMap { f ->
            val text = f.readText()
            val aliases = alias.findAll(text).map { it.groupValues[1] }.toList()
            val viaAlias = if (aliases.isEmpty()) null else Regex("(?<![A-Za-z_.])(?:${aliases.joinToString("|")})(?:\\.|::)($names)\\b")
            text.lines().mapIndexedNotNull { i, l -> if (reads.any { it.containsMatchIn(l) } || viaAlias?.containsMatchIn(l) == true) "${f.relativeTo(root!!)}:${i + 1}" else null }
        }
        assertEquals(emptyList<String>(), hits)
    }
}
