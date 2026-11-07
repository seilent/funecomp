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
private const val SIGN_IN = "sign in"
private const val SIGN_OUT = "sign out"
private const val KEEP_SIGNED_IN = "keep signed in"
private const val SIGNED_IN = "signed in"
private const val NOT_SIGNED_IN = "not signed in"

fun services(device: DeviceState): List<SvcInfo> = if (device.has(DeviceState.SVC)) device.hello?.services.orEmpty().filter { !it.id.isNullOrEmpty() } else emptyList()

fun serviceName(device: DeviceState, service: String): String = device.hello?.services?.firstOrNull { it.id == service }?.name?.takeIf { it.isNotBlank() } ?: service

private fun accountsOn(device: DeviceState) = device.has(DeviceState.SVC) && device.has(DeviceState.ACCOUNT)

fun accountValue(device: DeviceState, service: String): String {
    val a = device.accounts?.get(service)?.takeIf { accountsOn(device) } ?: return ""
    return when (a.signedIn) {
        true -> a.account ?: SIGNED_IN
        false -> NOT_SIGNED_IN
        null -> ""
    }
}

private fun signItem(device: DeviceState, held: Set<String>, service: String): String? {
    if (!accountsOn(device) || service in held) return null
    return when (device.accounts?.get(service)?.signedIn) {
        true -> SIGN_OUT
        false -> SIGN_IN
        null -> null
    }
}

private fun serviceLine(device: DeviceState, service: String): List<String> = when {
    !accountsOn(device) -> listOf(SIGN_IN_ON_DAP)
    device.accounts?.let { it[service]?.signedIn == null } == true -> listOf(SIGN_IN_ON_DAP)
    else -> emptyList()
}

private fun removes(n: Long?): String? = n?.takeIf { it > 0 }?.let { if (it == 1L) "removes 1 download on this card" else "removes $it downloads on this card" }

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
    var sheet by mutableStateOf<Pair<String, String?>?>(null)
    var confirm by mutableStateOf<Pair<String, String?>?>(null)
    var waiting: Any? = null
    val signOut = Any()

    fun drop() {
        draft?.drop()
        draft = null
    }
}

fun loginSheet(ctx: Ctx, l: SvcLogins.Login, owner: Any) {
    val seen = { ctx.control.svcSeen(l.service, l.seq) }
    val info = listOfNotNull(l.host, l.userCode?.takeIf { it.isNotEmpty() })
    ctx.sheet.open(serviceName(ctx.device, l.service), listOf(OPEN, CANCEL), info = info, onDismiss = seen, owner = owner, infoMixed = { info.map { true } }, titleMixed = true) {
        seen()
        if (it == 0) {
            l.host?.let { h -> ctx.control.svcKnown(l.service, h) }
            ctx.control.svcOpen(l.service, l.seq)
        }
    }
}

fun askSheet(ctx: Ctx, a: SvcAsk, owner: Any) {
    val cancel = { ctx.control.svcCancel() }
    ctx.sheet.open(a.name, listOf(CONTINUE, CANCEL), info = listOf(a.host), onDismiss = cancel, owner = owner, infoMixed = { listOf(true) }, titleMixed = true) {
        if (it == 0) {
            ctx.control.svcKnown(a.service, a.host)
            ctx.control.svcContinue()
        } else {
            cancel()
        }
    }
}

fun serviceSheet(ctx: Ctx, flow: KeysFlow, s: SvcInfo, held: Set<String> = emptySet()) {
    val id = s.id.orEmpty()
    val name = s.name?.takeIf { it.isNotBlank() } ?: id
    val sign = signItem(ctx.device, held, id)
    val items = listOfNotNull(sign, CUSTOM_KEYS.takeIf { s.secrets.isNotEmpty() }) + CANCEL
    flow.sheet = id to sign
    ctx.sheet.open(name, items, info = serviceLine(ctx.device, id), owner = flow, titleMixed = true) {
        when (items[it]) {
            CUSTOM_KEYS -> keysSheet(ctx, flow, KeyDraft(id, name, s.secrets))
            SIGN_IN -> signIn(ctx, flow, id, name)
            SIGN_OUT -> signOutSheet(ctx, flow, id, name)
        }
    }
}

private fun signIn(ctx: Ctx, flow: KeysFlow, service: String, name: String) {
    val token = Any()
    flow.waiting = token
    val cancel = { ctx.control.svcStartCancel(service) }
    ctx.sheet.open(name, listOf(CANCEL), info = listOf(WAITING), onDismiss = cancel, owner = token, titleMixed = true) { cancel() }
    ctx.control.svcStart(service) { line ->
        ctx.sheet.closeIf(token)
        line?.let { ctx.toast.show(name, it) }
    }
}

private fun signOutSheet(ctx: Ctx, flow: KeysFlow, service: String, name: String) {
    val account = ctx.device.accounts?.get(service)?.account
    val count = { ctx.live().accounts?.get(service)?.downloads }
    val named = { listOfNotNull(true.takeIf { account != null }, false.takeIf { removes(count()) != null }) }
    flow.confirm = service to account
    ctx.sheet.open(
        "$SIGN_OUT?",
        listOf(SIGN_OUT, KEEP_SIGNED_IN),
        1,
        owner = flow.signOut,
        infoLines = { listOfNotNull(account, removes(count())) },
        infoMixed = named,
        infoDrop = named,
    ) { if (it == 0) signOut(ctx, service, name, account.orEmpty(), count()) }
}

private fun signOut(ctx: Ctx, service: String, name: String, account: String, downloads: Long?) = ctx.control.svcLogout(service, account, downloads) { line -> line?.let { ctx.toast.show(name, it) } }

fun keysSheet(ctx: Ctx, flow: KeysFlow, d: KeyDraft, focus: Int = 0) {
    flow.draft = d
    ctx.control.secure(true)
    val items = d.names + listOfNotNull(SEND.takeIf { d.any }) + CANCEL
    ctx.sheet.open(d.name, items, focus.coerceIn(0, items.lastIndex), itemValues = { d.names.map { if (d.has(it)) SET else NOT_SET } }, owner = d, titleMixed = true) { i ->
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
    ctx.sheet.open(d.name, listOf(CANCEL), info = listOf(WAITING), owner = token, titleMixed = true) {}
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
        val waiting = shell.keys.waiting
        if (shell.sheet.shown && !shell.sheet.owns(shell.keys) && waiting?.let { shell.sheet.owns(it) } != true) return@LaunchedEffect
        if (ask == null && login == null) return@LaunchedEffect
        shell.sheet.closeIf(shell.keys)
        waiting?.let { shell.sheet.closeIf(it) }
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
    val flow = shell.keys
    val open = flow.sheet
    val item = open?.let { signItem(ctx.device, svc.held, it.first) }
    LaunchedEffect(open, item) { if (open != null && item != open.second) shell.sheet.closeIf(flow) }
    val confirm = flow.confirm
    val now = confirm?.let { ctx.device.accounts?.get(it.first) }
    LaunchedEffect(confirm, now?.signedIn, now?.account) { if (confirm != null && (now?.signedIn != true || now.account != confirm.second)) shell.sheet.closeIf(flow.signOut) }
    val hello = ctx.device.hello
    LaunchedEffect(hello) {
        shell.sheet.closeIf(flow)
        shell.sheet.closeIf(flow.signOut)
    }
    val reopen = svc.reopen
    LaunchedEffect(reopen) {
        if (reopen == null) return@LaunchedEffect
        ctx.control.svcReopened()
        if (ctx.device.accounts?.get(reopen)?.signedIn == true) signOutSheet(ctx, flow, reopen, serviceName(ctx.device, reopen))
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
fun StreamingRows(ctx: Ctx, flow: KeysFlow, held: Set<String> = emptySet()) {
    val ui = LocalUi.current
    val list = rememberLazyListState()
    val all = services(ctx.device)
    var focus by rememberSaveable { mutableStateOf(all.firstOrNull()?.id.orEmpty()) }
    val at = all.indexOfFirst { it.id == focus }.coerceAtLeast(0)
    Rows(ui.contentTop, list, focus = at) {
        itemsIndexed(all, key = { _, s -> "svc ${s.id}" }) { i, s ->
            val name = s.name?.takeIf { it.isNotBlank() } ?: s.id.orEmpty()
            SettingsRow(name, accountValue(ctx.device, s.id.orEmpty()), Modifier.enter { i }.testTag("svc.${s.id}"), focused = at == i) {
                focus = s.id.orEmpty()
                serviceSheet(ctx, flow, s, held)
            }
        }
    }
}
