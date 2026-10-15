package net.seilent.funecomp.sim

import java.io.File
import java.time.LocalTime
import kotlin.system.exitProcess

private const val USAGE = "usage: sim --board ap80|v1 (--listen <port> | --connect <host:port>) [--lib <file>] " +
    "[--fault <spec>]... [--idle-ms <n>] [--busy-ms <n>] [--quiet]"

fun main(args: Array<String>) {
    var board = "ap80"
    var listen: Int? = null
    var connect: String? = null
    var lib: String? = null
    val faults = ArrayList<String>()
    var idle: Long? = null
    var busy: Long? = null
    var quiet = false
    var i = 0
    fun value(): String = args.getOrNull(++i) ?: run {
        System.err.println(USAGE)
        exitProcess(2)
    }
    while (i < args.size) {
        when (args[i]) {
            "--board" -> board = value()
            "--listen" -> listen = value().toInt()
            "--connect" -> connect = value()
            "--lib" -> lib = value()
            "--fault" -> faults += value()
            "--idle-ms" -> idle = value().toLong()
            "--busy-ms" -> busy = value().toLong()
            "--quiet" -> quiet = true
            else -> {
                System.err.println(USAGE)
                exitProcess(2)
            }
        }
        i++
    }
    if ((listen == null) == (connect == null)) {
        System.err.println(USAGE)
        exitProcess(2)
    }
    val base = Options()
    val opts = Options(
        Board.of(board),
        Faults(faults),
        idle ?: base.idleMs,
        busy ?: base.busyMs,
        base.helloTimeoutMs,
        if (quiet) ({}) else ({ s -> println("${LocalTime.now()} $s") }),
    )
    val sim = Sim(opts, lib?.let { Library.load(File(it)) } ?: Library.synthetic())
    listen?.let {
        sim.listen(it)
        Thread.currentThread().join()
    }
    connect?.let {
        val at = it.lastIndexOf(':')
        sim.connect(it.substring(0, at), it.substring(at + 1).toInt())
    }
}
