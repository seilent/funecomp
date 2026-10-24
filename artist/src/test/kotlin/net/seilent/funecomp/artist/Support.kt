package net.seilent.funecomp.artist

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Random
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

class Fixtures : Http {
    val calls = ArrayList<String>()
    val agents = ArrayList<String?>()
    val sent = ArrayList<Map<String, String>>()
    val missing = ArrayList<String>()
    val times = ArrayList<Pair<Long, String>>()
    val queued = HashMap<String, ArrayDeque<Response>>()
    var now: () -> Long = { 0 }
    private val index = text("urls.tsv").lineSequence().filter { it.isNotBlank() }
        .associate { it.substringBefore('\t') to it.substringAfter('\t') }

    override fun get(url: String, headers: Map<String, String>): Response = serve(url, headers)

    override fun post(url: String, headers: Map<String, String>, body: ByteArray): Response = serve("POST $url ${String(body, Charsets.UTF_8)}", headers)

    val read = HashMap<String, Int>()
    var chunk = 4096

    override fun download(url: String, headers: Map<String, String>, sink: (Int, ByteArray, Int) -> Boolean): Response {
        val r = serve(url, headers)
        if (r.code !in 200..299) return r
        var at = 0
        while (at < r.body.size) {
            val n = minOf(chunk, r.body.size - at)
            val buf = r.body.copyOfRange(at, at + n)
            at += n
            synchronized(this) { read[url] = at }
            if (!sink(r.code, buf, n)) break
        }
        return Response(r.code, ByteArray(0), r.headers)
    }

    var clock: VirtualClock? = null
    private val holds = HashMap<String, Hold>()
    var answer: (String) -> Response? = { null }

    inner class Hold(val c: Cond) {
        @Volatile var open = false
            private set

        fun release() {
            open = true
            c.signalAll()
        }
    }

    fun hold(url: String): Hold = synchronized(this) { Hold(clock!!.condition()).also { holds[url] = it } }

    fun snapshot(): List<Pair<Long, String>> = synchronized(this) { times.toList() }

    private fun serve(url: String, headers: Map<String, String>): Response {
        val h = synchronized(this) {
            calls += url
            agents += headers["User-Agent"]
            sent += headers
            times += now() to url
            holds[url]
        }
        if (h != null) clock!!.external(h.c, HttpRun.current.get()) { h.open }
        answer(url)?.let { return it }
        val f = synchronized(this) {
            queued[url]?.removeFirstOrNull()?.let { return it }
            index[url] ?: run {
                missing += url
                return Response(404, ByteArray(0))
            }
        }
        if (f.startsWith("code:")) return Response(f.removePrefix("code:").toInt(), ByteArray(0))
        if (f.startsWith("gen:")) return Response(200, gen(f.removePrefix("gen:")))
        return Response(200, load(f))
    }

    fun hosts() = calls.map { it.substringAfter("://").substringBefore('/') }.toSet()

    companion object {
        private val made = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

        fun gen(spec: String): ByteArray = made.getOrPut(spec) {
            val (w, h) = spec.substringBefore('@').split('x').map { it.toInt() }
            Images.jpeg(Images.photo(w, h, spec.substringAfter('@', "7").toLong()), 92)
        }.copyOf()

        fun load(name: String): ByteArray =
            Fixtures::class.java.getResourceAsStream("/fixtures/$name")?.use { it.readBytes() } ?: error("no fixture $name")

        fun text(name: String) = String(load(name), Charsets.UTF_8)
    }
}

class FakeTime : Clock {
    var now = 1_000_000L
    val sleeps = ArrayList<Long>()
    val sleep: (Long) -> Unit = {
        sleeps += it
        now += it
    }

    override fun now() = now

    override fun park(until: Long) {
        if (until > now) sleep(until - now)
    }

    override fun condition(): Cond = ParkCond(this)
}

class ParkCond(private val clock: Clock) : Cond {
    override fun await(run: HttpRun?, until: Long?, ready: () -> Boolean) {
        while (true) {
            if (ready()) return
            if (run?.cancelled == true) throw IOException("cancelled")
            if (until == null) error("a single thread wait with no deadline never ends")
            if (clock.now() >= until) return
            clock.park(until)
        }
    }

    override fun signalAll() {}
}

class VirtualClock(start: Long = 1_000_000L, var auto: Boolean = true, private val strict: Boolean = true, private val stallNs: Long = STALL_NS, private val quietNs: Long = QUIET_NS) : Clock {
    private val lock = Object()
    private var t = start
    private var moved = System.nanoTime()
    private var pending = 0
    private var seq = 0L
    private var failure: AssertionError? = null
    private val states = LinkedHashMap<Thread, St>()

    private class St(val order: Long) {
        var depth = 0
        var parked = false
        var runnable = false
        var deadline: Long? = null
        var external = false
        var where = ""
    }

    private inner class VCond : Cond {
        val waiters = LinkedHashSet<St>()

        override fun await(run: HttpRun?, until: Long?, ready: () -> Boolean) = wait(this, run, until, false, ready)

        override fun signalAll() = synchronized(lock) {
            waiters.forEach { it.runnable = true }
            moved = System.nanoTime()
            lock.notifyAll()
        }
    }

    override fun now(): Long = synchronized(lock) { t }

    override fun park(until: Long) = condition().await(null, until) { false }

    override fun condition(): Cond = VCond()

    fun external(c: Cond, run: HttpRun?, ready: () -> Boolean) = wait(c as VCond, run, null, true, ready)

    private fun wait(c: VCond, run: HttpRun?, until: Long?, external: Boolean, ready: () -> Boolean) {
        synchronized(lock) {
            val me = enter(Thread.currentThread())
            run?.enter(c)
            try {
                val start = System.nanoTime()
                while (true) {
                    failure?.let { throw it }
                    if (ready()) return
                    if (run?.cancelled == true) throw IOException("cancelled")
                    if (until != null && t >= until) return
                    me.parked = true
                    me.runnable = false
                    me.deadline = until
                    me.external = external
                    me.where = Thread.currentThread().stackTrace.drop(2).take(6).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                    c.waiters += me
                    try {
                        step()
                        while (!me.runnable) {
                            failure?.let { throw it }
                            stuck(start)?.let { fail(it) }
                            lock.wait(50)
                        }
                    } finally {
                        me.parked = false
                        me.runnable = false
                        me.deadline = null
                        me.external = false
                        c.waiters -= me
                    }
                }
            } finally {
                run?.leave(c)
                leave(Thread.currentThread())
            }
        }
    }

    private fun enter(th: Thread): St {
        val s = states.getOrPut(th) { St(seq++) }
        s.depth++
        moved = System.nanoTime()
        return s
    }

    private fun leave(th: Thread) {
        val s = states[th] ?: return
        if (--s.depth <= 0) states.remove(th)
        moved = System.nanoTime()
        step()
    }

    private fun step() {
        lock.notifyAll()
        if (failure != null || pending > 0) return
        val all = states.values
        if (all.any { !it.parked || it.runnable }) return
        if (!auto) return
        val next = all.mapNotNull { it.deadline }.minOrNull()
        if (next == null) {
            if (strict && all.isNotEmpty() && all.none { it.external }) fail("deadlock: every registered thread waits with no deadline")
            return
        }
        if (next > t) t = next
        moved = System.nanoTime()
        all.filter { it.deadline != null && it.deadline!! <= t }.sortedBy { it.order }.forEach { it.runnable = true }
        lock.notifyAll()
    }

    private fun idle() = pending == 0 && states.values.all { it.parked && !it.runnable }

    private fun stuck(start: Long): String? {
        val now = System.nanoTime()
        if (now - start > BACKSTOP_NS) return "wall clock backstop"
        if (idle() && now - moved > stallNs) return "stalled: every registered thread waits and nothing moved"
        if (now - moved > quietNs) return "stalled: nothing moved, a registered thread is blocked outside the clock"
        return null
    }

    private fun fail(why: String): Nothing {
        val dump = states.entries.joinToString("\n") { (th, s) ->
            "${th.name} parked=${s.parked} deadline=${s.deadline} external=${s.external} at ${s.where}\n  " +
                th.stackTrace.take(12).joinToString("\n  ")
        }
        val e = AssertionError("$why at t=$t\n$dump")
        failure = e
        lock.notifyAll()
        throw e
    }

    fun <T> registered(block: () -> T): T {
        synchronized(lock) { enter(Thread.currentThread()) }
        try {
            return block()
        } finally {
            synchronized(lock) { leave(Thread.currentThread()) }
        }
    }

    fun task(r: Runnable): Runnable {
        synchronized(lock) { pending++ }
        return Runnable {
            synchronized(lock) {
                pending--
                enter(Thread.currentThread())
            }
            try {
                r.run()
            } finally {
                synchronized(lock) { leave(Thread.currentThread()) }
            }
        }
    }

    fun executor(n: Int): java.util.concurrent.ExecutorService {
        val d = java.util.concurrent.Executors.newFixedThreadPool(n) { r -> Thread(r, "art-browse-test").apply { isDaemon = true } }
        return object : java.util.concurrent.AbstractExecutorService() {
            override fun execute(command: Runnable) = d.execute(task(command))
            override fun shutdown() = d.shutdown()
            override fun shutdownNow(): MutableList<Runnable> = d.shutdownNow()
            override fun isShutdown() = d.isShutdown
            override fun isTerminated() = d.isTerminated
            override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit) = d.awaitTermination(timeout, unit)
        }
    }

    fun factory(name: String): java.util.concurrent.ThreadFactory = java.util.concurrent.ThreadFactory { r -> Thread(task(r), name).apply { isDaemon = true } }

    fun thread(name: String, block: () -> Unit): Thread = Thread(task(Runnable(block)), name).apply {
        isDaemon = true
        start()
    }

    fun awaitIdle() = synchronized(lock) {
        val start = System.nanoTime()
        while (true) {
            failure?.let { throw it }
            val all = states.values
            if (pending == 0 && all.all { it.parked && !it.runnable } && (!auto || all.none { it.deadline != null })) return
            stuck(start)?.let { fail("awaitIdle $it") }
            lock.wait(20)
        }
    }

    fun advance(to: Long) = synchronized(lock) {
        if (to > t) t = to
        moved = System.nanoTime()
        states.values.filter { it.deadline != null && it.deadline!! <= t }.forEach { it.runnable = true }
        lock.notifyAll()
    }

    fun alive(): Int = synchronized(lock) { states.size + pending }

    fun drained(): Int = synchronized(lock) {
        val start = System.nanoTime()
        while (states.size + pending > 0 && stuck(start) == null) lock.wait(20)
        states.size + pending
    }

    fun join(vararg threads: Thread) = synchronized(lock) {
        val start = System.nanoTime()
        while (threads.any { it.isAlive }) {
            stuck(start)?.let { fail("join $it") }
            lock.wait(20)
        }
    }

    fun join(threads: Iterable<Thread>) = join(*threads.toList().toTypedArray())

    fun parked(): Int = synchronized(lock) { states.values.count { it.parked } }

    fun check() = synchronized(lock) { failure?.let { throw it } }

    companion object {
        private const val STALL_NS = 30_000_000_000L
        private const val QUIET_NS = 180_000_000_000L
        private const val BACKSTOP_NS = 600_000_000_000L
    }
}

object Images {
    fun photo(w: Int, h: Int, seed: Long = 7): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.paint = java.awt.GradientPaint(0f, 0f, Color(30, 60, 90), w.toFloat(), h.toFloat(), Color(220, 180, 140))
        g.fillRect(0, 0, w, h)
        val r = Random(seed)
        repeat(40) {
            g.color = Color(r.nextInt(256), r.nextInt(256), r.nextInt(256))
            g.fillOval(r.nextInt(w), r.nextInt(h), 10 + r.nextInt(w / 4 + 1), 10 + r.nextInt(h / 4 + 1))
        }
        g.dispose()
        return img
    }

    fun noise(w: Int, h: Int, seed: Long = 11): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val r = Random(seed)
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, r.nextInt(0x1000000))
        return img
    }

    fun jpeg(img: BufferedImage, quality: Int): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            val p = writer.defaultWriteParam
            p.compressionMode = ImageWriteParam.MODE_EXPLICIT
            p.compressionQuality = quality / 100f
            writer.write(null, IIOImage(img, null, null), p)
        }
        writer.dispose()
        return out.toByteArray()
    }

    fun png(img: BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()

    fun size(jpeg: ByteArray): Pair<Int, Int> = ImageIO.read(ByteArrayInputStream(jpeg)).let { it.width to it.height }

    fun orient(img: BufferedImage, orientation: Int): BufferedImage {
        if (orientation <= 1 || orientation > 8) return img
        val swap = orientation >= 5
        val w = if (swap) img.height else img.width
        val h = if (swap) img.width else img.height
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val (nx, ny) = when (orientation) {
                2 -> img.width - 1 - x to y
                3 -> img.width - 1 - x to img.height - 1 - y
                4 -> x to img.height - 1 - y
                5 -> y to x
                6 -> img.height - 1 - y to x
                7 -> img.height - 1 - y to img.width - 1 - x
                else -> y to img.width - 1 - x
            }
            out.setRGB(nx, ny, img.getRGB(x, y))
        }
        return out
    }

    fun progressive(img: BufferedImage, quality: Int = 90): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            val p = writer.defaultWriteParam
            p.progressiveMode = ImageWriteParam.MODE_DEFAULT
            writer.write(null, IIOImage(img, null, null), p)
        }
        writer.dispose()
        return out.toByteArray()
    }

    fun exif(jpeg: ByteArray, orientation: Int): ByteArray {
        val tiff = byteArrayOf(
            'M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8,
            0, 1,
            0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0,
            0, 0, 0, 0,
        )
        val body = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val len = body.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + body
        val at = if ((jpeg[3].toInt() and 0xFF) == 0xE0) 4 + (((jpeg[4].toInt() and 0xFF) shl 8) or (jpeg[5].toInt() and 0xFF)) else 2
        return jpeg.copyOfRange(0, at) + app1 + jpeg.copyOfRange(at, jpeg.size)
    }
}

class AwtCodec(var face: ((Int, Int) -> Pair<Double, Double>?)? = null) : Codec {
    val qualities = ArrayList<Int>()
    var faces = 0

    override fun bounds(bytes: ByteArray): Size? = read(bytes)?.let { Size(it.width, it.height) }

    override fun decode(bytes: ByteArray, maxSide: Int): Picture? = read(bytes)?.let { picture(it) }

    override fun upright(bytes: ByteArray, maxSide: Int, orientation: Int): Picture? = read(bytes)?.let { picture(fit(Images.orient(it, orientation), maxSide)) }

    private fun read(bytes: ByteArray): BufferedImage? = try {
        ImageIO.read(ByteArrayInputStream(bytes))
    } catch (e: Exception) {
        null
    }

    private fun fit(img: BufferedImage, maxSide: Int): BufferedImage {
        val side = maxOf(img.width, img.height)
        if (side <= maxSide) return img
        val w = maxOf(1, (img.width.toLong() * maxSide / side).toInt())
        val h = maxOf(1, (img.height.toLong() * maxSide / side).toInt())
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(img, 0, 0, w, h, null)
        g.dispose()
        return out
    }

    private fun picture(img: BufferedImage): Picture {
        return object : Picture {
            override val w = img.width
            override val h = img.height
            override fun face() = face?.invoke(w, h).also { faces++ }
            override fun jpeg(crop: Rect, w: Int, h: Int, quality: Int): ByteArray {
                qualities += quality
                val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                val g = out.createGraphics()
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.drawImage(img, 0, 0, w, h, crop.x, crop.y, crop.x + crop.w, crop.y + crop.h, null)
                g.dispose()
                return Images.jpeg(out, quality)
            }
        }
    }
}

class Collected : BrowseSink {
    val order = java.util.concurrent.CopyOnWriteArrayList<String>()
    val tiles = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Tile>>()
    val done = java.util.concurrent.CopyOnWriteArrayList<String>()
    @Volatile var search: YouTube.Search? = null
    var onTile: (Pair<String, Tile>) -> Unit = {}

    override fun sections(order: List<String>) {
        this.order += order
    }

    override fun tile(section: String, t: Tile) {
        tiles += section to t
        onTile(section to t)
    }

    override fun sectionDone(section: String) {
        done += section
    }

    override fun search(outcome: YouTube.Search) {
        search = outcome
    }

    fun of(section: String): List<Tile> = synchronized(tiles) { tiles.filter { it.first == section }.map { it.second } }

    fun all(): List<Tile> = synchronized(tiles) { tiles.map { it.second } }
}

object Inline : java.util.concurrent.AbstractExecutorService() {
    override fun execute(command: Runnable) = command.run()
    override fun shutdown() {}
    override fun shutdownNow(): MutableList<Runnable> = ArrayList()
    override fun isShutdown() = false
    override fun isTerminated() = false
    override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit) = true
}
