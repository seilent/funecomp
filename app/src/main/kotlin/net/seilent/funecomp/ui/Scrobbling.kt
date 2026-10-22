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
import net.seilent.funecomp.artist.AppPair

private const val SIGN_OUT = "sign out"
private const val KEEP_SIGNED_IN = "keep signed in"
private const val REVOKE_INFO = "revoke access on last.fm"

@Immutable
data class ScrobbleView(
    val accounts: Accounts = Accounts(),
    val queued: Int = 0,
    val lastFm: String = "",
    val listenBrainz: String = "",
    val app: AppPair = AppPair.EMPTY,
    val pending: Boolean = false,
    val keyRefused: Set<String> = emptySet(),
    val tokenPage: String = "",
) {
    val signedIn: Boolean get() = accounts.lastFmSession.isNotBlank() && accounts.pair(app) != null

    val lastFmRow: String
        get() = when {
            signedIn && Accounts.LAST_FM in keyRefused -> if (accounts.own) "api key refused" else "app key refused"
            signedIn -> accounts.lastFmUser
            app.empty -> "not in this build"
            pending -> lastFm.ifEmpty { "waiting" }
            else -> lastFm.ifEmpty { "not signed in" }
        }
}

private class Item(val title: String, val value: String, val tag: String, val info: Boolean = false, val label: Boolean = false, val tap: (Int) -> Unit = {})

@Composable
fun ScrobbleRows(ctx: Ctx, view: ScrobbleView, labels: Boolean = true) {
    val ui = LocalUi.current
    var focus by rememberSaveable { mutableStateOf("lastfm") }
    val a = view.accounts
    fun signOut(service: String) = ctx.sheet.open("$SIGN_OUT?", listOf(SIGN_OUT, KEEP_SIGNED_IN), 1) { if (it == 0) ctx.control.signOut(service) }
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
        add(
            Item("last.fm account", view.lastFmRow, "account") { i ->
                if (view.signedIn) {
                    ctx.sheet.open(a.lastFmUser, listOf(SIGN_OUT), info = listOf(REVOKE_INFO), keep = keep(i)) { signOut(Accounts.LAST_FM) }
                } else {
                    ctx.control.lastFmSignIn()
                }
            },
        )
        if (labels) add(Item("listenbrainz", "", "label.listenbrainz", label = true))
        add(Item("listenbrainz", if (a.listenBrainz) ON else OFF, "listenbrainz") { i -> onOffSheet(ctx, "listenbrainz", a.listenBrainz, keep(i)) { ctx.control.scrobble(a.copy(listenBrainz = it)) } })
        add(Item("get token", view.tokenPage, "gettoken") { ctx.control.openListenBrainzToken() })
        add(
            Item("listenbrainz token", view.listenBrainz.ifEmpty { a.listenBrainzUser.takeIf { a.token.isNotBlank() } ?: "not set" }, "token") { i ->
                fun change() = entry("listenbrainz token", a.token, true) { if (it.isNotEmpty()) ctx.control.listenBrainzToken(it) }
                if (a.token.isNotBlank()) {
                    ctx.sheet.open(a.listenBrainzUser.ifBlank { "listenbrainz" }, listOf("change token", SIGN_OUT), keep = keep(i)) { if (it == 0) change() else signOut(Accounts.LISTEN_BRAINZ) }
                } else {
                    change()
                }
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
