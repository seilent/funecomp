package net.seilent.funecomp

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.provider.OpenableColumns
import net.seilent.funecomp.artist.Firmware
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.proto.FwStatementBuilder
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.ui.PICK_ONE
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.PickedFile
import net.seilent.funecomp.ui.UNSIGNED_FIRMWARE
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FirmwareSigTest {
    private val sims = ArrayList<Sim>()
    private val app get() = RuntimeEnvironment.getApplication()
    private val logs = CopyOnWriteArrayList<String>()
    private var service: ServiceController<LinkService>? = null
    private lateinit var card: File
    private lateinit var sim: Sim

    @Before
    fun setUp() {
        scrobblesOff(app)
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        MediaLink.controls(app, false)
        Remote.artist(app, Http { _, _ -> Response(404, ByteArray(0)) })
        Lyrics.init(app, Http { _, _ -> Response(404, ByteArray(0)) })
        shadowOf(app.getSystemService(android.os.BatteryManager::class.java)).setIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY, 80)
        card = File(kotlin.io.path.createTempDirectory("upd").toFile(), "card")
    }

    @After
    fun tearDown() {
        service?.destroy()
        Remote.stop()
        Remote.shown(false)
        Lyrics.reset()
        Firmwares.reset()
        sims.forEach { it.stop() }
        scrobblesReset()
        Picked.sizes.clear()
    }

    private fun live(vararg faults: String, minor: Int = Proto.minor, http: Http = Http { _, _ -> Response(404, ByteArray(0)) }) {
        Firmwares.init(app, http)
        sim = Sim(Options(Board.of("v1"), Faults(faults.toList()), updDir = card, minor = minor, log = { logs += it }))
        sims += sim
        service = Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, "tcp:127.0.0.1:${sim.listen(0)}")).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.battery != null }
    }

    private fun waitFor(ms: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out: ${logs.filter { it.startsWith("upd") || "UPD_" in it }.takeLast(10)} ${Firmwares.view}" }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun image(n: Int, seed: Int = 3) = ByteArray(n) { (it * 17 + seed).toByte() }

    private fun file(bytes: ByteArray) = File(card.parentFile, "image.upt").apply { writeBytes(bytes) }

    private val done get() = File(card, "v1.upt")

    private fun begins() = logs.count { "rx UPD_BEGIN" in it }

    private fun stored(bytes: ByteArray) {
        waitFor(30_000) { done.exists() }
        assertArrayEquals(bytes, done.readBytes())
        waitFor { Firmwares.view.info == "ready on the dap" }
    }

    private fun refusedWith(line: String) {
        waitFor { Firmwares.view.info == line && !Firmwares.sending }
        assertEquals("", Firmwares.view.send)
        val n = begins()
        repeat(4) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
            Thread.sleep(50)
        }
        sim.current!!.push(Msg.STATE, sim.device.state())
        Firmwares.state()
        Thread.sleep(Firmwares.BUSY_RETRY_MS / 5)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(Firmwares.BUSY_RETRY_MS + 1000))
        Thread.sleep(300)
        assertEquals(n, begins())
        assertEquals(line, Firmwares.view.info)
    }

    private val base = "https://github.com/seilent/funeos/releases/download"

    private fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun releaseHttp(tag: String, img: ByteArray, statement: ByteArray) = Http { url, _ ->
        when (url) {
            Firmware.URL -> Response(200, """[{"tag_name":"$tag","draft":false,"prerelease":false,"published_at":"2026-10-27T00:00:00Z","assets":[{"name":"v1.upt","size":${img.size},"browser_download_url":"$base/$tag/v1.upt","digest":"sha256:${sha(img)}"},{"name":"v1.upt.sig","size":${statement.size},"browser_download_url":"$base/$tag/v1.upt.sig","digest":"sha256:${sha(statement)}"}]}]""".toByteArray())
            "$base/$tag/v1.upt" -> Response(200, img)
            "$base/$tag/v1.upt.sig" -> Response(200, statement)
            else -> Response(404, ByteArray(0))
        }
    }

    private fun downloadedRelease() {
        Firmwares.check()
        waitFor { Firmwares.view.tag != null && Firmwares.view.status.isEmpty() }
        Firmwares.download()
        waitFor { Firmwares.view.ready && Firmwares.view.signed && !Firmwares.view.downloading }
    }

    @Test
    fun aSignedReleaseSendsTheCachedStatement() {
        val bytes = image(200_000)
        val statement = FwStatementBuilder.forBytes("v1", bytes)
        live(http = releaseHttp("v0.4.0", bytes, statement))
        downloadedRelease()
        Firmwares.send(picked = false)
        stored(bytes)
        assertArrayEquals(statement, sim.device.updSigs.single())
        assertArrayEquals(statement, File(app.filesDir, "firmware/v0.4.0/v1.upt.sig").readBytes())
    }

    @Test
    fun aDropResendsTheSameStatementFromThePartWithNoSecondHash() {
        live("drop-after-bytes=150000")
        val bytes = image(400_000)
        val statement = FwStatementBuilder.forBytes("v1", bytes)
        val fromZero = AtomicInteger()
        Firmwares.send(Firmwares.Source("drop.upt", bytes.size.toLong(), statement) { off ->
            if (off == 0L) fromZero.incrementAndGet()
            ByteArrayInputStream(bytes, off.toInt(), bytes.size - off.toInt()) as InputStream
        })
        stored(bytes)
        assertTrue(sim.device.updSigs.size >= 2)
        assertTrue(sim.device.updSigs.all { it.contentEquals(statement) })
        val offsets = logs.filter { it.startsWith("upd begin") }.map { it.substringAfter("offset=").toLong() }
        assertTrue("$offsets", offsets.drop(1).isNotEmpty() && offsets.drop(1).all { it > 0 })
        assertEquals(2, fromZero.get())
    }

    @Test
    fun noStatementOnAnUpdSigDeviceSendsNoFrame() {
        live()
        Firmwares.send(file(image(10_000)), null)
        waitFor { Firmwares.view.info == UNSIGNED_FIRMWARE && !Firmwares.sending }
        assertEquals(0, begins())
    }

    @Test
    fun aStatementThatDoesNotMatchTheImageSendsNoFrame() {
        live()
        val bytes = image(10_000)
        for (st in listOf(FwStatementBuilder.forBytes("v1", image(10_000, 9)), FwStatementBuilder.forBytes("r1", bytes), FwStatementBuilder.forBytes("v1", bytes).copyOf(50), FwStatementBuilder.build(size = 10_001, sha256 = FwStatementBuilder.sha256(bytes)))) {
            Firmwares.send(file(bytes), st)
            waitFor { Firmwares.view.info == UNSIGNED_FIRMWARE && !Firmwares.sending }
            Firmwares.stopSend()
        }
        assertEquals(0, begins())
    }

    @Test
    fun aV03DeviceTakesTheUnsignedPathAndIgnoresAStatement() {
        live(minor = 14)
        val bytes = image(50_000)
        Firmwares.send(file(bytes), null)
        stored(bytes)
        assertEquals(0, sim.device.updSigs.single().size)
        done.delete()
        val statement = FwStatementBuilder.forBytes("v1", bytes)
        Firmwares.send(file(bytes), statement)
        stored(bytes)
        assertArrayEquals(statement, sim.device.updSigs.last())
    }

    @Test
    fun olderNamesTheStatementVersionNotTheReleaseTag() {
        val bytes = image(50_000)
        val statement = FwStatementBuilder.forBytes("v1", bytes, version = "v0.4.0")
        live("sig-bad=older", http = releaseHttp("v0.4.1", bytes, statement))
        downloadedRelease()
        Firmwares.send(picked = false)
        refusedWith("older v0.4.0")
    }

    @Test
    fun anUnknownKeyHasItsOwnLine() {
        live("sig-bad=key")
        val bytes = image(50_000)
        Firmwares.send(file(bytes), FwStatementBuilder.forBytes("v1", bytes))
        refusedWith("unknown signing key")
    }

    private fun refusedAsUnsigned(text: String) {
        live("sig-bad=$text")
        val bytes = image(50_000)
        Firmwares.send(file(bytes), FwStatementBuilder.forBytes("v1", bytes))
        refusedWith(UNSIGNED_FIRMWARE)
    }

    @Test
    fun anUnsignedRefusalIsUnsignedFirmware() = refusedAsUnsigned("unsigned")

    @Test
    fun aSignatureRefusalIsUnsignedFirmware() = refusedAsUnsigned("signature")

    @Test
    fun aBoardRefusalIsUnsignedFirmware() = refusedAsUnsigned("board")

    @Test
    fun aSizeRefusalIsUnsignedFirmware() = refusedAsUnsigned("size")

    @Test
    fun anEndRefusal15IsUnsignedFirmware() {
        live("fail=UPD_END:15")
        val bytes = image(50_000)
        Firmwares.send(file(bytes), FwStatementBuilder.forBytes("v1", bytes))
        refusedWith(UNSIGNED_FIRMWARE)
        assertEquals(1, logs.count { "rx UPD_END" in it })
    }

    class Picked : ContentProvider() {
        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?) =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(uri.lastPathSegment!!, sizes.getValue(uri.lastPathSegment!!))) }

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0

        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

        companion object {
            val sizes = HashMap<String, Long>()
        }
    }

    private fun content(name: String, bytes: ByteArray): Uri {
        if (Picked.sizes.isEmpty()) Robolectric.buildContentProvider(Picked::class.java).create(ProviderInfo().apply { authority = "picked" })
        Picked.sizes[name] = bytes.size.toLong()
        val uri = Uri.parse("content://picked/$name")
        shadowOf(app.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        return uri
    }

    private fun pick(vararg uris: Uri): PickedFile? {
        var got: PickedFile? = null
        Firmwares.stopSend()
        Firmwares.read(uris.toList()) { got = it }
        waitFor { got != null || Firmwares.view.info == PICK_ONE }
        return got
    }

    @Test
    fun aPickedImageAndStatementSendTheStatement() {
        live()
        val bytes = image(60_000)
        val statement = FwStatementBuilder.forBytes("v1", bytes)
        val p = pick(content("v1.upt.sig", statement), content("v1.upt", bytes))!!
        assertTrue(p.signed)
        assertEquals("v1.upt", p.name)
        Firmwares.send(picked = true)
        stored(bytes)
        assertArrayEquals(statement, sim.device.updSigs.single())
    }

    @Test
    fun aPickedImageAloneIsUnsignedAndAMismatchSendsNoFrame() {
        live()
        val bytes = image(60_000)
        assertFalse(pick(content("v1.upt", bytes))!!.signed)
        val p = pick(content("v1.upt", bytes), content("other.sig", FwStatementBuilder.forBytes("v1", image(60_000, 5))))!!
        assertTrue(p.signed)
        Firmwares.send(picked = true)
        waitFor { Firmwares.view.info == UNSIGNED_FIRMWARE && !Firmwares.sending }
        assertEquals(0, begins())
    }

    @Test
    fun aPickedStatementForAnotherBoardOrSizeIsAMismatch() {
        live()
        val bytes = image(60_000)
        for (st in listOf(FwStatementBuilder.forBytes("r1", bytes), FwStatementBuilder.forBytes("v1", image(60_001)), FwStatementBuilder.forBytes("v1", bytes).copyOf(60))) {
            val p = pick(content("v1.upt", bytes), content("v1.upt.sig", st))!!
            assertFalse(p.signed)
            assertTrue(p.mismatch)
        }
        val ok = pick(content("v1.upt", bytes), content("v1.upt.sig", FwStatementBuilder.forBytes("v1", bytes)))!!
        assertTrue(ok.signed)
        assertFalse(ok.mismatch)
        assertFalse(pick(content("v1.upt", bytes))!!.mismatch)
    }

    @Test
    fun aPickByContentNotByName() {
        live()
        val bytes = image(60_000)
        val statement = FwStatementBuilder.forBytes("v1", bytes)
        val p = pick(content("a.upt", statement), content("b.sig", bytes))!!
        assertTrue(p.signed)
        assertEquals("b.sig", p.name)
        assertNull(pick(content("one.upt", bytes), content("two.upt", image(10))))
        assertNull(pick(content("s1", statement), content("s2", FwStatementBuilder.forBytes("v1", image(10))), content("one.upt", bytes)))
        assertNull(pick(content("s3", statement)))
        assertEquals(PICK_ONE, Firmwares.view.info)
    }
}
