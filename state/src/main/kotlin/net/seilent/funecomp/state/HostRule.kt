package net.seilent.funecomp.state

import java.net.URI
import java.net.URISyntaxException

object HostRule {
    class Url(val scheme: String, val host: String, val port: Int, val target: String)

    fun host(h: String?): Boolean {
        if (h.isNullOrEmpty() || '.' !in h || ".." in h) return false
        if (!h.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' }) return false
        if (h.first() == '.' || h.first() == '-' || h.last() == '.' || h.last() == '-') return false
        return !ipv4(h)
    }

    fun https(s: String?): Url? {
        if (s == null || !s.startsWith("https://")) return null
        val u = parse(s) ?: return null
        return u.takeIf { it.scheme == "https" && it.port == 443 && host(it.host) }
    }

    fun link(uri: String?, host: String?): Boolean {
        if (!host(host) || uri == null || https(uri) == null) return false
        if (uri.any { it.code !in 0x21..0x7e || it == '\\' }) return false
        val u = try {
            URI(uri)
        } catch (_: URISyntaxException) {
            return false
        }
        if (u.scheme != "https" || u.rawUserInfo != null || u.port !in setOf(-1, 443)) return false
        val h = u.host ?: return false
        return '%' !in h && h == host
    }

    fun parse(s: String): Url? {
        val c = s.indexOf("://")
        if (c < 0) return null
        val scheme = s.substring(0, c).lowercase()
        if (scheme != "https" && scheme != "http") return null
        val a = c + 3
        val e = s.indexOfAny(charArrayOf('/', '?', '#'), a).let { if (it < 0) s.length else it }
        var auth = s.substring(a, e)
        if ('@' in auth || auth.isEmpty() || auth[0] == '[') return null
        var port = if (scheme == "https") 443 else 80
        val colon = auth.lastIndexOf(':')
        if (colon >= 0) {
            val p = auth.substring(colon + 1)
            auth = auth.substring(0, colon)
            if (p.isEmpty() || p.length > 5 || !p.all { it in '0'..'9' }) return null
            port = p.toInt()
            if (port !in 1..65535) return null
        }
        val host = buildString {
            for (ch in auth) {
                val l = if (ch in 'A'..'Z') ch + 32 else ch
                if (!(l in 'a'..'z' || l in '0'..'9' || l == '-' || l == '.')) return null
                append(l)
            }
        }
        if (host.isEmpty() || host.first() == '.' || host.first() == '-') return null
        var rest = s.substring(e)
        rest.indexOf('#').takeIf { it >= 0 }?.let { rest = rest.substring(0, it) }
        if (rest.isEmpty() || rest[0] == '?') rest = "/$rest"
        val q = rest.indexOf('?')
        val path = if (q < 0) rest else rest.substring(0, q)
        val query = if (q < 0) "" else rest.substring(q)
        val target = dots(path) + query
        if (target.isEmpty() || target[0] != '/' || target.any { it.code <= 0x20 || it.code >= 0x7f }) return null
        return Url(scheme, host, port, target)
    }

    fun segments(target: String): List<String> {
        val path = target.substringBefore('?')
        val out = ArrayList<String>()
        var at = 1
        while (at <= path.length) {
            val k = path.indexOf('/', at).let { if (it < 0) path.length else it }
            out += path.substring(at, k)
            at = k + 1
        }
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.lastIndex)
        return out
    }

    private fun dots(path: String): String {
        val out = ArrayList<String>()
        var trail = false
        var at = 1
        while (true) {
            val e = path.indexOf('/', at)
            val last = e < 0
            val seg = if (last) path.substring(minOf(at, path.length)) else path.substring(at, e)
            trail = false
            when {
                seg == ".." -> {
                    if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                    trail = true
                }
                seg == "." -> trail = true
                !last -> out += seg
                seg.isEmpty() -> trail = true
                else -> out += seg
            }
            if (last) break
            at = e + 1
        }
        val r = out.joinToString("") { "/$it" }
        return if (trail || r.isEmpty()) "$r/" else r
    }

    private fun ipv4(s: String): Boolean {
        var parts = 0
        var i = 0
        while (parts < 4) {
            if (i >= s.length || s[i] !in '0'..'9') return false
            var x = 0
            val b = i
            while (i < s.length && s[i] in '0'..'9' && i - b < 3) x = x * 10 + (s[i++] - '0')
            if (x > 255 || (i - b > 1 && s[b] == '0')) return false
            parts++
            if (parts < 4) {
                if (i >= s.length || s[i] != '.') return false
                i++
            }
        }
        return i == s.length
    }
}
