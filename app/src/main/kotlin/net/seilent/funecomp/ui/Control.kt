package net.seilent.funecomp.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import net.seilent.funecomp.artist.Accounts
import net.seilent.funecomp.artist.ArtBlocks
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.artist.ArtPicks
import net.seilent.funecomp.artist.AutoEqIndex
import net.seilent.funecomp.artist.AutoEqList
import net.seilent.funecomp.artist.LyricSources
import net.seilent.funecomp.artist.Offer
import net.seilent.funecomp.artist.Tile
import net.seilent.funecomp.artist.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.seilent.funecomp.proto.Profile
import net.seilent.funecomp.proto.State

enum class Phase { NONE, CONNECTING, LIVE, DOWN, STOPPED }

enum class Battery(val word: String) { UNRESTRICTED("unrestricted"), OPTIMIZED("optimized"), RESTRICTED("restricted") }

@Immutable
data class LinkView(
    val phase: Phase = Phase.NONE,
    val device: String? = null,
    val bye: Int? = null,
    val permission: Boolean = true,
    val everLive: Boolean = false,
    val board: String? = null,
    val battery: Battery? = null,
    val askBattery: Boolean = false,
    val mediaControls: Boolean = true,
    val lyrics: LyricSources = LyricSources(),
    val reconnect: Boolean = false,
    val askReconnect: Boolean = false,
)

@Immutable
data class PickFor(val name: String, val mbid: String? = null, val library: Boolean = false, val origin: Int? = null)

class BrowseTile(val tile: Tile, val image: ImageBitmap)

class Preview(val offer: Offer, val image: ImageBitmap?)

sealed interface Previewed {
    class Ok(val previews: List<Preview>) : Previewed
    class Refused(val reason: String) : Previewed
    data object Closed : Previewed
}

@Immutable
data class Bonded(val name: String, val address: String, val companion: Boolean = false)

interface Control {
    fun transport(cmd: Int, seekMs: Long? = null, on: Boolean? = null, repeat: Int? = null)
    fun volume(detents: Int)
    fun volumePercent(pct: Int, done: () -> Unit = {}) = done()
    fun libGet(view: Int, parent: String, offset: Long)
    fun play(view: Int?, parent: String?, start: Long, shuffle: Boolean = false, tracks: List<Long> = emptyList())
    fun queuePage(offset: Long)
    fun queueOp(cmd: Int, slot: Long? = null, to: Long? = null, tracks: List<Long> = emptyList())
    fun set(key: String, value: String)
    fun draft(kind: Int, value: String? = null, minutes: Long? = null, unixS: Long? = null, zoneS: Int? = null, done: (Int?, String?) -> Unit = { _, _ -> })
    fun eqSet(output: Int, key: String? = null, ints: Profile? = null)
    fun eqPreset(op: Int, output: Int?, key: String?, name: String?, ints: Profile?, replace: Boolean, done: (Int?, String?) -> Unit) {}
    fun action(cmd: Int, track: Long? = null, done: (Int?, String?) -> Unit = { _, _ -> })
    fun favorite(s: State, toast: (String, String) -> Unit) {}
    fun bonded(): List<Bonded> = emptyList()
    fun choose(address: String) {}
    fun askPermission() {}
    fun retry() {}
    fun openBluetoothSettings() {}
    fun disconnect() {}
    fun allowBackground() {}
    fun backgroundLater() {}
    fun scrobble(accounts: Accounts) {}
    fun lastFmSignIn() {}
    fun signOut(service: String) {}
    fun openListenBrainzToken() {}
    fun listenBrainzToken(token: String) {}
    fun eqFile(done: (String, String?) -> Unit) {}
    fun autoEqList(done: (AutoEqList?) -> Unit) {}
    suspend fun autoEqView(list: AutoEqList, query: String): AutoEqList.View = withContext(Dispatchers.Default) { list.view(query) }
    fun autoEqProfile(entry: AutoEqIndex.Entry, done: (String?) -> Unit) {}
    fun mediaControls(on: Boolean) {}
    fun lyricsMode(): Boolean
    fun lyricsMode(on: Boolean)
    fun lyricSource(id: String, on: Boolean) {}
    fun lyricMove(id: String, by: Int) {}
    fun nowPlayingShown(on: Boolean) {}
    fun reconnect(on: Boolean) {}
    fun wantArt(slot: Int, track: Long, px: Int, album: String?) {}
    fun art(track: Long, px: Int): ImageBitmap? = null
    fun log(message: String) {}
    fun firmwareCheck() {}
    fun firmwareDownload() {}
    fun firmwareCancel() {}
    fun firmwareFile(done: (PickedFile) -> Unit) {}
    fun firmwareSettings(checkDaily: Boolean, prereleases: Boolean) {}
    fun firmwareSend(picked: Boolean) {}
    fun firmwareStopSend() {}
    fun blockArt(e: ArtCache.Entry) {}
    fun wrongArtist(e: ArtCache.Entry) {}
    fun unblock(r: ArtBlocks.Record) {}
    fun pickLink(text: String, name: String, done: (Previewed) -> Unit): () -> Unit = {}
    fun pickGallery(done: (Previewed) -> Unit): () -> Unit = {}
    fun browse(name: String, mbid: String?, px: Int, sections: (List<String>) -> Unit, tile: (String, BrowseTile) -> Unit, sectionDone: (String) -> Unit, search: (YouTube.Search) -> Unit, done: (Boolean) -> Unit): () -> Unit = {}
    fun takeTile(t: Tile, done: (Previewed) -> Unit): () -> Unit = {}
    fun browseShown(section: String) {}
    fun pick(target: PickFor, offer: Offer) {}
    fun undoPick(p: ArtPicks.Pick) {}
    fun tryAgain(p: ArtPicks.Pick) {}
    fun pickHandled(name: String) {}
}

const val LIB_PAGE = 100L
