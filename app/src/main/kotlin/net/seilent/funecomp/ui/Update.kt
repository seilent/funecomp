package net.seilent.funecomp.ui

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import net.seilent.funecomp.Apps

const val UPDATE_APP = "update app"
private const val CANCEL = "cancel"
private const val INSTALLING = "installing"
private const val UPDATE_SHEET = "update.sheet"

@Immutable
data class UpdateView(
    val installed: String = "",
    val tag: String? = null,
    val size: Long = 0,
    val newer: Boolean? = null,
    val status: String = "",
    val info: String = "",
    val downloading: Boolean = false,
    val download: String = "",
    val verifying: Boolean = false,
    val installing: Boolean = false,
) {
    val busy: Boolean get() = downloading || verifying
}

fun updateText(v: UpdateView) = when {
    v.downloading -> v.download
    v.tag != null && v.newer == true -> v.tag
    else -> ""
}

fun updateValue(v: UpdateView) = when {
    v.downloading -> v.download
    v.verifying -> Apps.CHECKING_FILE
    v.installing -> INSTALLING
    v.newer == true -> "newer"
    v.newer == false -> "up to date"
    else -> ""
}

private class UpdateItem(val title: String, val value: String, val tag: String, val info: Boolean = false, val tap: () -> Unit = {})

@Composable
fun UpdatePage(ctx: Ctx, view: UpdateView) {
    val ui = LocalUi.current
    var focus by remember { mutableStateOf("check") }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { ctx.control.updateCheck() }
    val items = buildList {
        add(UpdateItem("check", view.status, "check") { ctx.control.updateCheck() })
        if (view.info.isNotEmpty()) add(UpdateItem(view.info, "", "info", info = true))
        val tag = view.tag
        if (tag != null) {
            add(UpdateItem("$tag, ${sizeText(view.size)}", updateValue(view), "release") {
                val info = listOf(sizeText(view.size), "installed ${view.installed}")
                when {
                    view.downloading -> ctx.sheet.open(tag, listOf("cancel download"), owner = UPDATE_SHEET) { ctx.control.updateCancel() }
                    view.newer == true && !view.busy && !view.installing -> ctx.sheet.open(tag, listOf("install", CANCEL), info = info, owner = UPDATE_SHEET) { if (it == 0) ctx.control.updateInstall() }
                    else -> ctx.sheet.open(tag, listOf(CANCEL), info = info, owner = UPDATE_SHEET) {}
                }
            })
        }
    }
    val at = items.indexOfFirst { it.tag == focus }.coerceAtLeast(0)
    HubPage("update", { ctx.nav.pop() }, count = updateText(view)) {
        Rows(ui.contentTop, list) {
            itemsIndexed(items, key = { _, l -> l.tag }) { i, l ->
                SettingsRow(l.title, l.value, Modifier.enter().testTag("update.${l.tag}"), focused = at == i, info = l.info) {
                    focus = l.tag
                    l.tap()
                }
            }
        }
    }
}
