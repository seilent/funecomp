package net.seilent.funecomp

import android.os.Looper
import net.seilent.funecomp.ui.PickFor
import net.seilent.funekit.artist.Http
import net.seilent.funekit.artist.Offer
import net.seilent.funekit.artist.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArtLedgerTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val dj = "91352441-9f79-48c2-81ad-ccdd1e46915a"
    private val http = Http { _, _ -> Response(404, ByteArray(0)) }

    @Before
    fun setUp() {
        Remote.resetArtist()
    }

    @After
    fun tearDown() {
        Remote.resetArtist()
    }

    private fun waitFor(ms: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
            Thread.sleep(20)
        }
    }

    @Test
    fun aPushRecordedThroughTheAppSurvivesARestart() {
        Remote.artist(app, http)
        Remote.pick(PickFor("Other"), Offer("artist", "musicbrainz", "u", dj, null, dj, "Other", null))
        val file = File(app.filesDir, "artblock.tsv")
        waitFor { file.isFile && file.readText().contains("push\tOther\t$dj\t") }
        Remote.resetArtist()
        val a = Remote.artist(app, http)
        assertEquals(listOf("Other" to dj), a.blocks.ledger!!.pushes("tcp:A").map { it.name to it.mbid })
    }
}
