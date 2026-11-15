package net.seilent.funecomp.state

class LastSent {
    var speed: Int? = null
        private set
    var end: Boolean? = null
        private set

    fun sendSpeed(pct: Int) {
        speed = pct
    }

    fun speedReplied(pct: Int) {
        if (speed == pct) speed = null
    }

    fun sendEnd(on: Boolean) {
        end = on
    }

    fun endReplied(on: Boolean) {
        if (end == on) end = null
    }

    fun clear() {
        speed = null
        end = null
    }
}
