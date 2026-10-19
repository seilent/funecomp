package net.seilent.funecomp.link

import net.seilent.funecomp.proto.ErrorReply
import net.seilent.funecomp.proto.gen.Proto
import java.util.concurrent.atomic.AtomicBoolean

class Responder internal constructor(private val session: Session, private val type: Int, private val seq: Int) {
    private val sent = AtomicBoolean(false)

    fun ok(payload: ByteArray = ByteArray(0)) {
        if (sent.compareAndSet(false, true)) session.answer(type, seq, Proto.flagReply, payload)
    }

    fun error(code: Int, text: String) {
        if (sent.compareAndSet(false, true)) session.answer(type, seq, Proto.flagReply or Proto.flagError, ErrorReply(code, text).encode())
    }
}
