package net.seilent.funecomp.artist

import java.io.File

class GalleryStore(private val dir: File) {
    private fun file(sha: String) = File(dir, "$sha.jpg")

    @Synchronized
    fun put(bytes: ByteArray): String {
        val sha = ArtCache.sha256(bytes)
        val f = file(sha)
        if (f.isFile) return sha
        dir.mkdirs()
        val t = File(dir, "$sha.tmp")
        t.writeBytes(bytes)
        if (!t.renameTo(f)) {
            f.delete()
            t.renameTo(f)
        }
        return sha
    }

    @Synchronized
    fun get(sha: String): ByteArray? = file(sha).takeIf { sha.isNotEmpty() && it.isFile }?.readBytes()

    @Synchronized
    fun has(sha: String): Boolean = sha.isNotEmpty() && file(sha).isFile

    @Synchronized
    fun any(): Boolean = dir.listFiles()?.isNotEmpty() == true

    @Synchronized
    fun prune(keep: Set<String>): Int {
        var n = 0
        for (f in dir.listFiles() ?: return 0) {
            if (f.name.removeSuffix(".jpg") in keep && f.name.endsWith(".jpg")) continue
            if (f.delete()) n++
        }
        return n
    }

    companion object {
        const val ID_PREFIX = "gallery:"

        fun shas(picks: List<ArtPicks.Pick>): Set<String> =
            picks.filter { it.source == Sources.GALLERY && (it.kind == ArtPicks.IMAGE || it.was == ArtPicks.IMAGE) }.map { it.sha }.filter { it.isNotEmpty() }.toSet()
    }
}
