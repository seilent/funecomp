package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.gen.Enums
import java.io.File
import java.time.LocalTime
import kotlin.system.exitProcess

private const val USAGE = "usage: sim --board ap80|v1|r1 (--listen <port> | --connect <host:port>) [--lib <file>] " +
    "[--fault <spec>]... [--idle-ms <n>] [--busy-ms <n>] [--img-dir <dir>] [--minor <n>] [--img-cap <bytes>] " +
    "[--art-dir <dir>] [--lrc-dir <dir>] [--upd-dir <dir>] [--upd-keys <hex>,...] [--upd-seq <n>] [--battery <from>:<to>@<ms>] [--card <freeMiB>:<totalMiB>] " +
    "[--curve v1|linear] [--max-pct <n>] [--card-eq <name>,...] [--source local|phone] [--rescan-lib <file>] [--version <s>] [--backdrop <w>x<h>] [--lyr-offset <id>:<ms>]... " +
    "[--playlog <file>] [--playlog-id <hex>] [--playlog-cap <n>] [--receiver] [--phone <file>] [--headset] [--companion] [--quiet]"

fun main(args: Array<String>) {
    var board = "ap80"
    var backdrop: String? = null
    var listen: Int? = null
    var connect: String? = null
    var lib: String? = null
    val faults = ArrayList<String>()
    var idle: Long? = null
    var busy: Long? = null
    var quiet = false
    var imgDir: String? = null
    var minor: Int? = null
    var imgCap: Long? = null
    var artDir: String? = null
    var lrcDir: String? = null
    var updDir: String? = null
    var updKeys: Set<String>? = null
    var updSeq: Long? = null
    var battery: String? = null
    var card: String? = null
    var curve: String? = null
    var maxPct: String? = null
    val cardEq = ArrayList<String>()
    var source: String? = null
    var rescanLib: String? = null
    var version: String? = null
    val lyrOffsets = HashMap<Long, Int>()
    var playLog: String? = null
    var playLogId: String? = null
    var playLogCap: Int? = null
    var receiver = false
    var phone: String? = null
    var headset = false
    var companion = false
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
            "--img-dir" -> imgDir = value()
            "--minor" -> minor = value().toInt()
            "--img-cap" -> imgCap = value().toLong()
            "--art-dir" -> artDir = value()
            "--lrc-dir" -> lrcDir = value()
            "--upd-dir" -> updDir = value()
            "--upd-keys" -> updKeys = value().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            "--upd-seq" -> updSeq = value().toLong()
            "--battery" -> battery = value()
            "--card" -> card = value()
            "--curve" -> curve = value()
            "--max-pct" -> maxPct = value()
            "--backdrop" -> backdrop = value()
            "--card-eq" -> cardEq += value().split(',').filter { it.isNotEmpty() }
            "--source" -> source = value()
            "--rescan-lib" -> rescanLib = value()
            "--version" -> version = value()
            "--lyr-offset" -> value().let { lyrOffsets[it.substringBefore(':').toULong().toLong()] = it.substringAfter(':').toInt() }
            "--playlog" -> playLog = value()
            "--playlog-id" -> playLogId = value()
            "--playlog-cap" -> playLogCap = value().toInt()
            "--receiver" -> receiver = true
            "--phone" -> phone = value()
            "--quiet" -> quiet = true
            "--headset" -> headset = true
            "--companion" -> companion = true
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
    val b = backdrop?.let { v ->
        val wh = v.split('x').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 && it.all { n -> n > 0 } }
            ?: throw IllegalArgumentException("backdrop must be <w>x<h>, not $v")
        Board.of(board).withBackdrop(wh[0], wh[1])
    } ?: Board.of(board)
    val opts = Options(
        b,
        Faults(faults),
        idle ?: base.idleMs,
        busy ?: base.busyMs,
        base.helloTimeoutMs,
        if (quiet) ({}) else ({ s -> println("${LocalTime.now()} $s") }),
        imgDir?.let { File(it) },
        minor ?: base.minor,
        imgCap ?: base.imgCap,
        artDir?.let { File(it) },
        lrcDir?.let { File(it) },
        updDir?.let { File(it) },
        battery?.let { Battery.parse(it) } ?: base.battery,
        card?.substringBefore(':')?.toLong() ?: base.cardFreeMiB,
        card?.substringAfter(':')?.toLong() ?: base.cardTotalMiB,
        curve?.let { it == "v1" } ?: (b.name == "v1"),
        maxPct?.toInt(),
        cardEq,
        source?.let { v -> Enums.Source.names.entries.firstOrNull { it.value == v }?.key ?: throw IllegalArgumentException("source must be local or phone, not $v") } ?: base.source,
        rescanLib?.let { Library.load(File(it)) },
        version,
        lyrOffsets,
        playLog?.let { PlayLog.parse(File(it)) } ?: base.playLog,
        playLogId?.let { java.lang.Long.parseUnsignedLong(it.removePrefix("0x"), 16) } ?: base.playLogId,
        playLogCap ?: base.playLogCap,
        phone = phone?.let { PhoneLine.load(File(it)) }.orEmpty(),
        receiver = receiver,
        headset = headset,
        companion = companion,
        updKeys = updKeys,
        updSeq = updSeq ?: base.updSeq,
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
