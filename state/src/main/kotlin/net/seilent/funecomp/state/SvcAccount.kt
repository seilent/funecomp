package net.seilent.funecomp.state

import net.seilent.funecomp.proto.SvcInfo
import net.seilent.funecomp.proto.SvcState

data class SvcAccount(val signedIn: Boolean? = null, val account: String? = null, val downloads: Long? = null) {
    override fun toString() = "SvcAccount(signedIn=$signedIn, downloads=$downloads)"

    companion object {
        fun of(s: SvcInfo) = SvcAccount(s.signedIn, s.account?.takeIf { s.signedIn == true && it.isNotEmpty() }, s.downloads)

        fun of(s: SvcState): Map<String, SvcAccount> = s.services.mapNotNull { i -> i.id?.takeIf { it.isNotEmpty() }?.let { it to of(i) } }.toMap()
    }
}
