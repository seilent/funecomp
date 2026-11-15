package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import net.seilent.funecomp.proto.LibDeleteReply
import net.seilent.funecomp.proto.LibRow
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.state.CardView
import net.seilent.funecomp.state.LibDelete
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Executor
import javax.imageio.ImageIO

private const val LONG_SONG = "Sometimes It Snows in April, the Long Version From the Parade Sessions"
private const val LONG_ALBUM = "The Gold Experience, the Complete Sessions Remastered and Expanded Edition"

private val DELETE_LIB = Library(
    listOf(Library.track("Prince", "Raspberry Beret", "Raspberry Beret", 1, 213_000)) +
        (1..12).map { Library.track("Prince and The Revolution", "Around the World in a Day", "Track %02d".format(it), it, 200_000) } +
        listOf(Library.track("Prince", "Sign o' the Times", LONG_SONG, 1, 400_000)) +
        (1..2).map { Library.track("Prince", LONG_ALBUM, "Gold %02d".format(it), it, 300_000) },
)

private fun albumOf(name: String) = DELETE_LIB.tracks.first { it.album == name }.let { java.lang.Long.toUnsignedString(it.id) }

private val ROUND = Page.Album(albumOf("Around the World in a Day"), "Around the World in a Day", "Prince and The Revolution")
private val TIMES = Page.Album(albumOf("Sign o' the Times"), "Sign o' the Times", "Prince")
private val PRINCE = Page.Artist("Prince")
private val MUSIC = listOf(Page.Home, Page.Music)

private class DeleteShot(
    val name: String,
    val caption: String,
    val pages: List<Page> = MUSIC,
    val faults: List<String> = emptyList(),
    val seed: Boolean = true,
    val after: Long = 600,
    val act: DeleteShots.(SimControl, Shell) -> Unit,
)

private val SHOT_SIZES = setOf("411x960", "sn411x438", "wm361x343", "flip960x411")
private const val CAPTION = 64
private const val LINES = 3

private fun deleteSheet(rows: List<List<Pair<BufferedImage, List<String>>>>, file: File) {
    val w = rows.maxOf { r -> r.sumOf { it.first.width / 2 } }
    val h = rows.sumOf { r -> r.maxOf { it.first.height / 2 } + CAPTION * LINES }
    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.color = Color.BLACK
    g.fillRect(0, 0, w, h)
    var y = 0
    for (r in rows) {
        var x = 0
        val rh = r.maxOf { it.first.height / 2 }
        for ((img, lines) in r) {
            val tw = img.width / 2
            g.drawImage(img, x, y, tw, img.height / 2, null)
            g.color = Color.WHITE
            lines.forEachIndexed { i, text ->
                var size = CAPTION * 3 / 5
                do {
                    g.font = Font(Font.SANS_SERIF, Font.PLAIN, size--)
                } while (size > 1 && g.fontMetrics.stringWidth(text) > tw - CAPTION / 2)
                g.drawString(text, x + CAPTION / 4, y + rh + CAPTION * (i + 1) - CAPTION / 4)
            }
            x += tw
        }
        y += rh + CAPTION * LINES
    }
    g.dispose()
    ImageIO.write(out, "png", file)
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class DeleteShots {
    @get:Rule
    val rule = createComposeRule()

    private var frame by mutableStateOf<Size?>(null)
    private var control by mutableStateOf<SimControl?>(null)
    private var shell by mutableStateOf(Shell())
    private var t = 1_000L

    fun settle(ms: Long = 1000) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    fun hold(tag: String) {
        rule.onNodeWithTag(tag).performTouchInput { longClick() }
        settle()
    }

    fun pick(text: String) {
        rule.onNodeWithTag("sheet.item.${shell.sheet.items.indexOf(text)}").performClick()
        rule.waitForIdle()
    }

    fun pivot(name: String) = rule.pivotTo("music", name) { settle() }

    fun confirm(row: String) {
        hold(row)
        pick("delete")
        settle()
    }

    fun outcome(c: SimControl, album: Boolean, reply: LibDeleteReply?, code: Int?, text: String?, title: String? = null) {
        val row = if (album) LibRow(title ?: ROUND.title, 12, ROUND.key.toULong().toLong(), ROUND.key, artist = "Prince and The Revolution") else LibRow(title ?: "Track 01", 1, ROUND.key.toULong().toLong(), ROUND.key)
        val ask = LibDelete.ask(c.device, row, album, SimControl.DELETE_KEY)!!
        when (val o = LibDelete.outcome(ask, reply, code, text)) {
            is LibDelete.Outcome.Toast -> c.deletes.show(o.line, o.title)
            is LibDelete.Outcome.Card -> c.deletes.show(CardView.REFUSED, "")
            else -> error("no toast for ${o.log}")
        }
    }

    private val cases = listOf(
        DeleteShot("songs-sheet", "SONGS row sheet") { _, _ ->
            pivot("songs")
            hold("row.0")
        },
        DeleteShot("albumpage-sheet", "album page row sheet", pages = MUSIC + ROUND) { _, _ -> hold("row.0") },
        DeleteShot("albums-sheet", "ALBUMS row sheet") { _, _ -> hold("row.1") },
        DeleteShot("albums-sheet-nodelete", "ALBUMS row sheet, noDelete", faults = listOf("no-delete=${ROUND.key}")) { _, _ -> hold("row.1") },
        DeleteShot("song-confirm", "song confirm", pages = MUSIC + ROUND) { _, _ -> confirm("row.0") },
        DeleteShot("song-confirm-long", "song confirm, a long title on one line", pages = MUSIC + TIMES) { _, _ -> confirm("row.0") },
        DeleteShot("song-confirm-first", "song confirm, first frame after the item tap", pages = MUSIC + ROUND, after = 0) { _, _ ->
            hold("row.0")
            pick("delete")
            rule.mainClock.advanceTimeByFrame()
        },
        DeleteShot("album-confirm-1", "album confirm, 1 song", pages = MUSIC + PRINCE) { _, _ -> confirm("row.0") },
        DeleteShot("album-confirm-long", "album confirm, a long title", pages = MUSIC + PRINCE) { _, _ -> confirm("row.2") },
        DeleteShot("album-confirm-first", "album confirm, first frame after the item tap", pages = MUSIC + PRINCE, after = 0) { _, _ ->
            hold("row.0")
            pick("delete")
            rule.mainClock.advanceTimeByFrame()
        },
        DeleteShot("album-confirm-12", "album confirm, 12 songs") { _, _ -> confirm("row.1") },
        DeleteShot("toast-some", "reply failed 3 of 12", pages = MUSIC + ROUND) { c, _ -> outcome(c, true, LibDeleteReply(9, 3), null, null) },
        DeleteShot("toast-one", "reply failed 1 of 12", pages = MUSIC + ROUND) { c, _ -> outcome(c, true, LibDeleteReply(11, 1), null, null) },
        DeleteShot("toast-album", "reply failed all", pages = MUSIC + ROUND) { c, _ -> outcome(c, true, LibDeleteReply(0, 12), null, null) },
        DeleteShot("toast-song", "song reply failed", pages = MUSIC + ROUND) { c, _ -> outcome(c, false, LibDeleteReply(0, 1), null, null) },
        DeleteShot("toast-cue", "4 part of a cue image", pages = MUSIC + ROUND) { c, _ -> outcome(c, true, null, Err.refused, LibDelete.CUE) },
        DeleteShot("toast-refused", "4 refused, 7, 10 album", pages = MUSIC + ROUND) { c, _ -> outcome(c, true, null, Err.refused, "refused") },
        DeleteShot("toast-readonly", "11 card is read only", pages = MUSIC + ROUND) { c, _ -> outcome(c, true, null, Err.readOnly, LibDelete.READ_ONLY) },
        DeleteShot("toast-card", "14 card not available", pages = MUSIC + ROUND) { c, _ -> outcome(c, true, null, Err.noCard, "card not available") },
        DeleteShot("toast-long-song", "song reply failed, a long title", pages = MUSIC + TIMES) { c, _ -> outcome(c, false, LibDeleteReply(0, 1), null, null, LONG_SONG) },
        DeleteShot("toast-long-album", "reply failed all, a long title", pages = MUSIC + PRINCE) { c, _ -> outcome(c, true, LibDeleteReply(0, 12), null, null, LONG_ALBUM) },
        DeleteShot("toast-songs", "song reply failed over SONGS") { c, _ ->
            pivot("songs")
            outcome(c, false, LibDeleteReply(0, 1), null, null)
        },
        DeleteShot("toast-albums", "reply failed 3 of 12 over ALBUMS") { c, _ -> outcome(c, true, LibDeleteReply(9, 3), null, null) },
        DeleteShot("confirm-on-dap", "the wait for the dap", pages = MUSIC + ROUND, seed = false) { _, _ ->
            confirm("row.0")
            pick("delete song")
        },
        DeleteShot("busy", "the wait, then an at once busy", pages = MUSIC + ROUND, faults = listOf("bind=busy"), seed = false) { _, _ ->
            confirm("row.0")
            pick("delete song")
        },
        DeleteShot("screen-off", "screen off", pages = MUSIC + ROUND, faults = listOf("bind=screen"), seed = false) { _, _ ->
            confirm("row.0")
            pick("delete song")
        },
        DeleteShot("declined", "declined", pages = MUSIC + ROUND, faults = listOf("bind=keep"), seed = false) { c, _ ->
            confirm("row.0")
            pick("delete song")
            settle()
            c.answer()
        },
        DeleteShot("no-answer", "no answer", pages = MUSIC + ROUND, seed = false) { c, _ ->
            confirm("row.0")
            pick("delete song")
            settle()
            c.unlink()
        },
    )

    @Test
    fun shots() {
        assumeTrue(System.getProperty("shots.run") == "true")
        val only = System.getProperty("shots.only").orEmpty()
        val dir = File(System.getProperty("shots.dir") ?: "build/shots", "delete")
        BindTokens.init(RuntimeEnvironment.getApplication(), JvmSeal(), Executor { it.run() })
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val s = frame ?: return@setContent
            val c = control ?: return@setContent
            key(s, shell) {
                Box(Modifier.requiredSize(s.w.dp, s.h.dp).testTag("frame")) {
                    App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), c, shell, insets = s.bars)
                }
            }
        }
        val sizes = SIZES.filter { it.name in SHOT_SIZES }
        for (case in cases) {
            if (only.isNotEmpty() && only.split(',').none { "DeleteShots-${case.name}".contains(it) }) continue
            val row = ArrayList<Pair<BufferedImage, List<String>>>()
            for (size in sizes) {
                BindTokens.get(SimControl.DELETE_KEY)?.let { BindTokens.drop(SimControl.DELETE_KEY, it) }
                val c = SimControl("ap80", DELETE_LIB, now = { t }, stateNow = SHOT_CLOCK, faults = listOf("bind-window") + case.faults).also { shotClock(it) }
                if (case.seed) c.sim.bindToken?.let { BindTokens.put(SimControl.DELETE_KEY, it) }
                control = c
                shell = Shell(Nav(case.pages))
                frame = size
                settle()
                case.act(this, c, shell)
                settle(case.after)
                val path = "${case.name}/${case.name}-${size.name}.png"
                val file = File(dir, path)
                file.parentFile.mkdirs()
                rule.onNodeWithTag("frame").captureRoboImage(file.path)
                System.gc()
                row += ImageIO.read(file) to listOf(case.caption, path)
            }
            deleteSheet(listOf(row), File(dir, "${case.name}.png"))
        }
    }
}
