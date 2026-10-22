package net.seilent.funecomp.artist

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

interface Clock {
    fun now(): Long

    fun park(until: Long)

    fun condition(): Cond

    companion object {
        val SYSTEM: Clock = object : Clock {
            override fun now() = System.nanoTime() / 1_000_000

            override fun park(until: Long) {
                while (true) {
                    val left = until - now()
                    if (left <= 0) return
                    Thread.sleep(left)
                }
            }

            override fun condition(): Cond = LockCond(this)
        }
    }
}

interface Cond {
    fun await(run: HttpRun?, until: Long?, ready: () -> Boolean)

    fun signalAll()
}

private class LockCond(private val clock: Clock) : Cond {
    private val lock = ReentrantLock()
    private val signal = lock.newCondition()

    override fun await(run: HttpRun?, until: Long?, ready: () -> Boolean) {
        lock.lockInterruptibly()
        run?.enter(this)
        try {
            while (true) {
                if (ready()) return
                if (run?.cancelled == true) throw IOException("cancelled")
                if (until == null) {
                    signal.await()
                } else {
                    val left = until - clock.now()
                    if (left <= 0) return
                    signal.await(left, TimeUnit.MILLISECONDS)
                }
            }
        } finally {
            run?.leave(this)
            lock.unlock()
        }
    }

    override fun signalAll() {
        lock.lock()
        try {
            signal.signalAll()
        } finally {
            lock.unlock()
        }
    }
}
