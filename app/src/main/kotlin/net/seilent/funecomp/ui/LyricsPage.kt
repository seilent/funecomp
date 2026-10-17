package net.seilent.funecomp.ui

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

private const val COPYRIGHT = "lyrics © their owners"

@Composable
fun LyricsPage(ctx: Ctx, on: Boolean) {
    val ui = LocalUi.current
    val rows = listOf("lrclib" to (if (on) "on" else "off"), COPYRIGHT to "")
    HubPage("lyrics", { ctx.nav.pop() }, count = if (on) "lrclib" else "off") {
        Rows(ui.contentTop, focus = 0) {
            itemsIndexed(rows, key = { _, r -> r.first }) { i, r ->
                val info = r.first == COPYRIGHT
                SettingsRow(r.first, r.second, Modifier.enter { i }.testTag(if (info) "lyrics.copyright" else "lyrics.lrclib"), focused = i == 0, info = info) {
                    if (!info) ctx.control.lyrics(!on)
                }
            }
        }
    }
}
