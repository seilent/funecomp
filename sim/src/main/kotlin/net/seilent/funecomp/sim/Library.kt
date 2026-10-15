package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.LibRow
import net.seilent.funecomp.proto.gen.Enums
import java.io.File

class Track(
    val id: Long,
    val artist: String,
    val artists: List<String>,
    val album: String,
    val albumArtist: String,
    val title: String,
    val durationMs: Long,
    val folder: String,
)

class Library(val tracks: List<Track>) {
    val byId: Map<Long, Track> = tracks.associateBy { it.id }
    val favorites = LinkedHashSet<Long>()
    var rev = 1L

    private val albums: List<List<Track>> =
        tracks.groupBy { it.album to it.albumArtist }.values.toList()

    private fun albumOf(key: String): List<Track>? {
        val id = key.toULongOrNull()?.toLong() ?: return null
        val t = byId[id] ?: return null
        return albums.firstOrNull { a -> a.any { it.id == t.id } }
    }

    private fun albumRow(a: List<Track>) = LibRow(a[0].album, a.size.toLong(), a[0].id, java.lang.Long.toUnsignedString(a[0].id))

    private fun trackRow(t: Track) = LibRow(t.title, 1, t.id, java.lang.Long.toUnsignedString(t.id))

    fun rows(view: Int, parent: String): List<LibRow>? = when (view) {
        Enums.View.artists -> tracks.flatMap { it.artists }.distinct().map { a ->
            val ts = tracks.filter { a in it.artists }
            LibRow(a, ts.size.toLong(), ts[0].id, a)
        }
        Enums.View.albums -> albums.map { albumRow(it) }
        Enums.View.songs -> tracks.map { trackRow(it) }
        Enums.View.artistAlbums -> albums.filter { a -> a.any { parent in it.artists } }.map { albumRow(it) }
            .takeIf { tracks.any { parent in it.artists } }
        Enums.View.albumTracks -> albumOf(parent)?.map { trackRow(it) }
        Enums.View.playlists -> emptyList()
        Enums.View.folders -> {
            val under = under(parent)
            if (parent.isNotEmpty() && under.isEmpty()) {
                null
            } else {
                subfolders(parent).map { path ->
                    val ts = under(path)
                    LibRow(path.substringAfterLast('/'), ts.size.toLong(), ts[0].id, path)
                } + own(parent).map { trackRow(it) }
            }
        }
        Enums.View.favorites -> favorites.mapNotNull { byId[it] }.map { trackRow(it) }
        else -> null
    }

    private fun fold(s: String) = s.lowercase()

    private fun under(parent: String): List<Track> {
        val prefix = if (parent.isEmpty()) "" else "$parent/"
        return tracks.filter { parent.isEmpty() || it.folder == parent || it.folder.startsWith(prefix) }
    }

    private fun subfolders(parent: String): List<String> {
        val prefix = if (parent.isEmpty()) "" else "$parent/"
        return under(parent).filter { it.folder != parent }.map { prefix + it.folder.removePrefix(prefix).substringBefore('/') }
            .distinct().sortedBy { fold(it.substringAfterLast('/')) }
    }

    private fun own(parent: String) = tracks.filter { it.folder == parent }.sortedBy { fold(it.title) }

    private fun folderOrder(parent: String): List<Track> = subfolders(parent).flatMap { folderOrder(it) } + own(parent)

    fun tracksOf(view: Int, parent: String): List<Long>? = when (view) {
        Enums.View.artists -> tracks.map { it.id }
        Enums.View.albums, Enums.View.songs -> tracks.map { it.id }
        Enums.View.artistAlbums -> tracks.filter { parent in it.artists }.map { it.id }.takeIf { it.isNotEmpty() }
        Enums.View.albumTracks -> albumOf(parent)?.map { it.id }
        Enums.View.folders -> folderOrder(parent).map { it.id }.takeIf { parent.isEmpty() || it.isNotEmpty() }
        Enums.View.favorites -> favorites.toList()
        Enums.View.playlists -> emptyList()
        else -> null
    }

    companion object {
        fun hash64(s: String): Long {
            var h = -0x340d631b7bdddcdbL
            for (b in s.toByteArray(Charsets.UTF_8)) {
                h = h xor (b.toLong() and 0xFF)
                h *= 0x100000001b3L
            }
            return h
        }

        fun split(raw: String, albumArtist: String): List<String> {
            val text = raw.trim()
            if (text.isEmpty()) return emptyList()
            if (albumArtist.isNotEmpty() && text.equals(albumArtist, ignoreCase = true)) return listOf(text)
            return text.replace(" / ", "; ").replace(", ", "; ").split("; ").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        }

        fun track(artist: String, album: String, title: String, no: Int, durationMs: Long, albumArtist: String = artist): Track {
            val folder = "${albumArtist.trim()}/${album.trim()}"
            val path = "$folder/%02d %s.flac".format(no, title)
            return Track(hash64(path), artist, split(artist, albumArtist), album, albumArtist, title, durationMs, folder)
        }

        fun load(f: File): Library {
            var no = 0
            val tracks = f.readLines().filter { it.isNotBlank() }.map { line ->
                val p = line.split('\t')
                require(p.size >= 4) { "${f.name}: needs artist, album, title, duration ms: $line" }
                track(p[0], p[1], p[2], ++no, p[3].toLong(), p.getOrElse(4) { p[0] })
            }
            return Library(tracks)
        }

        fun synthetic(): Library {
            val albums = listOf(
                Triple("Utada Hikaru", "First Love", listOf("Automatic", "Movin' on without you", "In My Room", "First Love", "Never Let Go")),
                Triple("Utada Hikaru", "BADモード", listOf("BADモード", "君に夢中", "One Last Kiss", "PINK BLOOD")),
                Triple("宇多田ヒカル", "初恋", listOf("Play A Love Song", "あなた", "初恋", "誓い")),
                Triple("Ichiko Aoba", "Windswept Adan", listOf("Prologue", "Porcelain", "Pilgrimage", "Sagu Palm's Song")),
                Triple("きのこ帝国", "フェイクワールドワンダーランド", listOf("東京", "クロノスタシス", "ラストデイ")),
                Triple("Sigur Rós", "Ágætis byrjun", listOf("Svefn-g-englar", "Starálfur", "Flugufrelsarinn", "Ný batterí")),
                Triple("Yorushika", "だから僕は音楽を辞めた", listOf("八月、某、月明かり", "藍二乗", "爆弾魔")),
                Triple("坂本龍一", "async", listOf("andata", "disintegration", "solari", "ZURE")),
            )
            val out = ArrayList<Track>()
            var n = 0
            for ((artist, album, titles) in albums) {
                titles.forEachIndexed { i, t -> out += track(artist, album, t, i + 1, 180_000L + 17_000L * ((n++ * 7) % 11)) }
            }
            out += track("Utada Hikaru, Skrillex", "Hikari", "Face My Fears", 1, 220_000, "Utada Hikaru")
            out += track("Yorushika / n-buna", "盗作", "春ひさぎ", 1, 240_000, "Yorushika")
            out += track("Kendrick Lamar; SZA", "Black Panther", "All The Stars", 1, 232_000, "Various Artists")
            return Library(out)
        }
    }
}
