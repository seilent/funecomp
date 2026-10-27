package net.seilent.funecomp

import android.content.Context
import net.seilent.funecomp.artist.Accounts
import net.seilent.funecomp.artist.LyricSources
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
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileNotFoundException

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

    private val upgraded = Sources(false, false, false, "fixturekey", false, false, listOf("deezer", "youtube", "discogs", "fanarttv", "theaudiodb", "commons"))

    private fun art() = app.getSharedPreferences(Main.ART_PREFS, Context.MODE_PRIVATE)

    private fun load(resource: String) {
        val xml = javaClass.getResourceAsStream(resource)?.use { String(it.readBytes()) } ?: throw FileNotFoundException(resource)
        val e = art().edit().clear()
        Regex("<boolean name=\"([^\"]+)\" value=\"([^\"]+)\" />").findAll(xml).forEach { e.putBoolean(it.groupValues[1], it.groupValues[2].toBoolean()) }
        Regex("<int name=\"([^\"]+)\" value=\"([^\"]+)\" />").findAll(xml).forEach { e.putInt(it.groupValues[1], it.groupValues[2].toInt()) }
        Regex("<string name=\"([^\"]+)\">([^<]*)</string>").findAll(xml).forEach { e.putString(it.groupValues[1], it.groupValues[2]) }
        e.commit()
    }

    private fun anOldFullWriteReadsAsTheNewDefaults() {
        load("/srcdefaults/v1/art.xml")
        assertEquals(Sources(), Main.sources(app))
        assertEquals(mapOf("defaults" to 2, "fanarttvKey" to ""), art().all)
        val file = File(app.dataDir, "shared_prefs/${Main.ART_PREFS}.xml").readText()
        assertTrue(file, file.contains("<int name=\"defaults\" value=\"2\" />"))
        for (k in listOf("theaudiodb", "discogs", "\"fanarttv\"", "deezer", "youtube", "order")) assertFalse(k, file.contains(k))
        assertEquals(Sources(), Main.sources(app))
    }

    private fun anUpgradedStoreReadsBackAsStored() {
        load("/uirework/shared_prefs/art.xml")
        assertEquals(upgraded, Main.sources(app))
        assertEquals(false, art().getBoolean("deezer", true))
    }

    @Test
    fun commonsReadsOnWhenAbsentAndAStoredOffStays() {
        art().edit().clear().commit()
        assertTrue(Main.sources(app).commons)
        Main.writeSources(app, Main.sources(app), Main.sources(app).copy(commons = false))
        assertFalse(art().getBoolean("commons", true))
        assertFalse(Main.sources(app).commons)
        art().edit().clear().putBoolean("commons", false).putBoolean("deezer", false).commit()
        assertEquals(Sources(deezer = true, commons = false), Main.sources(app))
        art().edit().clear().putInt("defaults", 2).putBoolean("commons", false).commit()
        assertEquals(Sources(commons = false), Main.sources(app))
    }

    @Test
    fun anOldInstallThenAnUpgradedOne() {
        anOldFullWriteReadsAsTheNewDefaults()
        anUpgradedStoreReadsBackAsStored()
    }

    @Test
    fun anUpgradedInstallThenAnOldOne() {
        anUpgradedStoreReadsBackAsStored()
        anOldFullWriteReadsAsTheNewDefaults()
    }

    @Test
    fun aLyricsStoreWithOnlyTheLrclibKeyReadsNeteaseOnInTheDefaultOrder() {
        app.getSharedPreferences("lyrics", Context.MODE_PRIVATE).edit().clear().putBoolean("lrclib", false).commit()
        Lyrics.reset()
        Lyrics.init(app)
        assertEquals(LyricSources(lrclib = false, netease = true, order = LyricSources.ORDER), Lyrics.sources)
        assertTrue(Lyrics.on)
    }

    @Test
    fun aToggleOnAFreshInstallWritesOnlyThatKey() {
        val fresh = Main.sources(app)
        assertEquals(Sources(), fresh)
        assertEquals(mapOf("defaults" to 2), art().all)
        Main.writeSources(app, fresh, fresh.copy(discogs = false))
        assertEquals(mapOf("defaults" to 2, "discogs" to false), art().all)
        assertEquals(fresh.copy(discogs = false), Main.sources(app))
        Main.writeSources(app, fresh.copy(discogs = false), fresh.copy(discogs = false).moved("deezer", -1))
        assertEquals(setOf("defaults", "discogs", "order"), art().all.keys)
    }

    @Test
    fun everyStoredSettingReadsBackThroughItsReader() {
        val dir = File(app.dataDir, "shared_prefs").apply { mkdirs() }
        for (name in files) {
            val src = javaClass.getResourceAsStream("/uirework/shared_prefs/$name.xml") ?: error("no fixture $name")
            src.use { i -> File(dir, "$name.xml").outputStream().use { i.copyTo(it) } }
        }
        for (name in files) assertEquals("fixture $name", app.getSharedPreferences(name, Context.MODE_PRIVATE).getString("uireworkSentinel", null))
        assertEquals(upgraded, Main.sources(app))
        assertEquals(Accounts(true, "fixtureapikey", "fixturesecret", "fixtureuser", "fixturesession", true, "fixturetoken", "fixturelbuser"), Scrobbles.accounts(app))
        assertFalse(MediaLink.controls(app))
        Lyrics.reset()
        Lyrics.init(app)
        assertEquals(LyricSources(false, false, listOf("netease", "lrclib")), Lyrics.sources)
        assertFalse(Lyrics.on)
        Firmwares.reset()
        Firmwares.init(app)
        assertTrue(Firmwares.view.checkDaily)
        assertFalse(Firmwares.view.prereleases)
    }

    private fun excludes(id: Int): List<String> {
        val x = app.resources.getXml(id)
        val out = ArrayList<String>()
        var section = ""
        while (x.next() != XmlPullParser.END_DOCUMENT) {
            if (x.eventType != XmlPullParser.START_TAG) continue
            if (x.name == "exclude") out += "$section ${x.getAttributeValue(null, "domain")} ${x.getAttributeValue(null, "path")}" else section = x.name
        }
        return out
    }

    @Test
    fun theLyricsModeReadsOffUntilSetAndStaysInTheBackup() {
        assertFalse(LyricsMode.on(app))
        LyricsMode.on(app, true)
        assertTrue(LyricsMode.on(app))
        assertEquals(mapOf("mode" to true), app.getSharedPreferences(LyricsMode.PREFS, Context.MODE_PRIVATE).all)
        Lyrics.init(app)
        assertEquals(LyricSources(), Lyrics.sources)
        val rules = excludes(R.xml.backup_rules) + excludes(R.xml.data_extraction_rules)
        for (section in listOf("full-backup-content", "cloud-backup", "device-transfer")) assertTrue(section, rules.contains("$section sharedpref art.xml"))
        assertTrue(rules.toString(), rules.none { it.endsWith(" ${LyricsMode.PREFS}.xml") })
    }
}
