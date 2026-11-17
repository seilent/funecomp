package net.seilent.funecomp.sources

import net.seilent.funekit.artist.Api
import net.seilent.funekit.artist.FakeTime
import net.seilent.funekit.artist.Fixtures
import net.seilent.funekit.artist.Http
import net.seilent.funekit.artist.Lrclib.Query
import net.seilent.funekit.artist.LyricStore
import net.seilent.funekit.artist.MemoryStore
import net.seilent.funekit.artist.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

class LyricStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val time = FakeTime()
    private val now = 1_800_000_000_000L

    private class Seen(private val inner: LyricStore) : LyricStore {
        val names = LinkedHashMap<String, String>()

        override fun read(name: String) = inner.read(name)

        override fun save(name: String, text: String) {
            names[name] = text
            inner.save(name, text)
        }

        override fun delete(name: String) {
            names.remove(name)
            inner.delete(name)
        }
    }

    private fun dir(name: String) = File(tmp.root, name)

    private fun files(d: File) = d.listFiles().orEmpty().associate { it.name to it.readText() }

    private fun netease(d: File, f: Http) = NetEase(d, f, "0.1.0", { true }, { now }, time.sleep, { time.now }, Random(1))

    @Test
    fun neteaseWritesTheSameNamesToEitherStore() {
        val files = dir("files")
        val mem = dir("mem")
        val five = (1..5).joinToString("\n") { "[00:0$it.00]x$it" }
        fun stubbed(): Fixtures = Fixtures().apply {
            queued["POST ${NetEase.SEARCH} s=${Api.formEnc("Alpha Beta")}&type=1&limit=10&offset=0"] =
                ArrayDeque(listOf(Response(200, "{\"result\":{\"songs\":[{\"id\":1,\"name\":\"Alpha\",\"alia\":[],\"tns\":[],\"ar\":[],\"dt\":200000}]},\"code\":200}".toByteArray())))
            queued["POST ${NetEase.LYRIC} id=1&lv=1&tv=-1"] = ArrayDeque(listOf(Response(200, "{\"lrc\":{\"lyric\":\"${five.replace("\n", "\\n")}\"},\"code\":200}".toByteArray())))
        }
        val q = Query("Alpha", "Beta", null, 200_000)
        val onDisk = netease(files, stubbed()).get(q)
        val store = Seen(MemoryStore())
        assertEquals(onDisk, netease(mem, stubbed()).get(q, store))
        assertEquals(files(files), store.names)
        assertTrue(!mem.exists())
    }
}
