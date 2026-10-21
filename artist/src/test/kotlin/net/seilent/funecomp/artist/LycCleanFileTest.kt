package net.seilent.funecomp.artist

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class LycCleanFileTest {
    private val file = File("../app/src/androidTest/resources/lyc-clean.tsv")

    private fun expected(): String = buildString {
        for (r in CleanRows.all()) {
            val c = LyricNames.clean(r.title, r.artist)
            append(listOf(r.artist, r.title, c.title, c.artist ?: "", c.tags.joinToString(" ")).joinToString("\t")).append('\n')
        }
    }

    @Test
    fun theDeviceCleanFileMatchesTheJvm() {
        val want = expected()
        if (System.getenv("LYC_CLEAN_WRITE") == "1") file.writeText(want)
        assertEquals("rerun with LYC_CLEAN_WRITE=1 to rewrite ${file.path}", want, file.readText())
    }
}
