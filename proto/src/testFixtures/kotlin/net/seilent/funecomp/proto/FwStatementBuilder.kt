package net.seilent.funecomp.proto

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

object FwStatementBuilder {
    fun build(
        board: String = "v1",
        version: String = "v0.4.0",
        seq: Long = 0,
        size: Long = 0,
        sha256: ByteArray = ByteArray(32),
        keyId: ByteArray = ByteArray(8),
        signature: ByteArray = ByteArray(64),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(FwStatement.MAGIC.toByteArray(Charsets.ISO_8859_1))
        out.write(keyId)
        for (s in listOf(board, version)) {
            val b = s.toByteArray(Charsets.ISO_8859_1)
            out.write(b.size)
            out.write(b)
        }
        for (i in 0 until 4) out.write((seq ushr (8 * i)).toInt() and 0xff)
        for (i in 0 until 8) out.write((size ushr (8 * i)).toInt() and 0xff)
        out.write(sha256)
        out.write(signature)
        return out.toByteArray()
    }

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    fun forBytes(board: String, image: ByteArray, version: String = "v0.4.0", seq: Long = 0) =
        build(board, version, seq, image.size.toLong(), sha256(image))

    fun forFile(board: String, file: File, version: String = "v0.4.0", seq: Long = 0) = forBytes(board, file.readBytes(), version, seq)
}
