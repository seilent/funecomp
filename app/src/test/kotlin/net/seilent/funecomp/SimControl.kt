package net.seilent.funecomp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.seilent.funecomp.artist.AutoEqIndex
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Profile
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.sim.Answer
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Device
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Out
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.ui.Bonded
import net.seilent.funecomp.ui.Control
import net.seilent.funecomp.ui.LIB_PAGE

class SimControl(board: String, lib: Library = Library.synthetic(), private val now: () -> Long = { 0L }) : Control {
    val sim = Device(Options(Board.of(board)), lib, now)
    var device by mutableStateOf(DeviceState())
    val sent = ArrayList<String>()
    val failed = ArrayList<Int>()

    private val out = object : Out {
        override fun push(type: Int, payload: ByteArray) {
            device = device.reduce(type, Node.decode(Msg.info.getValue(type).name, payload), now())
        }
    }

    init {
        device = device.withHello(HelloReply.from(Node.decode("HELLO.reply", sim.helloReply().encode())))
        sim.greet(out)
    }

    private fun req(type: Int, payload: ByteArray): Answer {
        sent += Msg.info.getValue(type).name
        return sim.request(type, Node.decode(Msg.info.getValue(type).name, payload), out).also { if (it is Answer.Fail) failed += it.code }
    }

    override fun transport(cmd: Int, seekMs: Long?, on: Boolean?, repeat: Int?) {
        req(Msg.TRANSPORT, Req.transport(cmd, seekMs, on, repeat))
    }

    override fun volume(detents: Int) {
        req(Msg.VOLUME, Req.volume(step = detents))
    }

    override fun libGet(view: Int, parent: String, offset: Long) {
        req(Msg.LIB_GET, Req.libGet(view, parent, offset, LIB_PAGE, device.lib.rev))
    }

    override fun play(view: Int?, parent: String?, start: Long, shuffle: Boolean, tracks: List<Long>) {
        req(Msg.PLAY, Req.play(view, parent, if (tracks.isEmpty()) device.lib.rev else null, start, shuffle, tracks))
    }

    override fun queuePage(offset: Long) {
        req(Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.page, offset = offset, count = LIB_PAGE))
    }

    override fun queueOp(cmd: Int, slot: Long?, to: Long?, tracks: List<Long>) {
        req(Msg.QUEUE_OP, Req.queueOp(cmd, device.queue.rev ?: device.playback.state.queueRev, slot = slot, to = to, tracks = tracks))
    }

    override fun set(key: String, value: String) {
        req(Msg.SET, Req.set("$key=$value\n"))
    }

    override fun draft(kind: Int, value: String?, minutes: Long?, unixS: Long?, zoneS: Int?) {
        req(Msg.DRAFT, Req.draft(kind, value, minutes = minutes, unixS = unixS, zoneS = zoneS))
    }

    override fun eqSet(output: Int, key: String?, ints: Profile?) {
        req(Msg.EQ_SET, Req.eqSet(output, key, ints))
    }

    override fun action(cmd: Int, track: Long?) {
        req(Msg.ACTION, Req.action(cmd, track))
    }

    var bonds = listOf(Bonded("AP80", "00:00:00:00:00:03", true), Bonded("V1", "00:00:00:00:00:01", true), Bonded("WH-1000XM4", "00:00:00:00:00:02"))
    var retries = 0
    var asks = 0
    var disconnects = 0
    var allows = 0
    var laters = 0

    override fun bonded() = bonds

    override fun retry() {
        retries++
    }

    override fun askPermission() {
        asks++
    }

    override fun disconnect() {
        disconnects++
    }

    override fun allowBackground() {
        allows++
    }

    override fun backgroundLater() {
        laters++
    }

    var eqText: String? = HD650
    var eqFileName = "Sennheiser HD 650 ParametricEQ.txt"
    var found: List<AutoEqIndex.Entry>? = AutoEqIndex.parse(INDEX)
    val queries = ArrayList<String>()

    override fun eqFile(done: (String, String?) -> Unit) = done(eqFileName, eqText)

    override fun autoEqFind(query: String, done: (List<AutoEqIndex.Entry>?) -> Unit) {
        queries += query
        done(found)
    }

    override fun autoEqProfile(entry: AutoEqIndex.Entry, done: (String?) -> Unit) = done(eqText)

    companion object {
        val HD650 = """
            Preamp: -6.1 dB
            Filter 1: ON LSC Fc 105 Hz Gain 6.4 dB Q 0.70
            Filter 2: ON PK Fc 8800 Hz Gain 5.1 dB Q 1.42
            Filter 3: ON PK Fc 118 Hz Gain -3.1 dB Q 0.50
            Filter 4: ON PK Fc 37 Hz Gain 0.7 dB Q 3.96
            Filter 5: ON PK Fc 3169 Hz Gain -1.7 dB Q 3.89
            Filter 6: ON HSC Fc 10000 Hz Gain -2.1 dB Q 0.70
            Filter 7: ON PK Fc 1227 Hz Gain -1.2 dB Q 2.53
            Filter 8: ON PK Fc 2055 Hz Gain 1.2 dB Q 3.23
            Filter 9: ON PK Fc 587 Hz Gain 0.4 dB Q 1.19
            Filter 10: ON PK Fc 5332 Hz Gain -1.1 dB Q 5.75
        """.trimIndent()

        val INDEX = listOf(
            "Sennheiser HD 650" to "oratory1990/over-ear/Sennheiser%20HD%20650",
            "Sennheiser HD 650 (2020)" to "crinacle/GRAS%2043AG-7%20over-ear/Sennheiser%20HD%20650%20(2020)",
            "Sennheiser HD 650 (2020, worn earpads)" to "crinacle/GRAS%2043AG-7%20over-ear/Sennheiser%20HD%20650%20(2020,%20worn%20earpads)",
            "Sennheiser HD 650 (balanced)" to "Headphone.com%20Legacy/over-ear/Sennheiser%20HD%20650%20(balanced)",
        ).joinToString("\n") { "- [${it.first}](./${it.second})" }
    }
}
