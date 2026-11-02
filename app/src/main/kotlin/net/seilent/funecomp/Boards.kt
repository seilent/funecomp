package net.seilent.funecomp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.seilent.funecomp.ui.Detected
import net.seilent.funecomp.ui.Flight

class Find(val address: String, val name: String?, val audioVideo: Boolean, val companion: Boolean, val bonded: Boolean, val linked: Boolean)

class Boards {
    private class Window(var last: Long, var name: String?, var sdp: Boolean? = null, var cancelled: Boolean = false, var laters: Int = 0, var laterAt: Long = Long.MIN_VALUE)

    private val windows = HashMap<String, Window>()
    private val queue = ArrayList<Detected>()

    var waiting by mutableStateOf<Detected?>(null)
        private set
    var flight by mutableStateOf<Flight?>(null)
        private set
    private var flightAt = 0L
    var picking by mutableStateOf(false)
    var rev by mutableIntStateOf(0)
        private set
    val prompt: Detected? get() = waiting.takeIf { flight == null && !picking }

    @Synchronized
    fun found(f: Find, now: Long, round: Long = now): Boolean {
        expire(now)
        if (!candidate(f.name, f.audioVideo)) {
            if (windows.remove(f.address) != null) drop(f.address)
            return false
        }
        val w = windows[f.address]?.also { it.last = now } ?: Window(now, f.name).also { windows[f.address] = it }
        if (f.name != null) w.name = f.name
        if (f.bonded || f.linked || w.cancelled || w.sdp == false) return false
        if (f.companion) w.sdp = true
        if (w.sdp == true) {
            if (round > w.laterAt) offer(f.address, w)
            return false
        }
        return true
    }

    @Synchronized
    fun pending(now: Long): List<String> {
        expire(now)
        return windows.filterValues { it.sdp == null && !it.cancelled }.keys.toList()
    }

    @Synchronized
    fun sdp(address: String, companion: Boolean, now: Long) {
        expire(now)
        val w = windows[address] ?: return
        w.sdp = companion
        if (companion && !w.cancelled) offer(address, w)
    }

    @Synchronized
    fun cancel(address: String) {
        windows[address]?.cancelled = true
        drop(address)
    }

    @Synchronized
    fun later(address: String, now: Long) {
        windows[address]?.let {
            it.laters++
            it.laterAt = now
            if (it.laters > 1) it.cancelled = true
        }
        drop(address)
    }

    @Synchronized
    fun taken(address: String) = drop(address)

    @Synchronized
    fun detected(): List<Detected> = listOfNotNull(waiting) + queue

    @Synchronized
    fun pairing(address: String, name: String, now: Long) {
        flight = Flight(name, address)
        flightAt = now
        drop(address)
        rev++
    }

    @Synchronized
    fun paired(address: String) {
        val f = flight ?: return
        if (f.address != address) return
        flight = f.copy(paired = true)
        rev++
    }

    @Synchronized
    fun settled(address: String) {
        if (flight?.address == address) flight = null
        drop(address)
        rev++
    }

    @Synchronized
    fun bump() {
        rev++
    }

    @Synchronized
    fun tick(now: Long) {
        expire(now)
        if (flight != null && now - flightAt > FLIGHT_MS) {
            flight = null
            rev++
        }
    }

    @Synchronized
    fun away() {
        queue.clear()
        waiting = null
        rev++
    }

    @Synchronized
    fun clear() {
        windows.clear()
        flight = null
        picking = false
        away()
    }

    private fun offer(address: String, w: Window) {
        if (waiting?.address == address || queue.any { it.address == address }) return
        val d = Detected(w.name ?: address, address)
        if (waiting == null) waiting = d else queue += d
        rev++
    }

    private fun drop(address: String) {
        val queued = queue.removeAll { it.address == address }
        if (waiting?.address == address) waiting = queue.removeFirstOrNull() else if (!queued) return
        rev++
    }

    private fun expire(now: Long) {
        val gone = windows.filterValues { now - it.last > WINDOW_MS }.keys
        if (gone.isEmpty()) return
        gone.forEach { windows.remove(it) }
        queue.removeAll { it.address in gone }
        if (waiting?.address in gone) waiting = queue.removeFirstOrNull()
        rev++
    }

    companion object {
        const val WINDOW_MS = 120_000L
        const val FLIGHT_MS = 60_000L

        fun candidate(name: String?, audioVideo: Boolean) = name?.lowercase()?.startsWith("fune") ?: audioVideo
    }
}
