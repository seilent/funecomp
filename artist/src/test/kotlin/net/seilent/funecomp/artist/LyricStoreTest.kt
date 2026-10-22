package net.seilent.funecomp.artist

import net.seilent.funecomp.artist.Lrclib.Query
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

    private fun lrclib(d: File, f: Http) = Lrclib(d, f, "0.1.0", { true }, { now }, time.sleep, { time.now }, Random(1))

    private fun netease(d: File, f: Http) = NetEase(d, f, "0.1.0", { true }, { now }, time.sleep, { time.now }, Random(1))

    private val airbag = Query("Airbag", "Radiohead", "OK Computer", 284_200)

    @Test
    fun aMemoryStoreHoldsWhatTheFileStoreWritesAndTouchesNoFile() {
        val files = dir("files")
        val mem = dir("mem")
        val f = Fixtures()
        val onDisk = lrclib(files, f).get(airbag)
        val store = Seen(MemoryStore())
        val inMemory = lrclib(mem, f).get(airbag, store)
        assertEquals(onDisk, inMemory)
        assertEquals(files(files), store.names)
        assertTrue(!mem.exists())
        val calls = f.calls.size
        assertEquals(inMemory, lrclib(mem, f).get(airbag, store))
        assertEquals(calls, f.calls.size)
        assertTrue(!mem.exists())
    }

    @Test
    fun aMissInMemoryIsGoneWithTheStore() {
        val mem = dir("mem")
        val f = Fixtures()
        val q = Query("Nothing Here", "Nobody", null, null)
        val store = MemoryStore()
        assertEquals(null, lrclib(mem, f).get(q, store))
        assertTrue(store.read("${q.key}.miss")!!.endsWith("cleaned=3"))
        assertEquals(null, lrclib(mem, f).get(q, MemoryStore()))
        assertEquals(2, f.calls.size)
        assertEquals(f.calls, f.missing)
        assertTrue(!mem.exists())
    }

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
