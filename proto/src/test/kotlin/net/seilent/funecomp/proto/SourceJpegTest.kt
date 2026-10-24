package net.seilent.funecomp.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceJpegTest {
    private fun seg(m: Int, body: ByteArray) = byteArrayOf(0xFF.toByte(), m.toByte(), ((body.size + 2) shr 8).toByte(), (body.size + 2).toByte()) + body

    private fun sof(m: Int = 0xC0, w: Int = 640, h: Int = 480, precision: Int = 8, comps: List<Int> = listOf(0x22, 0x11, 0x11)): ByteArray {
        val b = ArrayList<Byte>()
        b += precision.toByte()
        b += (h shr 8).toByte()
        b += h.toByte()
        b += (w shr 8).toByte()
        b += w.toByte()
        b += comps.size.toByte()
        comps.forEachIndexed { i, f ->
            b += (i + 1).toByte()
            b += f.toByte()
            b += 0
        }
        return seg(m, b.toByteArray())
    }

    private fun sos(n: Int) = seg(0xDA, byteArrayOf(n.toByte()) + ByteArray(n * 2) + byteArrayOf(0, 63, 0))

    private val data = byteArrayOf(0x12, 0x34, 0xFF.toByte(), 0x00, 0x56, 0xFF.toByte(), 0xD0.toByte(), 0x78)

    private fun jpeg(vararg parts: ByteArray, end: Boolean = true): ByteArray {
        var b = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        for (p in parts) b += p
        if (end) b += byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        return b
    }

    private fun exif(o: Int): ByteArray {
        val tiff = byteArrayOf(
            'I'.code.toByte(), 'I'.code.toByte(), 42, 0, 8, 0, 0, 0,
            1, 0,
            0x12, 0x01, 3, 0, 1, 0, 0, 0, o.toByte(), 0, 0, 0,
            0, 0, 0, 0,
        )
        return seg(0xE1, "Exif".toByteArray() + byteArrayOf(0, 0) + tiff)
    }

    @Test
    fun aBaselineYcbcrFileIsSendable() {
        val b = jpeg(sof(), sos(3), data)
        val i = SourceJpeg.read(b)!!
        assertEquals(640 to 480, i.w to i.h)
        assertTrue(i.baseline)
        assertTrue(SourceJpeg.sendable(b))
    }

    @Test
    fun grayAndSof1AreSendable() {
        assertTrue(SourceJpeg.sendable(jpeg(sof(comps = listOf(0x11)), sos(1), data)))
        assertTrue(SourceJpeg.sendable(jpeg(sof(0xC1), sos(3), data)))
    }

    @Test
    fun progressiveArithmeticAndLosslessAreRefused() {
        assertFalse(SourceJpeg.sendable(jpeg(sof(0xC2), sos(3), data)))
        assertFalse(SourceJpeg.sendable(jpeg(sof(0xC3), sos(3), data)))
        assertFalse(SourceJpeg.sendable(jpeg(sof(0xC9), sos(3), data)))
        assertFalse(SourceJpeg.sendable(jpeg(seg(0xCC, byteArrayOf(0, 0)), sof(), sos(3), data)))
    }

    @Test
    fun cmykTwelveBitAndWideSamplingAreRefused() {
        assertFalse(SourceJpeg.sendable(jpeg(sof(comps = listOf(0x11, 0x11, 0x11, 0x11)), sos(4), data)))
        assertFalse(SourceJpeg.sendable(jpeg(sof(precision = 12), sos(3), data)))
        assertFalse(SourceJpeg.sendable(jpeg(sof(comps = listOf(0x44, 0x11, 0x11)), sos(3), data)))
        assertFalse(SourceJpeg.sendable(jpeg(sof(comps = listOf(0x55, 0x11, 0x11)), sos(3), data)))
        assertTrue(SourceJpeg.sendable(jpeg(sof(comps = listOf(0x42, 0x11, 0x11)), sos(3), data)))
    }

    @Test
    fun aMultiScanOrNonInterleavedFileIsRefused() {
        assertFalse(SourceJpeg.sendable(jpeg(sof(), sos(1), data, sos(1), data, sos(1), data)))
        assertFalse(SourceJpeg.sendable(jpeg(sof(), sos(3), data, sos(3), data)))
        assertFalse(SourceJpeg.sendable(jpeg(sof(), sos(1), data)))
    }

    @Test
    fun dnlZeroHeightAndTruncatedFilesAreRefused() {
        assertFalse(SourceJpeg.sendable(jpeg(sof(h = 0), sos(3), data, seg(0xDC, byteArrayOf(1, 0xE0.toByte())))))
        assertFalse(SourceJpeg.sendable(jpeg(sof(h = 0), sos(3), data)))
        assertFalse(SourceJpeg.sendable(jpeg(sof(), sos(3), data, end = false)))
        assertFalse(SourceJpeg.sendable(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
    }

    @Test
    fun bytesAfterThePrimaryEoiAreAllowed() {
        val b = jpeg(sof(), sos(3), data) + jpeg(sof(w = 64, h = 64), sos(3), data)
        assertTrue(SourceJpeg.sendable(b))
        assertEquals(640, SourceJpeg.read(b)!!.w)
    }

    @Test
    fun exifOrientationOtherThanOneIsNotUpright() {
        assertTrue(SourceJpeg.sendable(jpeg(exif(1), sof(), sos(3), data)))
        val rotated = jpeg(exif(6), sof(), sos(3), data)
        assertEquals(6, SourceJpeg.read(rotated)!!.orientation)
        assertFalse(SourceJpeg.sendable(rotated))
    }

    @Test
    fun theLimitsAreBytesAndSides() {
        assertTrue(SourceJpeg.sendable(jpeg(sof(w = 8192, h = 8192), sos(3), data)))
        assertFalse(SourceJpeg.sendable(jpeg(sof(w = 8193, h = 100), sos(3), data)))
        val b = jpeg(sof(), sos(3), data)
        assertTrue(SourceJpeg.sendable(b, maxBytes = b.size))
        assertFalse(SourceJpeg.sendable(b, maxBytes = b.size - 1))
    }
}
