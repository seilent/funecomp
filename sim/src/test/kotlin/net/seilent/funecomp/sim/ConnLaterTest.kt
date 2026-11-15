package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.Frame
import net.seilent.funecomp.proto.FrameReader
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.Hello
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ConnLaterTest {
    private val sims = ArrayList<Sim>()
    private val socks = ArrayList<Socket>()

    @After
    fun tearDown() {
        socks.forEach { it.close() }
        sims.forEach { it.stop() }
    }

    private inner class App(port: Int) {
        val sock = Socket("127.0.0.1", port).also { socks += it }
        private val replies = LinkedBlockingQueue<Frame>()
        private var seq = 1
        val hello: HelloReply

        init {
            Thread {
                val r = FrameReader()
                val buf = ByteArray(8192)
                try {
                    while (true) {
                        val n = sock.getInputStream().read(buf)
                        if (n < 0) break
                        for (f in r.feed(buf, 0, n)) if (f.isReply) replies += f
                    }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true }.start()
            val s = send(Msg.HELLO, Hello(Proto.major, Proto.minor, "t", Groups.all).encode())
            val f = replies.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no hello reply")
            check(f.seq == s)
            hello = HelloReply.from(Node.decode("HELLO.reply", f.payload))
        }

        @Synchronized
        fun send(type: Int, payload: ByteArray): Int {
            val s = seq++
            sock.getOutputStream().write(Frame(type, Proto.flagWant, s, payload).encode())
            sock.getOutputStream().flush()
            return s
        }
    }

    private class Sink : Out {
        override fun push(type: Int, payload: ByteArray) {}
    }

    private fun inDue() = Thread.currentThread().stackTrace.any { it.className == Device::class.java.name && it.methodName == "due" }

    @Test(timeout = 30_000)
    fun aDueCheckRunsOutsideTheConnLock() {
        val open = CountDownLatch(1)
        val checked = CountDownLatch(1)
        val arm = AtomicBoolean(false)
        val free = AtomicBoolean(false)
        lateinit var sim: Sim
        val clock: () -> Long = {
            if (inDue() && arm.compareAndSet(true, false)) {
                val pushed = CountDownLatch(1)
                Thread {
                    sim.current!!.push(Msg.STATE, ByteArray(0))
                    pushed.countDown()
                }.apply { isDaemon = true }.start()
                free.set(pushed.await(5, TimeUnit.SECONDS))
                checked.countDown()
            }
            System.nanoTime() / 1_000_000
        }
        sim = Sim(Options(Board.of("v1"), Faults(listOf("bind-ms=60000")), log = { if (it == "bind ask open") open.countDown() }, deviceClock = clock)).also { sims += it }
        val app = App(sim.listen(0))
        app.send(Msg.BIND_ASK, Req.bindAsk())
        assertTrue(open.await(10, TimeUnit.SECONDS))
        arm.set(true)
        assertTrue(checked.await(10, TimeUnit.SECONDS))
        assertTrue(free.get())
    }

    @Test(timeout = 30_000)
    fun aCloseResolvesAParkedDeleteSoTheNextIsServed() {
        val parked = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val sim = Sim(Options(Board.of("v1"), Faults(listOf("bind-window", "del-ms=60000")), log = {
            if (it.startsWith("lib delete song")) parked.countDown()
            if (it.contains(" closed ")) closed.countDown()
        })).also { sims += it }
        val app = App(sim.listen(0))
        val token = app.hello.bindToken!!
        val song = sim.device.lib.tracks.last().id
        app.send(Msg.LIB_DELETE, Req.libDelete(song, null, null, app.hello.snapRev, token, app.hello.bootId))
        assertTrue(parked.await(10, TimeUnit.SECONDS))
        app.sock.close()
        assertTrue(closed.await(10, TimeUnit.SECONDS))
        val out = Sink()
        sim.device.linked(out, Groups.all, "t")
        val next = Req.libDelete(song, null, null, app.hello.snapRev, token, app.hello.bootId)
        assertTrue(sim.device.request(Msg.LIB_DELETE, Node.decode("LIB_DELETE", next), out) is Answer.Later)
    }
}
