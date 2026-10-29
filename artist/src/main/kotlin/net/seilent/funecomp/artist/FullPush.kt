package net.seilent.funecomp.artist

import net.seilent.funecomp.link.Session
import net.seilent.funecomp.proto.gen.Proto

class FullPush(
    private val source: (String) -> Full?,
    private val refusal: (String) -> Int,
    val bluetooth: () -> Boolean = { false },
    private val cropped: (String, Long) -> Unit = { _, _ -> },
    private val log: (String) -> Unit = {},
) {
    constructor(s: FullSource, bluetooth: () -> Boolean = { false }, log: (String) -> Unit = {}) : this(s::of, s::refused, bluetooth, s::cropped, log)

    private var low: Session? = null
    private val sent = HashMap<String, Pair<Long, String>>()

    @Synchronized
    fun sent(name: String, crc: Long, key: String) {
        sent[Names.fold(name)] = crc to key
    }

    @Synchronized
    fun sentAs(name: String, srcCrc: Long): String? {
        val k = Names.fold(name)
        val (crc, key) = sent[k] ?: return null
        if (crc != srcCrc) return null
        sent.remove(k)
        return key
    }

    fun crop(key: String, crc: Long) = cropped(key, crc)

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
