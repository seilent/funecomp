package net.seilent.funecomp.state

import net.seilent.funecomp.proto.gen.Proto
import java.security.MessageDigest

class SvcOpens(hosts: String = "", private val save: (String) -> Unit = {}) {
    enum class Kind { NOTICE, OPEN, TOAST }

    enum class Verdict { OPEN, ASK, NONE }

    private data class Key(val service: String, val seq: Long, val h: String)

    private class Acts {
        var at: Long? = null
        val kinds = HashSet<Kind>()
    }

    private val keys = LinkedHashMap<Key, Acts>()
    private val asks = ArrayList<Long>()
    private val pairs = ArrayList(hosts.lines().filter { it.isNotBlank() }.takeLast(HOSTS))

    val hosts: String get() = synchronized(this) { pairs.joinToString("\n") }

    @Synchronized
    fun take(service: String, seq: Long, link: String, listed: Boolean, known: Boolean, kind: Kind, now: Long, tap: Boolean = false): Verdict {
        if (kind == Kind.OPEN && (!listed || !known)) return Verdict.ASK
        val k = key(service, seq, link)
        val a = keys[k]
        if (tap) {
            record(k, a, kind, null)
            return Verdict.OPEN
        }
        if (a != null && kind in a.kinds) return Verdict.NONE
        val counted = a?.at != null
        if (!counted && full(now)) {
            if (kind != Kind.OPEN) record(k, a, kind, null)
            return Verdict.ASK
        }
        record(k, a, kind, now)
        return Verdict.OPEN
    }

    @Synchronized
    fun opened(service: String, seq: Long, link: String): Boolean = keys[key(service, seq, link)]?.kinds?.contains(Kind.OPEN) == true

    @Synchronized
    fun known(service: String, host: String): Boolean = line(service, host)?.let { it in pairs } == true

    @Synchronized
    fun remember(service: String, host: String) {
        val l = line(service, host) ?: return
        if (l in pairs) return
        pairs += l
        while (pairs.size > HOSTS) pairs.removeAt(0)
        save(pairs.joinToString("\n"))
    }

    private fun line(service: String, host: String): String? = "$service $host".takeIf { '\n' !in it && '\r' !in it }

    private fun record(k: Key, a: Acts?, kind: Kind, now: Long?) {
        val acts = a ?: Acts().also {
            keys[k] = it
            while (keys.size > KEYS) keys.remove(keys.keys.first())
        }
        acts.kinds += kind
        if (acts.at == null && now != null) {
            acts.at = now
            asks += now
        }
    }

    private fun full(now: Long): Boolean {
        asks.removeAll { now - it >= WINDOW }
        return asks.size >= CAP
    }

    private fun key(service: String, seq: Long, link: String): Key {
        val d = MessageDigest.getInstance("SHA-256").digest(link.toByteArray(Charsets.UTF_8))
        return Key(service, seq, d.copyOf(16).joinToString("") { "%02x".format(it) })
    }

    companion object {
        const val KEYS = 16
        const val HOSTS = 32
        const val CAP = 3
        val WINDOW = Proto.svcPkceMs.toLong()
    }
}
