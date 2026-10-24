package net.seilent.funecomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NoSynchronizedListTest {
    @Test
    fun noTestSourceCallsCollectionsSynchronizedList() {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }.firstOrNull { File(it, "settings.gradle.kts").isFile }
        assertNotNull("no settings.gradle.kts above ${System.getProperty("user.dir")}", root)
        val banned = "synchronized" + "List("
        val sources = root!!.listFiles { f -> f.isDirectory }!!.flatMap { m ->
            File(m, "src/test/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.map { m to it }.toList()
        }
        assertTrue("no test sources under $root", sources.any { it.first.name == "app" } && sources.any { it.first.name == "artist" })
        val hits = sources.filter { banned in it.second.readText() }.map { "${it.first.name}/${it.second.name}" }
        assertEquals(emptyList<String>(), hits.sorted())
    }
}
