package net.seilent.funecomp.sources

import net.seilent.funekit.artist.FakeTime
import net.seilent.funekit.artist.Fixtures
import net.seilent.funekit.artist.Lrclib.Found
import net.seilent.funekit.artist.Lrclib.Query
import net.seilent.funekit.artist.LyricChain
import net.seilent.funekit.artist.LyricChain.Outcome
import net.seilent.funekit.artist.LyricSources
import net.seilent.funekit.artist.Response
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

class LyricChainTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val q = Query("t", "a", null, 200_000)
    private val lrPlain = Found("l", false)

    @Test
    fun aRejectedNeteaseBodyLeavesTheLrclibPlainInBothOrders() {
        for (order in listOf(listOf("lrclib", "netease"), listOf("netease", "lrclib"))) {
            tmp.root.listFiles()?.forEach { it.delete() }
            val f = Fixtures()
            f.queued["POST ${NetEase.SEARCH} s=t+a&type=1&limit=10&offset=0"] = ArrayDeque(listOf(Response(200, "{\"result\":{\"songs\":[{\"id\":1,\"name\":\"t\",\"alia\":[],\"tns\":[],\"dt\":200000}]},\"code\":200}".toByteArray())))
            f.queued["POST ${NetEase.LYRIC} id=1&lv=1&tv=-1"] = ArrayDeque(listOf(Response(200, "{\"lrc\":{\"lyric\":\"${(1..5).joinToString("\\n") { "[00:0$it.00]あめが 视る$it" }}\"},\"code\":200}".toByteArray())))
            val time = FakeTime()
            val n = NetEase(tmp.root, f, "0", { true }, { 0 }, time.sleep, { time.now }, Random(1))
            val c = LyricChain(LyricSources(netease = true, order = order), { lrPlain }, n::get).get(q)
            assertEquals("$order", lrPlain, c.found)
            assertEquals("$order", mapOf("lrclib" to Outcome.Answered, "netease" to Outcome.Answered), c.outcome)
            assertEquals(emptyList<String>(), f.missing)
        }
    }
}
