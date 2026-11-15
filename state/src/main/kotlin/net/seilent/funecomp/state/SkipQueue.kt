package net.seilent.funecomp.state

class SkipQueue {
    private var flying: Int? = null
    private var pending = 0

    fun tap(session: Int, ms: Int): Int? {
        if (flying == session) {
            pending = (pending + ms).coerceIn(-MAX_MS, MAX_MS)
            return null
        }
        flying = session
        pending = 0
        return ms
    }

    fun replied(session: Int): Int? {
        if (flying != session) return null
        val next = pending
        pending = 0
        if (next == 0) flying = null
        return next.takeIf { it != 0 }
    }

    fun closed() {
        flying = null
        pending = 0
    }

    companion object {
        const val MAX_MS = 3_600_000
    }
}
