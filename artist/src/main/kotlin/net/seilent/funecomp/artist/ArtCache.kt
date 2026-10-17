package net.seilent.funecomp.artist

import java.io.File
import java.security.MessageDigest
import java.util.Properties

class ArtCache(private val dir: File) {
    class Entry(val key: String, val name: String, val mbid: String?, val source: String, val credit: Credit?, val url: String, val at: Long)
    class Miss(val at: Long, val sources: Set<String>, val keyHash: String = "")
    class Looked(val mbid: String?, val at: Long)

    private val names = File(dir, "names.properties")
    private var nameMap: Properties? = null
    private var listed: List<Entry>? = null

    @Synchronized
    fun mbid(name: String): Looked? {
        val v = nameMap().getProperty(Names.fold(name)) ?: return null
        val at = v.substringBefore(' ').toLongOrNull() ?: return null
        return Looked(Names.mbid(v.substringAfter(' ', "")), at)
    }

    @Synchronized
    fun putMbid(name: String, mbid: String?, at: Long) {
        val p = nameMap()
        p.setProperty(Names.fold(name), "$at ${mbid ?: ""}".trim())
        save(names, p)
    }

    @Synchronized
    fun hit(key: String): Entry? {
        val p = load(meta(key))
        if (p.getProperty("state") != "hit" || !src(key).isFile) return null
        val credit = p.getProperty("license")?.let { Credit(p.getProperty("author", ""), it, p.getProperty("page", ""), p.getProperty("licenseUrl", "")) }
        return Entry(key, p.getProperty("name", ""), p.getProperty("mbid"), p.getProperty("source", ""), credit, p.getProperty("url", ""), p.getProperty("at")?.toLongOrNull() ?: 0)
    }

    @Synchronized
    fun miss(key: String): Miss? {
        val p = load(meta(key))
        if (p.getProperty("state") != "miss") return null
        return Miss(p.getProperty("at")?.toLongOrNull() ?: 0, p.getProperty("sources", "").split(',').filter { it.isNotEmpty() }.toSet(), p.getProperty("keyHash", ""))
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
        e.credit?.let {
            p.setProperty("author", it.author)
            p.setProperty("license", it.license)
            p.setProperty("page", it.page)
            p.setProperty("licenseUrl", it.licenseUrl)
        }
        save(meta(e.key), p)
    }

    @Synchronized
    fun putRendered(key: String, w: Int, h: Int, jpeg: ByteArray) = write(jpg(key, w, h), jpeg)

    @Synchronized
    fun putMiss(key: String, at: Long, sources: Set<String>, keyHash: String = "") {
        listed = null
        dropFiles(key)
        val p = Properties()
        p.setProperty("state", "miss")
        p.setProperty("key", key)
        p.setProperty("at", at.toString())
        p.setProperty("sources", sources.sorted().joinToString(","))
        if (keyHash.isNotEmpty()) p.setProperty("keyHash", keyHash)
        save(meta(key), p)
    }

    @Synchronized
    fun entries(): List<Entry> = listed ?: (dir.listFiles { f -> f.name.endsWith(".meta") } ?: emptyArray())
        .mapNotNull { f -> load(f).getProperty("key")?.let { hit(it) } }
        .sortedBy { Names.fold(it.name) }
        .also { listed = it }

    @Synchronized
    fun clear() {
        nameMap = null
        listed = null
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun nameMap() = nameMap ?: load(names).also { nameMap = it }

    private fun dropFiles(key: String) {
        val h = hash(key)
        dir.listFiles { f -> f.name.startsWith("$h.") }?.forEach { it.delete() }
    }

    private fun meta(key: String) = File(dir, "${hash(key)}.meta")
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
}
