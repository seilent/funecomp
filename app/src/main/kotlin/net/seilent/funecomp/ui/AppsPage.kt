package net.seilent.funecomp.ui

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

const val INSTALL_APP = "install app"
const val PICK_APP = "pick file"
const val SEND_APP = "send"
private const val CANCEL = "cancel"
private const val APPS_SHEET = "apps.sheet"

private class AppItem(val title: String, val value: String, val tag: String, val info: Boolean = false, val tap: (Int) -> Unit = {})

@Composable
fun AppsPage(ctx: Ctx, view: AppsView) {
    val ui = LocalUi.current
    val list = rememberLazyListState()
    var focus by rememberSaveable { mutableStateOf("pick") }
    LaunchedEffect(ctx.device.hello, ctx.device.installer) {
        if (ctx.device.hello == null || ctx.device.installer != false || ctx.nav.top != Page.Apps) return@LaunchedEffect
        ctx.sheet.closeIf(APPS_SHEET)
        ctx.nav.pop()
    }
    val items = buildList {
        add(AppItem(PICK_APP, view.picked, "pick") { ctx.control.appFile() })
        if (view.send.isNotEmpty()) add(AppItem("app", view.send.removePrefix("app "), "send") {
            ctx.sheet.open("app", listOf(CANCEL), owner = APPS_SHEET) { ctx.control.appStop() }
        }) else if (view.picked.isNotEmpty()) add(AppItem(SEND_APP, "", "send") { ctx.control.appSend() })
        if (view.info.isNotEmpty()) add(AppItem(view.info, "", "info", info = true))
    }
    val at = items.indexOfFirst { it.tag == focus }.coerceAtLeast(0)
    HubPage("apps", { ctx.nav.pop() }) {
        Rows(ui.contentTop, list) {
            itemsIndexed(items, key = { _, l -> l.tag }) { i, l ->
                SettingsRow(l.title, l.value, Modifier.enter { i }.testTag("apps.${l.tag}"), focused = at == i, info = l.info) {
                    focus = l.tag
                    l.tap(i)
                }
            }
        }
    }
}
