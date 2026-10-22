package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.proto.gen.Tags
import java.io.File

const val RECEIVER_TEXT = "bluetooth receiver"
private const val PREV_RESTART_MS = 3000
private const val JUMP_MS = 2000

class PhoneLine(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val startMs: Long,
    val playing: Boolean,
    val name: String? = null,
    val trackNo: Int? = null,
    val count: Int? = null,
    val cover: File? = null,
) {
    companion object {
        fun load(f: File): List<PhoneLine> = f.readLines().filter { it.isNotBlank() }.map { line ->
            val p = line.split('\t')
            require(p.size >= 6) { "${f.name}: needs title, artist, album, duration ms, start ms, playing: $line" }
            PhoneLine(
                p[0], p[1], p[2], p[3].toLong(), p[4].toLong(), p[5] == "1",
                p.getOrNull(6)?.ifEmpty { null }, p.getOrNull(7)?.toIntOrNull()?.takeIf { it > 0 },
                p.getOrNull(8)?.toIntOrNull()?.takeIf { it > 0 }, p.getOrNull(9)?.ifEmpty { null }?.let { File(f.parentFile, it) },
            )
        }
    }
}

class Phone(private val opts: Options, private val clock: () -> Long) {
    private val lines = opts.phone
    private val faults = opts.faults
    private val started = clock()
    private var at: Int? = null
    private var playing = false
    private var positionMs = 0L
    private var positionAt = 0L
    private var lineAt = 0L
    private var pausedAt: Long? = null
    private var before: PhoneLine? = null
    private var seeked = false
    private var sentSig: List<Any?>? = null
    private var sentPos = 0L
    private var sentAt = 0L

    init {
        if (lines.isNotEmpty()) enter(0)
    }

    private fun now() = clock()

    private fun exited() = faults.phoneExit?.let { now() - started >= it } == true

    val receiver: Boolean get() = opts.receiver && !exited()

    private fun line() = at?.let { lines[it] }

    private fun enter(i: Int) {
        before = line()
        at = i
        val l = lines[i]
        positionMs = l.startMs
        positionAt = now()
        lineAt = now()
        playing = l.playing
        pausedAt = if (playing) null else now()
    }

    private fun suspended(): Boolean {
        val p = pausedAt ?: return false
        val s = faults.phoneSuspend ?: return false
        return now() - p >= s
    }

    fun current(): PhoneLine? {
        if (exited()) return null
        val l = line() ?: return null
        if (playing) return l
        return if (opts.receiver && !suspended()) l else null
    }

    private fun lateMeta() = faults.phoneLateMeta?.let { now() - lineAt < it } == true

    private fun duration(l: PhoneLine): Long {
        if (lateMeta()) return before?.durationMs ?: 0
        if (l.durationMs == 0L && faults.phoneStaleDuration) return before?.durationMs ?: 0
        return l.durationMs
    }

    private fun position(l: PhoneLine): Long {
        seek()
        val p = if (playing) positionMs + (now() - positionAt) else positionMs
        val d = l.durationMs
        return if (d > 0) minOf(p, d) else p
    }

    private fun seek() {
        val (t, to) = faults.phoneSeek ?: return
        if (seeked || now() - started < t || line() == null) return
        seeked = true
        positionMs = to
        positionAt = now()
    }

    fun cover(): ByteArray? {
        val l = current() ?: return null
        if (faults.phoneArtLate?.let { now() - lineAt < it } == true) return null
        return l.cover?.takeIf { it.isFile }?.readBytes()
    }

    private fun sig(): List<Any?> {
        val l = current()
        return listOf(l != null, at, l?.title, l != null && playing)
    }

    fun over(s: State): State {
        val l = current()
        sentSig = sig()
        sentAt = now()
        if (l == null) return s
        sentPos = position(l)
        val late = lateMeta()
        val artist = if (late) before?.artist.orEmpty() else l.artist
        return s.copy(
            source = Enums.Source.phone, output = Enums.Output.phoneReceiver, track = 0, title = l.title, artist = artist,
            artists = Library.split(artist, ""), mbids = emptyList(), durationMs = duration(l), positionMs = sentPos,
            playing = playing, shuffle = false, repeat = Enums.Repeat.off, album = if (late) before?.album.orEmpty() else l.album,
            lyrics = null, favorite = null, lyricsPlain = null, lyricsCrc = null,
        )
    }

    fun tick(lastStateAt: Long): Boolean {
        if (lines.isEmpty() || sentSig == null) return false
        val l = line()
        if (l != null && playing && l.durationMs > 0 && position(l) >= l.durationMs) step(1, auto = true)
        val c = current() ?: return sig() != sentSig
        if (sig() != sentSig) return true
        val p = position(c)
        if (playing) {
            if (kotlin.math.abs(p - (sentPos + now() - sentAt)) > JUMP_MS) return true
            return now() - lastStateAt >= Proto.stateIntervalMs
        }
        return p != sentPos
    }

    private fun step(d: Int, auto: Boolean) {
        val i = (at ?: return) + d
        when {
            i < 0 -> enter(0)
            i < lines.size -> enter(i)
            auto -> {
                before = line()
                at = null
                playing = false
            }
        }
    }

    private fun mark(l: PhoneLine) {
        positionMs = position(l)
        positionAt = now()
    }

    fun transport(b: Node): Answer? {
        val cmd = b.int(Tags.Transport.cmd)
        val c = current()
        if (c == null) {
            if (!receiver || (cmd != Enums.TransportCmd.toggle && cmd != Enums.TransportCmd.play)) return null
            val l = line() ?: return Answer.Fail(Err.refused, RECEIVER_TEXT)
            mark(l)
            playing = true
            pausedAt = null
            return Answer.Ok(state = true)
        }
        when (cmd) {
            Enums.TransportCmd.toggle, Enums.TransportCmd.play, Enums.TransportCmd.pause -> {
                mark(c)
                playing = if (cmd == Enums.TransportCmd.toggle) !playing else cmd == Enums.TransportCmd.play
                pausedAt = if (playing) null else now()
            }
            Enums.TransportCmd.next -> step(1, auto = false)
            Enums.TransportCmd.prev -> if (position(c) > PREV_RESTART_MS) {
                positionMs = 0
                positionAt = now()
            } else {
                step(-1, auto = false)
            }
            else -> return Answer.Fail(Err.refused, "")
        }
        return Answer.Ok(state = true)
    }

    fun refuse(): Answer? = if (receiver) Answer.Fail(Err.refused, RECEIVER_TEXT) else null
}
