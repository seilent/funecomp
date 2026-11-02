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
import net.seilent.funecomp.proto.GraphicBand
import net.seilent.funecomp.proto.Profile
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.SvcKey
import net.seilent.funecomp.state.PhoneKey
import net.seilent.funecomp.state.SvcOpens

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
data class SvcAsk(val service: String, val name: String, val seq: Long, val host: String, val link: String, val page: Boolean = false)

@Immutable
data class SvcLine(val title: String, val line: String, val id: Int)

@Immutable
data class SvcView(val ask: SvcAsk? = null, val line: SvcLine? = null)

@Immutable
data class Bonded(val name: String, val address: String, val companion: Boolean = false)

@Immutable
data class Detected(val name: String, val address: String)

@Immutable
data class Flight(val name: String, val address: String, val paired: Boolean = false)

interface Control {
    fun transport(cmd: Int, seekMs: Long? = null, on: Boolean? = null, repeat: Int? = null)
    fun volume(detents: Int)
    fun volumePercent(pct: Int, done: () -> Unit = {}) = done()
    fun libGet(view: Int, parent: String, offset: Long)
    fun play(view: Int?, parent: String?, start: Long, shuffle: Boolean = false, tracks: List<Long> = emptyList())
    fun queuePage(offset: Long)
    fun queueOp(cmd: Int, slot: Long? = null, to: Long? = null, tracks: List<Long> = emptyList())
    fun queueAdd(cmd: Int, view: Int?, parent: String?, tracks: List<Long> = emptyList(), done: (Long?, Int?) -> Unit = { _, _ -> }) = done(null, -1)
    fun set(key: String, value: String, done: (Int?) -> Unit = {})
    fun draft(kind: Int, value: String? = null, role: Int? = null, minutes: Long? = null, unixS: Long? = null, zoneS: Int? = null, done: (Int?, String?) -> Unit = { _, _ -> })
    fun eqSet(slot: String, key: String? = null, ints: Profile? = null, mode: Int? = null, graphicKey: String? = null, gains: List<GraphicBand>? = null, on: Boolean? = null, done: (Int?) -> Unit = {})
    fun eqPreset(op: Int, slot: String?, key: String?, name: String?, ints: Profile?, replace: Boolean, mode: Int? = null, gains: List<GraphicBand>? = null, done: (Int?, String?) -> Unit) {}
    fun action(cmd: Int, track: Long? = null, done: (Int?, String?) -> Unit = { _, _ -> })
    fun favorite(s: State, toast: (String, String) -> Unit) {}
    fun refusals(show: ((String, String) -> Unit)?, owner: ((String, String) -> Unit)? = null) {}
    fun bonded(): List<Bonded> = emptyList()
    fun choose(address: String) {}
    fun askPermission() {}
    fun retry() {}
    fun openBluetoothSettings() {}
    fun pairBoard(address: String) {}
    fun cancelBoard(address: String) {}
    fun laterBoard(address: String) {}
    fun connectionShown() {}
    val boardRev: Int get() = 0
    val flight: Flight? get() = null
    val picking: Boolean get() = false
    fun detectedBoards(): List<Detected> = emptyList()
    fun pairDevice() {}
    fun disconnect() {}
    fun allowBackground() {}
    fun backgroundLater() {}
    fun scrobble(accounts: Accounts) {}
    fun lastFmSignIn() {}
    fun signOut(service: String) {}
    fun openListenBrainzToken() {}
    fun openSupport() {}
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
    fun lyricsOffset(track: Long, ms: Int?, done: (Int?, Int?) -> Unit) = done(-1, null)
    fun lyricsOffsetEdit(edit: OffsetEdit?) {}
    fun lyricsHold(track: Long?) {}
    fun phoneLyricsMode(): Boolean = false
    fun phoneLyricsMode(on: Boolean) {}
    fun phoneOffset(key: PhoneKey, ms: Int?, done: (Int?, Int?) -> Unit) = done(-1, null)
    fun phoneCover(crc: Long): ImageBitmap? = null
    fun reconnect(on: Boolean) {}
    fun wantArt(slot: Int, track: Long, px: Int, album: String?) {}
    fun art(track: Long, px: Int): ImageBitmap? = null
    fun artCrc(track: Long, px: Int): Long? = null
    fun albumArtCrc(album: String, px: Int): Long? = null
    fun log(message: String) {}
    fun firmwareCheck() {}
    fun firmwareOpened() {}
    fun firmwareDownload() {}
    fun firmwareCancel() {}
    fun firmwareFile(done: (PickedFile) -> Unit) {}
    fun firmwareSettings(checkDaily: Boolean, prereleases: Boolean) {}
    fun firmwareSend(picked: Boolean) {}
    fun firmwareStopSend() {}
    fun blockArt(e: ArtCache.Entry) {}
    fun wrongArtist(e: ArtCache.Entry) {}
    fun unblock(r: ArtBlocks.Record) {}
    fun clearArt(source: String?) {}
    fun artUsage(done: (Map<String?, ArtCache.Usage>) -> Unit) {}
    fun pickLink(text: String, name: String, done: (Previewed) -> Unit): () -> Unit = {}
    fun pickGallery(done: (Previewed) -> Unit): () -> Unit = {}
    fun browse(name: String, mbid: String?, px: Int, sections: (List<String>) -> Unit, tile: (String, BrowseTile) -> Unit, sectionDone: (String) -> Unit, search: (YouTube.Search) -> Unit, done: (Boolean) -> Unit): () -> Unit = {}
    fun takeTile(t: Tile, done: (Previewed) -> Unit): () -> Unit = {}
    fun browseShown(section: String) {}
    fun pick(target: PickFor, offer: Offer) {}
    fun undoPick(p: ArtPicks.Pick) {}
    fun tryAgain(p: ArtPicks.Pick) {}
    fun pickHandled(name: String) {}
    fun svcSeen(service: String, seq: Long) {}
    fun svcSheet() {}
    fun svcLineShown(id: Int) {}
    fun svcOpen(service: String, seq: Long) {}
    fun svcContinue() {}
    fun svcTake(service: String, seq: Long, link: String, host: String?, listed: Boolean): SvcOpens.Verdict = SvcOpens.Verdict.ASK
    fun svcKnown(service: String, host: String) {}
    fun svcCancel() {}
    fun svcKeys(service: String, keys: List<SvcKey>, done: (String) -> Unit) {}
    fun secure(on: Boolean) {}
}

const val LIB_PAGE = 100L
