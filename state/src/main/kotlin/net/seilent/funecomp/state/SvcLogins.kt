package net.seilent.funecomp.state

import net.seilent.funecomp.proto.SvcLogin
import net.seilent.funecomp.proto.SvcLoginEnd

data class SvcLogins(val open: Map<String, Login> = emptyMap()) {
    data class Login(val service: String, val seq: Long, val uri: String?, val host: String?, val userCode: String?, val seen: Boolean = false) {
        val ok: Boolean get() = HostRule.link(uri, host)
    }

    val shown: Login? get() = open.values.firstOrNull { !it.seen && it.ok }

    fun login(l: SvcLogin): SvcLogins {
        val service = l.service?.takeIf { it.isNotEmpty() } ?: return this
        val seq = l.seq ?: return this
        if (open[service]?.seq == seq) return this
        return copy(open = open + (service to Login(service, seq, l.uri, l.host, l.userCode)))
    }

    fun end(e: SvcLoginEnd): SvcLogins {
        val service = e.service ?: return this
        if (open[service]?.seq != e.seq) return this
        return copy(open = open - service)
    }

    fun seen(service: String, seq: Long, on: Boolean = true): SvcLogins {
        val l = open[service]?.takeIf { it.seq == seq && it.seen != on } ?: return this
        return copy(open = open + (service to l.copy(seen = on)))
    }

    fun unseen(): SvcLogins = copy(open = open.mapValues { it.value.copy(seen = false) })
}
