import org.junit.Assert.assertEquals
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
        assertTrue(kt.contains("val r1 = Board("))
        assertTrue(kt.contains("canvasH = 400.0f") || kt.contains("canvasH = 400f"))
        assertTrue(kt.contains("\"r1\" -> r1"))
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
