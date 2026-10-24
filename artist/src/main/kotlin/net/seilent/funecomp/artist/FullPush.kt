package net.seilent.funecomp.artist

import net.seilent.funecomp.link.Session
import net.seilent.funecomp.proto.gen.Proto

class FullPush(
    private val source: (String) -> Full?,
    private val refusal: (String) -> Int,
    val bluetooth: () -> Boolean = { false },
    private val log: (String) -> Unit = {},
) {
    constructor(s: FullSource, bluetooth: () -> Boolean = { false }, log: (String) -> Unit = {}) : this(s::of, s::refused, bluetooth, log)

    private var low: Session? = null

    fun of(key: String): Full? = source(key)

    fun refused(key: String): Int = refusal(key).also { log("art full refused $key $it") }

    @Synchronized
    fun low(s: Session): Boolean = low === s

    @Synchronized
    fun noSpace(s: Session) {
        if (low !== s) log("art full no space, crops until room")
        low = s
    }

    @Synchronized
    fun room(s: Session, srcRoom: Long?) {
        if (low === s && srcRoom != null && srcRoom > Proto.imgSrcMaxBytes) {
            low = null
            log("art full room $srcRoom")
        }
    }
}
