package net.seilent.funecomp.ui

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import net.seilent.funecomp.proto.SvcInfo
import net.seilent.funecomp.proto.SvcKey
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.state.SvcLogins
import net.seilent.funecomp.state.SvcOpens

const val STREAMING = "streaming"
private const val OPEN = "open"
private const val CONTINUE = "continue"
private const val CANCEL = "cancel"
private const val SEND = "send"
private const val SET = "set"
private const val NOT_SET = "not set"
private const val WAITING = "waiting for the dap"
private const val SIGN_IN_ON_DAP = "sign in on the dap"
private const val CUSTOM_KEYS = "custom keys"

fun services(device: DeviceState): List<SvcInfo> = if (device.has(DeviceState.SVC)) device.hello?.services.orEmpty().filter { !it.id.isNullOrEmpty() } else emptyList()

fun serviceName(device: DeviceState, service: String): String = device.hello?.services?.firstOrNull { it.id == service }?.name?.takeIf { it.isNotBlank() } ?: service

@Stable
class KeyDraft(val service: String, val name: String, val names: List<String>) {
    private val values = HashMap<String, String>()

    val any: Boolean get() = values.isNotEmpty()

    fun has(n: String) = n in values

    fun get(n: String) = values[n].orEmpty()

    fun set(n: String, v: String) {
        if (v.isEmpty()) values.remove(n) else values[n] = v
    }

    fun take(): List<SvcKey> = names.mapNotNull { n -> values[n]?.let { SvcKey(n, it) } }.also { drop() }

    fun drop() = values.clear()
}

@Stable
class KeysFlow {
    var draft by mutableStateOf<KeyDraft?>(null)

    fun drop() {
        draft?.drop()
        draft = null
    }
}

fun loginSheet(ctx: Ctx, l: SvcLogins.Login, owner: Any) {
    val seen = { ctx.control.svcSeen(l.service, l.seq) }
    ctx.sheet.open(serviceName(ctx.device, l.service), listOf(OPEN, CANCEL), info = listOfNotNull(l.host, l.userCode?.takeIf { it.isNotEmpty() }), onDismiss = seen, owner = owner) {
        seen()
        if (it == 0) {
            l.host?.let { h -> ctx.control.svcKnown(l.service, h) }
            ctx.control.svcOpen(l.service, l.seq)
        }
    }
}

fun askSheet(ctx: Ctx, a: SvcAsk, owner: Any) {
    val cancel = { ctx.control.svcCancel() }
    ctx.sheet.open(a.name, listOf(CONTINUE, CANCEL), info = listOf(a.host), onDismiss = cancel, owner = owner) {
        if (it == 0) {
            ctx.control.svcKnown(a.service, a.host)
            ctx.control.svcContinue()
        } else {
            cancel()
        }
    }
}

fun serviceSheet(ctx: Ctx, flow: KeysFlow, s: SvcInfo) {
    val name = s.name?.takeIf { it.isNotBlank() } ?: s.id.orEmpty()
    val items = listOfNotNull(CUSTOM_KEYS.takeIf { s.secrets.isNotEmpty() }) + CANCEL
    ctx.sheet.open(name, items, info = listOf(SIGN_IN_ON_DAP), owner = flow) { if (items[it] == CUSTOM_KEYS) keysSheet(ctx, flow, KeyDraft(s.id.orEmpty(), name, s.secrets)) }
}

fun keysSheet(ctx: Ctx, flow: KeysFlow, d: KeyDraft, focus: Int = 0) {
    flow.draft = d
    ctx.control.secure(true)
    val items = d.names + listOfNotNull(SEND.takeIf { d.any }) + CANCEL
    ctx.sheet.open(d.name, items, focus.coerceIn(0, items.lastIndex), itemValues = { d.names.map { if (d.has(it)) SET else NOT_SET } }, owner = d) { i ->
        when {
            i < d.names.size -> keyEntry(ctx, flow, d, i)
            items[i] == SEND -> send(ctx, flow, d)
            else -> flow.drop()
        }
    }
}

private fun keyEntry(ctx: Ctx, flow: KeysFlow, d: KeyDraft, i: Int) {
    val n = d.names[i]
    ctx.sheet.openEntry(n, owner = d) {
        key(n) {
            TextEntry(d.get(n), secret = true) { v ->
                d.set(n, v.trim())
                keysSheet(ctx, flow, d, i)
            }
        }
    }
}

private fun send(ctx: Ctx, flow: KeysFlow, d: KeyDraft) {
    val keys = d.take()
    flow.drop()
    ctx.control.secure(false)
    val token = Any()
    ctx.sheet.open(d.name, listOf(CANCEL), info = listOf(WAITING), owner = token) {}
    ctx.control.svcKeys(d.service, keys) { line ->
        ctx.sheet.closeIf(token)
        ctx.toast.show(d.name, line)
    }
}

@Composable
fun SvcSheets(ctx: Ctx, shell: Shell, svc: SvcView, keyboard: Boolean) {
    val on = ctx.device.has(DeviceState.SVC)
    val login = ctx.device.logins.shown.takeIf { on }
    val ask = svc.ask?.takeIf { !it.page }
    val loginOwner = remember { Any() }
    val askOwner = remember { Any() }
    var showing by remember { mutableStateOf<Pair<String, Long>?>(null) }
    LaunchedEffect(login, ask, shell.gated, shell.sheet.shown, shell.front, shell.pick, keyboard) {
        val s = showing
        if (s != null && shell.sheet.owns(loginOwner) && (login == null || login.service to login.seq != s)) shell.sheet.closeIf(loginOwner)
        if (!shell.sheet.owns(loginOwner)) showing = null
        if (ask == null) shell.sheet.closeIf(askOwner)
        if (shell.gated || !shell.front || shell.pick != null || keyboard) return@LaunchedEffect
        if (shell.sheet.shown && !shell.sheet.owns(shell.keys)) return@LaunchedEffect
        if (ask == null && login == null) return@LaunchedEffect
        shell.sheet.closeIf(shell.keys)
        if (ask != null) {
            if (ctx.control.svcTake(ask.service, ask.seq, ask.link, ask.host, true) == SvcOpens.Verdict.OPEN) ctx.control.svcContinue() else askSheet(ctx, ask, askOwner)
        } else if (login != null) {
            loginSheet(ctx, login, loginOwner)
            showing = login.service to login.seq
            val listed = services(ctx.device).any { it.id == login.service }
            if (ctx.control.svcTake(login.service, login.seq, login.uri.orEmpty(), login.host, listed) == SvcOpens.Verdict.OPEN) ctx.control.svcOpen(login.service, login.seq)
        }
        ctx.control.svcSheet()
    }
    val line = svc.line
    LaunchedEffect(line?.id, shell.front) {
        if (line == null || !shell.front) return@LaunchedEffect
        withFrameNanos {}
        shell.toast.show(line.title, line.line)
        ctx.control.svcLineShown(line.id)
    }
    val draft = shell.keys.draft
    LaunchedEffect(draft, shell.sheet.shown, on) {
        if (draft != null && (!shell.sheet.owns(draft) || !on)) {
            shell.sheet.closeIf(draft)
            shell.keys.drop()
        }
        if (shell.keys.draft == null) ctx.control.secure(false)
    }
}

@Composable
fun StreamingRows(ctx: Ctx, flow: KeysFlow) {
    val ui = LocalUi.current
    val list = rememberLazyListState()
    val all = services(ctx.device)
    var focus by rememberSaveable { mutableStateOf(all.firstOrNull()?.id.orEmpty()) }
    val at = all.indexOfFirst { it.id == focus }.coerceAtLeast(0)
    Rows(ui.contentTop, list, focus = at) {
        itemsIndexed(all, key = { _, s -> "svc ${s.id}" }) { i, s ->
            val name = s.name?.takeIf { it.isNotBlank() } ?: s.id.orEmpty()
            SettingsRow(name, "", Modifier.enter { i }.testTag("svc.${s.id}"), focused = at == i) {
                focus = s.id.orEmpty()
                serviceSheet(ctx, flow, s)
            }
        }
    }
}
