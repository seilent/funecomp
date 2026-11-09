import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GenTokensTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(ap80: String, v1: String, r1: String): File = tmp.newFolder().also {
        File(it, "tokens.txt").writeText(ap80)
        File(it, "tokens-v1.txt").writeText(v1)
        File(it, "tokens-r1.txt").writeText(r1)
    }

    private val base = "u 1.5 px_per_u\ncanvasH 320 u\nedge 16 u\n"

    @Test
    fun aTokenThatDiffersOnlyOnTheR1IsABoardToken() {
        val d = dir(base, base.replace("u 1.5", "u 1.0"), base.replace("u 1.5", "u 2.0").replace("canvasH 320", "canvasH 400"))
        assertEquals(setOf("u", "canvasH"), Gen.boardTokens(d))
        val kt = Gen.tokBoardKt(d)
        assertTrue(kt.contains("    object r1 : Board() {\n"))
        assertTrue(kt.contains("        override val canvasH: Float = 400f\n"))
        assertTrue(kt.contains("\"r1\" -> r1"))
    }

    @Test
    fun threeHundredBoardTokensMakeAnAbstractBoardWithOneObjectPerBoard() {
        val names = (1..300).map { "t$it" }
        fun file(v: Int) = base + names.joinToString("") { "$it $v u\n" }
        val d = dir(file(1), file(2), file(3))
        assertEquals(names.toSet(), Gen.boardTokens(d))
        val kt = Gen.tokBoardKt(d)
        assertFalse(kt.contains("class Board("))
        assertTrue(kt.contains("    abstract class Board {\n"))
        assertEquals(300, Regex("(?m)^        abstract val t[0-9]+: Float$").findAll(kt).count())
        for ((b, v) in listOf("ap80" to 1, "v1" to 2, "r1" to 3)) {
            val body = kt.substringAfter("    object $b : Board() {\n").substringBefore("\n    }\n")
            assertEquals(b, 300, Regex("        override val t[0-9]+: Float = ${v}f").findAll(body).count())
            assertTrue(b, body.endsWith("        override val t300: Float = ${v}f"))
        }
        assertTrue(kt.contains("        \"v1\" -> v1\n"))
    }

    @Test
    fun aNameMissingOrMovedInTokensR1Fails() {
        val missing = dir(base, base, "u 1.5 px_per_u\nedge 16 u\n")
        assertThrows(GenException::class.java) { Gen.boardTokens(missing) }
        val moved = dir(base, base, "canvasH 320 u\nu 1.5 px_per_u\nedge 16 u\n")
        assertThrows(GenException::class.java) { Gen.boardTokens(moved) }
        val renamed = dir(base, base, base.replace("edge", "gutter"))
        val e = assertThrows(GenException::class.java) { Gen.tokBoardKt(renamed) }
        assertTrue(e.message!!.contains("tokens-r1.txt"))
    }
}
