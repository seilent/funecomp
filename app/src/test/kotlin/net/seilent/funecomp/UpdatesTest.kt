package net.seilent.funecomp

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.Signature
import android.content.pm.SigningInfo
import net.seilent.funecomp.push.Firmware
import net.seilent.funecomp.ui.updateValue
import net.seilent.funekit.artist.Http
import net.seilent.funekit.artist.Response
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSigningInfo
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdatesTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val device get() = app.getSharedPreferences(Main.DEVICE_PREFS, Context.MODE_PRIVATE)
    private val nm get() = shadowOf(app.getSystemService(NotificationManager::class.java))
    private val apk = ByteArray(40_000) { ((it * 11 + 3) and 0xff).toByte() }

    private fun asset(tag: String, name: String, digest: String?) =
        """{"name":"$name","size":${apk.size},"browser_download_url":"$BASE/$tag/$name"${if (digest != null) ""","digest":"sha256:$digest"""" else ""}}"""

    private fun release(tag: String, vararg assets: String) =
        """{"tag_name":"$tag","draft":false,"prerelease":true,"published_at":"2026-11-18T00:00:00Z","assets":[${assets.joinToString(",")}]}"""

    private fun withApk(tag: String) = release(tag, asset(tag, "funecomp-$tag.apk", Firmware.sha256(apk)))

    private class Net(var list: String) : Http {
        val calls = CopyOnWriteArrayList<String>()
        val bins = HashMap<String, ByteArray>()
        val texts = HashMap<String, String>()
        @Volatile var broken = false
        @Volatile var done: () -> Unit = {}

        override fun download(url: String, headers: Map<String, String>, sink: (Int, ByteArray, Int) -> Boolean) = super.download(url, headers, sink).also { done() }

        override fun get(url: String, headers: Map<String, String>): Response {
            calls += url
            if (broken) throw IOException("offline")
            if (url == Firmware.APP_URL) return Response(200, list.toByteArray())
            bins[url]?.let { return Response(200, it) }
            return texts[url]?.let { Response(200, it.toByteArray()) } ?: Response(404, ByteArray(0))
        }

        fun lists() = calls.count { it == Firmware.APP_URL }
    }

    @Before
    fun setUp() {
        Updates.front = null
    }

    @After
    fun tearDown() {
        Updates.front = null
        Updates.cancel()
        Updates.idle()
    }

    private fun start(net: Net, installed: String = "0.3.0") {
        Updates.init(app, net, installed)
        Updates.idle()
    }

    private fun due(chance: Boolean = false) {
        Updates.due(chance)
        Updates.idle()
    }

    private fun checked() {
        Updates.check()
        Updates.idle()
    }

    private fun aDayAgo() = device.edit().putLong(Updates.LAST, System.currentTimeMillis() - 25 * HOUR).commit()

    private fun title() = nm.getNotification(UpdateNotice.ID)?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    @Test
    fun aCheckWithinADayAsksNothingAndADayLaterAsksOnce() {
        val net = Net("[${withApk("v0.3.0")}]")
        start(net)
        device.edit().putLong(Updates.LAST, System.currentTimeMillis() - HOUR).commit()
        due()
        due(chance = true)
        assertEquals(0, net.lists())
        aDayAgo()
        due()
        assertEquals(1, net.lists())
        due()
        due(chance = true)
        assertEquals(1, net.lists())
        assertEquals("up to date", updateValue(Updates.view))
    }

    @Test
    fun aFirstStartAndAClockSetBackBothCheck() {
        val net = Net("[${withApk("v0.3.0")}]")
        start(net)
        due()
        assertEquals(1, net.lists())
        device.edit().putLong(Updates.LAST, System.currentTimeMillis() + HOUR).commit()
        due()
        assertEquals(2, net.lists())
    }

    @Test
    fun aFailedCheckIsSilentAndWaitsForTheNextChance() {
        val net = Net("[${withApk("v0.4.0")}]")
        net.broken = true
        start(net)
        due()
        assertEquals(1, net.lists())
        assertEquals("offline", Updates.view.status)
        assertFalse(device.contains(Updates.LAST))
        assertEquals(0, nm.allNotifications.size)
        due()
        assertEquals(1, net.lists())
        net.broken = false
        due(chance = true)
        assertEquals(2, net.lists())
        assertEquals("v0.4.0" to "newer", Updates.view.tag to updateValue(Updates.view))
    }

    @Test
    fun aNewerVersionPostsOneNoticePerVersion() {
        val net = Net("[${withApk("v0.4.0")}]")
        start(net)
        due()
        assertEquals("funeCOMP v0.4.0", title())
        assertEquals(Main.ACTION_UPDATE, shadowOf(nm.getNotification(UpdateNotice.ID)!!.contentIntent).savedIntent.action)
        app.getSystemService(NotificationManager::class.java).cancelAll()
        aDayAgo()
        due()
        assertEquals(2, net.lists())
        assertEquals(0, nm.allNotifications.size)
        net.list = "[${withApk("v0.5.0")},${withApk("v0.4.0")}]"
        aDayAgo()
        due()
        assertEquals("funeCOMP v0.5.0", title())
        assertEquals(1, nm.allNotifications.size)
    }

    @Test
    fun aPageCheckOrOffNotificationsPostNothingAndAreAskedAgainLater() {
        val net = Net("[${withApk("v0.4.0")}]")
        start(net)
        checked()
        assertEquals("v0.4.0" to "newer", Updates.view.tag to updateValue(Updates.view))
        assertEquals(0, nm.allNotifications.size)
        nm.setNotificationsEnabled(false)
        aDayAgo()
        due()
        assertEquals(2, net.lists())
        assertEquals(0, nm.allNotifications.size)
        nm.setNotificationsEnabled(true)
        aDayAgo()
        due()
        assertEquals("funeCOMP v0.4.0", title())
    }

    @Test
    fun aTestBuildOfTheLatestTagIsUpToDateAndClearsTheNotice() {
        val net = Net("[${withApk("v0.4.0")}]")
        start(net)
        due()
        assertEquals("funeCOMP v0.4.0", title())
        start(net, installed = "0.4.0-1a2b3c4d")
        checked()
        assertEquals(false, Updates.view.newer)
        assertEquals("up to date", updateValue(Updates.view))
        assertNull(nm.getNotification(UpdateNotice.ID))
    }

    @Test
    fun aChecksumMismatchIsRefusedAndTheFileDeleted() {
        val tag = "v0.4.0"
        val name = "funecomp-$tag.apk"
        val net = Net("[${release(tag, asset(tag, name, null), """{"name":"$name.sha256","size":90,"browser_download_url":"$BASE/$tag/$name.sha256"}""")}]")
        net.bins["$BASE/$tag/$name"] = apk
        net.texts["$BASE/$tag/$name.sha256"] = "${"0".repeat(64)}  $name\n"
        start(net)
        checked()
        Updates.install()
        Updates.idle()
        assertEquals("checksum mismatch", Updates.view.info)
        assertFalse(Updates.view.busy)
        assertFalse(File(app.cacheDir, "update/$tag").exists())
    }

    private fun sig(seed: Int) = Signature(ByteArray(64) { (it * 3 + seed).toByte() })

    private fun pkg(name: String, vararg history: Signature, signers: Array<Signature> = arrayOf(history.last()), code: Long = NEXT): PackageInfo {
        val s = Shadow.newInstanceOf(SigningInfo::class.java)
        val shadow = Shadow.extract<ShadowSigningInfo>(s)
        shadow.setSignatures(signers)
        if (history.size > 1) shadow.setPastSigningCertificates(arrayOf(*history))
        return PackageInfo().apply {
            packageName = name
            signingInfo = s
            longVersionCode = code
        }
    }

    private fun downloaded(tag: String) = File(app.cacheDir, "update/$tag/funecomp-$tag.apk")

    private fun signedBy(tag: String, archive: PackageInfo, vararg mine: Signature): Net {
        shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName).apply {
            signingInfo = pkg(app.packageName, *mine).signingInfo
            longVersionCode = CODE
        }
        shadowOf(app.packageManager).setPackageArchiveInfo(downloaded(tag).path, archive)
        val net = Net("[${withApk(tag)}]")
        net.bins["$BASE/$tag/funecomp-$tag.apk"] = apk
        start(net)
        checked()
        return net
    }

    private fun current() = app.packageManager.packageInstaller.allSessions.single().sessionId

    private fun status(code: Int, id: Int = current()) = Intent().putExtra(PackageInstaller.EXTRA_STATUS, code).putExtra(PackageInstaller.EXTRA_SESSION_ID, id)

    @Test
    fun aMatchingApkGoesToTheInstallerAndItsFileIsDeleted() {
        val old = sig(1)
        val key = sig(2)
        signedBy("v0.4.0", pkg(app.packageName, old, key), old, key)
        Updates.install()
        Updates.idle()
        assertEquals("installing", updateValue(Updates.view))
        assertFalse(downloaded("v0.4.0").parentFile!!.exists())
        assertEquals(1, app.packageManager.packageInstaller.allSessions.size)
        val asks = ArrayList<Intent>()
        Updates.front = { asks += it }
        val confirm = Intent(CONFIRM)
        Updates.status(status(PackageInstaller.STATUS_PENDING_USER_ACTION).putExtra(Intent.EXTRA_INTENT, confirm))
        assertEquals(listOf(CONFIRM), asks.map { it.action })
        Updates.resumed(true)
        assertEquals(1, asks.size)
        Updates.status(status(PackageInstaller.STATUS_FAILURE_ABORTED))
        assertEquals("install cancelled", Updates.view.info)
    }

    @Test
    fun aConfirmWhileTheAppIsAwayWaitsForTheResume() {
        val old = sig(1)
        val key = sig(2)
        signedBy("v0.4.0", pkg(app.packageName, old, key), old, key)
        Updates.install()
        Updates.idle()
        Updates.status(status(PackageInstaller.STATUS_PENDING_USER_ACTION).putExtra(Intent.EXTRA_INTENT, Intent(CONFIRM)))
        val asks = ArrayList<Intent>()
        Updates.front = { asks += it }
        Updates.resumed(true)
        Updates.resumed(true)
        assertEquals(listOf(CONFIRM), asks.map { it.action })
    }

    @Test
    fun aSessionLeftByAnEarlierProcessIsAbandonedBeforeANewOne() {
        val old = sig(1)
        val key = sig(2)
        val pi = app.packageManager.packageInstaller
        val left = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        signedBy("v0.4.0", pkg(app.packageName, old, key), old, key)
        Updates.install()
        Updates.idle()
        assertEquals("installing", updateValue(Updates.view))
        assertEquals(1, pi.allSessions.size)
        assertFalse(pi.allSessions.any { it.sessionId == left })
    }

    @Test
    fun aCheckDuringThePendingConfirmKeepsInstalling() {
        val old = sig(1)
        val key = sig(2)
        signedBy("v0.4.0", pkg(app.packageName, old, key), old, key)
        Updates.install()
        Updates.idle()
        Updates.status(status(PackageInstaller.STATUS_PENDING_USER_ACTION).putExtra(Intent.EXTRA_INTENT, Intent(CONFIRM)))
        val id = current()
        checked()
        assertTrue(Updates.view.installing)
        assertEquals("installing", updateValue(Updates.view))
        Updates.install()
        Updates.idle()
        assertEquals(id, current())
        assertTrue(Updates.view.installing)
    }

    @Test
    fun aStatusFromAnAbandonedSessionChangesNothing() {
        val old = sig(1)
        val key = sig(2)
        val left = app.packageManager.packageInstaller.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        signedBy("v0.4.0", pkg(app.packageName, old, key), old, key)
        Updates.install()
        Updates.idle()
        val asks = ArrayList<Intent>()
        Updates.front = { asks += it }
        Updates.status(status(PackageInstaller.STATUS_PENDING_USER_ACTION).putExtra(Intent.EXTRA_INTENT, Intent(CONFIRM)))
        Updates.status(status(PackageInstaller.STATUS_FAILURE_ABORTED, left))
        assertTrue(Updates.view.installing)
        assertEquals("", Updates.view.info)
        assertEquals(listOf(CONFIRM), asks.map { it.action })
    }

    @Test
    fun aStartedConfirmWithNoStatusClearsInstallingAtTheNextResume() {
        val old = sig(1)
        val key = sig(2)
        signedBy("v0.4.0", pkg(app.packageName, old, key), old, key)
        Updates.install()
        Updates.idle()
        Updates.status(status(PackageInstaller.STATUS_PENDING_USER_ACTION).putExtra(Intent.EXTRA_INTENT, Intent(CONFIRM)))
        val asks = ArrayList<Intent>()
        Updates.front = { asks += it }
        Updates.resumed(true)
        assertEquals(1, asks.size)
        assertTrue(Updates.view.installing)
        Updates.resumed(true)
        assertFalse(Updates.view.installing)
        assertEquals("newer", updateValue(Updates.view))
        assertEquals(1, asks.size)
    }

    @Test
    fun aCancelAfterTheLastByteInstallsNothing() {
        val old = sig(1)
        val key = sig(2)
        val net = signedBy("v0.4.0", pkg(app.packageName, old, key), old, key)
        net.done = { Updates.cancel() }
        Updates.install()
        Updates.idle()
        assertEquals(0, app.packageManager.packageInstaller.allSessions.size)
        assertEquals("", Updates.view.info)
        assertFalse(Updates.view.busy || Updates.view.installing)
        assertFalse(downloaded("v0.4.0").parentFile!!.exists())
    }

    @Test
    fun otherTagsAreClearedBeforeADownload() {
        val old = sig(1)
        val key = sig(2)
        val left = File(app.cacheDir, "update/v0.3.9/funecomp-v0.3.9.apk.part")
        left.parentFile!!.mkdirs()
        left.writeBytes(apk)
        signedBy("v0.4.0", pkg(app.packageName, old, key), old, key)
        Updates.install()
        Updates.idle()
        assertFalse(left.parentFile!!.exists())
        assertTrue(File(app.cacheDir, "update/${Firmware.RELEASES}").isFile)
    }

    @Test
    fun aNewInitDropsAHeldConfirm() {
        val old = sig(1)
        val key = sig(2)
        signedBy("v0.4.0", pkg(app.packageName, old, key), old, key)
        Updates.install()
        Updates.idle()
        Updates.status(status(PackageInstaller.STATUS_PENDING_USER_ACTION).putExtra(Intent.EXTRA_INTENT, Intent(CONFIRM)))
        start(Net("[${withApk("v0.4.0")}]"))
        val asks = ArrayList<Intent>()
        Updates.front = { asks += it }
        Updates.resumed(true)
        assertEquals(0, asks.size)
    }

    @Test
    fun anApkWithAnotherKeyIsRefusedBeforeTheInstaller() {
        val old = sig(1)
        val key = sig(2)
        signedBy("v0.4.0", pkg(app.packageName, sig(9)), old, key)
        Updates.install()
        Updates.idle()
        assertEquals("unknown signing key", Updates.view.info)
        assertFalse(downloaded("v0.4.0").parentFile!!.exists())
        assertEquals(0, app.packageManager.packageInstaller.allSessions.size)
    }

    @Test
    fun theSameKeyAndLineageAtAHigherCodeMatches() {
        val old = sig(1)
        val key = sig(2)
        assertNull(Updates.refusal(pkg(app.packageName, old, key), pkg(app.packageName, old, key, code = CODE)))
    }

    @Test
    fun anApkAtTheInstalledCodeOrBelowIsOlder() {
        val old = sig(1)
        val key = sig(2)
        val mine = pkg(app.packageName, old, key, code = CODE)
        assertEquals("older than installed", Updates.refusal(pkg(app.packageName, old, key, code = CODE), mine))
        assertEquals("older than installed", Updates.refusal(pkg(app.packageName, old, key, code = CODE - 1), mine))
    }

    @Test
    fun anyOtherSignerIsRefused() {
        val old = sig(1)
        val key = sig(2)
        val mine = pkg(app.packageName, old, key, code = CODE)
        assertEquals("unknown signing key", Updates.refusal(pkg(app.packageName, sig(9)), mine))
        assertEquals("unknown signing key", Updates.refusal(pkg(app.packageName, key), mine))
        assertEquals("unknown signing key", Updates.refusal(pkg(app.packageName, sig(9), key), mine))
        assertEquals("unknown signing key", Updates.refusal(pkg(app.packageName, old, key, signers = arrayOf(key, sig(9))), mine))
        assertEquals("unknown signing key", Updates.refusal(PackageInfo().apply { packageName = app.packageName }, mine))
        assertEquals("wrong package", Updates.refusal(pkg("net.seilent.other", old, key), mine))
        assertEquals("damaged package", Updates.refusal(null, mine))
        assertEquals("wrong package", Updates.refusal(pkg(app.packageName, old, key), null))
    }

    companion object {
        const val BASE = "https://github.com/seilent/funecomp/releases/download"
        const val HOUR = 3_600_000L
        const val CODE = 7L
        const val NEXT = 8L
        const val CONFIRM = "android.content.pm.action.CONFIRM_INSTALL"
    }
}
