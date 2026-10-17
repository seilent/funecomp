package net.seilent.funecomp.art

import net.seilent.funecomp.proto.gen.Err
import java.io.File

class Thumbs(private val dir: File) {
    data class Want(val slot: Int, val track: Long, val px: Int, val album: String?) {
        val key: String get() = key(track, px)
    }

    sealed interface Got {
        class Image(val crc: Long, val jpeg: ByteArray) : Got
        class Have(val crc: Long) : Got
        class Failed(val code: Int?) : Got
    }

    sealed interface Done {
        class Ready(val file: File) : Done
        class Retry(val afterMs: Long) : Done
        data object Miss : Done
        data object Dropped : Done
    }

    private var rev: Long? = null
    private val index = LinkedHashMap<String, Long>()
    private val misses = HashSet<String>()
    private val retries = HashMap<String, Int>()
    private val queued = arrayOfNulls<Want>(SLOTS)

    @Synchronized
    fun rev(libRev: Long) {
        if (libRev == rev) return
        rev = libRev
        index.clear()
        misses.clear()
        retries.clear()
        dir.listFiles { f -> f.name.startsWith(INDEX) && f.name != indexName(libRev) }?.forEach { it.delete() }
        val f = File(dir, indexName(libRev))
        if (f.isFile) f.readLines().forEach { l ->
            val p = l.split('\t')
            if (p.size == 2) p[1].toLongOrNull()?.let { index[p[0]] = it }
        }
    }

    @Synchronized
    fun session() {
        misses.clear()
        retries.clear()
    }

    @Synchronized
    fun cached(track: Long, px: Int): File? = index[key(track, px)]?.let { file(it) }?.takeIf { it.isFile }

    @Synchronized
    fun want(w: Want): File? {
        cached(w.track, w.px)?.let { return it }
        if (w.key in misses || rev == null) return null
        queued[w.slot] = w
        return null
    }

    @Synchronized
    fun next(): Want? {
        for (i in queued.indices) {
            val w = queued[i] ?: continue
            queued[i] = null
            if (cached(w.track, w.px) != null || w.key in misses) continue
            return w
        }
        return null
    }

    @Synchronized
    fun superseded(w: Want): Boolean = queued[w.slot].let { it != null && it != w }

    @Synchronized
    fun have(w: Want): Long? = w.album?.let { index[album(it, w.px)] }

    @Synchronized
    fun done(w: Want, got: Got): Done = when (got) {
        is Got.Image -> {
            dir.mkdirs()
            val f = file(got.crc)
            if (!f.isFile || f.length() != got.jpeg.size.toLong()) write(f, got.jpeg)
            put(w, got.crc)
            Done.Ready(f)
        }
        is Got.Have -> {
            val f = file(got.crc)
            if (f.isFile) {
                put(w, got.crc)
                Done.Ready(f)
            } else {
                Done.Dropped
            }
        }
        is Got.Failed -> when (got.code) {
            Err.notFound -> {
                misses += w.key
                Done.Miss
            }
            Err.busy -> {
                val n = (retries[w.key] ?: 0) + 1
                retries[w.key] = n
                if (n > BUSY_TRIES) Done.Dropped else Done.Retry(BUSY_RETRY_MS)
            }
            else -> Done.Dropped
        }
    }

    private fun put(w: Want, crc: Long) {
        index[w.key] = crc
        w.album?.let { index[album(it, w.px)] = crc }
        val r = rev ?: return
        write(File(dir, indexName(r)), index.entries.joinToString("") { "${it.key}\t${it.value}\n" }.toByteArray())
    }

    private fun write(f: File, bytes: ByteArray) {
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    private fun file(crc: Long) = File(dir, "%08x.jpg".format(crc))

    companion object {
        const val NOW_PLAYING = 0
        const val TILE = 1
        const val MEDIA = 2
        private const val SLOTS = 3
        private const val INDEX = "index-"
        private const val BUSY_TRIES = 3
        const val BUSY_RETRY_MS = 5_000L

        fun bucket(px: Int) = if (px <= SMALL) SMALL else LARGE

        const val SMALL = 256
        const val LARGE = 512

        fun key(track: Long, px: Int) = "track:${java.lang.Long.toUnsignedString(track)}:$px"

        private fun album(name: String, px: Int) = "album:${name.replace('\t', ' ').replace('\n', ' ')}:$px"

        private fun indexName(rev: Long) = "$INDEX$rev.tsv"
    }
}
