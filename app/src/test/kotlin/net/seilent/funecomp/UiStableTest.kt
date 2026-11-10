package net.seilent.funecomp

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.rememberUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UiStableTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun oneUiPerWindowSize() {
        var tick by mutableIntStateOf(0)
        var width by mutableFloatStateOf(1079f)
        val seen = ArrayList<Pair<Int, Ui>>()
        rule.setContent {
            val t = tick
            val ui = rememberUi(width, 2372f)
            SideEffect { seen += t to ui }
        }
        rule.waitForIdle()
        repeat(3) {
            tick++
            rule.waitForIdle()
        }
        assertEquals(listOf(0, 1, 2, 3), seen.map { it.first })
        seen.forEach { assertSame("composition ${it.first}", seen[0].second, it.second) }
        width = 1080f
        rule.waitForIdle()
        val resized = seen.last().second
        assertNotSame(seen[0].second, resized)
        assertEquals(Ui.of(1080f, 2372f).w, resized.w, 1e-4f)
    }

    @Test
    fun theAppBuildsItsUiThroughRememberUi() {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }.firstOrNull { File(it, "settings.gradle.kts").isFile }
        assertNotNull("no settings.gradle.kts above ${System.getProperty("user.dir")}", root)
        val call = Regex("(?<![A-Za-z_.])Ui\\.of\\(")
        val sources = File(root, "app/src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val calls = sources.flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (call.containsMatchIn(l)) f to "${f.relativeTo(root!!)}:${i + 1}" else null } }
        assertTrue("no Ui.of( in Ui.kt under $root", calls.any { it.first.name == "Ui.kt" })
        assertEquals(emptyList<String>(), calls.filter { it.first.name != "Ui.kt" }.map { it.second })
    }
}
