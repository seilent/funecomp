package net.seilent.funecomp.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelMatchTest {
    private val http = Fixtures()
    private val time = FakeTime()
    private val mb by lazy {
        http.now = { time.now }
        MusicBrainz(Api(http, "funecomp/0.1.0 (github@seilent.net)", RateLimiter(1000) { time.now }, time.sleep))
    }

    private fun page(tag: String): List<YouTube.Channel> {
        val url = "https://www.youtube.com/results?search_query=${Api.enc(tag)}&sp=EgIQAg%253D%253D"
        val file = Fixtures.text("urls.tsv").lines().first { it.startsWith("$url\t") }.substringAfter('\t')
        return YouTube.channels(Fixtures.text(file))!!
    }

    private fun run(tag: String, results: List<YouTube.Channel> = page(tag)): ChannelMatch.Match? {
        val e = ChannelMatch.equal(tag, results) ?: return null
        val links = ChannelMatch.lookups(e).associate { it.id to mb.byUrl(YouTube.channelUrl(it.id)) }
        return ChannelMatch.decide(tag, e, links)
    }

    private fun ch(id: String, title: String, verified: Boolean = false) = YouTube.Channel(id, title, "https://yt3.ggpht.com/$id=s88", verified)

    private fun link(id: String, name: String, sort: String = name) = Linked(id, name, sort)

    @Test
    fun theOutcomeTable() {
        val rows = listOf(
            "Suisei Channel" to ("UC5CwaMl1eIgY8h02uZw7u8A" to "8eeb59f4-0071-4261-b1f8-91d8294622d2"),
            "chelly Ch." to ("UC0OvtxMEg001QRAyOk9qZ8Q" to "4dfe9c0b-7602-4189-9919-151d493d1028"),
            "Aoi ch." to ("UC3Ruo_5doyu514PesWGvCAg" to "03b96523-db37-4e85-ac9d-b7ceb6926e9b"),
            "Chise Itsuki" to ("UC1FnOvfv5G6EkFIdUmbGGPg" to "61d436ef-099b-4e25-b936-de7bda576565"),
            "Kano" to ("UCX0eAghP7jrWHWfYBAhrnVw" to "2bf3971e-8595-4bd2-aace-8a71c42afb66"),
        )
        for ((tag, want) in rows) {
            val m = run(tag)
            assertEquals(tag, want, m?.let { it.channel.id to it.mbid })
        }
        for (tag in listOf("Inari Iroha", "NÄO Official Channel", "Kapi", "Memento Mori")) assertNull(tag, run(tag))
        assertTrue("missing ${http.missing}", http.missing.isEmpty())
    }

    @Test
    fun thePassThatDecides() {
        assertEquals(ChannelMatch.Pass.WHOLE, ChannelMatch.equal("Suisei Channel", page("Suisei Channel"))!!.pass)
        assertEquals(listOf("UC5CwaMl1eIgY8h02uZw7u8A", "UC0PIE1PuUtJ_JADIi4eOqNw", "UCA4CUIySvg5p-vYJ_3KfLrA"), ChannelMatch.equal("Suisei Channel", page("Suisei Channel"))!!.channels.map { it.id })
        assertEquals(ChannelMatch.Pass.PART, ChannelMatch.equal("Chise Itsuki", page("Chise Itsuki"))!!.pass)
        assertEquals(listOf("UCWIPfdcux1WxuX5yZLPJDww"), ChannelMatch.equal("Inari Iroha", page("Inari Iroha"))!!.channels.map { it.id })
        assertTrue(page("NÄO Official Channel").first { it.id == "UCnxkvT_b7UtqYcpF6ytFNyg" }.verified)
        assertEquals(ChannelMatch.Pass.KANA, ChannelMatch.equal("すいせい", listOf(ch("UCa", "スイセイ")))!!.pass)
    }

    @Test
    fun theVerifiedBadgePicksAmongLinkedChannelsInAnyOrder() {
        val list = page("Suisei Channel")
        val reordered = list.sortedBy { if (it.id == "UC0PIE1PuUtJ_JADIi4eOqNw") 0 else 1 }
        assertEquals("UC0PIE1PuUtJ_JADIi4eOqNw", reordered.first().id)
        assertEquals("UC5CwaMl1eIgY8h02uZw7u8A", run("Suisei Channel", reordered)!!.channel.id)
    }

    @Test
    fun falseMatches() {
        val x = "UCxxxxxxxxxxxxxxxxxxxxxx"
        val y = "UCyyyyyyyyyyyyyyyyyyyyyy"
        val linked = mapOf(x to listOf(link("11111111-1111-1111-1111-111111111111", "X")), y to listOf(link("22222222-2222-2222-2222-222222222222", "X")))
        assertNull(ChannelMatch.equal("X Channel", listOf(ch(x, "X", true))))
        assertNull(ChannelMatch.equal("X", listOf(ch(x, "X - トピック"))))
        val part = ChannelMatch.equal("Inari Iroha", listOf(ch(x, "稲荷いろはInari Iroha", true)))!!
        assertEquals(ChannelMatch.Pass.PART, part.pass)
        assertNull(ChannelMatch.decide("Inari Iroha", part, emptyMap()))
        val two = ChannelMatch.equal("X", listOf(ch(x, "X"), ch(y, "x")))!!
        assertNull(ChannelMatch.decide("X", two, linked))
        val badge = ChannelMatch.equal("X", listOf(ch(x, "X", true), ch(y, "x")))!!
        assertNull(ChannelMatch.decide("X", badge, emptyMap()))
        assertEquals(x, ChannelMatch.decide("X", badge, linked)!!.channel.id)
        assertNull(ChannelMatch.equal("X", listOf(ch(x, "Xちゃんねる X"))))
        val parts = ChannelMatch.equal("X", listOf(ch(y, "X / ワイ")))!!
        assertEquals(ChannelMatch.Pass.PART, parts.pass)
        assertNull(ChannelMatch.decide("X", parts, emptyMap()))
        assertEquals(y, ChannelMatch.decide("X", parts, linked)!!.channel.id)
        val twoParts = ChannelMatch.equal("X", listOf(ch(x, "X / エックス"), ch(y, "X / ワイ")))!!
        assertNull(ChannelMatch.decide("X", twoParts, linked))
    }

    @Test
    fun theMbidOfAChannelLinkedToSeveralArtists() {
        val a = link("33333333-3333-3333-3333-333333333333", "鹿乃まほろ", "Kano, Mahoro")
        val b = link("44444444-4444-4444-4444-444444444444", "鹿乃", "Kano")
        assertEquals(b.id, ChannelMatch.mbid("Kano", listOf(a, b)))
        assertEquals(a.id, ChannelMatch.mbid("Kano Mahoro", listOf(a, b)))
        assertNull(ChannelMatch.mbid("Someone", listOf(a, b)))
        assertEquals(a.id, ChannelMatch.mbid("Someone", listOf(a)))
    }

    @Test
    fun titleParts() {
        assertEquals(listOf("伊月知世", "CHISE ITSUKI"), ChannelMatch.parts("伊月知世 / CHISE ITSUKI"))
        assertEquals(listOf("稲荷いろはInari Iroha", "稲荷いろは", "Inari Iroha"), ChannelMatch.parts("稲荷いろはInari Iroha"))
        assertEquals(listOf("月城アオイ - Aoi Ch."), ChannelMatch.parts("月城アオイ - Aoi Ch.【Vsinger】"))
        assertEquals(listOf("天神 子兎音 Tenjin Kotone", "天神 子兎音", "Tenjin Kotone"), ChannelMatch.parts("天神 子兎音 Tenjin Kotone"))
        assertEquals(listOf("A", "B"), ChannelMatch.parts("A｜B"))
    }

    @Test
    fun theBareWordIsNeverAnAllowedTitle() {
        assertEquals(listOf("Suisei Channel"), ChannelMatch.allowed("Suisei Channel"))
        assertEquals(listOf("空星きらめ/Sorahoshi Kirame【にじさんじ】", "空星きらめ/Sorahoshi Kirame", "空星きらめ", "Sorahoshi Kirame"), ChannelMatch.allowed("空星きらめ/Sorahoshi Kirame【にじさんじ】"))
    }
}
