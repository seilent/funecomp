package net.seilent.funecomp.artist

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class NetEaseCoreFileTest {
    private val file = File("../app/src/androidTest/resources/ne-core.tsv")
    private val stamp = Regex("\\[[0-9]+:[0-9]+:[0-9]+\\]")

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun expected(): String = buildString {
        val titles = Fixtures.text("ne-tags.tsv").lineSequence().filter { it.isNotBlank() }.map { it.split('\t')[0] }
        val names = Fixtures.text("urls.tsv").lineSequence().filter { it.startsWith("POST ${NetEase.SEARCH} ") }.map { it.substringAfter('\t') }
            .flatMap { f -> J.parse(Fixtures.text(f))["result"]["songs"].list.flatMap { s -> listOfNotNull(s["name"].str) + s["alia"].list.mapNotNull { it.str } + s["tns"].list.mapNotNull { it.str } } }
        for (s in (titles + names).distinct().sorted()) append("core\t").append(esc(s)).append('\t').append(esc(NetEase.core(s))).append('\n')
        val bodies = Fixtures.text("urls.tsv").lineSequence().filter { it.startsWith("POST ${NetEase.LYRIC} ") }.map { it.substringAfter('\t') }
            .mapNotNull { f -> J.parse(Fixtures.text(f))["lrc"]["lyric"].str?.let { f to it } }.sortedBy { it.first }.toList()
        val picked = bodies.filter { stamp.containsMatchIn(it.second) } + bodies.filterNot { stamp.containsMatchIn(it.second) }.take(33)
        for ((f, raw) in picked) {
            val got = NetEase.text(raw)
            append("text\t").append(f).append('\t').append(esc(raw)).append('\t')
            append(if (got == null) "miss" else if (got.synced) "synced" else "plain").append('\t').append(got?.let { sha(it.text) } ?: "").append('\n')
        }
    }

    @Test
    fun theDeviceCaseFileMatchesTheJvm() {
        val want = expected()
        if (System.getenv("NE_CORE_WRITE") == "1") file.writeText(want)
        assertEquals("rerun with NE_CORE_WRITE=1 to rewrite ${file.path}", want, file.readText())
    }
}
