import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GenProtoTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(proto: String): File = tmp.newFolder().also {
        File(it, "PIN").writeText("commit.spec abc\ncommit.tokens abc\n")
        File(it, "proto.txt").writeText(proto)
    }

    @Test
    fun aRequestNamedLikeARecordTakesTheMsgSuffix() {
        val kt = Gen.protoKt(
            dir(
                "msg SCROB_AUTH 0x52 app req\ntag SCROB_AUTH 1 rev u32 one\ntag SCROB_AUTH 3 auth ScrobAuth list\n" +
                    "tag SCROB_AUTH.reply 1 ok bool one\ntag ScrobAuth 1 user str one\n",
            ),
        )
        assertTrue(kt.contains("    object ScrobAuthMsg {\n        const val rev = 1\n        const val auth = 3\n    }\n"))
        assertTrue(kt.contains("    object ScrobAuthReply {\n        const val ok = 1\n    }\n"))
        assertTrue(kt.contains("    object ScrobAuth {\n        const val user = 1\n    }\n"))
        assertTrue(kt.contains("        \"SCROB_AUTH\" to mapOf(\n"))
        assertTrue(kt.contains("        \"ScrobAuth\" to mapOf(\n"))
    }

    @Test
    fun twoRecordsTwoMessagesOrAReplyAndARecordThatCollideStillFail() {
        val records = dir("tag FooBar 1 a u8 one\ntag Foo_Bar 1 a u8 one\n")
        assertEquals(COLLIDE, assertThrows(GenException::class.java) { Gen.protoKt(records) }.message)
        val msgs = dir("msg FOO_BAR 0x10 app req\nmsg FOO__BAR 0x11 app req\ntag FOO_BAR 1 a u8 one\ntag FOO__BAR 1 a u8 one\n")
        assertEquals(COLLIDE, assertThrows(GenException::class.java) { Gen.protoKt(msgs) }.message)
        val reply = dir("msg X_Y 0x12 app req\ntag X_Y.reply 1 a u8 one\ntag XYReply 1 a u8 one\n")
        assertEquals(COLLIDE, assertThrows(GenException::class.java) { Gen.protoKt(reply) }.message)
    }

    private companion object {
        const val COLLIDE = "proto.txt: scope names collide in Kotlin"
    }
}
