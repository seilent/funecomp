package net.seilent.funecomp.artist

import java.io.File
import java.security.MessageDigest
import java.util.Properties

class ArtCache(private val dir: File) {
    class Entry(
        val key: String,
        val name: String,
        val mbid: String?,
        val source: String,
        val credit: Credit?,
        val url: String,
        val at: Long,
        val id: String = "",
        val sha: String = "",
        val resolvedBy: String = "",
        val w: Int = 0,
        val h: Int = 0,
        val base: String = "",
        val failed: Long = 0,
        val sized: String = "",
        val bare: Boolean = false,
        val channel: String = "",
    )
    class Miss(val at: Long, val sources: Set<String>, val keyHash: String = "", val cleaned: Boolean = false, val yt: Long? = null, val ytFailed: Long? = null)
    class Looked(val mbid: String?, val at: Long)

    private val names = File(dir, "names.properties")
    private var nameMap: Properties? = null
    private val bares = File(dir, "bare.properties")
    private var bareMap: Properties? = null
    private var listed: List<Entry>? = null

    @Synchronized
    fun mbid(name: String): Looked? {
        val k = Names.fold(name)
        if (k == KANA) return null
        val v = nameMap().getProperty(k) ?: return null
        val at = v.substringBefore(' ').toLongOrNull() ?: return null
        return Looked(Names.mbid(v.substringAfter(' ', "")), at)
    }

    @Synchronized
    fun putMbid(name: String, mbid: String?, at: Long) {
        val k = Names.fold(name)
        if (k == KANA) return
        val p = nameMap()
        p.setProperty(k, "$at ${mbid ?: ""}".trim())
        save(names, p)
    }

    @Synchronized
    fun bare(name: String): Looked? {
        val v = bareMap().getProperty(Names.fold(name)) ?: return null
        val at = v.substringBefore(' ').toLongOrNull() ?: return null
        return Looked(Names.mbid(v.substringAfter(' ', "")), at)
    }

    @Synchronized
    fun putBare(name: String, mbid: String?, at: Long) {
        val p = bareMap()
        p.setProperty(Names.fold(name), "$at ${mbid ?: ""}".trim())
        save(bares, p)
    }

    @Synchronized
    fun hit(key: String): Entry? {
        val p = load(meta(key))
        if (p.getProperty("state") != "hit" || !src(key).isFile) return null
        val credit = p.getProperty("license")?.let { Credit(p.getProperty("author", ""), it, p.getProperty("page", ""), p.getProperty("licenseUrl", "")) }
        return Entry(
            key, p.getProperty("name", ""), p.getProperty("mbid"), p.getProperty("source", ""), credit, p.getProperty("url", ""),
            p.getProperty("at")?.toLongOrNull() ?: 0, p.getProperty("id", ""), p.getProperty("sha", ""), p.getProperty("resolvedBy", ""),
            p.getProperty("w")?.toIntOrNull() ?: 0, p.getProperty("h")?.toIntOrNull() ?: 0, p.getProperty("base", ""),
            p.getProperty("failed")?.toLongOrNull() ?: 0, p.getProperty("sized", ""), p.getProperty("bare") == "1", p.getProperty("channel", ""),
        )
    }

    @Synchronized
    fun sha(e: Entry): String {
        if (e.sha.isNotEmpty()) return e.sha
        val src = source(e.key) ?: return ""
        val sha = sha256(src)
        val f = meta(e.key)
        val p = load(f)
        if (p.getProperty("state") == "hit") {
            p.setProperty("sha", sha)
            save(f, p)
        }
        return sha
    }

    @Synchronized
    fun putFailed(key: String, at: Long, failed: Long) {
        val f = meta(key)
        val p = load(f)
        if (p.getProperty("state") != "hit" || p.getProperty("at")?.toLongOrNull() != at) return
        p.setProperty("failed", failed.toString())
        save(f, p)
        listed = null
    }

    @Synchronized
    fun putSized(key: String, at: Long, sized: String) {
        val f = meta(key)
        val p = load(f)
        if (p.getProperty("state") != "hit" || p.getProperty("at")?.toLongOrNull() != at) return
        p.setProperty("sized", sized)
        save(f, p)
        listed = null
    }

    @Synchronized
    fun putSize(key: String, at: Long, s: Size) {
        val f = meta(key)
        val p = load(f)
        if (p.getProperty("state") != "hit" || p.getProperty("at")?.toLongOrNull() != at) return
        p.setProperty("w", s.w.toString())
        p.setProperty("h", s.h.toString())
        save(f, p)
        listed = null
    }

    @Synchronized
    fun drop(key: String) {
        listed = null
        dropFiles(key)
    }

    @Synchronized
    fun drop(key: String, at: Long) {
        if (load(meta(key)).getProperty("at")?.toLongOrNull() == at) drop(key)
    }

    @Synchronized
    fun forgetName(name: String) {
        val p = nameMap()
        if (p.remove(Names.fold(name)) != null) save(names, p)
    }

    @Synchronized
    fun miss(key: String): Miss? {
        val p = load(missFile(key)).takeIf { it.getProperty("state") == "miss" } ?: load(meta(key))
        if (p.getProperty("state") != "miss") return null
        return Miss(
            p.getProperty("at")?.toLongOrNull() ?: 0, p.getProperty("sources", "").split(',').filter { it.isNotEmpty() }.toSet(), p.getProperty("keyHash", ""),
            p.getProperty("cleaned") == CLEANED, p.getProperty("yt")?.toLongOrNull(), p.getProperty("ytFailed")?.toLongOrNull(),
        )
    }

    @Synchronized
    fun source(key: String): ByteArray? = src(key).takeIf { it.isFile }?.readBytes()

    @Synchronized
    fun hasSource(key: String) = src(key).isFile

    @Synchronized
    fun rendered(key: String, w: Int, h: Int): ByteArray? = jpg(key, w, h).takeIf { it.isFile }?.readBytes()

    @Synchronized
    fun putHit(e: Entry, source: ByteArray) {
        listed = null
        dropFiles(e.key)
        write(src(e.key), source)
        val p = Properties()
        p.setProperty("state", "hit")
        p.setProperty("key", e.key)
        p.setProperty("name", e.name)
        e.mbid?.let { p.setProperty("mbid", it) }
        p.setProperty("source", e.source)
        p.setProperty("url", e.url)
        p.setProperty("at", e.at.toString())
        p.setProperty("id", e.id)
        p.setProperty("sha", e.sha.ifEmpty { sha256(source) })
        if (e.resolvedBy.isNotEmpty()) p.setProperty("resolvedBy", e.resolvedBy)
        if (e.w > 0 && e.h > 0) {
            p.setProperty("w", e.w.toString())
            p.setProperty("h", e.h.toString())
        }
        if (e.base.isNotEmpty()) p.setProperty("base", e.base)
        if (e.bare) p.setProperty("bare", "1")
        if (e.channel.isNotEmpty()) p.setProperty("channel", e.channel)
        e.credit?.let {
            p.setProperty("author", it.author)
            p.setProperty("license", it.license)
            p.setProperty("page", it.page)
            p.setProperty("licenseUrl", it.licenseUrl)
        }
        save(meta(e.key), p)
    }

    @Synchronized
    fun replaceHit(at: Long, e: Entry, source: ByteArray): Boolean {
        val p = load(meta(e.key))
        if (p.getProperty("state") != "hit" || p.getProperty("at")?.toLongOrNull() != at) return false
        putHit(e, source)
        return true
    }

    @Synchronized
    fun putRendered(key: String, w: Int, h: Int, jpeg: ByteArray) = write(jpg(key, w, h), jpeg)

    @Synchronized
    fun putMiss(key: String, at: Long, sources: Set<String>, keyHash: String = "", yt: Long? = null, ytFailed: Long? = null) {
        listed = null
        val m = meta(key)
        if (load(m).getProperty("state") == "miss") m.delete()
        val p = Properties()
        p.setProperty("state", "miss")
        p.setProperty("key", key)
        p.setProperty("at", at.toString())
        p.setProperty("sources", sources.sorted().joinToString(","))
        if (keyHash.isNotEmpty()) p.setProperty("keyHash", keyHash)
        p.setProperty("cleaned", CLEANED)
        yt?.let { p.setProperty("yt", it.toString()) }
        ytFailed?.let { p.setProperty("ytFailed", it.toString()) }
        save(missFile(key), p)
    }

    @Synchronized
    fun entries(): List<Entry> = listed ?: (dir.listFiles { f -> f.name.endsWith(".meta") } ?: emptyArray())
        .mapNotNull { f -> load(f).getProperty("key")?.let { hit(it) } }
        .sortedBy { Names.fold(it.name) }
        .also { listed = it }

    @Synchronized
    fun clear() {
        nameMap = null
        bareMap = null
        listed = null
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun nameMap() = nameMap ?: load(names).also { p ->
        nameMap = p
        if (p.getProperty(KANA) == null) {
            p.stringPropertyNames().filter { Names.mbid(p.getProperty(it).substringAfter(' ', "")) == null }.forEach { p.remove(it) }
            p.setProperty(KANA, "1")
            save(names, p)
        }
    }

    private fun bareMap() = bareMap ?: load(bares).also { bareMap = it }

    private fun dropFiles(key: String) {
        val h = hash(key)
        dir.listFiles { f -> f.name.startsWith("$h.") }?.forEach { it.delete() }
    }

    private fun meta(key: String) = File(dir, "${hash(key)}.meta")
    private fun missFile(key: String) = File(dir, "${hash(key)}.miss")
    private fun src(key: String) = File(dir, "${hash(key)}.src")
    private fun jpg(key: String, w: Int, h: Int) = File(dir, "${hash(key)}.${w}x$h.jpg")

    fun keyHash(key: String) = if (key.isEmpty()) "" else hash("key:$key")

    private fun hash(key: String) = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    private fun load(f: File) = Properties().apply {
        if (f.isFile) {
            try {
                f.reader(Charsets.UTF_8).use { load(it) }
            } catch (e: IllegalArgumentException) {
                clear()
            }
        }
    }

    private fun save(f: File, p: Properties) {
        dir.mkdirs()
        val t = File(dir, f.name + ".tmp")
        t.writer(Charsets.UTF_8).use { p.store(it, null) }
        if (!t.renameTo(f)) {
            f.delete()
            t.renameTo(f)
        }
    }

    private fun write(f: File, b: ByteArray) {
        dir.mkdirs()
        val t = File(dir, f.name + ".tmp")
        t.writeBytes(b)
        if (!t.renameTo(f)) {
            f.delete()
            t.renameTo(f)
        }
    }

    companion object {
        private const val KANA = ".kana"
        private const val CLEANED = "3"

        fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}
