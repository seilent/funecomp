package net.seilent.funecomp.artist

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

class Asset(val name: String, val size: Long, val url: String, val digest: String?, val sha256Url: String?)

class Release(val tag: String, val prerelease: Boolean, val publishedAt: String, val asset: Asset)

class FirmwareRefused(message: String) : IOException(message)

object Releases {
    private val VERSION = Regex("^v?([0-9]+)\\.([0-9]+)\\.([0-9]+)(?:-([0-9]+)-g[0-9a-f]+)?$")
    private val HEX64 = Regex("\\b[0-9a-fA-F]{64}\\b")

    fun assetName(board: String?): String? = when (board) {
        "v1" -> "v1.upt"
        "ap80" -> "update.upt"
        else -> null
    }

    fun pick(json: J, board: String?, prereleases: Boolean): Release? {
        val name = assetName(board) ?: return null
        return json.list.mapNotNull { r ->
            if (r["draft"].bool == true) return@mapNotNull null
            val pre = r["prerelease"].bool == true
            if (pre && !prereleases) return@mapNotNull null
            val tag = r["tag_name"].str ?: return@mapNotNull null
            val at = r["published_at"].str ?: return@mapNotNull null
            val assets = r["assets"].list
            val a = assets.firstOrNull { it["name"].str == name } ?: return@mapNotNull null
            val url = a["browser_download_url"].str ?: return@mapNotNull null
            val size = a["size"].long ?: return@mapNotNull null
            val sha = assets.firstOrNull { it["name"].str == "$name.sha256" }?.get("browser_download_url")?.str
            Release(tag, pre, at, Asset(name, size, url, a["digest"].str?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase(), sha))
        }.maxByOrNull { it.publishedAt }
    }

    fun version(v: String?): List<Int>? {
        val m = VERSION.find(v?.trim() ?: return null) ?: return null
        return (1..4).map { m.groupValues[it].ifEmpty { "0" }.toInt() }
    }

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
) {
    sealed interface Check {
        data class Found(val release: Release, val newer: Boolean?) : Check
        data object NoAsset : Check
        data object OnlyPre : Check
        data object NoPublic : Check
        data object RateLimited : Check
    }

    private val ua = "funecomp/$version (https://github.com/seilent/funecomp)"

    @Synchronized
    fun check(board: String?, device: String?, prereleases: Boolean): Check {
        val body = File(dir, RELEASES)
        val etag = File(dir, ETAG)
        val headers = HashMap(mapOf("User-Agent" to ua, "Accept" to "application/vnd.github+json"))
        if (body.isFile && etag.isFile) headers["If-None-Match"] = etag.readText().trim()
        val r = http.get(URL, headers)
        val text = when {
            r.code == 200 -> r.text.also {
                save(body, it)
                r.headers["etag"]?.let { e -> save(etag, e) } ?: etag.delete()
            }
            r.code == 304 && body.isFile -> body.readText()
            r.code == 404 -> return Check.NoPublic
            r.code == 403 || r.code == 429 -> return Check.RateLimited
            else -> throw IOException("http ${r.code}: $URL")
        }
        val json = try {
            J.parse(text)
        } catch (e: IllegalArgumentException) {
            throw IOException("${e.message}: $URL")
        }
        val rel = Releases.pick(json, board, prereleases)
            ?: return if (!prereleases && Releases.pick(json, board, true) != null) Check.OnlyPre else Check.NoAsset
        return Check.Found(rel, Releases.newer(rel.tag, device))
    }

    fun file(rel: Release) = File(dir, "${rel.tag}/${rel.asset.name}")

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
        if (f.isFile && f.length() == size) return f
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
                }
                if (r.code == 416) part.delete()
                if (r.code != 200 && r.code != 206) throw IOException("http ${r.code}: ${rel.asset.url}")
            } finally {
                out?.close()
            }
        }
        if (part.length() != size) {
            if (part.length() > size) part.delete()
            throw IOException("short download ${part.length()} of $size")
        }
        if (hex(part) != want) {
            part.delete()
            throw FirmwareRefused("checksum mismatch")
        }
        if (!part.renameTo(f)) throw IOException("rename ${f.name}")
        return f
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
        const val RELEASES = "releases.json"
        const val ETAG = "releases.etag"
        const val PART = ".part"

        fun hex(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(65536)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
