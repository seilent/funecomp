package net.seilent.funecomp.ui

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import net.seilent.funecomp.artist.Accounts

@Immutable
data class ScrobbleView(val accounts: Accounts = Accounts(), val queued: Int = 0, val lastFm: String = "", val listenBrainz: String = "")

private class Item(val title: String, val value: String, val tag: String, val info: Boolean = false, val label: Boolean = false, val tap: (Int) -> Unit = {})

@Composable
fun ScrobbleRows(ctx: Ctx, view: ScrobbleView, labels: Boolean = true) {
    val ui = LocalUi.current
    var focus by rememberSaveable { mutableStateOf("lastfm") }
    val a = view.accounts
    fun set(v: String) = if (v.isBlank()) "not set" else "set"
    fun entry(title: String, start: String, secret: Boolean, done: (String) -> Unit) = ctx.sheet.openEntry(title) {
        key(title) {
            TextEntry(start, secret = secret) { t ->
                ctx.sheet.close()
                done(t.trim())
            }
        }
    }
    val list = rememberLazyListState()
    fun keep(i: Int) = Keep(list, i, ui.px(ui.contentTop))
    val items = buildList {
        if (labels) add(Item("last.fm", "", "label.lastfm", label = true))
        add(Item("last.fm", if (a.lastFm) ON else OFF, "lastfm") { i -> onOffSheet(ctx, "last.fm", a.lastFm, keep(i)) { ctx.control.scrobble(a.copy(lastFm = it)) } })
        add(Item("api key", set(a.apiKey), "key") { entry("api key", a.apiKey, true) { ctx.control.scrobble(a.copy(apiKey = it)) } })
        add(Item("secret", set(a.secret), "secret") { entry("secret", a.secret, true) { ctx.control.scrobble(a.copy(secret = it)) } })
        add(
            Item("last.fm account", view.lastFm.ifEmpty { a.lastFmUser.takeIf { a.lastFmSession.isNotBlank() } ?: "not set" }, "account") {
                entry("username", a.lastFmUser, false) { user ->
                    if (user.isNotEmpty()) entry("password", "", true) { pass -> if (pass.isNotEmpty()) ctx.control.lastFmLogin(user, pass) }
                }
            },
        )
        if (labels) add(Item("listenbrainz", "", "label.listenbrainz", label = true))
        add(Item("listenbrainz", if (a.listenBrainz) ON else OFF, "listenbrainz") { i -> onOffSheet(ctx, "listenbrainz", a.listenBrainz, keep(i)) { ctx.control.scrobble(a.copy(listenBrainz = it)) } })
        add(
            Item("listenbrainz token", view.listenBrainz.ifEmpty { a.listenBrainzUser.takeIf { a.token.isNotBlank() } ?: "not set" }, "token") {
                entry("listenbrainz token", a.token, true) { if (it.isNotEmpty()) ctx.control.listenBrainzToken(it) }
            },
        )
        add(Item("queued", view.queued.toString(), "queued", info = true))
    }
    val at = items.indexOfFirst { it.tag == focus }.coerceAtLeast(0)
    Rows(ui.contentTop, list, focus = at) {
        itemsIndexed(items, key = { _, l -> l.tag }) { i, l ->
            if (l.label) {
                SectionLabel(l.title, i)
            } else {
                SettingsRow(l.title, l.value, Modifier.enter { i }.testTag("scrobble.${l.tag}"), focused = at == i, info = l.info) {
                    if (!l.info) {
                        focus = l.tag
                        l.tap(i)
                    }
                }
            }
        }
    }
}
