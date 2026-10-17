package net.seilent.funecomp.ui

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import net.seilent.funecomp.artist.Accounts

@Immutable
data class ScrobbleView(val accounts: Accounts = Accounts(), val queued: Int = 0, val lastFm: String = "", val listenBrainz: String = "")

fun scrobbleText(v: ScrobbleView) = buildList {
    if (v.accounts.lastFm) add("last.fm")
    if (v.accounts.listenBrainz) add("listenbrainz")
}.joinToString(" ").ifEmpty { "off" }

private class Item(val title: String, val value: String, val tag: String, val tap: () -> Unit)

@Composable
fun ScrobblingPage(ctx: Ctx, view: ScrobbleView) {
    val ui = LocalUi.current
    var focus by remember { mutableStateOf("lastfm") }
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
    val items = listOf(
        Item("last.fm", if (a.lastFm) "on" else "off", "lastfm") { ctx.control.scrobble(a.copy(lastFm = !a.lastFm)) },
        Item("api key", set(a.apiKey), "key") { entry("api key", a.apiKey, true) { ctx.control.scrobble(a.copy(apiKey = it)) } },
        Item("secret", set(a.secret), "secret") { entry("secret", a.secret, true) { ctx.control.scrobble(a.copy(secret = it)) } },
        Item("last.fm account", view.lastFm.ifEmpty { a.lastFmUser.takeIf { a.lastFmSession.isNotBlank() } ?: "not set" }, "account") {
            entry("username", a.lastFmUser, false) { user ->
                if (user.isNotEmpty()) entry("password", "", true) { pass -> if (pass.isNotEmpty()) ctx.control.lastFmLogin(user, pass) }
            }
        },
        Item("listenbrainz", if (a.listenBrainz) "on" else "off", "listenbrainz") { ctx.control.scrobble(a.copy(listenBrainz = !a.listenBrainz)) },
        Item("listenbrainz token", view.listenBrainz.ifEmpty { a.listenBrainzUser.takeIf { a.token.isNotBlank() } ?: "not set" }, "token") {
            entry("listenbrainz token", a.token, true) { if (it.isNotEmpty()) ctx.control.listenBrainzToken(it) }
        },
        Item("queued", view.queued.toString(), "queued") {},
    )
    val at = items.indexOfFirst { it.tag == focus }.coerceAtLeast(0)
    HubPage("scrobbling", { ctx.nav.pop() }, count = scrobbleText(view)) {
        Rows(ui.contentTop, focus = at) {
            itemsIndexed(items, key = { _, l -> l.tag }) { i, l ->
                SettingsRow(l.title, l.value, Modifier.enter { i }.testTag("scrobble.${l.tag}"), focused = at == i) {
                    focus = l.tag
                    l.tap()
                }
            }
        }
    }
}
