package net.seilent.funecomp

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

class Tap internal constructor(private val at: Long, private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private val used = AtomicBoolean(false)

    fun take(): Boolean = clock() - at <= LIFE_MS && used.compareAndSet(false, true)

    companion object {
        const val LIFE_MS = 30_000L
    }
}
