package net.seilent.funecomp.state

data class DeviceClock(val windowMs: Long = WINDOW_MS, val mins: List<Sample> = emptyList()) {
    data class Sample(val at: Long, val offset: Long)

    val offset: Long? get() = mins.firstOrNull()?.offset

    fun add(at: Long, offset: Long) = copy(mins = mins.filter { it.at > at - windowMs && it.offset < offset } + Sample(at, offset))

    companion object {
        const val WINDOW_MS = 537_000L
    }
}
