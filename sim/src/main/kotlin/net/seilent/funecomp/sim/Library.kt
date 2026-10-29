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
    val file: String,
    val mbids: List<String> = artists.map { "" },
    val genre: String = "",
    val no: Int = 1,
    val service: String? = null,
    val serviceKey: Long? = null,
) {
    fun mbidOf(name: String): String? = mbids.getOrNull(artists.indexOf(name))?.takeIf { it.isNotEmpty() }
}

private class Album(val artist: String, val album: String, val genre: String, val titles: List<String>)

class Library(val tracks: List<Track>, val services: List<Track> = emptyList()) {
    val byId: Map<Long, Track> = (tracks + services).associateBy { it.id }
    val favorites = LinkedHashSet<Long>()
    var rev = 1L
    var snapRev = 1L

    private val albums: List<List<Track>> =
        tracks.groupBy { it.album to it.albumArtist }.values.toList()

    private fun albumOf(key: String): List<Track>? {
        val id = key.toULongOrNull()?.toLong() ?: return null
        val t = byId[id] ?: return null
        return albums.firstOrNull { a -> a.any { it.id == t.id } }
    }

    fun albumKey(track: Long): String? = albums.firstOrNull { a -> a.any { it.id == track } }?.let { java.lang.Long.toUnsignedString(it[0].id) }

    private fun albumRow(a: List<Track>) = LibRow(a[0].album, a.size.toLong(), a[0].id, java.lang.Long.toUnsignedString(a[0].id))

    private fun trackRow(t: Track) = LibRow(t.title, 1, t.id, java.lang.Long.toUnsignedString(t.id))

    fun rows(view: Int, parent: String): List<LibRow>? = when (view) {
        Enums.View.artists -> tracks.flatMap { it.artists }.distinct().map { a ->
            val ts = tracks.filter { a in it.artists }
            LibRow(a, ts.size.toLong(), ts[0].id, a, ts[0].mbidOf(a))
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
        Enums.View.genres -> if (parent.isEmpty()) {
            genres().map { g ->
                val ts = tracks.filter { it.genre == g }
                LibRow(g, ts.size.toLong(), ts[0].id, g)
            }
        } else {
            tracks.filter { it.genre == parent }.map { trackRow(it) }.takeIf { it.isNotEmpty() }
        }
        else -> null
    }

    private fun genres(): List<String> = tracks.map { it.genre }.filter { it.isNotEmpty() }.distinct().sortedWith { a, b -> folderCompare(a, b) }

    private fun under(parent: String): List<Track> {
        val prefix = if (parent.isEmpty()) "" else "$parent/"
        return tracks.filter { parent.isEmpty() || it.folder == parent || it.folder.startsWith(prefix) }
    }

    private fun subfolders(parent: String): List<String> {
        val prefix = if (parent.isEmpty()) "" else "$parent/"
        return under(parent).filter { it.folder != parent }.map { prefix + it.folder.removePrefix(prefix).substringBefore('/') }
            .distinct().sortedWith { a, b -> folderCompare(a.substringAfterLast('/'), b.substringAfterLast('/')) }
    }

    private fun own(parent: String) = tracks.filter { it.folder == parent }.sortedWith { a, b -> folderCompare(a.file, b.file) }

    private fun folderOrder(parent: String): List<Track> = subfolders(parent).flatMap { folderOrder(it) } + own(parent)

    fun tracksOf(view: Int, parent: String): List<Long>? = when (view) {
        Enums.View.artists -> tracks.map { it.id }
        Enums.View.albums, Enums.View.songs -> tracks.map { it.id }
        Enums.View.artistAlbums -> tracks.filter { parent in it.artists }.map { it.id }.takeIf { it.isNotEmpty() }
        Enums.View.albumTracks -> albumOf(parent)?.map { it.id }
        Enums.View.folders -> folderOrder(parent).map { it.id }.takeIf { parent.isEmpty() || it.isNotEmpty() }
        Enums.View.favorites -> favorites.toList()
        Enums.View.playlists -> emptyList()
        Enums.View.genres -> if (parent.isEmpty()) emptyList() else tracks.filter { it.genre == parent }.map { it.id }.takeIf { it.isNotEmpty() }
        else -> null
    }

    companion object {
        private fun lower(b: Byte): Int = (b.toInt() and 0xFF).let { if (it in 'A'.code..'Z'.code) it + 32 else it }

        fun folderCompare(a: String, b: String): Int {
            val x = a.toByteArray(Charsets.UTF_8)
            val y = b.toByteArray(Charsets.UTF_8)
            for (i in 0 until minOf(x.size, y.size)) {
                val c = lower(x[i]) - lower(y[i])
                if (c != 0) return c
            }
            if (x.size != y.size) return x.size - y.size
            return java.util.Arrays.compareUnsigned(x, y)
        }

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

        fun track(artist: String, album: String, title: String, no: Int, durationMs: Long, albumArtist: String = artist, mbids: List<String> = emptyList(), genre: String = "", service: String? = null, serviceKey: Long? = null): Track {
            val folder = "${albumArtist.trim()}/${album.trim()}"
            val file = "%02d %s.flac".format(no, title)
            val names = split(artist, albumArtist)
            val id = if (service == null) hash64("$folder/$file") else hash64("$service:${java.lang.Long.toUnsignedString(serviceKey ?: 0)}")
            return Track(id, artist, names, album, albumArtist, title, durationMs, folder, file, names.indices.map { mbids.getOrElse(it) { "" }.trim() }, genre.trim(), no, service, serviceKey)
        }

        fun load(f: File): Library {
            val no = HashMap<Pair<String, String>, Int>()
            val tracks = f.readLines().filter { it.isNotBlank() }.map { line ->
                val p = line.split('\t')
                require(p.size >= 4) { "${f.name}: needs artist, album, title, duration ms: $line" }
                val albumArtist = p.getOrElse(4) { p[0] }.ifEmpty { p[0] }
                val n = no.merge(albumArtist.trim() to p[1].trim(), 1, Int::plus)!!
                val service = p.getOrNull(7)?.trim()?.ifEmpty { null }
                val key = p.getOrNull(8)?.trim()?.ifEmpty { null }?.toULong()?.toLong()
                require(service == null || key != null) { "${f.name}: a service row needs a service key: $line" }
                track(p[0], p[1], p[2], n, p[3].toLong(), albumArtist, p.getOrNull(5)?.split(';').orEmpty(), p.getOrElse(6) { "" }, service, key)
            }
            return Library(tracks.filter { it.service == null }, tracks.filter { it.service != null })
        }

        fun synthetic(): Library {
            val albums = listOf(
                Album("Utada Hikaru", "First Love", "J-Pop", listOf("Automatic", "Movin' on without you", "In My Room", "First Love", "Never Let Go")),
                Album("Utada Hikaru", "BADモード", "J-Pop", listOf("BADモード", "君に夢中", "One Last Kiss", "PINK BLOOD")),
                Album("宇多田ヒカル", "初恋", "J-pop", listOf("Play A Love Song", "あなた", "初恋", "誓い")),
                Album("Ichiko Aoba", "Windswept Adan", "Folk", listOf("Prologue", "Porcelain", "Pilgrimage", "Sagu Palm's Song")),
                Album("きのこ帝国", "フェイクワールドワンダーランド", "Rock", listOf("東京", "クロノスタシス", "ラストデイ")),
                Album("Sigur Rós", "Ágætis byrjun", "Post-Rock", listOf("Svefn-g-englar", "Starálfur", "Flugufrelsarinn", "Ný batterí")),
                Album("Yorushika", "だから僕は音楽を辞めた", "Rock", listOf("八月、某、月明かり", "藍二乗", "爆弾魔")),
                Album("坂本龍一", "async", "Contemporary Classical and Electronic Ambient", listOf("andata", "disintegration", "solari", "ZURE")),
            )
            val out = ArrayList<Track>()
            var n = 0
            for (a in albums) {
                a.titles.forEachIndexed { i, t -> out += track(a.artist, a.album, t, i + 1, 180_000L + 17_000L * ((n++ * 7) % 11), genre = a.genre) }
            }
            out += track("Utada Hikaru, Skrillex", "Hikari", "Face My Fears", 1, 220_000, "Utada Hikaru", genre = "J-Pop")
            out += track("Yorushika / n-buna", "盗作", "春ひさぎ", 1, 240_000, "Yorushika", genre = "Rock")
            out += track("Kendrick Lamar; SZA", "Black Panther", "All The Stars", 1, 232_000, "Various Artists", genre = "Hip-Hop")
            return Library(out)
        }
    }
}
