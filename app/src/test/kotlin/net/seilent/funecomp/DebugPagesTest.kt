package net.seilent.funecomp

import net.seilent.funecomp.ui.Page
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
            "art" to home + Page.ArtistArt,
            "artpicked" to home + Page.ArtistArt + Page.ArtPicked,
            "artblocked" to home + Page.ArtistArt + Page.ArtBlocked,
            "artcredits" to home + Page.ArtistArt + Page.ArtCredits,
            "scrobbling" to home + Page.Connection + Page.Scrobbling,
            "firmware" to home + Page.Connection + Page.Firmware,
            "lyrics" to home + Page.Connection + Page.Lyrics,
            "online" to home + Page.Online(),
            "artist:Radiohead" to home + Page.Music + Page.Artist("Radiohead"),
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
