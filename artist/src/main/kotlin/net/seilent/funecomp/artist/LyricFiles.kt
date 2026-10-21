package net.seilent.funecomp.artist

import java.io.File
import java.io.IOException

internal object LyricFiles {
    fun save(f: File, text: String) {
        val tmp = File(f.path + ".tmp")
        try {
            tmp.writeText(text)
        } catch (e: IOException) {
            throw LyricsFileException("write ${tmp.name}", e)
        }
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) throw LyricsFileException("rename ${f.name}")
        }
    }

    fun read(f: File): String = try {
        f.readText()
    } catch (e: IOException) {
        throw LyricsFileException("read ${f.name}", e)
    }

    fun delete(f: File) {
        if (f.exists() && !f.delete()) throw LyricsFileException("delete ${f.name}")
    }
}
