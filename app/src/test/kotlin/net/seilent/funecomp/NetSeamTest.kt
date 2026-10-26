package net.seilent.funecomp

import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.artist.UrlHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NetSeamTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun aTestGetsTheArtistHttpItPassesAfterTheTestReset() {
        val first = CopyOnWriteArrayList<String>()
        val second = CopyOnWriteArrayList<String>()
        val a = Remote.artist(app, Http { u, _ -> first += u; Response(404, ByteArray(0)) })
        (app as TestApp).beforeTest(javaClass.getMethod("aTestGetsTheArtistHttpItPassesAfterTheTestReset"))
        val b = Remote.artist(app, Http { u, _ -> second += u; Response(404, ByteArray(0)) })
        assertNotSame(a, b)
        b.get("Seam Artist", null, 240, 320)
        assertTrue(first.isEmpty())
        assertTrue(second.isNotEmpty())
    }

    @Test
    fun aRealHttpNeverOpensAConnection() {
        val url = "https://musicbrainz.org/ws/2/artist/91352441-9f79-48c2-81ad-ccdd1e46915a"
        val e = assertThrows(IOException::class.java) { UrlHttp().get(url, emptyMap()) }
        assertEquals("tests never reach the network: $url", e.message)
        assertEquals(listOf("$url on ${Thread.currentThread().name}"), TestApp.blocked.toList())
        TestApp.blocked.clear()
    }
}
