package net.seilent.funecomp

import net.seilent.funecomp.sim.Sim

fun listenAgain(s: Sim, port: Int, waitMs: Long = 10_000) {
    val end = System.currentTimeMillis() + waitMs
    var seen = ""
    while (true) {
        try {
            s.listen(port)
            return
        } catch (e: java.net.BindException) {
            val holders = portHolders(port)
            if (System.currentTimeMillis() > end) throw java.net.BindException("${e.message} on $port, held by $holders")
            if (holders != seen) System.err.println("listenAgain $port busy, held by $holders")
            seen = holders
            Thread.sleep(50)
        }
    }
}

fun ephemeralLow(): Int = runCatching { java.io.File("/proc/sys/net/ipv4/ip_local_port_range").readText().trim().split(Regex("\\s+"))[0].toInt() }.getOrDefault(32768)

fun listenBelowEphemeral(s: Sim): Int {
    val low = ephemeralLow()
    val rnd = java.util.Random()
    repeat(200) {
        try {
            return s.listen(low - 12_000 + rnd.nextInt(12_000))
        } catch (_: java.net.BindException) {
        }
    }
    error("no free port below $low")
}

private fun portHolders(port: Int): String {
    val own = java.io.File("/proc/self/fd").listFiles().orEmpty().mapNotNull { runCatching { java.nio.file.Files.readSymbolicLink(it.toPath()).toString() }.getOrNull() }.toSet()
    val states = mapOf("01" to "ESTABLISHED", "02" to "SYN_SENT", "03" to "SYN_RECV", "04" to "FIN_WAIT1", "05" to "FIN_WAIT2", "06" to "TIME_WAIT", "07" to "CLOSE", "08" to "CLOSE_WAIT", "09" to "LAST_ACK", "0A" to "LISTEN", "0B" to "CLOSING")
    val local = ":%04X".format(port)
    val rows = listOf("/proc/net/tcp", "/proc/net/tcp6").map { java.io.File(it) }.filter { it.canRead() }.flatMap { it.readLines().drop(1) }
        .map { it.trim().split(Regex("\\s+")) }
        .filter { it.size > 9 && it[1].endsWith(local) }
    if (rows.isEmpty()) return "nothing listed"
    return rows.joinToString("; ") {
        val owner = when {
            it[9] == "0" -> "no owner"
            "socket:[${it[9]}]" in own -> "this jvm"
            else -> "another process"
        }
        "${states[it[3]] ?: it[3]} remote ${it[2]} $owner"
    }
}
