package net.seilent.funecomp.artist

object ChannelMatch {
    enum class Pass { WHOLE, KANA, PART }

    class Equal(val pass: Pass, val channels: List<YouTube.Channel>)

    class Match(val channel: YouTube.Channel, val mbid: String?)

    const val MAX_LINKS = 4

    private val separators = listOf(" / ", "/", "／", "|", "｜")
    private val tail = Regex("\\s*(【[^【】]*】|\\[[^\\[\\]]*])\\s*$")

    fun allowed(tag: String): List<String> {
        val bare = Names.bareOf(tag)
        return (listOf(tag.trim()) + Names.variants(tag)).filter { it.isNotEmpty() && it != bare }.distinct()
    }

    fun equal(tag: String, results: List<YouTube.Channel>): Equal? {
        val n = allowed(tag)
        val fold = n.mapTo(HashSet()) { Names.fold(it) }
        val kana = n.mapTo(HashSet()) { Names.kana(it) }
        results.filter { Names.fold(it.title) in fold }.takeIf { it.isNotEmpty() }?.let { return Equal(Pass.WHOLE, it) }
        results.filter { Names.kana(it.title) in kana }.takeIf { it.isNotEmpty() }?.let { return Equal(Pass.KANA, it) }
        results.filter { c -> parts(c.title).any { Names.fold(it) in fold } }.takeIf { it.isNotEmpty() }?.let { return Equal(Pass.PART, it) }
        return null
    }

    fun lookups(e: Equal): List<YouTube.Channel> =
        if (e.pass == Pass.PART) e.channels.takeIf { it.size == 1 } ?: emptyList() else e.channels.take(MAX_LINKS)

    fun decide(tag: String, e: Equal, links: Map<String, List<Linked>>): Match? {
        val linked = lookups(e).filter { links[it.id].orEmpty().isNotEmpty() }
        val c = when {
            e.pass == Pass.PART -> linked.singleOrNull()
            linked.size == 1 -> linked[0]
            else -> linked.filter { it.verified }.singleOrNull()
        } ?: return null
        return Match(c, mbid(tag, links.getValue(c.id)))
    }

    fun mbid(tag: String, artists: List<Linked>): String? {
        artists.singleOrNull()?.let { return it.id }
        val fold = allowed(tag).mapTo(HashSet()) { Names.fold(it) }
        return artists.filter { Names.fold(it.name) in fold || Names.fold(it.sortName.replace(", ", " ")) in fold }.singleOrNull()?.id
    }

    fun parts(title: String): List<String> {
        var segs = listOf(title)
        for (s in separators) segs = segs.flatMap { it.split(s) }
        return segs.map { strip(it) }.filter { it.isNotEmpty() }.flatMap { listOf(it) + split(it) }.distinct()
    }

    private fun strip(s: String): String {
        var out = s.trim()
        while (true) {
            val m = tail.find(out) ?: return out
            out = out.substring(0, m.range.first).trim()
        }
    }

    private fun cjk(c: Char): Boolean = c == 'ー' || Character.UnicodeScript.of(c.code) in CJK

    private val CJK = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL)

    private fun split(s: String): List<String> {
        val cuts = ArrayList<Pair<Int, Int>>()
        for (i in s.indices) {
            if (s[i].isWhitespace()) continue
            var j = i + 1
            while (j < s.length && s[j].isWhitespace()) j++
            if (j >= s.length) break
            if ((cjk(s[i]) && Names.latin(s[j])) || (Names.latin(s[i]) && cjk(s[j]))) cuts += i to j
        }
        val (i, j) = cuts.singleOrNull() ?: return emptyList()
        return listOf(s.substring(0, i + 1).trim(), s.substring(j).trim())
    }
}
