package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.PageInfo
import net.seilent.funecomp.proto.PlayEntry
import net.seilent.funecomp.proto.PlayLogReply

class CardLog(private val store: Long, var key: String, seed: List<PlayEntry> = emptyList(), private val lost: Set<Long> = emptySet()) {
    private class Item(val entry: PlayEntry, val card: String, val queued: Boolean, var old: Boolean, val epoch: Int)

    private val items = ArrayList<Item>()
    private var next = 1L
    private var epoch = 1
    private val verified = HashMap<String, Int>()

    init {
        seed.forEach { add(it, queued = false, old = true) }
    }

    val held: List<PlayEntry> get() = items.map { it.entry }

    fun append(e: PlayEntry, queued: Boolean): PlayEntry = add(e, queued, old = false)

    private fun add(e: PlayEntry, queued: Boolean, old: Boolean): PlayEntry {
        val s = e.copy(seq = next++)
        items += Item(s, key, queued, old, epoch)
        return s
    }

    fun sub(card: String): Long = card.toByteArray().fold(store xor FNV_OFFSET) { h, b -> (h xor (b.toLong() and 0xFF)) * FNV_PRIME }

    fun keyOf(id: Long): String? = (items.map { it.card } + key).firstOrNull { sub(it) == id }

    fun expose() {
        epoch++
    }

    fun restart() {
        items.forEach { if (!it.queued) it.old = true }
    }

    fun verify() {
        verified[key] = epoch
    }

    private fun lost(i: Item) = !i.queued && i.entry.seq in lost

    private fun exposed(i: Item) = !i.queued && (lost(i) || i.old || i.epoch != epoch)

    private fun blocked(i: Item) = !i.queued && !lost(i) && (verified[i.card] ?: 1) != epoch

    private fun servable(card: String): List<Item> {
        val mine = items.filter { it.card == card }
        val stop = mine.indexOfFirst { blocked(it) }
        return if (stop < 0) mine else mine.subList(0, stop)
    }

    fun stops(card: String = key): Boolean = items.any { it.card == card && blocked(it) }

    fun newest(card: String = key): Long = servable(card).lastOrNull()?.entry?.seq ?: 0

    fun page(after: Long, count: Long?): PlayLogReply {
        val all = servable(key)
        val above = all.filter { it.entry.seq > after }.let { l -> count?.takeIf { it > 0 }?.let { l.take(it.toInt().coerceAtMost(l.size)) } ?: l }
        val entries = above.map { if (exposed(it)) it.entry.copy(exposed = true) else it.entry }
        val newest = all.lastOrNull()?.entry?.seq ?: 0
        val low = items.firstOrNull { it.card == key }?.entry?.seq
        val fixed = PlayLogReply(sub(key), newest, true, emptyList(), null, low).encode().size + 8
        val sent = entries.take(PageInfo.fill(entries.map { it.size }, 0, fixed))
        val more = all.any { it.entry.seq > (sent.lastOrNull()?.seq ?: after) }
        return PlayLogReply(sub(key), newest, more, sent, null, low)
    }

    fun ack(card: String, upTo: Long) {
        items.removeAll { it.card == card && it.entry.seq <= upTo }
    }

    companion object {
        const val KEY = "a"
        private const val FNV_OFFSET = -0x340d631b7bdddcdbL
        private const val FNV_PRIME = 0x100000001b3L
    }
}
