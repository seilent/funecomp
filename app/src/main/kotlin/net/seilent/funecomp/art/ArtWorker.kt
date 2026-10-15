package net.seilent.funecomp.art

import net.seilent.funecomp.artist.ArtPusher
import net.seilent.funecomp.link.Session
import net.seilent.funecomp.proto.gen.Proto

class ArtWorker(
    private val session: () -> Session?,
    private val push: (Session) -> ArtPusher.Report,
    private val show: (Boolean) -> Boolean,
    private val fetch: () -> Unit,
    private val gateWaitMs: Long = Proto.pushGateMs.toLong(),
    private val done: (ArtPusher.Report?, Throwable?) -> Unit,
) {
    private val lock = Any()
    private var wanted = false
    private var stopped = false
    private var thread: Thread? = null

    fun want() = synchronized(lock) {
        if (stopped) return
        wanted = true
        if (thread == null) thread = Thread(::loop, "art").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        val t = synchronized(lock) {
            stopped = true
            thread
        }
        t?.interrupt()
    }

    private fun live() = synchronized(lock) { !stopped }

    private fun loop() {
        try {
            while (true) {
                synchronized(lock) {
                    if (!wanted || stopped) {
                        thread = null
                        return
                    }
                    wanted = false
                }
                var report: ArtPusher.Report? = null
                var error: Throwable? = null
                try {
                    show(false)
                    val s = session()
                    if (s != null) {
                        report = push(s)
                        var waits = 0
                        while (report?.stop == ArtPusher.Stop.GATE && waits++ < GATE_WAITS) {
                            Thread.sleep(gateWaitMs)
                            report = push(s)
                        }
                    }
                    if (!show(false)) {
                        fetch()
                        show(true)
                    }
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Throwable) {
                    error = e
                }
                if (live()) done(report, error)
            }
        } catch (_: InterruptedException) {
        } finally {
            synchronized(lock) { if (thread === Thread.currentThread()) thread = null }
        }
    }

    private companion object {
        const val GATE_WAITS = 3
    }
}
