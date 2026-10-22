package net.seilent.funecomp.artist

import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.ThreadFactory

interface BrowseSink {
    fun sections(order: List<String>)

    fun permit() {}

    fun drop() {}

    fun tile(section: String, t: Tile)

    fun sectionDone(section: String)

    fun youtubeSearch(ok: Boolean)
}

class BrowseFeed<T : Any>(
    private val decode: (ByteArray) -> T?,
    private val post: (() -> Unit) -> Unit,
    private val clock: Clock,
    private val run: HttpRun,
    factory: ThreadFactory,
    private val out: Out<T>,
    private val log: (String) -> Unit = {},
    private val permits: Int = PERMITS,
) : BrowseSink {
    interface Out<T> {
        fun sections(order: List<String>)

        fun tile(section: String, t: Tile, image: T)

        fun sectionDone(section: String)

        fun done(ok: Boolean)
    }

    private sealed interface Ev {
        class Sections(val order: List<String>) : Ev
        class Shown(val section: String, val t: Tile) : Ev
        class Done(val section: String) : Ev
        data object End : Ev
    }

    private val lock = Any()
    private val queue = ArrayDeque<Ev>()
    private val ready = clock.condition()
    private val room = clock.condition()
    private var free = permits
    private var closed = false
    @Volatile var result = true
        private set
    private val counts = HashMap<String, Int>()
    private val t0 = clock.now()
    private var taken = 0
    var peak = 0
        private set
    val live: Int get() = synchronized(lock) { taken }
    val thread: Thread = factory.newThread { loop() }

    init {
        run.onCancel {
            synchronized(lock) {
                closed = true
                queue.clear()
                free = permits
                taken = 0
            }
            ready.signalAll()
            room.signalAll()
        }
        thread.start()
    }

    override fun sections(order: List<String>) = put(Ev.Sections(order))

    override fun permit() {
        room.await(run, null) {
            synchronized(lock) {
                if (closed) {
                    true
                } else if (free > 0) {
                    free--
                    taken++
                    if (taken > peak) peak = taken
                    true
                } else {
                    false
                }
            }
        }
        if (synchronized(lock) { closed }) throw IOException("cancelled")
    }

    override fun drop() = release()

    override fun tile(section: String, t: Tile) = put(Ev.Shown(section, t))

    override fun sectionDone(section: String) = put(Ev.Done(section))

    override fun youtubeSearch(ok: Boolean) {
        result = ok
    }

    fun finish() = put(Ev.End)

    private fun put(e: Ev) {
        val held = synchronized(lock) {
            if (closed) {
                e is Ev.Shown
            } else {
                queue.addLast(e)
                false
            }
        }
        if (held) release()
        ready.signalAll()
    }

    private fun release() {
        synchronized(lock) {
            if (taken > 0) {
                taken--
                free++
            }
        }
        room.signalAll()
    }

    private fun loop() {
        var ended = false
        try {
            while (true) {
                var e: Ev? = null
                ready.await(run, null) { synchronized(lock) { queue.pollFirst().also { e = it } != null || closed } }
                val ev = e ?: return
                when (ev) {
                    is Ev.Sections -> post { if (!run.cancelled) out.sections(ev.order) }
                    is Ev.Shown -> {
                        val image = try {
                            decode(ev.t.preview)
                        } catch (x: RuntimeException) {
                            log("art browse decode ${ev.section}: $x")
                            null
                        } catch (x: OutOfMemoryError) {
                            log("art browse decode ${ev.section}: $x")
                            null
                        } finally {
                            release()
                        }
                        if (image != null) {
                            counts[ev.section] = (counts[ev.section] ?: 0) + 1
                            val t = Tile(ev.t.source, ev.t.caption, NO_BYTES, ev.t.full, ev.t.id, ev.t.credit)
                            val ms = clock.now() - t0
                            post {
                                if (!run.cancelled) {
                                    log("art browse tile ${ev.section} +$ms")
                                    out.tile(ev.section, t, image)
                                }
                            }
                        }
                    }
                    is Ev.Done -> {
                        val n = counts[ev.section] ?: 0
                        val ms = clock.now() - t0
                        post {
                            if (!run.cancelled) {
                                log("art browse source ${ev.section} done n=$n +$ms")
                                out.sectionDone(ev.section)
                            }
                        }
                    }
                    Ev.End -> {
                        ended = true
                        val ok = result
                        post { if (!run.cancelled) out.done(ok) }
                        return
                    }
                }
            }
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
        } finally {
            synchronized(lock) { closed = true }
            if (!ended && !run.cancelled) post { if (!run.cancelled) out.done(false) }
        }
    }

    companion object {
        const val PERMITS = 8
        private val NO_BYTES = ByteArray(0)
    }
}
