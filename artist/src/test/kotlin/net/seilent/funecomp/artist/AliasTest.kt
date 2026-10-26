package net.seilent.funecomp.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AliasTest {
    private val http = Fixtures()
    private val time = FakeTime()

    private fun mb(): MusicBrainz {
        http.now = { time.now }
        return MusicBrainz(Api(http, "funecomp/0.1.0 (github@seilent.net)", RateLimiter(1000) { time.now }, time.sleep))
    }

    private fun a(name: String, locale: String?, primary: Boolean, type: String? = "Artist name") = Alias(name, locale, primary, type)

    @Test
    fun risaYuzukiGivesItsJapaneseAlias() {
        val r = mb().aliases("2bbcc070-62f8-4669-b77c-fdaa2d7c3f4a")!!
        assertEquals("Risa Yuzuki", r.name)
        assertEquals(listOf("Risa Yuzuki", "Yuzurisa", "柚木梨沙"), r.aliases.map { it.name })
        assertEquals("柚木梨沙", Aliases.native(r.name, r.aliases, "Risa Yuzuki"))
    }

    @Test
    fun theOrderOfTheAliases() {
        val hayami = listOf(a("Saori Hayami", "en", true), a("早见沙织", "zh_Hans", true), a("早見沙織", "ja", true))
        assertEquals("早見沙織", Aliases.native("早見沙織", hayami, "Saori Hayami"))
        assertEquals("早見沙織", Aliases.native("x", listOf(a("早见沙织", "zh_Hans", true), a("早見沙織", "ja_JP", true)), "Saori Hayami"))
        assertEquals("早见沙织", Aliases.native("x", listOf(a("早见沙织", "zh_Hans", true), a("サオリ", null, false)), "Saori Hayami"))
        assertEquals("サオリ", Aliases.native("x", listOf(a("さおりん", "ja", true, "Search hint"), a("サオリ", "ja", true)), "Saori Hayami"))
        assertEquals("さおり", Aliases.native("x", listOf(a("サオリ", "en", true, null), a("さおり", null, false, "Legal name")), "Saori Hayami"))
        assertNull(Aliases.native("Saori", listOf(a("さおりん", null, false, "Search hint"), a("サオリ", null, false, null)), "Saori"))
        assertNull(Aliases.native("Saori", listOf(a("Saori H", "ja", true)), "Saori"))
        assertEquals("早見沙織", Aliases.native("早見沙織", listOf(a("Saori Hayami", "en", true)), "Saori Hayami"))
        assertNull(Aliases.native("早見沙織", listOf(a("早見沙織", "ja", true)), "早見沙織"))
        assertEquals("柚木梨沙", Aliases.native("x", listOf(a("ＲＩＳＡ", "ja", true), a("柚木梨沙", "ja", true)), "risa"))
        assertNull(Aliases.native("x", listOf(a("柚木梨沙", "ja", true)), "柚木梨沙"))
    }
}
