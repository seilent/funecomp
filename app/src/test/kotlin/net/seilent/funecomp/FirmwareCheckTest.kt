package net.seilent.funecomp

import android.content.Context
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FirmwareCheckTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private val pre = """[{"tag_name":"v0.2.0","draft":false,"prerelease":true,"published_at":"2026-10-18T00:00:00Z","assets":[{"name":"v1.upt","size":28315648,"browser_download_url":"https://github.com/seilent/funeos/releases/download/v0.2.0/v1.upt"}]}]"""

    private val http = Http { _, _ -> Response(200, pre.toByteArray()) }

    @After
    fun tearDown() {
        Firmwares.reset()
    }

    private fun checked(): String {
        Firmwares.hello("v1", "v0.1.0")
        Firmwares.check()
        val end = System.currentTimeMillis() + 5000
        while (Firmwares.view.status == "checking" || (Firmwares.view.info.isEmpty() && Firmwares.view.tag == null)) {
            check(System.currentTimeMillis() < end) { "timed out: ${Firmwares.view}" }
            Thread.sleep(20)
        }
        return Firmwares.view.info
    }

    @Test
    fun prereleasesDefaultOnAndFindThePreview() {
        Firmwares.init(app, http)
        assertTrue(Firmwares.view.prereleases)
        assertEquals("", checked())
        assertEquals("v0.2.0", Firmwares.view.tag)
    }

    @Test
    fun aStoredOffStaysOffAndOnlyPrereleasesSaysSo() {
        app.getSharedPreferences("firmware", Context.MODE_PRIVATE).edit().putBoolean("prereleases", false).commit()
        Firmwares.init(app, http)
        assertFalse(Firmwares.view.prereleases)
        assertEquals("only prereleases", checked())
    }
}
