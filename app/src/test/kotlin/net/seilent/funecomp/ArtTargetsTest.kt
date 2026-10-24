package net.seilent.funecomp

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArtTargetsTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun target() = app.getSharedPreferences(Main.ART_PREFS, Context.MODE_PRIVATE).getString("phoneTarget", null)

    @Before
    fun clear() {
        app.getSharedPreferences(Main.ART_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun aCoverScreenStartGrowsToTheMainWindowAndAFoldNeverLowersIt() {
        Remote.window(app, 948, 748)
        assertEquals("499x632", target())
        Remote.window(app, 1080, 2520)
        assertEquals("720x1680", target())
        Remote.window(app, 948, 748)
        assertEquals("720x1680", target())
        Remote.window(app, 2520, 1080)
        assertEquals("720x1680", target())
    }
}
