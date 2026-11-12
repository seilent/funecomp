package net.seilent.funecomp.state

import net.seilent.funecomp.proto.State

class HomeShuffle {
    class Tap internal constructor(val rev: Long?) {
        internal var replied = false
        internal var landed = false
    }

    private var armed: Tap? = null

    @Synchronized
    fun tap(rev: Long?): Tap = Tap(rev).also { armed = it }

    @Synchronized
    fun replied(t: Tap, code: Int?): Boolean {
        if (armed !== t) return false
        if (code != null) {
            armed = null
            return false
        }
        t.replied = true
        return due(t)
    }

    @Synchronized
    fun state(s: State): Boolean {
        val t = armed ?: return false
        if (t.landed || s.queueRev == t.rev) return false
        if (s.playing != true || s.shuffle != true) {
            armed = null
            return false
        }
        t.landed = true
        return due(t)
    }

    @Synchronized
    fun end() {
        armed = null
    }

    private fun due(t: Tap): Boolean {
        if (!t.replied || !t.landed) return false
        armed = null
        return true
    }
}
