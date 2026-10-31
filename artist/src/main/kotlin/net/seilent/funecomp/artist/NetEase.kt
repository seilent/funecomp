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
    private val log: (String) -> Unit = {},
) {
    private class Song(val id: Long, val names: List<String>, val dt: Long)

    private sealed interface Lyric {
        class Body(val found: Lrclib.Found) : Lyric

        data object Skip : Lyric

        data object End : Lyric
    }

    data class Swept(val files: Int, val rejected: Int, val errors: Int = 0)

    private val files = FileStore(dir)

    private val api = Api(http, "funecomp/$version (https://github.com/seilent/funecomp)", RateLimiter(INTERVAL_MS, mono), sleep, tries = TRIES, jitter = { random.nextDouble() * JITTER })

    @Synchronized
    fun get(q: Lrclib.Query, store: LyricStore = files): Lrclib.Found? {
        if (!enabled()) return null
        val d = q.durationMs ?: return null
        val hit = "${q.key}.ne.lrc"
        val txt = "${q.key}.ne.txt"
        val miss = "${q.key}.ne.miss"
        store.read(hit)?.let { h ->
            val t = cached(h)
            if (t != null && !rejected(store, q.key, t)) return Lrclib.Found(t, true, LyricSources.NETEASE)
            store.delete(hit)
        }
        store.read(miss)?.let { m ->
            val lines = m.lines()
            if (lines.getOrNull(1) == RULE && lines.getOrNull(2) == NAMES && clock() - (lines[0].trim().toLongOrNull() ?: 0) in 0 until Lrclib.MISS_MS) {
                val t = store.read(txt) ?: return null
                val plain = cached(t)
                if (plain != null && !rejected(store, q.key, plain)) return Lrclib.Found(plain, false, LyricSources.NETEASE)
                if (plain != null) {
                    store.delete(txt)
                    store.delete(miss)
                }
            }
        }
        val found = lookup(q, d)
        when {
            found == null -> {
                store.delete(txt)
                store.save(miss, "${clock()}\n$RULE\n$NAMES")
            }
            found.synced -> {
                store.save(hit, "$RULE\n${found.text}")
                store.delete(txt)
                store.delete(miss)
            }
            else -> {
                store.save(txt, "$RULE\n${found.text}")
                store.save(miss, "${clock()}\n$RULE\n$NAMES")
            }
        }
        return found
    }

    private fun cached(text: String): String? = if (text.startsWith("$RULE\n")) text.substring(RULE.length + 1) else null

    private fun rejected(store: LyricStore, key: String, text: String): Boolean {
        val v = NeMix.check(text) as? NeMix.Verdict.Reject ?: return false
        store.save("$key.ne.bad", "${clock()}\n${v.reason}")
        log("nemix bad $key ${v.reason}")
        return true
    }

    @Synchronized
    fun sweep(): Swept {
        val names = dir.list()?.filter { it.endsWith(".ne.lrc") || it.endsWith(".ne.txt") }?.sorted() ?: emptyList()
        var bad = 0
        var errors = 0
        for (name in names) {
            try {
                val text = files.read(name) ?: continue
                val nl = text.indexOf('\n')
                if (nl < 0 || !ruled(text.substring(0, nl))) continue
                val key = name.substring(0, name.length - ".ne.lrc".length)
                if (!rejected(files, key, text.substring(nl + 1))) continue
                files.delete(name)
                if (name.endsWith(".ne.txt")) files.delete("$key.ne.miss")
                bad++
            } catch (e: LyricsFileException) {
                errors++
                log("nemix sweep $name: $e")
            }
        }
        log("nemix sweep ${names.size} $bad${if (errors > 0) " errors $errors" else ""}")
        return Swept(names.size, bad, errors)
    }

    fun marked(key: String): String? = files.read("$key.ne.bad")?.substringAfter('\n', "")

    @Synchronized
    fun unmark(keys: Collection<String>) = keys.forEach { files.delete("$it.ne.bad") }

    private fun ruled(line: String) = line.startsWith("rule=") && line.length > 5 && line.substring(5).all { it in '0'..'9' }

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
        var asked = 0
        var strict = false
        for (s in songs) {
            if (abs(s.dt - durationMs) > MATCH_MS || s.names.none { core(it) == tc }) continue
            if (asked == CAP) return null
            asked++
            val found = when (val l = lyric(s.id)) {
                is Lyric.Body -> l.found
                Lyric.Skip -> continue
                Lyric.End -> return null
            }
            val v = NeMix.check(found.text, strict) as? NeMix.Verdict.Reject ?: return found
            strict = true
            log("netease reject ${s.id} ${v.reason} ${v.counts}")
        }
        return null
    }

    private fun search(q: String): List<Song> {
        val j = post(SEARCH, mapOf("s" to q, "type" to "1", "limit" to "10", "offset" to "0")) ?: return emptyList()
        return j["result"]["songs"].list.mapNotNull { s ->
            val id = s["id"].long ?: return@mapNotNull null
            val names = listOf(s["name"].str ?: "") + s["alia"].list.mapNotNull { it.str } + s["tns"].list.mapNotNull { it.str }
            Song(id, names, s["dt"].long ?: 0)
        }
    }

    private fun lyric(id: Long): Lyric {
        val j = post(LYRIC, mapOf("id" to id.toString(), "lv" to "1", "tv" to "-1")) ?: return Lyric.End
        if (j["nolyric"].bool == true) return Lyric.End
        if (j["uncollected"].bool == true) return Lyric.Skip
        val raw = j["lrc"]["lyric"].str ?: ""
        if ("纯音乐" in raw) return Lyric.End
        return text(raw)?.let { Lyric.Body(it) } ?: Lyric.Skip
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
        const val CAP = 3
        private const val OK = 200L
        private const val RULE = "rule=1"
        private const val NAMES = "names=2"
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
        internal val LABELS = setOf(
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
