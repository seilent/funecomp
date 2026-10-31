package net.seilent.funecomp.state

object PkcePolicy {
    const val MAX_BYTES = 2048

    sealed interface Seen {
        data object Pass : Seen
        data object Blocked : Seen
        class Caught(val url: String) : Seen
        data object TooLong : Seen
    }

    class Prefix internal constructor(private val url: HostRule.Url) {
        private val want = HostRule.segments(url.target)

        fun matches(u: HostRule.Url) = u.scheme == "https" && u.host == url.host && u.port == url.port && HostRule.segments(u.target).take(want.size) == want
    }

    fun prefix(s: String?): Prefix? {
        val u = HostRule.https(s) ?: return null
        if ('?' in u.target || HostRule.segments(u.target).isEmpty()) return null
        return Prefix(u)
    }

    fun seen(url: String, prefix: Prefix): Seen {
        if (!url.startsWith("https://")) return Seen.Blocked
        val u = HostRule.parse(url) ?: return Seen.Pass
        if (!prefix.matches(u)) return Seen.Pass
        return if (url.toByteArray(Charsets.UTF_8).size > MAX_BYTES) Seen.TooLong else Seen.Caught(url)
    }
}
