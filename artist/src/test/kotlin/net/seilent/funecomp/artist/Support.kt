package net.seilent.funecomp.artist

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
            read[url] = at
            if (!sink(r.code, buf, n)) break
        }
        return Response(r.code, ByteArray(0), r.headers)
    }

    private fun serve(url: String, headers: Map<String, String>): Response {
        calls += url
        agents += headers["User-Agent"]
        sent += headers
        times += now() to url
        queued[url]?.removeFirstOrNull()?.let { return it }
        val f = index[url] ?: run {
            missing += url
            return Response(404, ByteArray(0))
        }
        if (f.startsWith("code:")) return Response(f.removePrefix("code:").toInt(), ByteArray(0))
        if (f.startsWith("gen:")) return Response(200, gen(f.removePrefix("gen:")))
        return Response(200, load(f))
    }

    fun hosts() = calls.map { it.substringAfter("://").substringBefore('/') }.toSet()

    companion object {
        fun gen(spec: String): ByteArray {
            val (w, h) = spec.substringBefore('@').split('x').map { it.toInt() }
            return Images.jpeg(Images.photo(w, h, spec.substringAfter('@', "7").toLong()), 92)
        }

        fun load(name: String): ByteArray =
            Fixtures::class.java.getResourceAsStream("/fixtures/$name")?.use { it.readBytes() } ?: error("no fixture $name")

        fun text(name: String) = String(load(name), Charsets.UTF_8)
    }
}

class FakeTime {
    var now = 1_000_000L
    val sleeps = ArrayList<Long>()
    val sleep: (Long) -> Unit = {
        sleeps += it
        now += it
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
}

class AwtCodec(var face: ((Int, Int) -> Pair<Double, Double>?)? = null) : Codec {
    val qualities = ArrayList<Int>()

    override fun decode(bytes: ByteArray, maxSide: Int): Picture? {
        val img = try {
            ImageIO.read(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            null
        } ?: return null
        return object : Picture {
            override val w = img.width
            override val h = img.height
            override fun face() = face?.invoke(w, h)
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
