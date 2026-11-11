package net.seilent.funecomp

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.BatteryManager
import android.os.Looper
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.LyricSources
import net.seilent.funecomp.artist.Names
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.artist.Wikimedia
import net.seilent.funecomp.proto.AppState
import net.seilent.funecomp.proto.FwStatementBuilder
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.state.CardView
import net.seilent.funecomp.state.PushGate
import net.seilent.funecomp.ui.AppsView
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.byeText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppsTest {
    private val sims = ArrayList<Sim>()
    private val app get() = RuntimeEnvironment.getApplication()
    private val logs = CopyOnWriteArrayList<String>()
    private val toasts = CopyOnWriteArrayList<String>()
    private var service: ServiceController<LinkService>? = null
    private lateinit var sim: Sim
    private lateinit var apps: Apps
    private var port = 0
    private val whole = 5 * Proto.updChunk
    private val part = 2 * Proto.updChunk

    private val vt = AtomicLong()
    private val timers = ArrayList<Pair<Long, () -> Unit>>()

    private fun fresh(retryMs: Long = 200, busyBoundMs: Long = 60_000, resultMs: Long = 60_000, liveMs: Long = 30_000) = Apps(
        now = vt::get,
        retryMs = retryMs,
        busyBoundMs = busyBoundMs,
        resultMs = resultMs,
        liveMs = liveMs,
        later = { ms, f -> synchronized(timers) { timers += vt.get() + ms to f } },
    ).also {
        if (::apps.isInitialized) apps.close()
        synchronized(timers) { timers.clear() }
        it.init(app)
        it.onToast = { id, line -> toasts += "$id $line" }
        Remote.apps = it
        apps = it
    }

    @Before
    fun setUp() {
        scrobblesOff(app)
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        MediaLink.controls(app, false)
        Remote.artist(app, Http { _, _ -> Response(404, ByteArray(0)) })
        Lyrics.init(app, Http { _, _ -> Response(404, ByteArray(0)) })
        app.getSharedPreferences("apps", 0).edit().clear().commit()
        fresh()
    }

    @After
    fun tearDown() {
        service?.destroy()
        apps.close()
        Remote.stop()
        Remote.shown(false)
        Lyrics.reset()
        Firmwares.reset()
        Remote.apps = Apps()
        sims.forEach { it.stop() }
        scrobblesReset()
    }

    private fun start(
        faults: List<String>,
        replyMs: Long = Proto.svcReplyMs.toLong(),
        minor: Int = Proto.minor,
        board: String = "v1",
        version: String? = null,
        lib: Library = Library.synthetic(),
        lrcDir: File? = null,
        log: (String) -> Unit = { logs += it },
        clock: (() -> Long)? = null,
    ) = Sim(Options(Board.of(board), Faults(faults), log = log, appReplyMs = replyMs, minor = minor, version = version, lrcDir = lrcDir, deviceClock = clock), lib).also { sims += it }

    private fun live(vararg faults: String, replyMs: Long = Proto.svcReplyMs.toLong(), minor: Int = Proto.minor, lib: Library = Library.synthetic(), lrcDir: File? = null) {
        Firmwares.init(app, Http { _, _ -> Response(404, ByteArray(0)) })
        sim = start(faults.toList(), replyMs, minor, lib = lib, lrcDir = lrcDir)
        port = listenBelowEphemeral(sim)
        service = Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, "tcp:127.0.0.1:$port")).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.battery != null }
    }

    private fun swap(vararg faults: String, board: String = "v1", version: String? = null, clock: (() -> Long)? = null): List<String> {
        val own = CopyOnWriteArrayList<String>()
        sim.stop()
        sim = start(faults.toList(), board = board, version = version, log = {
            logs += it
            own += it
        }, clock = clock)
        listenAgain(sim, port)
        return own
    }

    private fun tick(ms: Long) {
        vt.addAndGet(ms)
        while (true) {
            val f = synchronized(timers) { timers.filter { it.first <= vt.get() }.minByOrNull { it.first }?.also { timers.remove(it) }?.second } ?: return
            f()
        }
    }

    private fun waitFor(ms: Long = 10_000, advance: Boolean = false, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out: ${apps.view} ${logs.filter { "APP_" in it || it.startsWith("app") }.takeLast(10)}" }
            if (advance) tick(100)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun quiet(ms: Long) {
        var left = ms
        while (left > 0) {
            tick(minOf(100, left))
            left -= 100
            apps.sync()
            shadowOf(Looper.getMainLooper()).idle()
        }
        apps.sync()
    }

    private fun pkg(id: String = "paddle", version: Long = 3, size: Int = 60_000, magic: String = "FNSVCST2"): ByteArray {
        val head = magic.toByteArray() + ByteArray(8) { 7 } + byteArrayOf(id.length.toByte()) + id.toByteArray() + byteArrayOf(1, 4) +
            ByteArray(4) { (version shr (8 * it)).toByte() }
        return head + ByteArray(size - head.size) { (it * 13 + 1).toByte() }
    }

    private fun source(b: ByteArray, size: Long = b.size.toLong()) = Apps.source("app.fnapp", size, Apps.sha256(ByteArrayInputStream(b)), Apps.head(ByteArrayInputStream(b))) { off ->
        ByteArrayInputStream(b, off.toInt(), b.size - off.toInt())
    }

    private fun send(b: ByteArray = pkg()) {
        apps.pick(source(b)!!)
        apps.send()
    }

    private fun held(until: () -> Boolean = { sim.device.apps.partSize >= part }): CountDownLatch {
        val go = CountDownLatch(1)
        val b = pkg(size = whole)
        apps.pick(Apps.source("app.fnapp", b.size.toLong(), Apps.sha256(ByteArrayInputStream(b)), Apps.head(ByteArrayInputStream(b))) { off ->
            object : InputStream() {
                var i = off.toInt()

                override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 0xff }

                override fun read(buf: ByteArray, o: Int, n: Int): Int {
                    if (i >= b.size) return -1
                    if (i >= part) go.await(30, TimeUnit.SECONDS)
                    val k = minOf(n, if (i < part) part - i else b.size - i)
                    System.arraycopy(b, i, buf, o, k)
                    i += k
                    return k
                }
            }
        }!!)
        apps.send()
        waitFor { until() && Remote.bulkSending() }
        return go
    }

    private fun resumedAt(offset: Long) = assertTrue(logs.any { it == "app begin stage=2 offset=$offset" })

    private fun resumedAtThePart() = resumedAt(part.toLong())

    private fun held(name: String, go: CountDownLatch) = Firmwares.Source(name, 1000, FwStatementBuilder.forBytes("v1", ByteArray(1000))) {
        go.await(30, TimeUnit.SECONDS)
        ByteArrayInputStream(ByteArray(1000))
    }

    private fun begins() = logs.count { "rx APP_BEGIN" in it }

    private fun jpeg(): ByteArray {
        val i = java.awt.image.BufferedImage(600, 800, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = i.createGraphics()
        g.color = java.awt.Color(120, 80, 40)
        g.fillRect(0, 0, 600, 800)
        g.dispose()
        return ByteArrayOutputStream().also { javax.imageio.ImageIO.write(i, "jpg", it) }.toByteArray()
    }

    private fun info(line: String, ms: Long = 10_000, advance: Boolean = false) = waitFor(ms, advance) { apps.view.info == line }

    @Test
    fun theHelloCarriesTheAppBitAndAPushIsInstalled() {
        live()
        send()
        info(Apps.INSTALLED)
        assertTrue(toasts.isEmpty())
    }

    @Test
    fun anOpeningAnswerIsRetriedOnceThenCompletes() {
        live("app-open-ms=1000", replyMs = 2100)
        send()
        info(Apps.INSTALLED, advance = true)
        assertEquals(2, begins())
    }

    @Test
    fun busyPastTheBoundStopsAndAManualSendStartsAgain() {
        fresh(busyBoundMs = 1500)
        live("app-staged")
        send()
        info(Apps.BUSY_STOP, advance = true)
        val n = begins()
        apps.send()
        waitFor { begins() > n }
    }

    @Test
    fun anOffsetMismatchTwiceInARowStops() {
        live("app-begins=stop/stop")
        send()
        info(Apps.SEND_FAILED)
        assertEquals(2, begins())
    }

    @Test
    fun anOffsetMismatchTwiceInARowWithTheAbortAtTheEndStops() {
        live("app-begins=stop/stop", "app-abort-late")
        send()
        info(Apps.SEND_FAILED)
        assertEquals(2, begins())
    }

    @Test
    fun anEndCardErrorWaitsForTheCard() {
        live("app-end-fail=14:no card")
        send()
        info(CardView.REFUSED)
        quiet(800)
        assertEquals(CardView.REFUSED, apps.view.info)
    }

    @Test
    fun anotherStagesResultToastsAndThePushGoesOn() {
        live("app-ask-ms=60000")
        send()
        info(Apps.CONFIRM)
        sim.current!!.push(Msg.APP_STATE, AppState("paddle", 4, Enums.AppResult.installed, "installed", 99).encode())
        waitFor { toasts.isNotEmpty() }
        assertEquals("paddle installed", toasts.single())
        assertEquals(Apps.CONFIRM, apps.view.info)
    }

    @Test
    fun theBulkGuardEndsAtTheEndReply() {
        live("app-ask-ms=60000", "slow-bps=20000")
        send()
        waitFor { apps.view.send.startsWith("app ") && Remote.bulkSending() }
        info(Apps.CONFIRM, 20_000)
        assertFalse(Remote.bulkSending())
    }

    @Test
    fun theStatementRefusalsAndAVersionZeroPackage() {
        assertNull(source(pkg(magic = "FNSVCST1")))
        assertNull(source(pkg(id = "Paddle")))
        assertNull(source(pkg().copyOf(20)))
        assertNull(source(pkg(), 8667137))
        val zero = source(pkg(version = 0))
        assertNotNull(zero)
        assertEquals("paddle", zero!!.id)
        assertEquals(0L, zero.version)
        assertEquals(3L, source(pkg())!!.version)
    }

    @Test
    fun aReplacedByeAtFortyPercentWaitsForTheUserAndResumesFromThePart() {
        live()
        val go = held()
        sim.current!!.bye(Enums.ByeReason.replaced)
        waitFor { Remote.phase != Phase.LIVE }
        go.countDown()
        info("replaced")
        quiet(2 * Proto.pushGateMs.toLong())
        assertEquals(1, begins())
        assertEquals("replaced", apps.view.info)
        Remote.userAction()
        info(Apps.INSTALLED)
        assertEquals(2, begins())
        resumedAtThePart()
    }

    @Test
    fun aDropAtFortyPercentResumesFromThePartAtTheNextLive() {
        live()
        val go = held()
        sim.current!!.drop("test")
        waitFor { Remote.phase != Phase.LIVE }
        go.countDown()
        info(Apps.INSTALLED)
        assertEquals(2, begins())
        resumedAtThePart()
    }

    private fun byePauses(reason: Int) {
        live()
        val go = held()
        sim.current!!.bye(reason)
        waitFor { Remote.phase != Phase.LIVE }
        go.countDown()
        info(Apps.INSTALLED)
        assertEquals(2, begins())
        resumedAtThePart()
    }

    @Test
    fun aShutdownByeAtFortyPercentPausesAndTheNextLiveResumes() = byePauses(Enums.ByeReason.shutdown)

    @Test
    fun aCompanionOffByeAtFortyPercentPausesAndTheNextLiveResumes() = byePauses(Enums.ByeReason.companionOff)

    @Test
    fun aReplacedPushResumedAfterAnotherClaimRestartsFromZero() {
        live("app-begins=ok/zero")
        val go = held()
        sim.current!!.bye(Enums.ByeReason.replaced)
        waitFor { Remote.phase != Phase.LIVE }
        go.countDown()
        info("replaced")
        Remote.userAction()
        info(Apps.INSTALLED)
        resumedAt(0)
    }

    @Test
    fun aBusyAbortIsRetriedAndResumesAtTheReplyOffset() {
        live("app-begins=step@busy")
        send()
        info(Apps.INSTALLED, advance = true)
        assertEquals(2, begins())
        resumedAt(Proto.updChunk.toLong())
    }

    private fun abortGives(code: String, line: String) {
        live("app-begins=step@$code")
        send()
        info(line)
        quiet(500)
        assertEquals(line, apps.view.info)
        assertEquals(1, begins())
    }

    @Test
    fun aNoSpaceAbortEndsOnNoRoom() = abortGives("noSpace", Apps.NO_ROOM)

    @Test
    fun aWriteErrorAbortEndsOnSendFailed() = abortGives("writeError", Apps.SEND_FAILED)

    @Test
    fun aBadValueAbortEndsOnSendFailed() = abortGives("badValue", Apps.SEND_FAILED)

    @Test
    fun aNoCardAbortWaitsForTheCard() = abortGives("noCard", CardView.REFUSED)

    @Test
    fun aNoCardAbortOnTheLastPieceWaitsForTheCardThenResumesToTheEnd() {
        live("app-begins=last@noCard", "card-run=300+0@manual")
        send()
        info(CardView.REFUSED)
        assertEquals(1, begins())
        sim.device.startCard()
        info(Apps.INSTALLED)
        assertEquals(2, begins())
        resumedAt(3L * Proto.updChunk)
    }

    @Test
    fun anEndCardErrorWaitsForTheCardEpochThenResumes() {
        live("app-begins=endcard", "card-run=300+0@manual")
        send()
        info(CardView.REFUSED)
        sim.device.startCard()
        info(Apps.INSTALLED)
        assertEquals(2, begins())
        resumedAt(60_000)
    }

    @Test
    fun aDropDuringTheEndWaitThenTheResultAtTheReconnectEndsThePush() {
        fresh(retryMs = 60_000)
        live("app-check-ms=3000", replyMs = 3000)
        send()
        waitFor { logs.any { "rx APP_END" in it } }
        sim.current!!.drop("test")
        info(Apps.INSTALLED)
        assertEquals(1, logs.count { "rx APP_END" in it })
        assertTrue(toasts.isEmpty())
    }

    @Test
    fun anOlderStagesKeptResultToastsAndTheNewerPushEndsOnItsOwn() {
        live("app-ask=expire", "app-ask-ms=600")
        send(pkg(id = "tidal"))
        info(Apps.CONFIRM)
        sim.current!!.drop("test")
        waitFor { Remote.phase != Phase.LIVE }
        send(pkg(id = "tidal"))
        waitFor { toasts.isNotEmpty() }
        assertEquals(listOf("tidal ${Apps.EXPIRED}"), toasts.toList())
        info(Apps.EXPIRED)
        assertEquals(1, toasts.size)
        assertTrue(logs.any { it.startsWith("app begin stage=2 ") })
    }

    @Test
    fun aRePushOfTheSameFileEndsOnItsOwnNewStage() {
        fresh(resultMs = 1500)
        live()
        send()
        info(Apps.INSTALLED)
        apps.send()
        waitFor { logs.any { it.startsWith("app begin stage=2 ") } && apps.states >= 2 }
        quiet(2000)
        assertEquals(Apps.INSTALLED, apps.view.info)
        assertTrue(toasts.isEmpty())
    }

    @Test
    fun aBusyAnswerToTheBeginAfterALostEndKeepsTheWait() {
        live("app-end-lost", "app-begins=ok/busy")
        send()
        apps.sync()
        val lives = apps.lives
        waitFor { apps.lives > lives }
        tick(30_000)
        info(Apps.INSTALLED, 20_000, advance = true)
        assertEquals(3, begins())
    }

    @Test
    fun aKeptResultAtTheNextLiveEndsThePushAfterTheBoundPassedWhileDown() {
        fresh(resultMs = 1500)
        live("app-ask-ms=600")
        send()
        info(Apps.CONFIRM)
        sim.current!!.drop("test")
        info(byeText(null))
        tick(2000)
        info(Apps.INSTALLED)
        assertTrue(toasts.isEmpty())
    }

    @Test
    fun aLiveNearTheBoundExtendsItAndAResultInsideThatEndsThePush() {
        fresh(resultMs = 3000, liveMs = 3000)
        live("app-ask-ms=60000")
        send()
        info(Apps.CONFIRM)
        val lives = apps.lives
        vt.addAndGet(2700)
        sim.current!!.drop("test")
        waitFor(advance = false) { apps.lives > lives }
        quiet(500)
        assertEquals(Apps.CONFIRM, apps.view.info)
        sim.current!!.push(Msg.APP_STATE, AppState("paddle", 3, Enums.AppResult.installed, "installed", 1).encode())
        info(Apps.INSTALLED)
    }

    private fun noAnswer(fault: String) {
        fresh(resultMs = 1500, liveMs = 500)
        live(fault)
        send()
        info(Apps.NO_ANSWER, advance = true)
        assertTrue(toasts.isEmpty())
    }

    @Test
    fun aLostResultEndsOnNoAnswer() = noAnswer("app-state-lost")

    @Test
    fun aRestartDuringTheAskEndsOnNoAnswer() = noAnswer("app-restart")

    @Test
    fun aResultForAnIdNotPendingToastsAndChangesNoRow() {
        live()
        sim.current!!.push(Msg.APP_STATE, AppState("other", 1, Enums.AppResult.installed, "installed", 7).encode())
        waitFor { toasts.isNotEmpty() }
        assertEquals(listOf("other ${Apps.INSTALLED}"), toasts.toList())
        assertEquals(AppsView(), apps.view)
    }

    @Test
    fun aFirmwareSendWaitsWhileAnAppSendsAndTheReverse() {
        shadowOf(app.getSystemService(BatteryManager::class.java)).setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 80)
        live()
        val go = held()
        Firmwares.send(held("first.upt", CountDownLatch(0)))
        waitFor { Firmwares.view.info == Apps.SENDING }
        assertFalse(Firmwares.sending)
        go.countDown()
        info(Apps.INSTALLED)
        sim.rescan(sim.device.lib)
        waitFor { logs.any { "rx UPD_BEGIN" in it } && !Firmwares.sending }
        assertTrue(logs.indexOfFirst { "rx UPD_BEGIN" in it } > logs.indexOfFirst { "rx APP_END" in it })
        val fw = CountDownLatch(1)
        Firmwares.send(held("held.upt", fw))
        waitFor { Firmwares.sending }
        send(pkg(version = 4))
        info(Apps.FIRMWARE)
        assertEquals(1, begins())
        fw.countDown()
        waitFor { !Firmwares.sending }
        tick(Proto.pushGateMs.toLong())
        info(Apps.INSTALLED)
        assertEquals(2, begins())
    }

    @Test
    fun aFirmwareSendInAnAppBusyGapWaits() {
        shadowOf(app.getSystemService(BatteryManager::class.java)).setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 80)
        live("app-staged")
        send()
        info(Apps.DAP_BUSY)
        quiet(300)
        Firmwares.send(held("gap.upt", CountDownLatch(0)))
        waitFor { Firmwares.view.info == Apps.SENDING }
        assertTrue(Remote.bulkSending())
        assertFalse(Firmwares.sending)
    }

    @Test
    fun aBluetoothOutputMidPushHoldsAndTheWiredStateResumesWithNoHello() {
        live()
        val lives = apps.lives
        val go = held()
        sim.output(Enums.Output.btHeadset)
        waitFor { Remote.device.playback.state.output == Enums.Output.btHeadset }
        go.countDown()
        info(Apps.BLUETOOTH)
        sim.output(Enums.Output.wired)
        info(Apps.INSTALLED)
        assertEquals(lives, apps.lives)
        resumedAtThePart()
    }

    @Test
    fun aCardDownSeenInStateHoldsAndTheReadyCardResumesWithNoHello() {
        live("card-run=2000+0@manual")
        val lives = apps.lives
        val go = held()
        sim.device.startCard()
        waitFor { PushGate.card(Remote.device) }
        go.countDown()
        info(CardView.REFUSED)
        info(Apps.INSTALLED)
        assertEquals(lives, apps.lives)
        resumedAtThePart()
    }

    @Test
    fun aCancelAtFortyPercentStopsTheDataAndSendsNoEnd() {
        live()
        val go = held()
        Remote.apps.stop()
        val mark = logs.size
        go.countDown()
        waitFor { apps.view.send.isEmpty() && apps.view.info.isEmpty() }
        apps.sync()
        assertEquals(emptyList<String>(), logs.drop(mark).filter { "rx APP_DATA" in it || "rx APP_END" in it })
        assertTrue(logs.none { "rx APP_END" in it })
        assertFalse(Remote.bulkSending())
    }

    @Test
    fun aRefusedByeAfterTheEndEndsTheWait() {
        fresh(resultMs = 1500, liveMs = 500)
        live("app-ask-ms=60000")
        send()
        info(Apps.CONFIRM)
        sim.current!!.bye(Enums.ByeReason.refused)
        info(byeText(Enums.ByeReason.refused))
        val lives = apps.lives
        Remote.userAction()
        waitFor { apps.lives > lives }
        quiet(2000)
        assertEquals(byeText(Enums.ByeReason.refused), apps.view.info)
    }

    @Test
    fun aFileReadFailureEndsOnSendFailed() {
        live()
        val b = pkg()
        apps.pick(Apps.source("app.fnapp", b.size.toLong(), Apps.sha256(ByteArrayInputStream(b)), Apps.head(ByteArrayInputStream(b))) {
            object : InputStream() {
                override fun read(): Int = throw IOException("gone")
            }
        }!!)
        apps.send()
        info(Apps.SEND_FAILED)
    }

    @Test
    fun aRuntimeFailureFromTheFileEndsOnSendFailedAndClearsTheBulkGuard() {
        live()
        val b = pkg()
        apps.pick(Apps.source("app.fnapp", b.size.toLong(), Apps.sha256(ByteArrayInputStream(b)), Apps.head(ByteArrayInputStream(b))) {
            throw SecurityException("grant gone")
        }!!)
        apps.send()
        info(Apps.SEND_FAILED)
        assertFalse(Remote.bulkSending())
    }

    @Test
    fun aSendBeforeAnyDapReadsTheLinkLine() {
        send()
        info(byeText(null))
        apps.sync()
        assertEquals(0, begins())
    }

    @Test
    fun theDigestIsSha256OverTheWholeFile() {
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex(Apps.sha256(ByteArrayInputStream("abc".toByteArray()))))
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", hex(Apps.sha256(ByteArrayInputStream(ByteArray(1_000_000) { 'a'.code.toByte() }))))
    }

    @Test
    fun aPickedFileIsReadAndAResumeSkipsToTheReplyOffset() {
        live("app-begins=step@busy")
        val b = pkg()
        val uri = Uri.parse("content://picked/app.fnapp")
        shadowOf(app.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(b) }
        apps.read(app, uri, "app.fnapp")
        waitFor { apps.view.picked == "paddle app.fnapp" }
        apps.send()
        info(Apps.INSTALLED, advance = true)
        resumedAt(Proto.updChunk.toLong())
    }

    @Test
    fun theLinesAreTheseWords() {
        val words = listOf(
            Apps.CHECKING_FILE to "checking file", Apps.CONFIRM to "confirm on the dap", Apps.CHECKING to "checking on the dap",
            Apps.INSTALLED to "installed", Apps.DECLINED to "declined on the dap", Apps.EXPIRED to "not confirmed in time",
            Apps.PENDING to "installs after this track", Apps.NO_ANSWER to "no answer from the dap",
            Apps.SIGNATURE to "signature not accepted", Apps.OLDER to "older than installed", Apps.NO_ROOM to "no room",
            Apps.BOARD to "wrong board", Apps.DAMAGED to "damaged package", Apps.NEWER to "needs newer firmware",
            Apps.SEND_FAILED to "send failed", Apps.BLUETOOTH to "dap on bluetooth output", Apps.BUSY_STOP to "dap busy, send again later",
            Apps.DAP_BUSY to "dap busy", Apps.OTHER_DAP to "sent to another dap", Apps.STREAMING to "streaming on the dap",
            Apps.NO_INSTALLER to "dap has no app installer", Apps.BAD_PACKAGE to "not an app package", Apps.FIRMWARE to "firmware sending",
            Apps.SENDING to "app sending",
        )
        assertEquals(words.map { it.second }, words.map { it.first })
        assertEquals(words.size, words.map { it.first }.toSet().size)
    }

    private fun byeEnds(reason: Int, line: String) {
        live("app-staged")
        send()
        info(Apps.DAP_BUSY)
        sim.current!!.bye(reason)
        info(line)
        val n = begins()
        val lives = apps.lives
        Remote.userAction()
        waitFor { apps.lives > lives }
        quiet(600)
        assertEquals(n, begins())
    }

    @Test
    fun aRefusedByeEndsThePushWithNoResume() = byeEnds(Enums.ByeReason.refused, byeText(Enums.ByeReason.refused))

    @Test
    fun aVersionByeEndsThePushWithNoResume() = byeEnds(Enums.ByeReason.version, byeText(Enums.ByeReason.version))

    @Test
    fun aStreamingByeEndsThePushWithNoResume() = byeEnds(Enums.ByeReason.streaming, Apps.STREAMING)

    @Test
    fun aPushPausedTenMinutesByReplacedResumesAndItsFirstBusyDoesNotStopIt() {
        fresh(retryMs = 60_000)
        live("app-staged")
        send()
        info(Apps.DAP_BUSY)
        sim.current!!.bye(Enums.ByeReason.replaced)
        info("replaced")
        tick(600_000)
        apps.sync()
        Remote.userAction()
        waitFor { begins() == 2 && apps.view.info != "replaced" }
        assertEquals(Apps.DAP_BUSY, apps.view.info)
    }

    @Test
    fun mismatchBusyBusyMismatchWithNoProgressStops() {
        live("app-begins=stop/busy/busy/stop")
        send()
        info(Apps.SEND_FAILED, advance = true)
        assertEquals(4, begins())
    }

    @Test
    fun mismatchBusyBusyMismatchWithTheAbortsAtTheEndStops() {
        live("app-begins=stop/busy/busy/stop", "app-abort-late")
        send()
        info(Apps.SEND_FAILED, advance = true)
        assertEquals(4, begins())
    }

    @Test
    fun aBusyAbortAtTheEndThenADropPastTheBoundRestartsAndInstalls() {
        live("app-begins=step@busy", "app-abort-late")
        send()
        info(Apps.DAP_BUSY)
        apps.sync()
        val lives = apps.lives
        sim.stop()
        info(byeText(null))
        tick(70_000)
        swap()
        waitFor { apps.lives > lives }
        tick(30_000)
        apps.sync()
        info(Apps.INSTALLED, 20_000)
    }

    @Test
    fun aMismatchThenARestartFromZeroThenAMismatchResumesAgain() {
        live("app-begins=step/stop/zero+stop")
        send()
        info(Apps.INSTALLED)
        assertEquals(4, begins())
    }

    @Test
    fun aStateWhileTheEndSitsInRetryEndsThePush() {
        fresh(retryMs = 60_000)
        live("app-end-busy")
        send()
        info(Apps.DAP_BUSY)
        sim.current!!.push(Msg.APP_STATE, AppState("paddle", 3, Enums.AppResult.installed, "installed", 1).encode())
        info(Apps.INSTALLED)
        assertEquals("", apps.view.send)
        assertTrue(toasts.isEmpty())
    }

    @Test
    fun aLiveOnTheSameDapAfterAFirmwareVersionChangeResumes() {
        live("app-staged")
        send()
        info(Apps.DAP_BUSY)
        swap(version = "sim.v1.next")
        info(Apps.INSTALLED)
        assertEquals("sim.v1.next", Remote.device.hello?.version)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theBulkGuardHoldsArtAndLyricsDuringTheDataAndTheyRunInTheResultWait() {
        val artist = "Heldone"
        ArtCache(File(app.filesDir, "art")).putHit(ArtCache.Entry("name:${Names.fold(artist)}", artist, null, Wikimedia.SOURCE, null, "https://upload.wikimedia.org/$artist.jpg", 1, "File:$artist.jpg"), jpeg())
        Lyrics.init(app, Http { _, _ -> Response(200, "{\"syncedLyrics\":\"[00:01.00]line\\n\",\"instrumental\":false}".toByteArray()) })
        Lyrics.set(LyricSources.NETEASE, false)
        Lyrics.set(LyricSources.LRCLIB, false)
        fresh(resultMs = 60_000)
        val first = Library.track("Heldnone", "Album 0", "Song 0", 1, 200_000)
        live("app-ask-ms=60000", lib = Library(listOf(first)), lrcDir = kotlin.io.path.createTempDirectory("lrc").toFile())
        waitFor { Remote.device.playback.state.track != null }
        Remote.queuePage(0)
        val go = held()
        val from = logs.size
        val rev = Remote.device.lib.dataRev
        sim.rescan(Library(listOf(first, Library.track(artist, "Album 1", "Song 1", 1, 200_000))))
        waitFor { Remote.device.lib.dataRev != rev }
        val lyrics = Lyrics.bulkHolds.get()
        val gates = Remote.bulkGates.get()
        Remote.sourcesChanged()
        Lyrics.set(LyricSources.LRCLIB, true)
        waitFor(15_000) { Lyrics.bulkHolds.get() > lyrics && Remote.bulkGates.get() > gates && Remote.artIdle }
        assertTrue(Remote.bulkSending())
        assertEquals(emptyList<String>(), logs.drop(from).filter { "rx IMG_BEGIN" in it || "rx LRC_PUT" in it })
        val held = Remote.artWants.get()
        go.countDown()
        info(Apps.CONFIRM)
        assertTrue(Remote.artWants.get() > held)
        waitFor(20_000) { sim.device.stored.values.any { it.name == artist } && logs.any { it.startsWith("stored lrc") } }
        assertEquals(Apps.CONFIRM, apps.view.info)
    }

    private fun ends(line: String, vararg faults: String, states: Int = 1) {
        live(*faults)
        send()
        info(line)
        waitFor { apps.states >= states }
        apps.sync()
        assertTrue(toasts.isEmpty())
    }

    @Test
    fun aDeclineReadsDeclinedOnTheDap() = ends(Apps.DECLINED, "app-ask=no")

    @Test
    fun anExpiredAskReadsNotConfirmedInTime() = ends(Apps.EXPIRED, "app-ask=expire")

    @Test
    fun aPendingUpdateReadsInstallsAfterThisTrack() = ends(Apps.PENDING, "app-pending")

    @Test
    fun anOlderPackageReadsOlderThanInstalled() = ends(Apps.OLDER, "app-end-fail=4:older")

    @Test
    fun anEndNoRoomReadsNoRoom() = ends(Apps.NO_ROOM, "app-end-fail=9:no room")

    @Test
    fun anEndLayoutReadsDamagedPackage() = ends(Apps.DAMAGED, "app-end-fail=4:layout")

    @Test
    fun anEndBadValueReadsSendFailed() = ends(Apps.SEND_FAILED, "app-end-fail=2:sha", states = 0)

    private fun noInstaller() {
        info(Apps.NO_INSTALLER)
        send()
        apps.sync()
        assertEquals(Apps.NO_INSTALLER, apps.view.info)
        assertEquals(0, begins())
    }

    @Test
    fun aPushKeptAcrossADowngradeReadsNoInstallerAndStops() {
        live("app-staged")
        send()
        info(Apps.DAP_BUSY)
        val own = swap("features=${Groups.all and Groups.mask(Apps.APP_BIT).inv()}")
        info(Apps.NO_INSTALLER)
        apps.sync()
        assertEquals("", apps.view.send)
        assertTrue(own.none { "rx APP_BEGIN" in it })
        assertFalse(Remote.bulkSending())
    }

    private fun relinkResumes() {
        info(CardView.REFUSED)
        apps.sync()
        val lives = apps.lives
        sim.current!!.drop("test")
        waitFor { apps.lives > lives }
        tick(30_000)
        info(Apps.INSTALLED)
        assertEquals(2, begins())
    }

    @Test
    fun aNoCardAbortsWaitIsClearedByARelinkWithTheCardReady() {
        live("app-begins=step@noCard")
        val go = held { logs.any { "tx APP_ABORT " in it } }
        go.countDown()
        relinkResumes()
        assertEquals(1, logs.count { "rx APP_END" in it })
    }

    @Test
    fun anEndCardErrorsWaitIsClearedByARelinkWithTheCardReady() {
        live("app-begins=endcard")
        send()
        relinkResumes()
    }

    @Test
    fun anEndRetriedThenDroppedEndsOnNoAnswerAtTheBound() {
        fresh(retryMs = 20_000, busyBoundMs = 120_000)
        live("app-end-busy")
        send()
        info(Apps.DAP_BUSY)
        apps.sync()
        tick(10_000)
        val lives = apps.lives
        sim.stop()
        info(byeText(null))
        tick(10_000)
        apps.sync()
        assertEquals(lives, apps.lives)
        swap("app-begins=busy/busy/busy/busy/busy/busy/busy")
        waitFor(20_000) { apps.lives > lives }
        tick(30_000)
        apps.sync()
        info(Apps.DAP_BUSY)
        assertEquals(2, begins())
        tick(20_000)
        apps.sync()
        assertEquals(Apps.NO_ANSWER, apps.view.info)
        assertEquals(2, begins())
    }

    @Test
    fun anEndRetriedThenDroppedPastItsBoundEndsAtTheNextAfterLive() {
        fresh(retryMs = 20_000)
        live("app-end-busy")
        send()
        info(Apps.DAP_BUSY)
        apps.sync()
        val lives = apps.lives
        sim.stop()
        info(byeText(null))
        tick(60_000)
        apps.sync()
        assertEquals(byeText(null), apps.view.info)
        assertEquals(lives, apps.lives)
        swap()
        waitFor(20_000) { apps.lives > lives }
        tick(30_000)
        apps.sync()
        assertEquals(Apps.NO_ANSWER, apps.view.info)
        assertEquals(1, begins())
    }

    @Test
    fun aBoundDueInsideLiveOnAnUnrepliedPushEndsAtTheNextAfterLive() {
        live("app-check-ms=600000")
        send()
        waitFor { logs.any { "rx APP_END" in it } }
        sim.stop()
        apps.sync()
        val lives = apps.lives
        tick(40_000)
        apps.sync()
        assertEquals(lives, apps.lives)
        swap()
        waitFor(20_000) { apps.lives > lives }
        tick(20_000)
        apps.sync()
        assertEquals(1, begins())
        assertTrue(apps.view.info != Apps.NO_ANSWER)
        tick(10_000)
        apps.sync()
        assertEquals(Apps.NO_ANSWER, apps.view.info)
        assertEquals(1, begins())
    }

    private fun restartedUploadEndsOnItsOwnResult(end: String, begins: String, after: Int, resume: Boolean = true) {
        fresh(retryMs = 20_000, busyBoundMs = 600_000)
        live(end, "app-begins=$begins")
        send()
        if (end == "app-end-busy" || resume) info(Apps.DAP_BUSY)
        apps.sync()
        val lives = apps.lives
        if (resume) {
            tick(20_000)
            apps.sync()
        }
        if (end == "app-end-busy") {
            info(Apps.DAP_BUSY)
            sim.current!!.drop("test")
        }
        info(byeText(null))
        waitFor { apps.lives > lives }
        repeat(after) {
            tick(10_000)
            apps.sync()
            assertTrue(apps.view.info != Apps.NO_ANSWER)
        }
        info(Apps.INSTALLED)
        assertTrue(logs.any { it.startsWith("app begin ") && it.endsWith(" offset=0") && "stage=1 " !in it })
    }

    @Test
    fun aRestartedUploadAfterADroppedEndRetryNeverEndsOnTheOldBound() =
        restartedUploadEndsOnItsOwnResult("app-end-busy", "step@busy/ok/zero+step@busy/busy/busy/busy/busy", 8)

    @Test
    fun aRestartedUploadAfterALostEndNeverEndsOnTheOldBound() =
        restartedUploadEndsOnItsOwnResult("app-end-lost", "step@busy/ok/zero+step@busy/busy", 8)

    @Test
    fun aRestartedPlainUploadAfterADroppedEndRetryNeverEndsOnTheOldBound() =
        restartedUploadEndsOnItsOwnResult("app-end-busy", "ok/zero+step@busy/busy/busy/busy", 8, resume = false)

    @Test
    fun aRestartedPlainUploadAfterALostEndNeverEndsOnTheOldBound() =
        restartedUploadEndsOnItsOwnResult("app-end-lost", "ok/zero+step@busy/busy", 8, resume = false)

    @Test
    fun anEndCardErrorThenADropWaitsForTheCardPastTheBound() {
        live("app-begins=endcard")
        send()
        info(CardView.REFUSED)
        apps.sync()
        val lives = apps.lives
        sim.stop()
        info(byeText(null))
        tick(70_000)
        apps.sync()
        assertEquals(lives, apps.lives)
        val dc = AtomicLong(1_000_000)
        swap("card-run=1000+0@hello", clock = dc::get)
        waitFor(20_000) { apps.lives > lives && PushGate.card(Remote.device) }
        tick(30_000)
        apps.sync()
        assertEquals(CardView.REFUSED, apps.view.info)
        dc.addAndGet(2000)
        info(Apps.INSTALLED, 20_000)
    }

    @Test
    fun aCardWaitKeepsOutOfTheBoundAcrossASecondDrop() {
        live("app-begins=endcard")
        send()
        info(CardView.REFUSED)
        apps.sync()
        var lives = apps.lives
        sim.stop()
        info(byeText(null))
        swap("card-run=1000+0@hello", clock = AtomicLong(1_000_000)::get)
        waitFor(20_000) { apps.lives > lives && PushGate.card(Remote.device) }
        lives = apps.lives
        sim.stop()
        info(byeText(null))
        tick(70_000)
        apps.sync()
        assertEquals(lives, apps.lives)
        val dc = AtomicLong(1_000_000)
        swap("card-run=1000+0@hello", clock = dc::get)
        waitFor(20_000) { apps.lives > lives && PushGate.card(Remote.device) }
        tick(30_000)
        apps.sync()
        assertEquals(CardView.REFUSED, apps.view.info)
        dc.addAndGet(2000)
        info(Apps.INSTALLED, 20_000)
    }

    @Test
    fun aCardDownAtTheRelinkAfterADroppedEndLeavesTheResultBound() {
        live("app-check-ms=600000")
        send()
        waitFor { logs.any { "rx APP_END" in it } }
        sim.stop()
        apps.sync()
        val lives = apps.lives
        val dc = AtomicLong(1_000_000)
        swap("card-run=1000+0@hello", clock = dc::get)
        waitFor(20_000) { apps.lives > lives && PushGate.card(Remote.device) }
        tick(30_000)
        apps.sync()
        assertEquals(CardView.REFUSED, apps.view.info)
        tick(30_000)
        apps.sync()
        assertEquals(CardView.REFUSED, apps.view.info)
        dc.addAndGet(2000)
        info(Apps.INSTALLED, 20_000)
    }

    @Test
    fun aCardWaitAfterALostEndLeavesTheResultBound() {
        live("app-end-lost", "app-begins=ok/endcard", "card-run=300+0@manual")
        send()
        apps.sync()
        val lives = apps.lives
        waitFor { apps.lives > lives }
        tick(30_000)
        apps.sync()
        info(CardView.REFUSED)
        tick(30_000)
        apps.sync()
        assertEquals(CardView.REFUSED, apps.view.info)
        sim.device.startCard()
        info(Apps.INSTALLED)
    }

    @Test
    fun aLostEndEndsOnNoAnswerAtTheBoundFromTheEndSend() {
        fresh(resultMs = 1500, liveMs = 500)
        live("app-end-lost", "app-begins=ok/busy/busy/busy/busy/busy/busy/busy/busy/busy/busy/busy")
        send()
        apps.sync()
        val lives = apps.lives
        waitFor { apps.lives > lives }
        tick(500)
        apps.sync()
        info(Apps.DAP_BUSY)
        quiet(1000)
        assertEquals(Apps.NO_ANSWER, apps.view.info)
    }

    @Test
    fun aDeviceWithoutTheAppBitShowsNoInstallerAndSendsNothing() {
        live("features=${Groups.all and Groups.mask(Apps.APP_BIT).inv()}")
        noInstaller()
    }

    @Test
    fun aOneSixteenPeerShowsNoInstallerAndSendsNothing() {
        live(minor = 16)
        noInstaller()
    }

    @Test
    fun theShaPassRunsOffTheMainThreadAndTheRowReadsCheckingFile() {
        val b = pkg()
        val go = CountDownLatch(1)
        val readers = CopyOnWriteArrayList<Thread>()
        val uri = Uri.parse("content://picked/app.fnapp")
        shadowOf(app.contentResolver).registerInputStreamSupplier(uri) {
            readers += Thread.currentThread()
            go.await(10, TimeUnit.SECONDS)
            ByteArrayInputStream(b)
        }
        apps.read(app, uri, "app.fnapp")
        assertEquals(Apps.CHECKING_FILE, apps.view.send)
        shadowOf(Looper.getMainLooper()).idle()
        go.countDown()
        waitFor { apps.view.picked == "paddle app.fnapp" }
        assertEquals("", apps.view.send)
        assertTrue(readers.isNotEmpty())
        assertTrue(readers.none { it === Looper.getMainLooper().thread })
    }

    @Test
    fun checkingWaitsForTheResultWithNoSecondEnd() {
        live("app-check-ms=1200", replyMs = 3000)
        send()
        info(Apps.CHECKING)
        info(Apps.INSTALLED)
        assertEquals(1, logs.count { "rx APP_END" in it })
        assertEquals(1, begins())
    }

    @Test
    fun noResultWithinTheBoundEndsOnNoAnswer() {
        fresh(resultMs = 1500)
        live("app-ask-ms=60000")
        send()
        info(Apps.CONFIRM)
        tick(1500)
        apps.sync()
        info(Apps.NO_ANSWER)
    }

    @Test
    fun aLiveOnAnotherBoardDropsThePushAndSendsNothing() {
        live("app-staged")
        send()
        info(Apps.DAP_BUSY)
        val r1 = swap(board = "r1")
        info(Apps.OTHER_DAP)
        apps.sync()
        assertTrue(r1.none { "rx APP_BEGIN" in it })
    }

    @Test
    fun aFailedCheckGivesOneLineAndNoToast() {
        live("app-end-fail=15:signature")
        send()
        info(Apps.SIGNATURE)
        waitFor { apps.states >= 1 }
        apps.sync()
        assertTrue(toasts.isEmpty())
    }

    @Test
    fun aRedeliveredResultIsTakenOnce() {
        live("app-redeliver")
        send()
        info(Apps.INSTALLED)
        val hellos = logs.count { "hello app" in it }
        sim.current!!.drop("test")
        waitFor { logs.count { "hello app" in it } > hellos && apps.states >= 2 }
        apps.sync()
        assertTrue(toasts.isEmpty())
    }

    @Test
    fun aRedeliveredResultIsTakenAfterAProcessRestart() {
        live("app-redeliver")
        send()
        info(Apps.INSTALLED)
        fresh()
        sim.current!!.drop("test")
        waitFor { apps.lives >= 1 && apps.states >= 1 }
        apps.sync()
        assertTrue(toasts.isEmpty())
    }

    @Test
    fun aLostEndBeginsAgainAtTheSize() {
        live("app-end-lost")
        send()
        apps.sync()
        val lives = apps.lives
        waitFor { apps.lives > lives }
        tick(30_000)
        info(Apps.INSTALLED, 20_000)
        assertEquals(2, begins())
    }

    @Test
    fun aRestartedSerialStillEndsTheNewPush() {
        live("app-ask-ms=500")
        send()
        info(Apps.INSTALLED)
        sim.device.apps.restart()
        send(pkg(version = 4))
        waitFor { logs.count { it.startsWith("app begin stage=1 ") } == 2 }
        info(Apps.CONFIRM)
        info(Apps.INSTALLED)
        assertTrue(toasts.isEmpty())
    }

    @Test
    fun theBoundWithTheLinkDownKeepsTheLinkLineAndTheNextLiveEndsOnNoAnswer() {
        fresh(resultMs = 1500, liveMs = 500)
        live("app-ask-ms=60000")
        send()
        info(Apps.CONFIRM)
        sim.stop()
        info(byeText(null))
        quiet(2000)
        assertEquals(byeText(null), apps.view.info)
        val lives = apps.lives
        swap()
        waitFor { apps.lives > lives }
        tick(500)
        apps.sync()
        info(Apps.NO_ANSWER)
    }
}
