package net.seilent.funecomp.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class FwStatementTest {
    private fun vectorSig(): ByteArray {
        val path = System.getProperty("funecomp.vectors") ?: "../third_party/funeos/vectors.txt"
        val hex = File(path).readLines().single { it.startsWith("dec upd_begin_sig 0.body.sig ") }.substringAfterLast(' ')
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    @Test
    fun theVectorStatementParses() {
        val b = vectorSig()
        assertEquals(134, b.size)
        val st = FwStatement.parse(b)!!
        assertEquals("v1", st.board)
        assertEquals("v0.4.0", st.version)
        assertEquals(4000L, st.seq)
        assertEquals(28043264L, st.size)
        assertEquals("f35ad3afb9592032", st.keyId)
        assertEquals("f8b2f6f23c2cba17fe182fb9d81bf90f18de1d4793fb3338033880f385626b91", st.sha256)
    }

    @Test
    fun theBuilderRoundTrips() {
        val sha = ByteArray(32) { it.toByte() }
        val st = FwStatement.parse(FwStatementBuilder.build("r1", "v1.2.3", 1_002_003, 5_000_000_000, sha, ByteArray(8) { 0xab.toByte() }))!!
        assertEquals("r1", st.board)
        assertEquals("v1.2.3", st.version)
        assertEquals(1_002_003L, st.seq)
        assertEquals(5_000_000_000L, st.size)
        assertEquals("abababababababab", st.keyId)
        assertEquals(sha.joinToString("") { "%02x".format(it) }, st.sha256)
    }

    @Test
    fun layoutRefusals() {
        val ok = FwStatementBuilder.build()
        assertNotNull(FwStatement.parse(ok))
        assertNull(FwStatement.parse(ByteArray(513)))
        assertNull(FwStatement.parse(ok + ByteArray(513 - ok.size)))
        assertNull(FwStatement.parse(ok.copyOf().also { it[7] = '2'.code.toByte() }))
        assertNull(FwStatement.parse(ok.copyOf(ok.size - 1)))
        assertNull(FwStatement.parse(ok + byteArrayOf(0)))
        assertNull(FwStatement.parse(ok.copyOf(16) + byteArrayOf(200.toByte())))
        assertNull(FwStatement.parse(ByteArray(0)))
        assertNull(FwStatement.parse(FwStatementBuilder.build(board = "")))
        assertNull(FwStatement.parse(FwStatementBuilder.build(version = "v0.4 0")))
        assertNull(FwStatement.parse(FwStatementBuilder.build(board = "v\u00011")))
        assertNull(FwStatement.parse(FwStatementBuilder.build(board = "v\u007f")))
        assertNull(FwStatement.parse(FwStatementBuilder.build(version = "")))
        assertNull(FwStatement.parse(FwStatementBuilder.build(version = "v".repeat(65))))
        assertNotNull(FwStatement.parse(FwStatementBuilder.build(version = "v".repeat(64))))
        assertNotNull(FwStatement.parse(FwStatementBuilder.build(board = "!~")))
    }
}
