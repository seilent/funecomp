package net.seilent.funecomp

import net.seilent.funecomp.ui.ONLINE_LYRICS
import net.seilent.funecomp.ui.ONLINE_SCROBBLING
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.PickFor
import net.seilent.funecomp.ui.debugPages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebugPagesTest {
    private val home = listOf(Page.Home)

    @Test
    fun everyNameMapsToItsStack() {
        val want = mapOf(
            "home" to home,
            "nowplaying" to home + Page.NowPlaying,
            "queue" to home + Page.NowPlaying + Page.Queue,
            "music" to home + Page.Music,
            "playlists" to home + Page.Playlists,
            "settings" to home + Page.Settings,
            "eq" to home + Page.Settings + Page.Eq(0),
            "autoeq" to home + Page.Settings + Page.Eq(0) + Page.AutoEq(0),
            "connection" to home + Page.Connection,
            "art" to home + Page.Online(),
            "artpicked" to home + Page.Online() + Page.ArtPicked,
            "artblocked" to home + Page.Online() + Page.ArtBlocked,
            "artcredits" to home + Page.Online() + Page.ArtCredits,
            "scrobbling" to home + Page.Online(ONLINE_SCROBBLING),
            "firmware" to home + Page.Settings + Page.Firmware,
            "lyrics" to home + Page.Online(ONLINE_LYRICS),
            "online" to home + Page.Online(),
            "artist:Radiohead" to home + Page.Music + Page.Artist("Radiohead"),
            "browse:TSUKI" to home + Page.Online() + Page.Browse(PickFor("TSUKI")),
            "album:k|In Rainbows|Radiohead" to home + Page.Music + Page.Album("k", "In Rainbows", "Radiohead"),
            "album:k" to home + Page.Music + Page.Album("k", "", ""),
        )
        want.forEach { (name, pages) -> assertEquals(name, pages, debugPages(name)) }
    }

    @Test
    fun anUnknownNameIsNull() {
        assertNull(debugPages("nowhere"))
        assertNull(debugPages(""))
    }
}
