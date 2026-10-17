package net.seilent.funecomp.state

import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.gen.Err
import java.time.Instant
import java.time.ZoneId

class ClockSync {
    var armed = false
        private set

    fun hello(h: HelloReply): Boolean {
        armed = h.has("clockAuto")
        return armed
    }

    fun failed(code: Int) {
        if (code == Err.busy || code == Err.refused) armed = false
    }

    fun closed() {
        armed = false
    }

    companion object {
        fun draft(nowMs: Long, zone: ZoneId): Pair<Long, Int> =
            Math.floorDiv(nowMs, 1000L) to zone.rules.getOffset(Instant.ofEpochMilli(nowMs)).totalSeconds

        fun next(nowMs: Long, zone: ZoneId): Long? = zone.rules.nextTransition(Instant.ofEpochMilli(nowMs))?.instant?.toEpochMilli()
    }
}
