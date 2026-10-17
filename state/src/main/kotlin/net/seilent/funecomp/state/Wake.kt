package net.seilent.funecomp.state

class Wake(
    private val acquire: (Long) -> Unit,
    private val release: () -> Unit,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val until = HashMap<String, Long>()

    val held: Set<String> get() = synchronized(this) {
        prune()
        until.keys.toSet()
    }

    fun hold(reason: String, ms: Long) = synchronized(this) {
        until[reason] = clock() + ms
        apply()
    }

    fun drop(reason: String) = synchronized(this) {
        if (until.remove(reason) != null) apply()
    }

    private fun prune() {
        val now = clock()
        until.values.removeAll { it <= now }
    }

    private fun apply() {
        prune()
        val end = until.values.maxOrNull()
        if (end == null) release() else acquire(end - clock())
    }
}
