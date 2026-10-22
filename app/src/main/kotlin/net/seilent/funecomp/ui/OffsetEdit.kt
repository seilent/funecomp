package net.seilent.funecomp.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.state.OffsetSend

private const val SHEET = "lyrics"
private const val ITEM = "offset"
private const val UNIT = "ms"

fun lyricsSheet(shell: Shell, control: Control, track: Long, shown: Int) {
    lateinit var e: OffsetEdit
    e = OffsetEdit(track, shown, control, { line, title -> shell.toast.show(line, title) }) {
        if (shell.offset === e) shell.offset = null
        shell.sheet.closeIf(e)
    }
    shell.offset = e
    control.lyricsOffsetEdit(e)
    e.show(shell.sheet)
    e.open()
}

@Stable
class OffsetEdit(
    val track: Long,
    shown: Int,
    private val control: Control,
    private val toast: (String, String) -> Unit = { _, _ -> },
    private val closed: () -> Unit = {},
) {
    private val send = OffsetSend(track, shown)
    private val main = Handler(Looper.getMainLooper())
    private var ended = false
    private var entry = false

    var shown by mutableIntStateOf(shown)
        private set
    var editing by mutableStateOf(false)
        private set
    val stored: Int? get() = send.stored

    private fun ui(f: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) f() else main.post(f)
    }

    private fun sync() {
        shown = send.shown
        editing = send.editing
    }

    private fun run(o: OffsetSend.Out) {
        sync()
        if (o.held) control.lyricsHold(track)
        o.toast?.let { code ->
            ui {
                toast(OffsetSend.TITLE, OffsetSend.toast(code))
                if (OffsetSend.closes(code)) end(true)
            }
        }
        val s = o.send ?: return
        control.lyricsOffset(track, s.ms) { code, ms ->
            run(
                send.replied(
                    when {
                        code == null -> OffsetSend.Reply.Ok(ms ?: 0)
                        code < 0 -> OffsetSend.Reply.Lost
                        else -> OffsetSend.Reply.Failed(code)
                    },
                ),
            )
        }
    }

    fun open() = run(send.open())

    fun show(sheet: SheetState) {
        entry = false
        sheet.open(
            SHEET,
            listOf(ITEM),
            owner = this,
            onDismiss = { end(true) },
            itemValues = { listOf(OffsetSend.text(shown)) },
            accent = { if (editing) 0 else -1 },
            onPress = { i, x ->
                press(i, x)
                true
            },
            onHold = { i ->
                if (i == 0) type(sheet)
                true
            },
            onScrim = { editing.also { if (it) restore() } },
        ) {}
    }

    fun press(i: Int, x: Float) {
        when {
            i != 0 -> if (editing) keep()
            editing -> step(if (x < 0.5f) -1 else 1)
            else -> begin()
        }
    }

    fun back(sheet: SheetState): Boolean = when {
        entry -> {
            show(sheet)
            true
        }
        editing -> {
            restore()
            true
        }
        else -> false
    }

    private fun type(sheet: SheetState) {
        if (editing) restore()
        entry = true
        val start = shown.toString()
        sheet.openEntry(OffsetSend.TITLE, owner = this) {
            Entry(start, unit = UNIT) { text ->
                if (text == start) {
                    show(sheet)
                } else {
                    val ms = OffsetSend.parse(text)
                    if (ms == null) {
                        toast(OffsetSend.TITLE, OffsetSend.RANGE)
                    } else {
                        typed(ms)
                        show(sheet)
                    }
                }
            }
        }
    }

    fun begin() = run(send.begin())

    fun step(d: Int) = run(send.step(d))

    fun coarse(d: Int) = run(send.coarse(d))

    fun typed(ms: Int) = run(send.typed(ms))

    fun keep() {
        send.keep()
        sync()
    }

    fun restore() = run(send.restore())

    fun mark() = send.mark()

    fun state(s: State, mark: OffsetSend.Mark?) {
        if (mark != null && s.track == track && s.lyrics == true && send.observed(s.lyricsOffsetMs ?: 0, mark)) sync()
    }

    fun replaced(track: Long) = run(send.replaced(track))

    fun end(sessionUp: Boolean) {
        entry = false
        synchronized(this) {
            if (ended) return
            ended = true
        }
        run(send.close(sessionUp))
        control.lyricsHold(null)
        control.lyricsOffsetEdit(null)
        ui(closed)
    }

    companion object {
        const val GROUP = "lyricsOffset"
    }
}
