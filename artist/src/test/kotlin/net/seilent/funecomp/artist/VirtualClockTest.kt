package net.seilent.funecomp.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualClockTest {
    @Test
    fun aSignalThenParkDoesNotMoveTime() {
        val clock = VirtualClock()
        val c = clock.condition()
        var ready = false
        val start = clock.now()
        val w = clock.task { c.await(null, null) { ready } }
        val sg = clock.task {
            while (clock.parked() < 1) Thread.sleep(5)
            ready = true
            c.signalAll()
            clock.park(clock.now() + 5000)
        }
        val waiter = Thread(w).apply { start() }
        val signaller = Thread(sg).apply { start() }
        waiter.join(10_000)
        signaller.join(10_000)
        clock.check()
        assertEquals(start + 5000, clock.now())
    }

    @Test
    fun aTaskSubmittedButNotStartedHoldsTime() {
        val clock = VirtualClock()
        val start = clock.now()
        val gate = java.util.concurrent.CountDownLatch(1)
        val late = clock.task { gate.await() }
        val sleeper = clock.thread("sleeper") { clock.park(clock.now() + 1000) }
        Thread.sleep(200)
        assertEquals(start, clock.now())
        gate.countDown()
        val t = Thread(late).apply { start() }
        sleeper.join(10_000)
        t.join(10_000)
        clock.check()
        assertEquals(start + 1000, clock.now())
    }

    @Test
    fun aDeadlockFailsWithADumpWithinASecond() {
        val clock = VirtualClock()
        val c = clock.condition()
        val begin = System.nanoTime()
        var thrown: Throwable? = null
        val t = clock.thread("stuck-thread") {
            try {
                c.await(null, null) { false }
            } catch (e: Throwable) {
                thrown = e
            }
        }
        t.join(5_000)
        val ms = (System.nanoTime() - begin) / 1_000_000
        assertTrue("$thrown", thrown is AssertionError)
        val e = thrown as AssertionError
        assertTrue(ms < 1000)
        assertTrue(e.message!!.contains("deadlock"))
        assertTrue(e.message!!.contains("stuck-thread"))
    }

    @Test
    fun timeMovesToTheEarliestDeadline() {
        val clock = VirtualClock()
        val start = clock.now()
        val order = java.util.Collections.synchronizedList(ArrayList<Int>())
        val ra = clock.task { clock.park(start + 300); order += 3 }
        val rb = clock.task { clock.park(start + 100); order += 1 }
        val a = Thread(ra).apply { start() }
        val b = Thread(rb).apply { start() }
        a.join(10_000)
        b.join(10_000)
        clock.check()
        assertEquals(listOf(1, 3), order)
        assertEquals(start + 300, clock.now())
    }
}
