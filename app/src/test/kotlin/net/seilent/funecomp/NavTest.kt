package net.seilent.funecomp

import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import org.junit.Assert.assertEquals
import org.junit.Test

class NavTest {
    @Test
    fun replaceRootSwapsOnlyTheRoot() {
        val nav = Nav(listOf(Page.Connection, Page.Settings, Page.ArtCredits))
        nav.replaceRoot(Page.Home)
        assertEquals(listOf(Page.Home, Page.Settings, Page.ArtCredits), nav.stack.toList())
        assertEquals(Page.ArtCredits, nav.top)
    }

    @Test
    fun replaceRootOnOnePageSwapsThePage() {
        val nav = Nav(listOf(Page.Connection))
        nav.replaceRoot(Page.Home)
        assertEquals(listOf(Page.Home), nav.stack.toList())
        assertEquals(Page.Home, nav.top)
    }
}
