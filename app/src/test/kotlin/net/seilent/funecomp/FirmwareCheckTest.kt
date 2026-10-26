package net.seilent.funecomp

import android.content.Context
import net.seilent.funecomp.artist.Firmware
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.proto.FwStatementBuilder
import net.seilent.funecomp.ui.UNSIGNED_RELEASE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

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

    private val image = ByteArray(50_000) { (it * 7 + 1).toByte() }

    private fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun asset(tag: String, name: String, b: ByteArray) =
        """{"name":"$name","size":${b.size},"browser_download_url":"$BASE/$tag/$name","digest":"sha256:${sha(b)}"}"""

    private fun release(tag: String, at: String, vararg assets: String) =
        """{"tag_name":"$tag","draft":false,"prerelease":false,"published_at":"$at","assets":[${assets.joinToString(",")}]}"""

    private inner class Net(var list: String) : Http {
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val bins = HashMap<String, ByteArray>()
        var broken = false
        var hold: java.util.concurrent.CountDownLatch? = null

        override fun get(url: String, headers: Map<String, String>): Response {
            calls += url
            if (url == Firmware.URL) hold?.await(5, java.util.concurrent.TimeUnit.SECONDS)
            if (url == Firmware.URL) return Response(200, list.toByteArray())
            if (broken && url.endsWith(".sig")) throw IOException("reset")
            return bins[url]?.let { Response(200, it) } ?: Response(404, ByteArray(0))
        }

        fun lists() = calls.count { it == Firmware.URL }
    }

    private val sig = FwStatementBuilder.forBytes("v1", image)

    private fun signed(statement: ByteArray = sig): Net {
        val n = Net("[${release("v0.4.0", "2026-10-27T00:00:00Z", asset("v0.4.0", "v1.upt", image), asset("v0.4.0", "v1.upt.sig", statement))}]")
        n.bins["$BASE/v0.4.0/v1.upt"] = image
        n.bins["$BASE/v0.4.0/v1.upt.sig"] = statement
        return n
    }

    private fun until(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out on $what: ${Firmwares.view}" }
            Thread.sleep(20)
        }
    }

    private fun checkedWith(updSig: Boolean) {
        Firmwares.hello("v1", "v0.3.0", updSig)
        Firmwares.check()
        until("check") { Firmwares.view.status != "checking" && (Firmwares.view.tag != null || Firmwares.view.info.isNotEmpty()) }
    }

    private fun downloaded() {
        Firmwares.download()
        until("download") { !Firmwares.view.downloading && (Firmwares.view.ready || Firmwares.view.info.isNotEmpty() || Firmwares.view.download.isNotEmpty()) }
    }

    @Test
    fun aSignedReleaseDownloadsWithItsStatement() {
        Firmwares.init(app, signed())
        checkedWith(true)
        assertTrue(Firmwares.view.sigListed)
        assertFalse(Firmwares.view.ready)
        downloaded()
        assertTrue(Firmwares.view.ready)
        assertTrue(Firmwares.view.signed)
        assertEquals("", Firmwares.view.download)
    }

    @Test
    fun aStatementTransportErrorLeavesTheImageReadyAndTheNextTapFetchesOnlyIt() {
        val net = signed()
        net.broken = true
        Firmwares.init(app, net)
        checkedWith(true)
        downloaded()
        assertTrue(Firmwares.view.ready)
        assertFalse(Firmwares.view.signed)
        assertEquals("paused 100%", Firmwares.view.download)
        assertEquals("", Firmwares.view.info)
        net.broken = false
        downloaded()
        assertTrue(Firmwares.view.signed)
        assertEquals(1, net.calls.count { it.endsWith("/v1.upt") })
    }

    @Test
    fun aRefusedStatementKeepsTheImageAndThePageOpenChecksOnce() {
        val net = signed(FwStatementBuilder.forBytes("r1", image))
        Firmwares.init(app, net)
        checkedWith(true)
        downloaded()
        assertEquals(Firmware.BAD_SIG, Firmwares.view.info)
        assertTrue(Firmwares.view.ready)
        assertFalse(Firmwares.view.signed)
        val lists = net.lists()
        Firmwares.opened()
        until("recheck") { net.lists() == lists + 1 && Firmwares.view.status.isEmpty() }
        Thread.sleep(200)
        assertEquals(lists + 1, net.lists())
        assertTrue(Firmwares.view.ready)
        assertFalse(Firmwares.view.signed)
    }

    @Test
    fun aSignedDeviceWithOnlyUnsignedReleasesSaysSoAndRechecksOnOpen() {
        val net = Net("[${release("v0.4.0", "2026-10-27T00:00:00Z", asset("v0.4.0", "v1.upt", image))}]")
        Firmwares.init(app, net)
        checkedWith(true)
        assertEquals(UNSIGNED_RELEASE, Firmwares.view.info)
        assertEquals(null, Firmwares.view.tag)
        Firmwares.opened()
        until("recheck") { net.lists() == 2 && Firmwares.view.status.isEmpty() }
        assertEquals(UNSIGNED_RELEASE, Firmwares.view.info)
    }

    @Test
    fun aHelloWithAnotherUpdSigPicksAgain() {
        val net = Net("[${release("v0.3.1", "2026-10-20T00:00:00Z", asset("v0.3.1", "v1.upt", image), asset("v0.3.1", "v1.upt.sig", sig))},${release("v0.4.0", "2026-10-27T00:00:00Z", asset("v0.4.0", "v1.upt", image))}]")
        Firmwares.init(app, net)
        checkedWith(false)
        assertEquals("v0.4.0", Firmwares.view.tag)
        Firmwares.hello("v1", "v0.3.0", true)
        until("repick") { net.lists() == 2 && Firmwares.view.tag == "v0.3.1" }
        Firmwares.hello("v1", "v0.3.0", true)
        Firmwares.opened()
        Thread.sleep(300)
        assertEquals(2, net.lists())
    }

    @Test
    fun aHelloDuringTheFirstCheckPicksAgain() {
        val net = Net("[${release("v0.3.1", "2026-10-20T00:00:00Z", asset("v0.3.1", "v1.upt", image), asset("v0.3.1", "v1.upt.sig", sig))},${release("v0.4.0", "2026-10-27T00:00:00Z", asset("v0.4.0", "v1.upt", image))}]")
        val hold = java.util.concurrent.CountDownLatch(1)
        net.hold = hold
        Firmwares.init(app, net)
        Firmwares.hello("v1", "v0.3.0", false)
        Firmwares.check()
        until("first get") { net.lists() == 1 }
        Firmwares.hello("v1", "v0.3.0", true)
        hold.countDown()
        until("repick") { net.lists() == 2 && Firmwares.view.tag == "v0.3.1" && Firmwares.view.status.isEmpty() }
    }

    @Test
    fun aStoredOffStaysOffAndOnlyPrereleasesSaysSo() {
        app.getSharedPreferences("firmware", Context.MODE_PRIVATE).edit().putBoolean("prereleases", false).commit()
        Firmwares.init(app, http)
        assertFalse(Firmwares.view.prereleases)
        assertEquals("only prereleases", checked())
    }

    companion object {
        const val BASE = "https://github.com/seilent/funeos/releases/download"
    }
}
