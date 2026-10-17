package net.seilent.funecomp.artist

import java.io.File

data class Listen(val startS: Long, val artist: String, val title: String, val album: String? = null, val durationS: Long? = null, val id: Long = 0) {
    fun line() = listOf(id.toString(), startS.toString(), durationS?.toString() ?: "", clean(artist), clean(title), clean(album ?: "")).joinToString("\t")

    companion object {
        private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

        fun parse(line: String): Listen? {
            val f = line.split('\t')
            if (f.size != 6) return null
            return Listen(
                f[1].toLongOrNull() ?: return null,
                f[3],
                f[4],
                f[5].ifEmpty { null },
                f[2].toLongOrNull(),
                f[0].toLongOrNull() ?: return null,
            )
        }
    }
}

class ScrobbleQueue(private val dir: File) {
    private var next = 1L
    private val lines = ArrayList<Listen>()

    init {
        dir.mkdirs()
        val f = File(dir, QUEUE)
        if (f.isFile) {
            val all = f.readLines()
            all.firstOrNull()?.takeIf { it.startsWith("next\t") }?.removePrefix("next\t")?.toLongOrNull()?.let { next = it }
            all.drop(1).mapNotNullTo(lines) { Listen.parse(it) }
            lines.lastOrNull()?.let { next = maxOf(next, it.id + 1) }
        }
    }

    val size: Int @Synchronized get() = lines.size

    @Synchronized
    fun add(l: Listen, enabled: Set<String>): Listen? {
        if (enabled.isEmpty()) return null
        enabled.forEach { cursor(it) }
        val e = l.copy(id = next++)
        lines += e
        write()
        return e
    }

    @Synchronized
    fun cursor(service: String): Long {
        val f = File(dir, "$SENT.$service")
        f.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()?.let { return it }
        return (next - 1).also { save(f, it.toString()) }
    }

    @Synchronized
    fun after(service: String): List<Listen> {
        val c = cursor(service)
        return lines.filter { it.id > c }
    }

    @Synchronized
    fun sent(service: String, id: Long) {
        save(File(dir, "$SENT.$service"), id.toString())
    }

    @Synchronized
    fun enable(service: String) = sent(service, next - 1)

    @Synchronized
    fun pending(enabled: Set<String>): Int {
        if (enabled.isEmpty()) return 0
        val low = enabled.minOf { cursor(it) }
        return lines.count { it.id > low }
    }

    @Synchronized
    fun compact(enabled: Set<String>) {
        val low = if (enabled.isEmpty()) Long.MAX_VALUE else enabled.minOf { cursor(it) }
        if (lines.none { it.id <= low }) return
        lines.removeAll { it.id <= low }
        write()
    }

    private fun write() = save(File(dir, QUEUE), buildString {
        append("next\t").append(next).append('\n')
        lines.forEach { append(it.line()).append('\n') }
    })

    private fun save(f: File, text: String) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    companion object {
        const val QUEUE = "queue.tsv"
        const val SENT = "sent"
    }
}
