package net.seilent.funecomp.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.IOException

class BrowseFeedTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val clock = VirtualClock(strict = false)
    private val run = HttpRun()
    private val events = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val gate = clock.condition()
    @Volatile private var open = true
    private var decoded = 0

    private val out = object : BrowseFeed.Out<String> {
        override fun sections(order: List<String>) {
            events += "sections $order"
        }

        override fun tile(section: String, t: Tile, image: String) {
            events += "tile $section ${t.id} ${t.preview.size}"
        }

        override fun sectionDone(section: String) {
            events += "done $section"
        }

        override fun search(outcome: YouTube.Search) {
            events += "search ${outcome::class.simpleName}"
        }

        override fun done(ok: Boolean) {
            events += "end $ok"
        }
    }

    private fun feed(decode: (ByteArray) -> String? = { b ->
        clock.external(gate, null) { open }
        decoded++
        when (b[0]) {
            9.toByte() -> throw IllegalArgumentException("bad")
            8.toByte() -> throw OutOfMemoryError("bitmap")
            7.toByte() -> throw StackOverflowError()
            else -> "img"
        }
    }) = BrowseFeed(decode, { it() }, clock, run, clock.factory("art-browse-decode"), out, { lines += it })

    private fun tile(id: String, first: Byte = 1) = Tile(Sources.DEEZER, id, byteArrayOf(first, 2, 3), "https://e/$id", id, null)

    @Test
    fun everyEventIsPostedInOrderAndADoneFollowsItsTiles() {
        open = false
        val f = feed()
        f.sections(listOf("a", "b"))
        f.permit()
        f.tile("a", tile("1"))
        f.permit()
        f.tile("b", tile("2"))
        f.sectionDone("a")
        f.permit()
        f.tile("a", tile("3"))
        f.sectionDone("b")
        f.search(YouTube.Search.Unavailable())
        f.finish()
        clock.awaitIdle()
        assertEquals(listOf("sections [a, b]"), events.toList())
        open = true
        gate.signalAll()
        clock.join(f.thread)
        clock.check()
        assertEquals(listOf("sections [a, b]", "tile a 1 0", "tile b 2 0", "done a", "tile a 3 0", "done b", "search Unavailable", "end true"), events.toList())
        assertEquals(listOf("tile a", "tile b", "source a done n=1", "tile a", "source b done n=1"), lines.map { it.removePrefix("art browse ").substringBeforeLast(" +") })
        assertEquals(0, f.live)
        assertEquals(0, clock.drained())
    }

    @Test
    fun aNoResultsSearchEndsTheRunOk() {
        val f = feed()
        f.sections(listOf("a"))
        f.permit()
        f.tile("a", tile("1"))
        f.sectionDone("a")
        f.search(YouTube.Search.NoResults)
        f.finish()
        clock.join(f.thread)
        clock.check()
        assertEquals(listOf("sections [a]", "tile a 1 0", "done a", "search NoResults", "end true"), events.toList())
    }

    @Test
    fun aFailedRunEndsFalseWhateverTheSearch() {
        val f = feed()
        f.sections(listOf("a"))
        f.search(YouTube.Search.Channels(emptyList()))
        f.fail()
        f.finish()
        clock.join(f.thread)
        assertEquals(listOf("sections [a]", "search Channels", "end false"), events.toList())
    }

    @Test
    fun aCancelledRunSendsNoSearch() {
        open = false
        val f = feed()
        f.sections(listOf("a"))
        f.permit()
        f.tile("a", tile("1"))
        f.search(YouTube.Search.Unavailable())
        f.finish()
        clock.awaitIdle()
        run.cancel()
        open = true
        gate.signalAll()
        clock.join(f.thread)
        assertTrue(events.none { it.startsWith("search") })
    }

    @Test
    fun aDecodeThatThrowsDropsOneTile() {
        val f = feed()
        f.sections(listOf("a"))
        for ((i, b) in listOf<Byte>(1, 9, 1).withIndex()) {
            f.permit()
            f.tile("a", tile("$i", b))
        }
        f.sectionDone("a")
        f.finish()
        clock.join(f.thread)
        clock.check()
        assertEquals(listOf("sections [a]", "tile a 0 0", "tile a 2 0", "done a", "end true"), events.toList())
        assertTrue(lines.contains(lines.first { it.startsWith("art browse source a done n=2 ") }))
        assertEquals(0, f.live)
    }

    @Test
    fun aDecodeOutOfMemoryDropsOneTile() {
        val f = feed()
        f.sections(listOf("a"))
        for ((i, b) in listOf<Byte>(1, 8, 1).withIndex()) {
            f.permit()
            f.tile("a", tile("$i", b))
        }
        f.sectionDone("a")
        f.finish()
        clock.join(f.thread)
        assertEquals(listOf("sections [a]", "tile a 0 0", "tile a 2 0", "done a", "end true"), events.toList())
        assertTrue(lines.any { it == "art browse decode a: java.lang.OutOfMemoryError: bitmap" })
        assertEquals(0, f.live)
    }

    @Test
    fun aDecodeErrorThatKillsTheThreadStillEndsTheBrowse() {
        val f = feed()
        f.sections(listOf("a"))
        f.permit()
        f.tile("a", tile("0", 7))
        clock.join(f.thread)
        assertFalse(f.thread.isAlive)
        assertEquals(listOf("sections [a]", "end false"), events.toList())
    }

    @Test
    fun atMostEightPreviewsAreAliveWhileSevenWorkersProduce() {
        open = false
        val f = feed()
        f.sections((0 until 7).map { "s$it" })
        val workers = (0 until 7).map { w ->
            clock.thread("w$w") {
                repeat(5) { i ->
                    f.permit()
                    if (i % 3 == 2) f.drop() else f.tile("s$w", tile("$w-$i"))
                }
                f.sectionDone("s$w")
            }
        }
        clock.awaitIdle()
        assertEquals(BrowseFeed.PERMITS, f.live)
        open = true
        gate.signalAll()
        clock.join(workers)
        f.finish()
        clock.join(f.thread)
        clock.check()
        assertEquals(BrowseFeed.PERMITS, f.peak)
        assertEquals(0, f.live)
        assertEquals(7 * 4, events.count { it.startsWith("tile") })
        assertEquals("end true", events.last())
    }

    @Test
    fun aCancelWakesTheDecodeWaitAndThePermitWaitAndPostsNothing() {
        open = false
        val f = feed()
        f.sections(listOf("a"))
        repeat(BrowseFeed.PERMITS) {
            f.permit()
            f.tile("a", tile("$it"))
        }
        var thrown: Throwable? = null
        val w = clock.thread("worker") {
            try {
                run.on { f.permit() }
            } catch (e: Throwable) {
                thrown = e
            }
        }
        clock.awaitIdle()
        val at = clock.now()
        run.cancel()
        clock.join(w)
        open = true
        gate.signalAll()
        clock.join(f.thread)
        f.tile("a", tile("late"))
        f.sectionDone("a")
        f.finish()
        clock.check()
        assertEquals("cancelled", (thrown as IOException).message)
        assertEquals(listOf("sections [a]"), events.toList())
        assertEquals(at, clock.now())
        assertFalse(f.thread.isAlive)
        assertEquals(0, clock.drained())
    }

    @Test
    fun anIdleDecodeThreadEndsOnCancel() {
        val f = feed()
        clock.awaitIdle()
        run.cancel()
        clock.join(f.thread)
        clock.check()
        assertFalse(f.thread.isAlive)
        assertTrue(events.isEmpty())
    }

    @Test
    fun anInterruptLeavesThePermitWaitAndTheDecodeWait() {
        open = false
        val f = feed()
        repeat(BrowseFeed.PERMITS) {
            f.permit()
            f.tile("a", tile("$it"))
        }
        var thrown: Throwable? = null
        val w = clock.thread("worker") {
            try {
                f.permit()
            } catch (e: Throwable) {
                thrown = e
            }
        }
        clock.awaitIdle()
        w.interrupt()
        clock.join(w)
        assertTrue("$thrown", thrown is InterruptedException)
        f.thread.interrupt()
        clock.join(f.thread)
        assertFalse(f.thread.isAlive)
    }
}
