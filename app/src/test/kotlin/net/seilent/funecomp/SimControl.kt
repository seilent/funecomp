package net.seilent.funecomp

import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CompletableDeferred
import net.seilent.funecomp.art.Thumbs
import net.seilent.funecomp.artist.AutoEqIndex
import net.seilent.funecomp.artist.AutoEqList
import net.seilent.funecomp.proto.ArtGetReply
import net.seilent.funecomp.proto.EqPresetReply
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Profile
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.sim.Answer
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Device
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Out
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.state.Heart
import net.seilent.funecomp.artist.Tile
import net.seilent.funecomp.ui.Bonded
import net.seilent.funecomp.ui.BrowseTile
import net.seilent.funecomp.ui.Control
import net.seilent.funecomp.ui.LIB_PAGE
import net.seilent.funecomp.ui.PickedFile
import net.seilent.funecomp.ui.Previewed
import java.io.ByteArrayOutputStream
import java.io.File

class SimControl(
    board: String,
    lib: Library = Library.synthetic(),
    private val now: () -> Long = { 0L },
    artDir: File? = null,
    minor: Int = Proto.minor,
    faults: List<String> = emptyList(),
    cardEq: List<String> = emptyList(),
    schema: String? = null,
) : Control {
    val sim = Device(Options(Board.of(board).let { b -> if (schema == null) b else Board(b.name, b.screenW, b.screenH, b.backdropW, b.backdropH, schema) }, Faults(faults), artDir = artDir, minor = minor, cardEq = cardEq), lib, now)
    var device by mutableStateOf(DeviceState())
    val sent = ArrayList<String>()
    val failed = ArrayList<Int>()
    val actions = ArrayList<Pair<Int, Long?>>()
    private val heart = Heart()
    private var toast: ((String, String) -> Unit)? = null
    var holdReply = false
    var reply: (() -> Unit)? = null

    private val out = object : Out {
        override fun push(type: Int, payload: ByteArray) {
            val body = Node.decode(Msg.info.getValue(type).name, payload)
            device = device.reduce(type, body, now())
            if (type == Msg.STATE) show(heart.state(State.from(body)))
        }
    }

    private fun show(t: Heart.Toast?) {
        if (t != null) toast?.invoke(t.line, t.title)
    }

    init {
        device = device.withHello(HelloReply.from(Node.decode("HELLO.reply", sim.helloReply().encode())))
        sim.greet(out)
    }

    private fun call(type: Int, payload: ByteArray): Answer {
        sent += Msg.info.getValue(type).name
        return sim.request(type, Node.decode(Msg.info.getValue(type).name, payload), out).also { if (it is Answer.Fail) failed += it.code }
    }

    private fun after(a: Answer) {
        if (a is Answer.Ok && a.state) out.push(Msg.STATE, sim.state())
    }

    private fun req(type: Int, payload: ByteArray): Answer = call(type, payload).also { after(it) }

    fun tick() = sim.tick(out)

    val transports = ArrayList<Int>()

    override fun transport(cmd: Int, seekMs: Long?, on: Boolean?, repeat: Int?) {
        transports += cmd
        req(Msg.TRANSPORT, Req.transport(cmd, seekMs, on, repeat))
    }

    override fun volume(detents: Int) {
        req(Msg.VOLUME, Req.volume(step = detents))
    }

    val percents = ArrayList<Int>()
    var held: (() -> Unit)? = null

    override fun volumePercent(pct: Int, done: () -> Unit) {
        percents += pct
        req(Msg.VOLUME, Req.volume(percent = pct))
        if (hold) held = done else done()
    }

    var hold = false

    override fun libGet(view: Int, parent: String, offset: Long) {
        req(Msg.LIB_GET, Req.libGet(view, parent, offset, LIB_PAGE, device.lib.libRev(view), device.lib.snapRev(view)))
    }

    override fun play(view: Int?, parent: String?, start: Long, shuffle: Boolean, tracks: List<Long>) {
        val lib = device.lib.takeIf { tracks.isEmpty() }
        req(Msg.PLAY, Req.play(view, parent, lib?.libRev(view), start, shuffle, tracks, lib?.snapRev(view)))
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

    override fun draft(kind: Int, value: String?, minutes: Long?, unixS: Long?, zoneS: Int?, done: (Int?, String?) -> Unit) {
        val a = req(Msg.DRAFT, Req.draft(kind, value, minutes = minutes, unixS = unixS, zoneS = zoneS))
        if (a is Answer.Fail) done(a.code, a.text) else done(null, null)
    }

    override fun eqSet(output: Int, key: String?, ints: Profile?) {
        req(Msg.EQ_SET, Req.eqSet(output, key, ints))
    }

    val presets = ArrayList<Node>()

    override fun eqPreset(op: Int, output: Int?, key: String?, name: String?, ints: Profile?, replace: Boolean, done: (Int?, String?) -> Unit) {
        if (!Groups.allowed(Msg.EQ_PRESET, device.hello?.features ?: 0)) return done(-1, null)
        val payload = Req.eqPreset(op, output, key, name, ints, replace.takeIf { it })
        presets += Node.decode("EQ_PRESET", payload)
        val a = req(Msg.EQ_PRESET, payload)
        if (a is Answer.Ok) done(null, EqPresetReply.from(Node.decode("EQ_PRESET.reply", a.payload)).key) else done((a as Answer.Fail).code, null)
    }

    override fun action(cmd: Int, track: Long?, done: (Int?, String?) -> Unit) {
        actions += cmd to track
        val a = call(Msg.ACTION, Req.action(cmd, track))
        val finish = {
            if (a is Answer.Fail) done(a.code, a.text) else done(null, null)
            after(a)
        }
        if (holdReply) reply = finish else finish()
    }

    override fun favorite(s: State, toast: (String, String) -> Unit) {
        this.toast = toast
        val t = heart.tap(s) ?: return
        action(Enums.ActionCmd.favorite, t.track) { code, text -> show(heart.replied(t, code, text, now())) }
    }

    fun release() {
        val r = reply ?: return
        reply = null
        r()
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

    val reconnects = ArrayList<Boolean>()
    val medias = ArrayList<Boolean>()

    override fun mediaControls(on: Boolean) {
        medias += on
    }

    override fun reconnect(on: Boolean) {
        reconnects += on
    }

    var eqText: String? = HD650
    var eqFileName = "Sennheiser HD 650 ParametricEQ.txt"
    var autoEq: AutoEqList? = FULL
    var holdList = false
    var heldList: (() -> Unit)? = null
    var lists = 0
    val profiles = ArrayList<String>()

    override fun eqFile(done: (String, String?) -> Unit) = done(eqFileName, eqText)

    override fun autoEqList(done: (AutoEqList?) -> Unit) {
        lists++
        val l = autoEq
        if (holdList) heldList = { done(l) } else done(l)
    }

    val slow = HashMap<String, CompletableDeferred<Unit>>()

    override suspend fun autoEqView(list: AutoEqList, query: String): AutoEqList.View {
        slow[query]?.await()
        return list.view(query)
    }

    override fun autoEqProfile(entry: AutoEqIndex.Entry, done: (String?) -> Unit) {
        profiles += entry.name
        done(eqText)
    }

    val arts = mutableStateMapOf<String, ImageBitmap>()
    val wanted = ArrayList<String>()

    override fun wantArt(slot: Int, track: Long, px: Int, album: String?) {
        val p = Thumbs.bucket(px)
        val key = Thumbs.key(track, p)
        wanted += "$slot $key"
        if (key in arts) return
        val out = ByteArrayOutputStream()
        var total = Long.MAX_VALUE
        while (out.size() < total) {
            val a = req(Msg.ART_GET, Req.artGet(track, p, out.size().toLong())) as? Answer.Ok ?: return
            val r = ArtGetReply.from(Node.decode("ART_GET.reply", a.payload))
            r.total?.let { total = it }
            val d = r.data?.takeIf { it.isNotEmpty() } ?: return
            out.write(d)
        }
        BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())?.let { arts[key] = it.asImageBitmap() }
    }

    override fun art(track: Long, px: Int): ImageBitmap? = arts[Thumbs.key(track, Thumbs.bucket(px))]

    val firmware = ArrayList<String>()

    var tiles: List<BrowseTile> = emptyList()
    var browsed: Boolean? = null
    var previewed: Previewed? = null
    var gallery: Previewed? = null
    var taken: Previewed? = null

    override fun browse(name: String, mbid: String?, px: Int, tile: (BrowseTile) -> Unit, done: (Boolean) -> Unit): () -> Unit {
        tiles.forEach(tile)
        browsed?.let(done)
        return {}
    }

    override fun pickLink(text: String, name: String, done: (Previewed) -> Unit): () -> Unit {
        previewed?.let(done)
        return {}
    }

    override fun pickGallery(done: (Previewed) -> Unit): () -> Unit {
        gallery?.let(done)
        return {}
    }

    override fun takeTile(t: Tile, done: (Previewed) -> Unit): () -> Unit {
        taken?.let(done)
        return {}
    }

    override fun firmwareCheck() {
        firmware += "check"
    }

    override fun firmwareDownload() {
        firmware += "download"
    }

    override fun firmwareCancel() {
        firmware += "cancel"
    }

    override fun firmwareFile(done: (PickedFile) -> Unit) = done(PickedFile("funeos-v0.4.0-v1.upt", 28_043_264, "v1"))

    override fun firmwareSettings(checkDaily: Boolean, prereleases: Boolean) {
        firmware += "settings $checkDaily $prereleases"
    }

    override fun firmwareSend(picked: Boolean) {
        firmware += if (picked) "send picked" else "send release"
    }

    override fun firmwareStopSend() {
        firmware += "stop send"
    }

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

        val FULL: AutoEqList by lazy { AutoEqList.build(AutoEqIndex.parse(File("../artist/src/test/resources/autoeq/index.md").readText())) }
    }
}
