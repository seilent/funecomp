package net.seilent.funecomp.art

import net.seilent.funecomp.artist.ArtPusher
import net.seilent.funecomp.artist.HttpRun
import net.seilent.funecomp.link.Session
import net.seilent.funecomp.proto.gen.Proto

class ArtWorker(
    private val session: () -> Session?,
    private val push: (Session, Run) -> ArtPusher.Report,
    private val show: (Boolean) -> Boolean,
    private val fetch: () -> Unit,
    private val gateWaitMs: Long = Proto.pushGateMs.toLong(),
    private val trigger: () -> Any? = { null },
    private val onHttp: (String) -> Unit = {},
    private val ended: () -> Unit = {},
    private val done: (ArtPusher.Report?, Throwable?) -> Unit,
) {
    inner class Run internal constructor(private val id: Int, private val key: Any?, val http: HttpRun) {
        val current: Boolean get() = synchronized(lock) { id == runId && !stopped }

        fun live(): Boolean = current && trigger() == key
    }

    private val lock = Any()
    private var wanted = false
    private var stopped = false
    private var runId = 0
    private var http: HttpRun? = null
    private var thread: Thread? = null

    fun want() = synchronized(lock) {
        if (stopped) return
        wanted = true
        if (thread == null) spawn()
    }

    fun cancel() {
        val h = synchronized(lock) {
            runId++
            thread = null
            http.also { http = null }
        }
        h?.cancel()
    }

    fun stop() {
        val (t, h) = synchronized(lock) {
            stopped = true
            runId++
            (thread to http).also { http = null }
        }
        h?.cancel()
        t?.interrupt()
    }

    private fun spawn() {
        thread = Thread(::loop, "art").apply {
            isDaemon = true
            start()
        }
    }

    private fun loop() {
        try {
            while (true) {
                val run = synchronized(lock) {
                    if (thread !== Thread.currentThread()) return
                    if (!wanted || stopped) {
                        thread = null
                        return
                    }
                    wanted = false
                    Run(runId, trigger(), HttpRun(onHttp).also { http = it })
                }
                Thread.interrupted()
                var report: ArtPusher.Report? = null
                var error: Throwable? = null
                try {
                    run.http.on {
                        show(false)
                        val s = session()
                        if (s != null) {
                            report = push(s, run)
                            var waits = 0
                            while (report?.stop == ArtPusher.Stop.GATE && waits++ < GATE_WAITS && run.live()) {
                                Thread.sleep(gateWaitMs)
                                report = push(s, run)
                            }
                        }
                        if (run.live() && !show(false)) {
                            fetch()
                            show(true)
                        }
                    }
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Throwable) {
                    error = e
                } finally {
                    ended()
                }
                if (run.current) done(report, error)
            }
        } catch (_: InterruptedException) {
        } finally {
            synchronized(lock) {
                if (thread === Thread.currentThread()) thread = null
                if (wanted && !stopped && thread == null) spawn()
            }
        }
    }

    internal companion object {
        const val GATE_WAITS = 3
    }
}
