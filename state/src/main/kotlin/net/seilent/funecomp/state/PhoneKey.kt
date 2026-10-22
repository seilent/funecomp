package net.seilent.funecomp.state

import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums

data class PhoneKey(val title: String, val artist: String) {
    companion object {
        fun of(s: State): PhoneKey? {
            if (s.source != Enums.Source.phone) return null
            val title = s.title?.takeIf { it.isNotBlank() } ?: return null
            val artist = s.artist?.takeIf { it.isNotBlank() } ?: return null
            return PhoneKey(title, artist)
        }
    }
}
