package net.seilent.funecomp.artist

import java.io.File

interface LyricStore {
    fun read(name: String): String?

    fun save(name: String, text: String)

    fun delete(name: String)
}

class FileStore(private val dir: File) : LyricStore {
    override fun read(name: String): String? = File(dir, name).takeIf { it.isFile }?.let { LyricFiles.read(it) }

    override fun save(name: String, text: String) {
        dir.mkdirs()
        LyricFiles.save(File(dir, name), text)
    }

    override fun delete(name: String) = LyricFiles.delete(File(dir, name))
}

class MemoryStore : LyricStore {
    private val texts = HashMap<String, String>()

    override fun read(name: String): String? = texts[name]

    override fun save(name: String, text: String) {
        texts[name] = text
    }

    override fun delete(name: String) {
        texts.remove(name)
    }
}
