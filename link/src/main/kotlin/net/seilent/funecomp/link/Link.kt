package net.seilent.funecomp.link

import net.seilent.funecomp.proto.Bye
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.state.Reconnect
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class Link(
    private val connect: () -> Transport,
    private val listener: Listener,
    private val policy: Reconnect = Reconnect(),
    private val config: Session.Config = Session.Config(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    interface Listener : Session.Listener {
        fun onAttempt(at: Long) {}
        fun onSession(session: Session) {}
        fun onStopped(stop: Reconnect.Stop) {}
    }

    private val lock = ReentrantLock()
    private val wakeup = lock.newCondition()
    private var running = false
    private var endedAt = 0L
    private var thread: Thread? = null

    @Volatile var session: Session? = null
        private set

    val stopped: Reconnect.Stop? get() = lock.withLock { policy.stopped }

    fun start() {
        lock.withLock {
            if (running) return
            running = true
            endedAt = clock()
        }
        thread = Thread({ loop() }, "link").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        lock.withLock {
            running = false
            wakeup.signalAll()
        }
        session?.close("stopped")
        thread?.join(2000)
    }

    fun foreground() = wake { policy.foreground() }

    fun userAction() = wake { policy.userAction() }

    private fun wake(block: () -> Unit) = lock.withLock {
        block()
        wakeup.signalAll()
    }

    private fun loop() {
        while (true) {
            lock.withLock {
                while (true) {
                    if (!running) return
                    val d = policy.delayMs()
                    if (d == null) {
                        wakeup.await()
                        continue
                    }
                    val left = endedAt + d - clock()
                    if (left > 0) {
                        wakeup.await(left, TimeUnit.MILLISECONDS)
                        continue
                    }
                    if (policy.begin()) break
                    wakeup.await()
                }
            }
            listener.onAttempt(clock())
            var reached = false
            val s = Session(connect(), object : Session.Listener {
                override fun onHello(hello: HelloReply) {
                    reached = true
                    listener.onHello(hello)
                }

                override fun onPush(type: Int, body: Node) = listener.onPush(type, body)

                override fun onBye(bye: Bye) {
                    lock.withLock { policy.bye(bye.reason) }
                    listener.onBye(bye)
                }

                override fun onRtt(ms: Long) = listener.onRtt(ms)

                override fun onClosed(reason: String) = listener.onClosed(reason)
            }, config, clock)
            val live = lock.withLock {
                session = s
                running
            }
            if (!live) {
                session = null
                lock.withLock { policy.ended(false) }
                return
            }
            listener.onSession(s)
            s.run()
            session = null
            val stop = lock.withLock {
                policy.ended(reached)
                endedAt = clock()
                policy.stopped
            }
            stop?.let { listener.onStopped(it) }
        }
    }
}
