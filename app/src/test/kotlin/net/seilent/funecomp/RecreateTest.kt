package net.seilent.funecomp

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.Looper
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import net.seilent.funecomp.ui.Page
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class RecreateTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val app get() = RuntimeEnvironment.getApplication()
    private val open = ArrayList<ActivityController<Main>>()

    @Before
    fun setUp() {
        scrobblesOff(app)
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        app.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE).edit().putBoolean("batteryAsked", true).commit()
    }

    @After
    fun tearDown() {
        open.forEach { it.pause().stop().destroy() }
        Remote.stop()
        Remote.resetArtist()
        Lyrics.reset()
        Firmwares.reset()
        scrobblesReset()
    }

    private fun main(): ActivityController<Main> {
        val a = Robolectric.buildActivity(Main::class.java, Intent(app, Main::class.java).setAction(Intent.ACTION_MAIN).putExtra(Main.EXTRA_PAGE, "settings")).create().start().resume().visible()
        open += a
        shadowOf(Looper.getMainLooper()).idle()
        rule.waitForIdle()
        return a
    }

    private fun changed(a: ActivityController<Main>, edit: Configuration.() -> Unit) {
        a.configurationChange(Configuration(a.get().resources.configuration).apply(edit))
        shadowOf(Looper.getMainLooper()).idle()
        rule.waitForIdle()
    }

    private fun header(): Rect = rule.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot

    @Test
    fun aFontScaleOrLocaleChangeKeepsTheActivityAndItsPages() {
        val a = main()
        val m = a.get()
        val shell = m.shell
        val text = header()
        shell.nav.push(Page.Firmware)
        changed(a) { fontScale = 1.3f }
        assertSame(m, a.get())
        assertSame(shell, m.shell)
        assertEquals(1.3f, m.resources.configuration.fontScale)
        shell.nav.pop()
        rule.waitForIdle()
        assertEquals(text.width, header().width)
        assertEquals(text.height, header().height)
        shell.nav.push(Page.Firmware)
        rule.waitForIdle()
        changed(a) { setLocale(Locale.forLanguageTag("ja")) }
        assertSame(m, a.get())
        assertSame(shell, m.shell)
        assertEquals(Page.Firmware, shell.nav.top)
    }

    @Test
    fun aRightToLeftLocaleInPlaceLaysOutAsAFreshStart() {
        val a = main()
        val before = header()
        changed(a) { setLocale(Locale.forLanguageTag("ar")) }
        val inPlace = header()
        a.pause().stop().destroy()
        open.clear()
        RuntimeEnvironment.setQualifiers("+ar")
        main()
        val fresh = header()
        assertEquals(fresh.left, inPlace.left)
        assertEquals(fresh.top, inPlace.top)
        assertEquals(before.left, inPlace.left)
    }
}
