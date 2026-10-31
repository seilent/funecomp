package net.seilent.funecomp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import net.seilent.funecomp.ui.LoginPage
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

private const val HOST = "login.svcdemo.test"
private const val LONG_HOST = "accounts.signin.identity.provider.example.region.cluster.edge.authentication.login.svcdemo.test"

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class SvcPageShots {
    @get:Rule
    val rule = createComposeRule()

    private var frame by mutableStateOf<Triple<Size, Boolean, String>?>(null)

    @Test
    fun shots() {
        assumeTrue(System.getProperty("shots.run") == "true")
        val only = System.getProperty("shots.only").orEmpty()
        val dir = File(System.getProperty("shots.dir") ?: "build/shots")
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val f = frame ?: return@setContent
            key(f) {
                Box(Modifier.requiredSize(f.first.w.dp, f.first.h.dp).testTag("frame")) {
                    LoginPage("TIDAL", f.third, {}, insets = f.first.bars, ime = WindowInsets(bottom = 0.dp), web = if (f.second) ({ Box(Modifier.fillMaxSize().background(Color.White)) }) else null)
                }
            }
        }
        for (size in SIZES) {
            for ((kind, white, host) in listOf(Triple("", false, HOST), Triple("-white", true, HOST), Triple("-longhost", true, LONG_HOST))) {
                val name = "svc-page$kind-${size.name}"
                if (only.isNotEmpty() && only.split(',').none { name.contains(it) }) continue
                frame = Triple(size, white, host)
                rule.waitForIdle()
                rule.mainClock.advanceTimeBy(1000)
                rule.waitForIdle()
                rule.onNodeWithTag("frame").captureRoboImage(File(dir, "$name.png").path)
            }
        }
    }
}
