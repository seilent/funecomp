package net.seilent.funecomp

import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class HeldSource(private val bytes: ByteArray, private val at: Int) {
    val reached = CountDownLatch(1)
    val go = CountDownLatch(1)

    fun source() = Firmwares.Source("held.upt", bytes.size.toLong()) { off -> Stream(off.toInt()) }.apply {
        md5 = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private inner class Stream(private var pos: Int) : InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= at && go.count > 0) {
                reached.countDown()
                go.await(30, TimeUnit.SECONDS)
            }
            val n = minOf(len, bytes.size - pos)
            if (n <= 0) return -1
            System.arraycopy(bytes, pos, b, off, n)
            pos += n
            return n
        }
    }
}
