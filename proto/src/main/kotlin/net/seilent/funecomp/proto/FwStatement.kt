package net.seilent.funecomp.proto

class FwStatement(
    val keyId: String,
    val board: String,
    val version: String,
    val seq: Long,
    val size: Long?,
    val sha256: String,
) {
    private class Reader(val b: ByteArray) {
        var at = 0

        fun take(n: Int): ByteArray? {
            if (n > b.size - at) return null
            return b.copyOfRange(at, at + n).also { at += n }
        }

        fun text(): String? {
            val len = take(1)?.let { it[0].toInt() and 0xff } ?: return null
            return take(len)?.toString(Charsets.ISO_8859_1)
        }

        fun le(n: Int): ULong? {
            val v = take(n) ?: return null
            var r = 0UL
            for (i in n - 1 downTo 0) r = (r shl 8) or (v[i].toULong() and 0xffUL)
            return r
        }
    }

    companion object {
        const val MAX = 512
        const val MAGIC = "FNFWSIG1"
        private const val ID = 8
        private const val SHA = 32
        private const val SIG = 64
        private const val VERSION_MAX = 64

        private fun printable(s: String) = s.all { it.code in 0x21..0x7e }

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

        fun parse(bytes: ByteArray): FwStatement? {
            if (bytes.size > MAX) return null
            val r = Reader(bytes)
            val magic = r.take(MAGIC.length) ?: return null
            if (!magic.contentEquals(MAGIC.toByteArray(Charsets.ISO_8859_1))) return null
            val id = r.take(ID) ?: return null
            val board = r.text() ?: return null
            val version = r.text() ?: return null
            val seq = r.le(4) ?: return null
            val size = r.le(8) ?: return null
            val sha = r.take(SHA) ?: return null
            r.take(SIG) ?: return null
            if (r.at != bytes.size) return null
            if (board.isEmpty() || !printable(board) || version.isEmpty() || version.length > VERSION_MAX || !printable(version)) return null
            return FwStatement(hex(id), board, version, seq.toLong(), size.takeIf { it <= Long.MAX_VALUE.toULong() }?.toLong(), hex(sha))
        }
    }
}
