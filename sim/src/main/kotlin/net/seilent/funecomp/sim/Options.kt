package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.proto.gen.Tags

class Board(
    val name: String,
    val screenW: Int,
    val screenH: Int,
    val backdropW: Int,
    val backdropH: Int,
    val schema: String,
) {
    companion object {
        fun of(name: String) = when (name) {
            "ap80" -> Board("ap80", 360, 480, 384, 480, "schema-ap80.txt")
            "v1" -> Board("v1", 240, 320, 256, 320, "schema-v1.txt")
            else -> throw IllegalArgumentException("board must be ap80 or v1, not $name")
        }
    }
}

class EnumFault(val type: Int, val tag: Int, val value: Int)

class Faults(specs: List<String>) {
    var dropAfterBytes: Long? = null
    var stallMs = 0L
    var noRead = false
    var major = Proto.major
    var slowBps: Long? = null
    val refuse = HashSet<Int>()
    var bye: Int? = null
    var restart = false
    var abortAt: Long? = null
    val enums = ArrayList<EnumFault>()
    var features = Groups.all

    init {
        for (spec in specs.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }) {
            val k = spec.substringBefore('=')
            val v = spec.substringAfter('=', "")
            when (k) {
                "drop-after-bytes" -> dropAfterBytes = v.toLong()
                "stall-ms" -> stallMs = v.toLong()
                "no-read" -> noRead = true
                "major" -> major = v.toInt()
                "slow-bps" -> slowBps = v.toLong()
                "refuse" -> refuse += Msg.byName[v] ?: throw IllegalArgumentException("refuse: unknown msg $v")
                "bye" -> bye = Enums.ByeReason.names.entries.firstOrNull { it.value == v }?.key
                    ?: throw IllegalArgumentException("bye: unknown reason $v")
                "restart" -> restart = true
                "abort-at" -> abortAt = v.toLong()
                "features" -> features = v.toLong()
                "enum" -> {
                    val target = v.substringBefore('=')
                    val msg = target.substringBefore('.')
                    val field = target.substringAfter('.')
                    val type = Msg.byName[msg] ?: throw IllegalArgumentException("enum: unknown msg $msg")
                    val tag = Tags.scopes[msg]?.values?.firstOrNull { it.name == field }?.tag
                        ?: throw IllegalArgumentException("enum: $msg has no field $field")
                    enums += EnumFault(type, tag, v.substringAfter('=').toInt())
                }
                else -> throw IllegalArgumentException("unknown fault $k")
            }
        }
    }
}

class Options(
    val board: Board = Board.of("ap80"),
    val faults: Faults = Faults(emptyList()),
    val idleMs: Long = Proto.keepaliveIdleMs.toLong(),
    val busyMs: Long = Proto.keepaliveBusyMs.toLong(),
    val helloTimeoutMs: Long = Proto.helloTimeoutMs.toLong(),
    val log: (String) -> Unit = {},
)
