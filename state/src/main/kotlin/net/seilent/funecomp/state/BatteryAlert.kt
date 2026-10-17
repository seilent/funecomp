package net.seilent.funecomp.state

class BatteryAlert(private val levels: List<Int> = LEVELS, private val rearm: Int = REARM) {
    sealed interface Event {
        data class Low(val pct: Int, val level: Int) : Event
        data object Clear : Event
    }

    private val fired = HashSet<Int>()
    private var shown = false

    fun update(pct: Int?, charging: Boolean?): Event? {
        if (pct == null) return null
        if (pct > rearm) fired.clear()
        if (charging == true) {
            if (!shown) return null
            shown = false
            return Event.Clear
        }
        val due = levels.filter { pct <= it && it !in fired }
        if (due.isEmpty()) return null
        fired += due
        shown = true
        return Event.Low(pct, due.min())
    }

    companion object {
        val LEVELS = listOf(15, 10, 5)
        const val REARM = 16
    }
}
