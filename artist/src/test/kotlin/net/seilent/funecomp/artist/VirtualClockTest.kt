package net.seilent.funecomp.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualClockTest {
    @Test
    fun aTaskThatSignalledButHasNotLeftIsStillAliveUntilItLeaves() {
        val clock = VirtualClock()
        val signalled = java.util.concurrent.CountDownLatch(1)
        val t = clock.thread("late leaver") {
            signalled.countDown()
            Thread.sleep(300)
        }
        signalled.await()
        assertEquals(1, clock.alive())
        assertEquals(0, clock.drained())
        t.join(10_000)
    }

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
        val order = java.util.concurrent.CopyOnWriteArrayList<Int>()
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

    @Test
    fun aWorkerSlowerThanTheStallCapNeverTripsIt() {
        val clock = VirtualClock(stallNs = 200_000_000L)
        val c = clock.condition()
        val done = java.util.concurrent.atomic.AtomicBoolean()
        var thrown: Throwable? = null
        val waiter = clock.thread("waiter") {
            try {
                c.await(null, null) { done.get() }
            } catch (e: Throwable) {
                thrown = e
            }
        }
        val worker = clock.thread("worker") {
            Thread.sleep(800)
            done.set(true)
            c.signalAll()
        }
        clock.join(waiter, worker)
        clock.check()
        assertEquals(null, thrown)
        assertEquals(0, clock.drained())
    }

    @Test
    fun aStallFailsWithADumpOnceNothingMovesForTheCap() {
        val clock = VirtualClock(stallNs = 200_000_000L)
        val c = clock.condition()
        val begin = System.nanoTime()
        var thrown: Throwable? = null
        val t = clock.thread("held-thread") {
            try {
                clock.external(c, null) { false }
            } catch (e: Throwable) {
                thrown = e
            }
        }
        t.join(10_000)
        val ms = (System.nanoTime() - begin) / 1_000_000
        assertTrue("$thrown", thrown is AssertionError)
        assertTrue(thrown!!.message!!.contains("stalled"))
        assertTrue(thrown!!.message!!.contains("held-thread"))
        assertTrue("$ms", ms in 200..5_000)
    }

    @Test(timeout = 10_000)
    fun aThreadBlockedOutsideTheClockFailsTheJoinOnceNothingMoves() {
        val clock = VirtualClock(stallNs = 200_000_000L, quietNs = 600_000_000L)
        val gate = java.util.concurrent.CountDownLatch(1)
        val t = clock.thread("blocked-thread") { gate.await() }
        val begin = System.nanoTime()
        val e = runCatching { clock.join(t) }.exceptionOrNull()
        val ms = (System.nanoTime() - begin) / 1_000_000
        gate.countDown()
        t.join(5_000)
        assertTrue("$e", e is AssertionError)
        assertTrue(e!!.message!!.contains("blocked outside the clock"))
        assertTrue(e.message!!.contains("blocked-thread"))
        assertTrue("$ms", ms in 600..5_000)
    }
}
