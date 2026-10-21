package net.seilent.funecomp.artist

import java.io.File
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

class NetEase(
    private val dir: File,
    http: Http,
    version: String,
    private val enabled: () -> Boolean,
    private val clock: () -> Long = { System.currentTimeMillis() },
    sleep: (Long) -> Unit = { Thread.sleep(it) },
    mono: () -> Long = { System.nanoTime() / 1_000_000 },
    random: Random = Random.Default,
) {
    private class Song(val id: Long, val names: List<String>, val dt: Long)

    private val api = Api(http, "funecomp/$version (https://github.com/seilent/funecomp)", RateLimiter(INTERVAL_MS, mono), sleep, tries = TRIES, jitter = { random.nextDouble() * JITTER })

    @Synchronized
    fun get(q: Lrclib.Query): Lrclib.Found? {
        if (!enabled()) return null
        val d = q.durationMs ?: return null
        val hit = File(dir, "${q.key}.ne.lrc")
        val txt = File(dir, "${q.key}.ne.txt")
        val miss = File(dir, "${q.key}.ne.miss")
        if (hit.isFile) {
            val t = cached(hit)
            if (t != null) return Lrclib.Found(t, true, LyricSources.NETEASE)
            LyricFiles.delete(hit)
        }
        if (miss.isFile) {
            val lines = LyricFiles.read(miss).lines()
            if (lines.getOrNull(1) == RULE && clock() - (lines[0].trim().toLongOrNull() ?: 0) in 0 until Lrclib.MISS_MS) {
                if (!txt.isFile) return null
                cached(txt)?.let { return Lrclib.Found(it, false, LyricSources.NETEASE) }
            }
        }
        val found = lookup(q, d)
        dir.mkdirs()
        when {
            found == null -> {
                LyricFiles.delete(txt)
                LyricFiles.save(miss, "${clock()}\n$RULE")
            }
            found.synced -> {
                LyricFiles.save(hit, "$RULE\n${found.text}")
                LyricFiles.delete(txt)
                LyricFiles.delete(miss)
            }
            else -> {
                LyricFiles.save(txt, "$RULE\n${found.text}")
                LyricFiles.save(miss, "${clock()}\n$RULE")
            }
        }
        return found
    }

    private fun cached(f: File): String? = LyricFiles.read(f).let { if (it.startsWith("$RULE\n")) it.substring(RULE.length + 1) else null }

    private fun lookup(q: Lrclib.Query, durationMs: Long): Lrclib.Found? {
        val pair = LyricNames.split(q.track, q.artist)
        val title = pair?.first ?: LyricNames.tails(q.track)
        val artist = pair?.second ?: q.artists.firstOrNull { it.isNotBlank() } ?: q.artist
        val tc = core(title)
        if (tc.isEmpty()) return null
        val songs = search("$title $artist").toMutableList()
        if (songs.none { abs(it.dt - durationMs) <= NEAR_MS }) {
            val ids = songs.map { it.id }.toSet()
            songs += search(title).filter { it.id !in ids }
        }
        val pick = songs.firstOrNull { s -> abs(s.dt - durationMs) <= MATCH_MS && s.names.any { core(it) == tc } } ?: return null
        return lyric(pick.id)
    }

    private fun search(q: String): List<Song> {
        val j = post(SEARCH, mapOf("s" to q, "type" to "1", "limit" to "10", "offset" to "0")) ?: return emptyList()
        return j["result"]["songs"].list.mapNotNull { s ->
            val id = s["id"].long ?: return@mapNotNull null
            val names = listOf(s["name"].str ?: "") + s["alia"].list.mapNotNull { it.str } + s["tns"].list.mapNotNull { it.str }
            Song(id, names, s["dt"].long ?: 0)
        }
    }

    private fun lyric(id: Long): Lrclib.Found? {
        val j = post(LYRIC, mapOf("id" to id.toString(), "lv" to "1", "tv" to "-1")) ?: return null
        if (j["nolyric"].bool == true || j["uncollected"].bool == true) return null
        return text(j["lrc"]["lyric"].str ?: "")
    }

    private fun post(url: String, form: Map<String, String>): J? {
        val r = api.post(url, form, HEADERS) ?: return null
        val j = try {
            J.parse(r.text)
        } catch (e: IllegalArgumentException) {
            throw IOException("${e.message}: $url")
        }
        val code = j["code"].long
        if (code != OK) throw IOException("netease code $code")
        return j
    }

    companion object {
        const val SEARCH = "https://music.163.com/api/cloudsearch/pc"
        const val LYRIC = "https://music.163.com/api/song/lyric"
        private const val INTERVAL_MS = 1500L
        private const val JITTER = 0.3
        private const val TRIES = 4
        private const val NEAR_MS = 5000L
        private const val MATCH_MS = 3000L
        private const val OK = 200L
        private const val RULE = "rule=1"
        private const val MIN_LINES = 5
        private val HEADERS = mapOf("Referer" to "https://music.163.com/", "Content-Type" to "application/x-www-form-urlencoded")

        private const val S = LyricNames.S
        private val BR = Regex("[(（\\[【《〈<].*?[)）\\]】》〉>]")
        private val CUT = Regex("$S+-$S+|$S+/$S+|／|｜|\\|")
        private val NOISE = Regex("(?i)(を?歌ってみた|歌いました|self cover|cover|ver\\.[^${LyricNames.SPACE}]*|MV)$")
        private val EDGE = Regex("^$S+|$S+$")
        private val STAMPS = Regex("(?s)^((?:\\[[0-9]+:[0-9]+(?:[.:][0-9]+)?\\])+)(.*)$")
        private val FIRST = Regex("^\\[([0-9]+):([0-9]+)")
        private val COLON = Regex("\\[([0-9]+):([0-9]{1,2}):([0-9]{1,3})\\]")
        private val LABELS = setOf(
            "作词", "作詞", "作曲", "编曲", "編曲", "词", "詞", "曲", "词曲", "制作人", "混音", "母带", "和声", "吉他", "贝斯", "鼓",
            "弦乐", "录音", "监制", "出品", "发行", "原唱", "歌唱", "演唱", "演唱者", "唄", "翻译", "打击乐", "音乐总监", "op", "sp",
            "lyrics", "composer", "arranger",
        )

        fun core(s: String): String {
            val n = BR.replace(Normalizer.normalize(s, Normalizer.Form.NFKC), " ")
            val cut = CUT.split(n, 2)[0]
            return LyricNames.fold(NOISE.replace(cut.trim(), "")).replace(" ", "")
        }

        fun text(raw: String): Lrclib.Found? {
            if (raw.isEmpty()) return null
            val lines = raw.split("\n").map { it.removeSuffix("\r") }
            if (lines.any { "纯音乐" in it }) return null
            var i = 0
            while (i < lines.size && lines[i].let { blank(body(it)) || credit(body(it)) }) i++
            val kept = lines.drop(i).map { l -> COLON.replace(l) { m -> "[${m.groupValues[1]}:${m.groupValues[2]}.${m.groupValues[3]}]" } }
            var stamped = 0
            var text = 0
            val secs = HashSet<Long>()
            for (l in kept) {
                val m = STAMPS.find(l)
                if (blank(m?.groupValues?.get(2) ?: l)) continue
                text++
                val f = m?.let { FIRST.find(it.groupValues[1]) } ?: continue
                stamped++
                secs += f.groupValues[1].toLong() * 60 + f.groupValues[2].toLong()
            }
            if (stamped >= MIN_LINES && secs.size >= MIN_LINES) return Lrclib.Found(kept.joinToString("\n"), true, LyricSources.NETEASE)
            if (text > 0) return Lrclib.Found(kept.joinToString("\n") { trim(body(it)) }, false, LyricSources.NETEASE)
            return null
        }

        private fun body(l: String): String = STAMPS.find(l)?.groupValues?.get(2) ?: l

        private fun trim(s: String): String = EDGE.replace(s, "")

        private fun blank(s: String): Boolean = trim(s).isEmpty()

        private fun credit(text: String): Boolean {
            val t = trim(text)
            val i = listOf(t.indexOf(':'), t.indexOf('：')).filter { it >= 0 }.minOrNull() ?: return false
            if (i == 0) return false
            val label = t.substring(0, i)
            return !blank(label) && label.split('/').all { trim(it).lowercase(Locale.ROOT) in LABELS }
        }
    }
}
