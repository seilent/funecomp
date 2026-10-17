package net.seilent.funecomp.artist

object Sweep {
    data class Row(val name: String, val mbid: String?, val count: Long)

    fun order(rows: List<Row>): List<Row> = rows.filter { it.name.isNotBlank() }.sortedByDescending { it.count }

    class Budget(bytes: Long, val cap: Long) {
        var used = bytes
            private set

        val room: Boolean get() = used * 10 < cap * 9

        fun add(bytes: Long): Boolean {
            used += bytes
            return used > cap
        }
    }

    class Breaker(private val clock: () -> Long, private val trip: Int = TRIP, private val pauseMs: Long = PAUSE_MS) {
        private var errors = 0
        private var until: Long? = null

        val open: Boolean get() = until?.let { clock() < it } ?: false

        fun result(httpError: Boolean) {
            if (!httpError) {
                errors = 0
                return
            }
            if (++errors >= trip) {
                errors = 0
                until = clock() + pauseMs
            }
        }

        fun network() {
            errors = 0
            until = null
        }
    }

    const val TRIP = 10
    const val PAUSE_MS = 3_600_000L
}
