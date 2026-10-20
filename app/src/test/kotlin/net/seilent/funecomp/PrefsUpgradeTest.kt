package net.seilent.funecomp

import android.content.Context
import net.seilent.funecomp.artist.Accounts
import net.seilent.funecomp.artist.Sources
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrefsUpgradeTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private val files = listOf(Main.PREFS, Main.ART_PREFS, "media", "firmware", "lyrics", "scrobble", "pick")

    @After
    fun clear() {
        Lyrics.reset()
        Firmwares.reset()
    }

    @Test
    fun everyStoredSettingReadsBackThroughItsReader() {
        val dir = File(app.dataDir, "shared_prefs").apply { mkdirs() }
        for (name in files) {
            val src = javaClass.getResourceAsStream("/uirework/shared_prefs/$name.xml") ?: error("no fixture $name")
            src.use { i -> File(dir, "$name.xml").outputStream().use { i.copyTo(it) } }
        }
        for (name in files) assertEquals("fixture $name", app.getSharedPreferences(name, Context.MODE_PRIVATE).getString("uireworkSentinel", null))
        assertEquals(Sources(true, true, true, "fixturekey", true, true, listOf("deezer", "youtube", "discogs", "fanarttv", "theaudiodb", "commons")), Main.sources(app))
        assertEquals(Accounts(true, "fixtureapikey", "fixturesecret", "fixtureuser", "fixturesession", true, "fixturetoken", "fixturelbuser"), Scrobbles.accounts(app))
        assertFalse(MediaLink.controls(app))
        Lyrics.reset()
        Lyrics.init(app)
        assertTrue(Lyrics.on)
        Firmwares.reset()
        Firmwares.init(app)
        assertTrue(Firmwares.view.checkDaily)
        assertFalse(Firmwares.view.prereleases)
    }
}
