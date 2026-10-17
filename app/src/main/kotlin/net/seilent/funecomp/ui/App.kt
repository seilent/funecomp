package net.seilent.funecomp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.artist.Sources
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.state.LibKey

private const val ACCENT_KEY = "accentHue"
private const val BACKGROUND_INFO = "works with the screen off"

private class Touch(base: ViewConfiguration, private val slop: Float) : ViewConfiguration by base {
    override val longPressTimeoutMillis: Long get() = Tok.input_keyHoldMs.toLong()
    override val touchSlop: Float get() = slop
}

class Shell(
    val nav: Nav = Nav(),
    val overlay: Overlay = Overlay(),
    val sheet: SheetState = SheetState(),
    val toast: ToastState = ToastState(),
    gated: Boolean = false,
) {
    var gated by mutableStateOf(gated)
}

fun homeItems(device: DeviceState, link: LinkView, sources: Sources): List<HomeItem> {
    val playlists = device.lib.pages[LibKey(Enums.View.playlists, "")]?.total
    val pivots = device.schema.rows.mapNotNull { it.pivot }.distinct()
    return listOf(
        HomeItem("music", "artists albums songs", Page.Music),
        HomeItem("connection", if (link.phase == Phase.LIVE) link.device ?: device.hello?.board ?: "" else statusText(link), Page.Connection),
        HomeItem("playlists", if (playlists != null && playlists > 0) (if (playlists == 1L) "1 playlist" else "$playlists playlists") else "now playing", Page.Playlists),
        HomeItem("artist art", sources.enabled.sorted().joinToString(" "), Page.ArtistArt),
        HomeItem("settings", pivots.joinToString(" "), Page.Settings),
    )
}

@Composable
fun App(
    device: DeviceState,
    link: LinkView,
    control: Control,
    shell: Shell,
    sources: Sources = Sources(),
    onSources: (Sources) -> Unit = {},
    art: BackdropArt? = null,
    credits: List<ArtCache.Entry> = emptyList(),
    onCredit: (ArtCache.Entry) -> Unit = {},
    scrobble: ScrobbleView = ScrobbleView(),
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Black)) {
        val ui = Ui.of(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val base = LocalViewConfiguration.current
        val touch = remember(base, ui.u) { Touch(base, ui.px(Tok.touchSlop)) }
        val accent = accentFor(device.settings.values[ACCENT_KEY]?.toIntOrNull())
        CompositionLocalProvider(
            LocalUi provides ui,
            LocalAccent provides accent,
            LocalViewConfiguration provides touch,
            LocalOverscrollFactory provides null,
        ) {
            val nav = shell.nav
            val ctx = Ctx(device, control, nav, shell.sheet, shell.toast)
            LaunchedEffect(shell.gated, link.phase, link.askBattery) {
                if (shell.gated && link.phase == Phase.LIVE) {
                    shell.gated = false
                    shell.sheet.close()
                    nav.reset(listOf(Page.Home, Page.NowPlaying))
                }
                if (link.askBattery && link.phase == Phase.LIVE && !shell.sheet.shown) {
                    shell.sheet.open("background", listOf("allow", "not now"), info = listOf(BACKGROUND_INFO), onDismiss = { control.backgroundLater() }) {
                        if (it == 0) control.allowBackground() else control.backgroundLater()
                    }
                }
            }
            BackHandler(shell.sheet.shown || nav.stack.size > 1) {
                if (shell.sheet.shown) shell.sheet.close() else nav.pop()
            }
            Stack(nav) { page ->
                when (page) {
                    Page.NowPlaying -> NowPlaying(device, control, { nav.pop() }, { nav.push(Page.Queue) }, art)
                    Page.Home -> Home(homeItems(device, link, sources)) { nav.push(it) }
                    Page.Music -> Music(ctx)
                    Page.Queue -> Queue(ctx)
                    Page.Playlists -> Playlists(ctx)
                    Page.Settings -> SettingsPage(ctx)
                    Page.Connection -> ConnectionPage(ctx, link, scrobble)
                    Page.ArtistArt -> ArtistArtPage(ctx, sources, onSources, credits, onCredit)
                    Page.Scrobbling -> ScrobblingPage(ctx, scrobble)
                    is Page.Artist -> ArtistDetail(ctx, page)
                    is Page.Album -> AlbumDetail(ctx, page)
                    is Page.Eq -> EqPage(ctx, page)
                }
            }
            VolumeOverlay(shell.overlay, device, control)
            Sheet(shell.sheet)
            val held = if (nav.top == Page.NowPlaying && link.everLive && link.phase != Phase.LIVE) (link.board ?: link.device ?: "") to statusText(link) else null
            Toast(shell.toast, held)
        }
    }
}
