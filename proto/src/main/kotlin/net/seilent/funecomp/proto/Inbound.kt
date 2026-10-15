package net.seilent.funecomp.proto

import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.proto.gen.Tags

sealed class Inbound {
    class Accept(val frame: Frame, val body: Node) : Inbound()
    class Skip(val frame: Frame) : Inbound()
    class Reply(val frame: Frame, val code: Int, val text: String) : Inbound()
    class Close(val reason: String, val text: String) : Inbound()

    companion object {
        fun scope(f: Frame): String {
            val name = Msg.info[f.type]?.name ?: return "error"
            return when {
                f.isError -> "error"
                f.isReply -> "$name.reply"
                else -> name
            }
        }

        fun classify(f: Frame): Inbound {
            if (f.isError && !f.isReply) return Close("errorWithoutReply", "error flag without reply flag")
            val info = Msg.info[f.type]
            if (info == null && !f.isReply) return if (f.wantsReply) Reply(f, Err.unknown, "unknown") else Skip(f)
            if (info == null && !f.isError) return Skip(f)
            val body = try {
                Node.decode(scope(f), f.payload)
            } catch (e: ProtoException) {
                return Close(e.reason, e.message ?: e.reason)
            }
            if (f.type == Msg.HELLO && !f.isError) {
                val magic = body.long(if (f.isReply) Tags.HelloReply.magic else Tags.Hello.magic)
                if (magic != Proto.magic.toLong()) return Close("badMagic", "bad magic $magic")
            }
            if (f.wantsReply && !f.isReply && body.hasUnknownEnum()) return Reply(f, Err.badValue, "bad value")
            return Accept(f, body)
        }
    }
}
