package net.seilent.funecomp

import java.util.PriorityQueue
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class ManualScheduler : AbstractExecutorService(), ScheduledExecutorService {
    var now = 0L
        private set
    private var seq = 0L
    private val tasks = PriorityQueue<Task>()

    inner class Task(val at: Long, private val n: Long, val run: Runnable) : ScheduledFuture<Unit> {
        private var cancelled = false
        private var done = false

        fun fire() {
            if (cancelled) return
            done = true
            run.run()
        }

        override fun compareTo(other: Delayed): Int {
            val o = other as Task
            return if (at != o.at) at.compareTo(o.at) else n.compareTo(o.n)
        }

        override fun getDelay(unit: TimeUnit) = unit.convert(at - now, TimeUnit.MILLISECONDS)

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            if (done || cancelled) return false
            cancelled = true
            return true
        }

        override fun isCancelled() = cancelled

        override fun isDone() = done || cancelled

        override fun get() {}

        override fun get(timeout: Long, unit: TimeUnit) {}
    }

    fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val t = tasks.peek()?.takeIf { it.at <= end } ?: break
            tasks.poll()
            now = t.at
            t.fire()
        }
        now = end
    }

    override fun execute(command: Runnable) {
        schedule(command, 0, TimeUnit.MILLISECONDS)
    }

    override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
        Task(now + unit.toMillis(delay), seq++, command).also { tasks += it }

    override fun <V> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> = throw UnsupportedOperationException()

    override fun scheduleAtFixedRate(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit): ScheduledFuture<*> = throw UnsupportedOperationException()

    override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit): ScheduledFuture<*> = throw UnsupportedOperationException()

    override fun shutdown() {}

    override fun shutdownNow(): MutableList<Runnable> = mutableListOf()

    override fun isShutdown() = false

    override fun isTerminated() = false

    override fun awaitTermination(timeout: Long, unit: TimeUnit) = false
}
