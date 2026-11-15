package net.seilent.funecomp.state

import net.seilent.funecomp.proto.gen.Enums

object ListGone {
    fun gone(view: Int, key: String, card: Int?, root: LibList?): Boolean? {
        if (view != Enums.View.playlists) return false
        if (card != Enums.Card.ready || root == null) return null
        if (root.firstMissing(0, root.total) != null) return null
        return root.rows.values.none { (it.key ?: it.name) == key }
    }
}
