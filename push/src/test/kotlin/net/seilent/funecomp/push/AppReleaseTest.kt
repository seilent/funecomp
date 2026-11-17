package net.seilent.funecomp.push

import net.seilent.funekit.artist.Fixtures
import net.seilent.funekit.artist.Http
import net.seilent.funekit.artist.J
import net.seilent.funekit.artist.Response
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppReleaseTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val apk = ByteArray(60_000) { ((it * 13 + 5) and 0xff).toByte() }

    private fun asset(tag: String, name: String, b: ByteArray, digest: Boolean = true) =
        """{"name":"$name","size":${b.size},"browser_download_url":"$BASE/$tag/$name"${if (digest) ""","digest":"sha256:${Firmware.sha256(b)}"""" else ""}}"""

    private fun apkAsset(tag: String, digest: Boolean = true) = asset(tag, "funecomp-$tag.apk", apk, digest)

    private fun release(tag: String, at: String, vararg assets: String, pre: Boolean = true, draft: Boolean = false) =
        """{"tag_name":"$tag","draft":$draft,"prerelease":$pre,"published_at":"$at","assets":[${assets.joinToString(",")}]}"""

    private fun list(vararg releases: String) = "[${releases.joinToString(",")}]"

    private class Net(val list: String) : Http {
        val calls = ArrayList<String>()
        val texts = HashMap<String, String>()
        val bins = HashMap<String, ByteArray>()

        override fun get(url: String, headers: Map<String, String>): Response {
            calls += url
            if (url == Firmware.APP_URL) return Response(200, list.toByteArray())
            bins[url]?.let { return Response(200, it) }
            return texts[url]?.let { Response(200, it.toByteArray()) } ?: Response(404, ByteArray(0))
        }
    }

    private fun feed(net: Http) = Firmware(tmp.root, net, "0.3.0", Firmware.APP_URL)

    private fun found(c: Firmware.Check) = c as Firmware.Check.Found

    @Test
    fun theHighestVersionWithItsApkIsPickedPrereleasesIncluded() {
        val net = Net(
            list(
                release("v0.3.0", "2026-11-11T00:00:00Z", apkAsset("v0.3.0")),
                release("v0.4.0", "2026-11-12T00:00:00Z", apkAsset("v0.4.0")),
                release("v0.3.1", "2026-11-20T00:00:00Z", apkAsset("v0.3.1"), pre = false),
                release("v0.5.0", "2026-11-21T00:00:00Z", asset("v0.5.0", "notes.txt", apk)),
                release("nightly", "2026-11-22T00:00:00Z", apkAsset("nightly")),
                release("v0.6.0", "2026-11-23T00:00:00Z", apkAsset("v0.6.0"), draft = true),
            ),
        )
        val r = found(feed(net).app("0.3.0-ca3df57a"))
        assertEquals("v0.4.0", r.release.tag)
        assertTrue(r.release.prerelease)
        assertEquals(true, r.newer)
        assertEquals("funecomp-v0.4.0.apk", r.release.asset.name)
        assertEquals("$BASE/v0.4.0/funecomp-v0.4.0.apk", r.release.asset.url)
        assertEquals(apk.size.toLong(), r.release.asset.size)
        assertEquals(Firmware.sha256(apk), r.release.asset.digest)
        assertEquals(listOf(Firmware.APP_URL), net.calls)
    }

    @Test
    fun noReleaseWithAnApkUnderAParsedTagIsNoAsset() {
        val net = Net(
            list(
                release("v0.5.0", "2026-11-21T00:00:00Z", asset("v0.5.0", "funecomp.apk", apk)),
                release("nightly", "2026-11-22T00:00:00Z", apkAsset("nightly")),
            ),
        )
        assertEquals(Firmware.Check.NoAsset, feed(net).app("0.3.0"))
        assertNull(Releases.app(J.parse(list())))
    }

    @Test
    fun testBuildNamesCountAsTheirNumericPart() {
        assertEquals("0.3.0", Releases.appVersion("0.3.0-ca3df57a"))
        assertEquals("0.3.0", Releases.appVersion("0.3.0"))
        assertEquals(true, Releases.newer("v0.4.0", Releases.appVersion("0.3.0-ca3df57a")))
        assertEquals(false, Releases.newer("v0.3.0", Releases.appVersion("0.3.0-ca3df57a")))
        assertEquals(false, Releases.newer("v0.3.0", Releases.appVersion("0.3.0")))
        assertEquals(false, Releases.newer("v0.2.9", Releases.appVersion("0.3.0")))
        assertEquals(true, Releases.newer("v0.3.10", Releases.appVersion("0.3.9-1a2b3c4d")))
        assertNull(Releases.newer("v0.4.0", Releases.appVersion("dev")))
        val net = Net(list(release("v0.4.0", "2026-11-12T00:00:00Z", apkAsset("v0.4.0"))))
        assertEquals(false, found(feed(net).app("0.4.0-1a2b3c4d")).newer)
        assertEquals(true, found(feed(net).app("0.3.0-ca3df57a")).newer)
    }

    @Test
    fun theCapturedReleaseListGivesItsApk() {
        val r = Releases.app(J.parse(Fixtures.text("gh-funecomp-releases.json")))!!
        assertEquals("v0.1.0", r.tag)
        assertTrue(r.prerelease)
        assertEquals("funecomp-v0.1.0.apk", r.asset.name)
        assertEquals("8c6732960900ef151d4b22ff61c11b2e30d898532a14c670b4d892ac5b61413f", r.asset.digest)
        assertNull(r.asset.sha256Url)
    }

    @Test
    fun theSha256AssetIsCheckedWhenTheReleaseHasOne() {
        val tag = "v0.4.0"
        val name = "funecomp-$tag.apk"
        val sums = """{"name":"$name.sha256","size":90,"browser_download_url":"$BASE/$tag/$name.sha256"}"""
        val net = Net(list(release(tag, "2026-11-12T00:00:00Z", apkAsset(tag, digest = false), sums)))
        net.bins["$BASE/$tag/$name"] = apk
        net.texts["$BASE/$tag/$name.sha256"] = "${"0".repeat(64)}  $name\n"
        val f = feed(net)
        val r = found(f.app("0.3.0")).release
        assertEquals("$BASE/$tag/$name.sha256", r.asset.sha256Url)
        assertEquals("checksum mismatch", runCatching { f.download(r) }.exceptionOrNull()?.message)
        assertFalse(f.file(r).exists())
        assertEquals(0L, f.downloaded(r))
        net.texts["$BASE/$tag/$name.sha256"] = "${Firmware.sha256(apk)}  $name\n"
        assertArrayEquals(apk, f.download(r).readBytes())
        f.clear(r)
        assertFalse(f.file(r).parentFile!!.exists())
    }

    @Test
    fun assetUrlsOffHttpsAreNotUsed() {
        val tag = "v0.4.0"
        val name = "funecomp-$tag.apk"
        val plain = BASE.replace("https://", "http://")
        val apkOff = """{"name":"$name","size":${apk.size},"browser_download_url":"$plain/$tag/$name","digest":"sha256:${Firmware.sha256(apk)}"}"""
        assertEquals(Firmware.Check.NoAsset, feed(Net(list(release(tag, "2026-11-12T00:00:00Z", apkOff)))).app("0.3.0"))
        val sumsOff = """{"name":"$name.sha256","size":90,"browser_download_url":"$plain/$tag/$name.sha256"}"""
        val r = found(feed(Net(list(release(tag, "2026-11-12T00:00:00Z", apkAsset(tag), sumsOff)))).app("0.3.0")).release
        assertEquals("$BASE/$tag/$name", r.asset.url)
        assertNull(r.asset.sha256Url)
    }

    companion object {
        const val BASE = "https://github.com/seilent/funecomp/releases/download"
    }
}
