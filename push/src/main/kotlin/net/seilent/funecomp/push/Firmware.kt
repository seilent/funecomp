package net.seilent.funecomp.push

import net.seilent.funecomp.proto.FwStatement
import net.seilent.funekit.artist.Http
import net.seilent.funekit.artist.J
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class Asset(
    val name: String,
    val size: Long,
    val url: String,
    val digest: String?,
    val sha256Url: String?,
    val sigUrl: String? = null,
    val sigSize: Long? = null,
    val sigDigest: String? = null,
)

class Sums(val md5: String, val sha256: String)

class Release(val tag: String, val prerelease: Boolean, val publishedAt: String, val asset: Asset)

class FirmwareRefused(message: String) : IOException(message)

object Releases {
    private val VERSION = Regex("^v?([0-9]+)\\.([0-9]+)\\.([0-9]+)(?:-([0-9]+)-g[0-9a-f]+)?$")
    private val HEX64 = Regex("\\b[0-9a-fA-F]{64}\\b")
    private val APP_VERSION = Regex("^v?[0-9]+\\.[0-9]+\\.[0-9]+")

    fun assetName(board: String?): String? = when (board) {
        "v1" -> "v1.upt"
        "ap80" -> "update.upt"
        "r1" -> "r1.upt"
        "a105" -> "a105.upt"
        else -> null
    }

    fun boardOf(name: String): String? = listOf("v1", "ap80", "r1", "a105").firstOrNull { assetName(it) == name }

    private fun digest(a: J) = a["digest"].str?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()

    private fun https(a: J?) = a?.get("browser_download_url")?.str?.takeIf { it.startsWith("https://") }

    private fun release(r: J, name: (String) -> String, prereleases: Boolean, signed: Boolean): Release? {
        if (r["draft"].bool == true) return null
        val pre = r["prerelease"].bool == true
        if (pre && !prereleases) return null
        val tag = r["tag_name"].str ?: return null
        val at = r["published_at"].str ?: return null
        val n = name(tag)
        val assets = r["assets"].list
        val a = assets.firstOrNull { it["name"].str == n } ?: return null
        val url = https(a) ?: return null
        val size = a["size"].long ?: return null
        val sha = https(assets.firstOrNull { it["name"].str == "$n.sha256" })
        val s = assets.firstOrNull { it["name"].str == "$n.sig" }
        val sigUrl = https(s)
        if (signed && sigUrl == null) return null
        return Release(tag, pre, at, Asset(n, size, url, digest(a), sha, sigUrl, s?.get("size")?.long, s?.let { digest(it) }))
    }

    fun pick(json: J, board: String?, prereleases: Boolean, signed: Boolean = false): Release? {
        val name = assetName(board) ?: return null
        return json.list.mapNotNull { release(it, { name }, prereleases, signed) }.maxByOrNull { it.publishedAt }
    }

    fun appAsset(tag: String) = "funecomp-$tag.apk"

    fun app(json: J): Release? = json.list.mapNotNull { r -> release(r, { appAsset(it) }, prereleases = true, signed = false)?.takeIf { version(it.tag) != null } }
        .reduceOrNull { a, b -> if (newer(b.tag, a.tag) == true) b else a }

    fun version(v: String?): List<Int>? {
        val m = VERSION.find(v?.trim() ?: return null) ?: return null
        return (1..4).map { m.groupValues[it].ifEmpty { "0" }.toInt() }
    }

    fun appVersion(name: String?): String? = APP_VERSION.find(name?.trim() ?: return null)?.value

    fun newer(tag: String, device: String?): Boolean? {
        val t = version(tag) ?: return null
        val d = version(device) ?: return null
        for (i in t.indices) if (t[i] != d[i]) return t[i] > d[i]
        return false
    }

    fun sha256Line(text: String): String? = HEX64.find(text)?.value?.lowercase()
}

class Firmware(
    private val dir: File,
    private val http: Http,
    version: String,
    private val url: String = URL,
) {
    sealed interface Check {
        data class Found(val release: Release, val newer: Boolean?) : Check
        data object NoAsset : Check
        data object OnlyPre : Check
        data object NoPublic : Check
        data object RateLimited : Check
        data object Unsigned : Check
    }

    private val ua = "funecomp/$version (https://github.com/seilent/funecomp)"

    private inline fun releases(refused: (Check) -> Nothing): J {
        val body = File(dir, RELEASES)
        val etag = File(dir, ETAG)
        val headers = HashMap(mapOf("User-Agent" to ua, "Accept" to "application/vnd.github+json"))
        if (body.isFile && etag.isFile) headers["If-None-Match"] = etag.readText().trim()
        val r = http.get(url, headers)
        if (r.body.size > MAX_LIST) throw IOException("release list over $MAX_LIST bytes: $url")
        val text = when {
            r.code == 200 -> r.text.also {
                save(body, it)
                r.headers["etag"]?.let { e -> save(etag, e) } ?: etag.delete()
            }
            r.code == 304 && body.isFile -> body.readText()
            r.code == 404 -> refused(Check.NoPublic)
            r.code == 403 || r.code == 429 -> refused(Check.RateLimited)
            else -> throw IOException("http ${r.code}: $url")
        }
        return try {
            J.parse(text)
        } catch (e: IllegalArgumentException) {
            throw IOException("${e.message}: $url")
        }
    }

    @Synchronized
    fun check(board: String?, device: String?, prereleases: Boolean, signed: Boolean = false): Check {
        val json = releases { return it }
        val rel = Releases.pick(json, board, prereleases, signed) ?: return when {
            !prereleases && Releases.pick(json, board, true, signed) != null -> Check.OnlyPre
            signed && Releases.pick(json, board, true) != null -> Check.Unsigned
            !prereleases && Releases.pick(json, board, true) != null -> Check.OnlyPre
            else -> Check.NoAsset
        }
        return Check.Found(rel, Releases.newer(rel.tag, device))
    }

    @Synchronized
    fun app(installed: String?): Check {
        val json = releases { return it }
        val rel = Releases.app(json) ?: return Check.NoAsset
        return Check.Found(rel, Releases.newer(rel.tag, Releases.appVersion(installed)))
    }

    fun file(rel: Release) = File(dir, "${rel.tag}/${rel.asset.name}")

    fun sigFile(rel: Release) = File(dir, "${rel.tag}/${rel.asset.name}.sig")

    private fun sumsFile(rel: Release) = File(dir, "${rel.tag}/${rel.asset.name}.sums")

    fun complete(rel: Release) = file(rel).let { it.isFile && it.length() == rel.asset.size }

    fun sums(rel: Release): Sums? {
        val f = sumsFile(rel)
        if (!f.isFile || !complete(rel)) return null
        val p = f.readText().trim().split(' ')
        return if (p.size == 2) Sums(p[0], p[1]) else null
    }

    fun signature(rel: Release): ByteArray? {
        val f = sigFile(rel)
        if (!f.isFile) return null
        val b = f.readBytes()
        if (FwStatement.parse(b) == null) return null
        if (rel.asset.sigDigest != null && sha256(b) != rel.asset.sigDigest) return null
        return b
    }

    fun downloaded(rel: Release): Long = File(file(rel).path + PART).length()

    fun sha256(a: Asset): String {
        val listed = a.sha256Url?.let { u ->
            val r = http.get(u, mapOf("User-Agent" to ua))
            if (r.code != 200) throw IOException("http ${r.code}: $u")
            Releases.sha256Line(r.text) ?: throw FirmwareRefused("bad checksum file")
        }
        if (listed != null && a.digest != null && listed != a.digest) throw FirmwareRefused("checksum mismatch in release")
        return listed ?: a.digest ?: throw FirmwareRefused("no checksum in release")
    }

    fun download(rel: Release, progress: (Long, Long) -> Unit = { _, _ -> }): File {
        val f = file(rel)
        val size = rel.asset.size
        if (f.isFile && f.length() == size) {
            if (sums(rel) == null) writeSums(rel, sums(f))
            return f
        }
        val want = sha256(rel.asset)
        f.parentFile.mkdirs()
        val part = File(f.path + PART)
        val from = part.length()
        if (from < size) {
            val headers = HashMap(mapOf("User-Agent" to ua))
            if (from > 0) headers["Range"] = "bytes=$from-"
            var out: FileOutputStream? = null
            var have = from
            try {
                val r = http.download(rel.asset.url, headers) { code, buf, n ->
                    val o = out ?: FileOutputStream(part, code == 206).also {
                        out = it
                        if (code != 206) have = 0
                    }
                    o.write(buf, 0, n)
                    have += n
                    progress(have, size)
                    have <= size
                }
                if (r.code == 416) part.delete()
                if (r.code != 200 && r.code != 206) throw IOException("http ${r.code}: ${rel.asset.url}")
            } finally {
                out?.close()
            }
        }
        val n = part.length()
        if (n > size) {
            part.delete()
            throw IOException("download over size $n of $size")
        }
        if (n != size) throw IOException("short download $n of $size")
        val got = sums(part)
        if (got.sha256 != want) {
            part.delete()
            throw FirmwareRefused("checksum mismatch")
        }
        if (!part.renameTo(f)) throw IOException("rename ${f.name}")
        writeSums(rel, got)
        return f
    }

    fun downloadSigned(rel: Release, progress: (Long, Long) -> Unit = { _, _ -> }): File {
        val f = download(rel, progress)
        val a = rel.asset
        if (a.sigUrl != null) {
            val have = sigFile(rel).takeIf { it.isFile }?.readBytes()
            if (have == null || (a.sigDigest != null && sha256(have) != a.sigDigest)) fetchSig(rel, sums(rel)?.sha256 ?: throw IOException("no sums ${f.name}"))
        }
        return f
    }

    private fun badSig(rel: Release): Nothing {
        sigFile(rel).delete()
        throw FirmwareRefused(BAD_SIG)
    }

    private fun fetchSig(rel: Release, sha: String) {
        val a = rel.asset
        val url = a.sigUrl ?: return
        if ((a.sigSize ?: 0) > FwStatement.MAX) badSig(rel)
        val got = ByteArrayOutputStream()
        val r = http.download(url, mapOf("User-Agent" to ua)) { _, buf, n ->
            got.write(buf, 0, minOf(n, FwStatement.MAX + 1 - got.size()))
            got.size() <= FwStatement.MAX
        }
        if (r.code != 200) throw IOException("http ${r.code}: $url")
        val b = got.toByteArray()
        if (b.size > FwStatement.MAX) badSig(rel)
        if (a.sigDigest != null && sha256(b) != a.sigDigest) badSig(rel)
        val st = FwStatement.parse(b) ?: badSig(rel)
        if (st.board != Releases.boardOf(a.name) || st.size != a.size || a.size > 0xFFFFFFFFL || st.sha256 != sha) badSig(rel)
        val f = sigFile(rel)
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(b)
        if (!tmp.renameTo(f)) throw IOException("rename ${f.name}")
    }

    private fun writeSums(rel: Release, s: Sums) {
        val f = sumsFile(rel)
        val tmp = File(f.path + ".tmp")
        tmp.writeText("${s.md5} ${s.sha256}\n")
        if (!tmp.renameTo(f)) throw IOException("rename ${f.name}")
    }

    fun clear(rel: Release) {
        file(rel).parentFile?.deleteRecursively()
    }

    private fun save(f: File, text: String) {
        dir.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    companion object {
        const val URL = "https://api.github.com/repos/seilent/funeos/releases?per_page=10"
        const val APP_URL = "https://api.github.com/repos/seilent/funecomp/releases?per_page=10"
        const val RELEASES = "releases.json"
        const val ETAG = "releases.etag"
        const val PART = ".part"
        const val MAX_LIST = 2 shl 20
        const val BAD_SIG = "bad signature file"

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

        fun sha256(b: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(b))

        fun sums(f: File): Sums = f.inputStream().use { sums(it) }

        fun sums(input: InputStream): Sums {
            val md5 = MessageDigest.getInstance("MD5")
            val sha = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(65536)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md5.update(buf, 0, n)
                sha.update(buf, 0, n)
            }
            return Sums(hex(md5.digest()), hex(sha.digest()))
        }
    }
}
