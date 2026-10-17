package net.seilent.funecomp.ui

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import java.util.Locale

private const val NO_UPDATER = "device has no updater"
private const val CANCEL = "cancel"

@Immutable
data class FirmwareView(
    val status: String = "",
    val info: String = "",
    val tag: String? = null,
    val size: Long = 0,
    val newer: Boolean? = null,
    val downloading: Boolean = false,
    val download: String = "",
    val ready: Boolean = false,
    val checkDaily: Boolean = false,
    val prereleases: Boolean = false,
)

@Immutable
data class PickedFile(val name: String, val size: Long, val board: String?)

fun sizeText(bytes: Long) = if (bytes < 0) "size unknown" else String.format(Locale.ROOT, "%.1f mb", bytes / 1_000_000.0)

fun firmwareText(v: FirmwareView) = when {
    v.downloading -> v.download
    v.ready -> "downloaded"
    v.tag != null && v.newer == true -> v.tag
    else -> ""
}

private class FwItem(val title: String, val value: String, val tag: String, val info: Boolean = false, val tap: () -> Unit = {})

@Composable
fun FirmwarePage(ctx: Ctx, view: FirmwareView) {
    val ui = LocalUi.current
    var focus by remember { mutableStateOf("check") }
    val board = ctx.device.hello?.board
    val items = buildList {
        add(FwItem("check", view.status, "check") { ctx.control.firmwareCheck() })
        if (view.info.isNotEmpty()) add(FwItem(view.info, "", "info", info = true))
        val tag = view.tag
        if (tag != null) {
            val value = when {
                view.downloading -> view.download
                view.ready -> "downloaded"
                view.download.isNotEmpty() -> view.download
                view.newer == true -> "newer"
                view.newer == false -> "up to date"
                else -> ""
            }
            add(FwItem("$tag, ${sizeText(view.size)}", value, "release") {
                when {
                    view.downloading -> ctx.sheet.open(tag, listOf("cancel download")) { ctx.control.firmwareCancel() }
                    view.ready -> ctx.sheet.open(tag, listOf(CANCEL, "delete"), info = listOf(sizeText(view.size), "board ${board ?: "unknown"}", NO_UPDATER)) {
                        if (it == 1) ctx.control.firmwareCancel()
                    }
                    else -> ctx.sheet.open(tag, listOf("download", CANCEL), info = listOf(sizeText(view.size))) { if (it == 0) ctx.control.firmwareDownload() }
                }
            })
        }
        add(FwItem("pick file", "", "pick") {
            ctx.control.firmwareFile { p ->
                ctx.sheet.open(p.name.ifEmpty { "firmware" }, listOf(CANCEL), info = listOf(sizeText(p.size), "board ${p.board ?: "unknown"}", NO_UPDATER)) {}
            }
        })
        add(FwItem("check releases", if (view.checkDaily) "on" else "off", "daily") { ctx.control.firmwareSettings(!view.checkDaily, view.prereleases) })
        add(FwItem("prereleases", if (view.prereleases) "on" else "off", "pre") { ctx.control.firmwareSettings(view.checkDaily, !view.prereleases) })
    }
    val at = items.indexOfFirst { it.tag == focus }.coerceAtLeast(0)
    HubPage("firmware", { ctx.nav.pop() }, count = firmwareText(view)) {
        Rows(ui.contentTop, focus = at) {
            itemsIndexed(items, key = { _, l -> l.tag }) { i, l ->
                SettingsRow(l.title, l.value, Modifier.enter { i }.testTag("fw.${l.tag}"), focused = at == i, info = l.info) {
                    focus = l.tag
                    l.tap()
                }
            }
        }
    }
}
