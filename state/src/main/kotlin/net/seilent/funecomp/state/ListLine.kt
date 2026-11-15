package net.seilent.funecomp.state

import net.seilent.funecomp.proto.gen.Enums

object ListLine {
    private const val RESCAN = "rescan"
    private const val SCANNING = "SCANNING"

    fun scanning(d: DeviceState) = d.liveWord(RESCAN) == SCANNING

    fun card(c: Int?): String? = when (c) {
        Enums.Card.out -> "no card"
        Enums.Card.error -> "card error"
        else -> null
    }

    fun empty(c: Int?, scanning: Boolean, view: Int, total: Long?, songs: Long?): String? {
        if (c != Enums.Card.ready || total == null || view == Enums.View.playlists) return null
        if (scanning && (songs ?: 0L) == 0L) return null
        if (total != 0L) return null
        if (view == Enums.View.genres) return "no genres"
        if (songs != 0L) return null
        return "no ${Enums.View.names.getValue(view)}"
    }

    fun queue(c: Int?, slots: Long?): String? = if (c == Enums.Card.ready && slots == 0L) "nothing here" else null
}
