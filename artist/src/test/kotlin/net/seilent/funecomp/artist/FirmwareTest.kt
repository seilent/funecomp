package net.seilent.funecomp.artist

import net.seilent.funecomp.proto.FwStatementBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class FirmwareTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val v1 = blob(100_000, 7)
    private val ap80 = blob(120_000, 11)

    private fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun blob(n: Int, seed: Int) = ByteArray(n) { ((it * 31 + seed) and 0xff).toByte() }

    private class Call(val url: String, val headers: Map<String, String>)

    private inner class Net(var releases: Response = Response(200, Fixtures.load("gh-funeos-releases.json"), mapOf("etag" to "W/\"e1\""))) : Http {
        val calls = ArrayList<Call>()
        var dropAfter = Long.MAX_VALUE
        var ignoreRange = false
        val blobs = HashMap<String, ByteArray>()
        val texts = HashMap<String, String>()
        val bins = HashMap<String, ByteArray>()
        val broken = HashSet<String>()
        var served = 0L

        init {
            for (tag in listOf("v0.3.0", "v0.4.0", "v0.4.1", "v0.5.0")) {
                blobs["$BASE/$tag/v1.upt"] = v1
                blobs["$BASE/$tag/update.upt"] = ap80
                texts["$BASE/$tag/v1.upt.sha256"] = "${sha(v1)}  v1.upt\n"
            }
        }

        override fun get(url: String, headers: Map<String, String>): Response {
            calls += Call(url, headers)
            if (url == Firmware.URL) return if (headers["If-None-Match"] != null && headers["If-None-Match"] == releases.headers["etag"]) Response(304, ByteArray(0)) else releases
            if (url in broken) throw IOException("reset: $url")
            bins[url]?.let { return Response(200, it) }
            return texts[url]?.let { Response(200, it.toByteArray()) } ?: Response(404, ByteArray(0))
        }

        override fun download(url: String, headers: Map<String, String>, sink: (Int, ByteArray, Int) -> Boolean): Response {
            calls += Call(url, headers)
            if (url in broken) throw IOException("reset: $url")
            val b = blobs[url] ?: bins[url] ?: return Response(404, ByteArray(0))
            val from = if (ignoreRange) 0 else headers["Range"]?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            if (from >= b.size) return Response(416, ByteArray(0))
            val code = if (from > 0) 206 else 200
            var sent = 0L
            var i = from
            while (i < b.size) {
                val n = minOf(4096, b.size - i)
                if (sent + n > dropAfter) {
                    val m = (dropAfter - sent).toInt()
                    if (m > 0) sink(code, b.copyOfRange(i, i + m), m)
                    dropAfter = Long.MAX_VALUE
                    throw IOException("dropped")
                }
                sent += n
                served += n
                i += n
                if (!sink(code, b.copyOfRange(i - n, i), n)) break
            }
            return Response(code, ByteArray(0))
        }
    }

    private fun fw(net: Http) = Firmware(tmp.root, net, "0.1.0")

    private fun found(c: Firmware.Check) = c as Firmware.Check.Found

    @Test
    fun picksNewestPublishedWithTheBoardAsset() {
        val f = fw(Net())
        val r = found(f.check("v1", "v0.3.0", prereleases = false))
        assertEquals("v0.4.0", r.release.tag)
        assertEquals(true, r.newer)
        assertEquals("v1.upt", r.release.asset.name)
        assertEquals(100_000L, r.release.asset.size)
        assertEquals("$BASE/v0.4.0/v1.upt.sha256", r.release.asset.sha256Url)
        assertEquals("v0.4.1", found(f.check("v1", "v0.4.0", prereleases = true)).release.tag)
        val ap = found(f.check("ap80", "v0.4.0", prereleases = false))
        assertEquals("update.upt", ap.release.asset.name)
        assertEquals(false, ap.newer)
        assertNull(ap.release.asset.sha256Url)
        assertEquals(sha(ap80), ap.release.asset.digest)
    }

    @Test
    fun anR1TakesOnlyItsOwnAsset() {
        assertEquals("r1.upt", Releases.assetName("r1"))
        val ap = """[{"tag_name":"v0.3.0","draft":false,"prerelease":false,"published_at":"2026-10-10T00:00:00Z","assets":[{"name":"update.upt","size":1,"browser_download_url":"$BASE/v0.3.0/update.upt"},{"name":"v1.upt","size":1,"browser_download_url":"$BASE/v0.3.0/v1.upt"}]}]"""
        assertEquals(Firmware.Check.NoAsset, fw(Net(Response(200, ap.toByteArray()))).check("r1", "v0.1.0", prereleases = true))
        val r1 = """[{"tag_name":"v0.3.0","draft":false,"prerelease":false,"published_at":"2026-10-10T00:00:00Z","assets":[{"name":"update.upt","size":1,"browser_download_url":"$BASE/v0.3.0/update.upt"},{"name":"r1.upt","size":2,"browser_download_url":"$BASE/v0.3.0/r1.upt"}]}]"""
        val r = found(fw(Net(Response(200, r1.toByteArray()))).check("r1", "v0.1.0", prereleases = true))
        assertEquals("r1.upt", r.release.asset.name)
        assertEquals(2L, r.release.asset.size)
    }

    @Test
    fun boardWithoutAssetAndUnknownBoard() {
        val only = """[{"tag_name":"v0.3.0","draft":false,"prerelease":false,"published_at":"2026-10-10T00:00:00Z","assets":[{"name":"update.upt","size":1,"browser_download_url":"$BASE/v0.3.0/update.upt"}]}]"""
        val f = fw(Net(Response(200, only.toByteArray())))
        assertEquals(Firmware.Check.NoAsset, f.check("v1", "v0.1.0", prereleases = true))
        assertEquals(Firmware.Check.NoAsset, f.check("x9", "v0.1.0", prereleases = true))
    }

    @Test
    fun onlyPrereleasesWithTheToggleOffIsNotANoAsset() {
        val pre = """[{"tag_name":"v0.2.0","draft":false,"prerelease":true,"published_at":"2026-10-18T00:00:00Z","assets":[{"name":"v1.upt","size":1,"browser_download_url":"$BASE/v0.2.0/v1.upt"}]}]"""
        val f = fw(Net(Response(200, pre.toByteArray())))
        assertEquals(Firmware.Check.OnlyPre, f.check("v1", "v0.1.0", prereleases = false))
        assertEquals("v0.2.0", found(f.check("v1", "v0.1.0", prereleases = true)).release.tag)
        assertEquals(Firmware.Check.NoAsset, f.check("ap80", "v0.1.0", prereleases = false))

        val mixed = """[{"tag_name":"v0.1.0","draft":false,"prerelease":false,"published_at":"2026-10-10T00:00:00Z","assets":[{"name":"update.upt","size":1,"browser_download_url":"$BASE/v0.1.0/update.upt"}]},""" +
            """{"tag_name":"v0.2.0","draft":false,"prerelease":true,"published_at":"2026-10-18T00:00:00Z","assets":[{"name":"v1.upt","size":1,"browser_download_url":"$BASE/v0.2.0/v1.upt"}]}]"""
        assertEquals(Firmware.Check.OnlyPre, fw(Net(Response(200, mixed.toByteArray()))).check("v1", "v0.1.0", prereleases = false))

        val both = """[{"tag_name":"v0.1.0","draft":false,"prerelease":false,"published_at":"2026-10-10T00:00:00Z","assets":[{"name":"v1.upt","size":1,"browser_download_url":"$BASE/v0.1.0/v1.upt"}]},""" +
            """{"tag_name":"v0.2.0","draft":false,"prerelease":true,"published_at":"2026-10-18T00:00:00Z","assets":[{"name":"v1.upt","size":1,"browser_download_url":"$BASE/v0.2.0/v1.upt"}]}]"""
        val older = found(fw(Net(Response(200, both.toByteArray()))).check("v1", "v0.2.0", prereleases = false))
        assertEquals("v0.1.0", older.release.tag)
        assertEquals(false, older.newer)
    }

    @Test
    fun capturedFunecompResponseParsesWithNoBoardAsset() {
        val f = fw(Net(Response(200, Fixtures.load("gh-funecomp-releases.json"))))
        assertEquals(Firmware.Check.NoAsset, f.check("v1", "v0.1.0", prereleases = true))
        val j = J.parse(Fixtures.text("gh-funecomp-releases.json"))
        assertEquals("sha256:8c6732960900ef151d4b22ff61c11b2e30d898532a14c670b4d892ac5b61413f", j[0]["assets"][0]["digest"].str)
    }

    @Test
    fun privateRepoAndRateLimit() {
        assertEquals(Firmware.Check.NoPublic, fw(Net(Response(404, Fixtures.load("gh-funeos-404.json")))).check("v1", "v0.1.0", false))
        assertEquals(Firmware.Check.RateLimited, fw(Net(Response(403, ByteArray(0), mapOf("x-ratelimit-remaining" to "0")))).check("v1", "v0.1.0", false))
    }

    @Test
    fun etagSentAndNotModifiedUsesTheStoredList() {
        val net = Net()
        val f = fw(net)
        f.check("v1", "v0.1.0", false)
        assertNull(net.calls[0].headers["If-None-Match"])
        assertEquals("v0.4.0", found(f.check("v1", "v0.1.0", false)).release.tag)
        assertEquals("W/\"e1\"", net.calls[1].headers["If-None-Match"])
        assertTrue(net.calls.all { it.headers["User-Agent"] == "funecomp/0.1.0 (https://github.com/seilent/funecomp)" })
    }

    @Test
    fun versionCompare() {
        assertEquals(true, Releases.newer("v0.4.0", "v0.3.0"))
        assertEquals(false, Releases.newer("v0.4.0", "v0.4.0"))
        assertEquals(false, Releases.newer("v0.4.0", "v0.4.0-3-gabc1234"))
        assertEquals(true, Releases.newer("v0.4.1", "v0.4.0-3-gabc1234"))
        assertEquals(true, Releases.newer("v0.10.0", "v0.9.9"))
        assertNull(Releases.newer("v0.4.0", "dev"))
        assertNull(Releases.newer("v0.4.0", "v0.4.0-dirty"))
        assertNull(Releases.newer("v0.4.0", "v0.4.0-3-gabc1234-dirty"))
        assertNull(Releases.newer("v0.4.0", null))
    }

    @Test
    fun downloadVerifiesWithTheSha256Asset() {
        val net = Net()
        val f = fw(net)
        val r = found(f.check("v1", "v0.1.0", false)).release
        val file = f.download(r)
        assertArrayEquals(v1, file.readBytes())
        assertEquals("v0.4.0/v1.upt", file.relativeTo(tmp.root).path)
        val n = net.calls.size
        assertEquals(file, f.download(r))
        assertEquals(n, net.calls.size)
    }

    @Test
    fun resumeWithRangeAfterADrop() {
        val net = Net()
        val f = fw(net)
        val r = found(f.check("ap80", "v0.1.0", false)).release
        net.dropAfter = 50_000
        try {
            f.download(r)
            error("no drop")
        } catch (e: IOException) {
            assertEquals("dropped", e.message)
        }
        assertEquals(50_000L, f.downloaded(r))
        val progress = ArrayList<Long>()
        assertArrayEquals(ap80, f.download(r) { have, _ -> progress += have }.readBytes())
        val gets = net.calls.filter { it.url.endsWith("/update.upt") }
        assertEquals(2, gets.size)
        assertEquals("bytes=50000-", gets[1].headers["Range"])
        assertEquals(120_000L, progress.last())
        assertTrue(progress.first() > 50_000)
    }

    @Test
    fun serverIgnoringRangeRestartsThePart() {
        val net = Net()
        val f = fw(net)
        val r = found(f.check("ap80", "v0.1.0", false)).release
        net.dropAfter = 30_000
        runCatching { f.download(r) }
        net.ignoreRange = true
        assertArrayEquals(ap80, f.download(r).readBytes())
    }

    @Test
    fun checksumRules() {
        val net = Net()
        val f = fw(net)
        val r = found(f.check("v1", "v0.1.0", false)).release
        net.texts["$BASE/v0.4.0/v1.upt.sha256"] = "${"0".repeat(64)}  v1.upt\n"
        assertEquals("checksum mismatch in release", runCatching { f.download(r) }.exceptionOrNull()?.message)
        net.texts["$BASE/v0.4.0/v1.upt.sha256"] = "${"0".repeat(64)}  v1.upt\n"
        val shaOnly = Release(r.tag, false, r.publishedAt, Asset(r.asset.name, r.asset.size, r.asset.url, null, r.asset.sha256Url))
        assertEquals("checksum mismatch", runCatching { f.download(shaOnly) }.exceptionOrNull()?.message)
        assertFalse(f.file(shaOnly).exists())
        assertEquals(0L, f.downloaded(shaOnly))
        val none = Release(r.tag, false, r.publishedAt, Asset(r.asset.name, r.asset.size, r.asset.url, null, null))
        val before = net.calls.count { it.url == r.asset.url }
        assertEquals("no checksum in release", runCatching { f.download(none) }.exceptionOrNull()?.message)
        assertEquals(before, net.calls.count { it.url == r.asset.url })
    }

    @Test
    fun clearRemovesTheTagDir() {
        val net = Net()
        val f = fw(net)
        val r = found(f.check("v1", "v0.1.0", false)).release
        net.dropAfter = 10_000
        runCatching { f.download(r) }
        f.clear(r)
        assertFalse(f.file(r).parentFile!!.exists())
    }

    private fun asset(tag: String, name: String, b: ByteArray, digest: Boolean = true, size: Int? = b.size) =
        """{"name":"$name",${if (size != null) "\"size\":$size," else ""}"browser_download_url":"$BASE/$tag/$name"${if (digest) ""","digest":"sha256:${sha(b)}"""" else ""}}"""

    private fun release(tag: String, at: String, vararg assets: String, pre: Boolean = false) =
        """{"tag_name":"$tag","draft":false,"prerelease":$pre,"published_at":"$at","assets":[${assets.joinToString(",")}]}"""

    private fun list(vararg releases: String) = Response(200, "[${releases.joinToString(",")}]".toByteArray())

    private val v1Sig = FwStatementBuilder.forBytes("v1", v1)

    private fun signedNet(sig: ByteArray = v1Sig, digest: Boolean = true, size: Int? = sig.size): Net {
        val net = Net(list(release("v0.4.0", "2026-10-27T00:00:00Z", asset("v0.4.0", "v1.upt", v1), asset("v0.4.0", "v1.upt.sig", sig, digest, size))))
        net.bins["$BASE/v0.4.0/v1.upt.sig"] = sig
        return net
    }

    private fun gets(net: Net, suffix: String) = net.calls.count { it.url.endsWith(suffix) }

    @Test
    fun theImageAndItsStatementAreStored() {
        val net = signedNet()
        val f = fw(net)
        val r = found(f.check("v1", "v0.3.0", false, signed = true)).release
        assertEquals("$BASE/v0.4.0/v1.upt.sig", r.asset.sigUrl)
        assertEquals(sha(v1Sig), r.asset.sigDigest)
        f.downloadSigned(r)
        assertArrayEquals(v1, f.file(r).readBytes())
        assertArrayEquals(v1Sig, f.sigFile(r).readBytes())
        assertArrayEquals(v1Sig, f.signature(r))
        assertEquals(sha(v1), f.sums(r)!!.sha256)
        assertEquals(java.security.MessageDigest.getInstance("MD5").digest(v1).joinToString("") { "%02x".format(it) }, f.sums(r)!!.md5)
        val n = net.calls.size
        f.downloadSigned(r)
        assertEquals(n, net.calls.size)
    }

    @Test
    fun anImageFromAnOlderAppFetchesOnlyTheStatement() {
        val net = signedNet()
        val f = fw(net)
        val r = found(f.check("v1", "v0.3.0", false, signed = true)).release
        f.file(r).apply { parentFile.mkdirs() }.writeBytes(v1)
        assertNull(f.sums(r))
        f.downloadSigned(r)
        assertEquals(0, gets(net, "/v1.upt"))
        assertEquals(1, gets(net, "/v1.upt.sig"))
        assertEquals(sha(v1), f.sums(r)!!.sha256)
        assertArrayEquals(v1Sig, f.signature(r))
    }

    @Test
    fun aStatementThatDoesNotMatchIsABadSignatureFile() {
        val cases = listOf(
            "r1 board" to signedNet(FwStatementBuilder.forBytes("r1", v1)),
            "another size" to signedNet(FwStatementBuilder.build(size = v1.size + 1L, sha256 = FwStatementBuilder.sha256(v1))),
            "another sha" to signedNet(FwStatementBuilder.build(size = v1.size.toLong(), sha256 = FwStatementBuilder.sha256(ap80))),
            "513 bytes" to signedNet(ByteArray(513), digest = false, size = 134),
            "listed 600" to signedNet(size = 600),
            "digest" to signedNet().also { it.bins["$BASE/v0.4.0/v1.upt.sig"] = FwStatementBuilder.forBytes("v1", v1, version = "v0.4.1") },
            "layout" to signedNet(v1Sig + byteArrayOf(0), digest = false),
        )
        for ((why, net) in cases) {
            val dir = tmp.newFolder()
            val f = Firmware(dir, net, "0.1.0")
            val r = found(f.check("v1", "v0.3.0", false, signed = true)).release
            assertEquals(why, Firmware.BAD_SIG, runCatching { f.downloadSigned(r) }.exceptionOrNull()?.message)
            assertTrue(why, f.complete(r))
            assertFalse(why, f.sigFile(r).exists())
            assertNull(why, f.signature(r))
            if (why == "listed 600") assertEquals(why, 0, gets(net, "/v1.upt.sig"))
        }
    }

    @Test
    fun anUnlistedSizeReadsAtMost513Bytes() {
        val net = signedNet(ByteArray(100_000), digest = false, size = null)
        val f = fw(net)
        val r = found(f.check("v1", "v0.3.0", false, signed = true)).release
        val before = net.served
        assertEquals(Firmware.BAD_SIG, runCatching { f.downloadSigned(r) }.exceptionOrNull()?.message)
        assertTrue("${net.served - before - v1.size}", net.served - before - v1.size <= 4096)
        assertTrue(f.complete(r))
    }

    @Test
    fun aStatementTransportErrorIsNotARefusal() {
        val net = signedNet()
        net.broken += "$BASE/v0.4.0/v1.upt.sig"
        val f = fw(net)
        val r = found(f.check("v1", "v0.3.0", false, signed = true)).release
        val e = runCatching { f.downloadSigned(r) }.exceptionOrNull()
        assertTrue("$e", e is IOException && e !is FirmwareRefused)
        assertTrue(f.complete(r))
        assertNull(f.signature(r))
        net.broken.clear()
        f.downloadSigned(r)
        assertEquals(1, gets(net, "/v1.upt"))
        assertArrayEquals(v1Sig, f.signature(r))
    }

    @Test
    fun aCachedStatementThatNoLongerMatchesTheListIsFetchedAgain() {
        val net = signedNet()
        val f = fw(net)
        val r = found(f.check("v1", "v0.3.0", false, signed = true)).release
        f.downloadSigned(r)
        f.sigFile(r).writeBytes(FwStatementBuilder.forBytes("v1", v1, version = "v0.4.1"))
        assertNull(f.signature(r))
        f.downloadSigned(r)
        assertEquals(2, gets(net, "/v1.upt.sig"))
        assertArrayEquals(v1Sig, f.signature(r))
    }

    @Test
    fun aSignedDeviceSkipsReleasesWithoutAStatement() {
        val unsigned = list(release("v0.4.0", "2026-10-27T00:00:00Z", asset("v0.4.0", "v1.upt", v1)))
        assertEquals(Firmware.Check.Unsigned, fw(Net(unsigned)).check("v1", "v0.3.0", false, signed = true))
        assertEquals("v0.4.0", found(fw(Net(unsigned)).check("v1", "v0.3.0", false, signed = false)).release.tag)
        val mixed = list(
            release("v0.3.0", "2026-10-20T00:00:00Z", asset("v0.3.0", "v1.upt", v1), asset("v0.3.0", "v1.upt.sig", v1Sig)),
            release("v0.4.0", "2026-10-27T00:00:00Z", asset("v0.4.0", "v1.upt", v1)),
        )
        assertEquals("v0.3.0", found(fw(Net(mixed)).check("v1", "v0.1.0", false, signed = true)).release.tag)
        assertEquals("v0.4.0", found(fw(Net(mixed)).check("v1", "v0.1.0", false, signed = false)).release.tag)
        assertEquals(Firmware.Check.NoAsset, fw(Net(unsigned)).check("ap80", "v0.3.0", false, signed = true))
        val pre = list(release("v0.4.0", "2026-10-27T00:00:00Z", asset("v0.4.0", "v1.upt", v1), pre = true))
        assertEquals(Firmware.Check.Unsigned, fw(Net(pre)).check("v1", "v0.3.0", false, signed = true))
        assertEquals(Firmware.Check.OnlyPre, fw(Net(pre)).check("v1", "v0.3.0", false, signed = false))
    }

    @Test
    fun anR1TakesItsOwnStatement() {
        val r1 = blob(50_000, 3)
        val json = list(release("v0.4.0", "2026-10-27T00:00:00Z", asset("v0.4.0", "v1.upt", v1), asset("v0.4.0", "v1.upt.sig", v1Sig), asset("v0.4.0", "r1.upt", r1), asset("v0.4.0", "r1.upt.sig", FwStatementBuilder.forBytes("r1", r1))))
        val net = Net(json)
        net.blobs["$BASE/v0.4.0/r1.upt"] = r1
        net.bins["$BASE/v0.4.0/v1.upt.sig"] = v1Sig
        net.bins["$BASE/v0.4.0/r1.upt.sig"] = FwStatementBuilder.forBytes("r1", r1)
        val f = fw(net)
        val r = found(f.check("r1", "v0.3.0", false, signed = true)).release
        assertEquals("$BASE/v0.4.0/r1.upt.sig", r.asset.sigUrl)
        f.downloadSigned(r)
        assertArrayEquals(FwStatementBuilder.forBytes("r1", r1), f.signature(r))
        assertEquals(0, gets(net, "/v1.upt.sig"))
    }

    @Test
    fun anA105TakesItsOwnAssetAndStatement() {
        val a105 = blob(50_000, 5)
        val sig = FwStatementBuilder.forBytes("a105", a105)
        val json = list(release("v0.4.0", "2026-10-27T00:00:00Z", asset("v0.4.0", "v1.upt", v1), asset("v0.4.0", "v1.upt.sig", v1Sig), asset("v0.4.0", "a105.upt", a105), asset("v0.4.0", "a105.upt.sig", sig)))
        val net = Net(json)
        net.blobs["$BASE/v0.4.0/a105.upt"] = a105
        net.bins["$BASE/v0.4.0/v1.upt.sig"] = v1Sig
        net.bins["$BASE/v0.4.0/a105.upt.sig"] = sig
        assertEquals("a105.upt", Releases.assetName("a105"))
        assertEquals("a105", Releases.boardOf("a105.upt"))
        val f = fw(net)
        val r = found(f.check("a105", "v0.3.0", false, signed = true)).release
        assertEquals("a105.upt", r.asset.name)
        assertEquals("$BASE/v0.4.0/a105.upt.sig", r.asset.sigUrl)
        f.downloadSigned(r)
        assertArrayEquals(sig, f.signature(r))
        assertEquals(0, gets(net, "/v1.upt.sig"))
    }

    companion object {
        const val BASE = "https://github.com/seilent/funeos/releases/download"
    }
}
