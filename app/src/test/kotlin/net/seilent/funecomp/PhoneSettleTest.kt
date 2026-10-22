package net.seilent.funecomp

import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.state.PhoneKey
import net.seilent.funecomp.state.Playback
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class PhoneSettleTest {
    private val asks: MutableList<PhoneKey> = CopyOnWriteArrayList()
    private val sched = ManualScheduler()
    private val phone = PhoneLyrics({ sched.execute(it); true }, { asks += it.key; PhoneLyrics.Found.Miss }, {})
    @Volatile private var state = DeviceState()
    private val lyrics = DapLyrics({ null }, { state }, { sched.now }, phone, sched)

    private fun play(artist: String) {
        state = DeviceState(playback = Playback(State(source = Enums.Source.phone, output = Enums.Output.phoneReceiver, track = 0, title = "Idol", artist = artist, durationMs = 213_000)))
    }

    private fun phone(artist: String, to: DapLyrics = lyrics) {
        play(artist)
        to.poke()
    }

    @Test
    fun aKeySeenOnceSettlesFromTheScheduledRecheck() {
        lyrics.shown(true)
        phone("YOASOBI")
        sched.advance(PhoneLyrics.SETTLE_MS - 1)
        assertEquals(emptyList<PhoneKey>(), asks.toList())
        sched.advance(1)
        assertEquals(listOf(PhoneKey("Idol", "YOASOBI")), asks.toList())
        sched.advance(10 * PhoneLyrics.SETTLE_MS)
        assertEquals(1, asks.size)
    }

    @Test
    fun aTitleWithAStaleArtistThenTheNewArtist400MsLaterGivesOneLookup() {
        lyrics.shown(true)
        phone("Previous Artist")
        sched.advance(400)
        phone("YOASOBI")
        sched.advance(PhoneLyrics.SETTLE_MS - 1)
        assertEquals(emptyList<PhoneKey>(), asks.toList())
        sched.advance(1)
        sched.advance(10 * PhoneLyrics.SETTLE_MS)
        assertEquals(listOf(PhoneKey("Idol", "YOASOBI")), asks.toList())
    }

    @Test
    fun aTitleWithAnEmptyArtistThenTheArtist400MsLaterGivesOneLookup() {
        lyrics.shown(true)
        phone("")
        sched.advance(400)
        assertEquals(emptyList<PhoneKey>(), asks.toList())
        phone("YOASOBI")
        sched.advance(PhoneLyrics.SETTLE_MS - 1)
        assertEquals(emptyList<PhoneKey>(), asks.toList())
        sched.advance(1)
        sched.advance(10 * PhoneLyrics.SETTLE_MS)
        assertEquals(listOf(PhoneKey("Idol", "YOASOBI")), asks.toList())
    }

    @Test
    fun aKeySettlesOnTheRealExecutor() {
        val exec = Executors.newSingleThreadExecutor()
        val real = PhoneLyrics({ exec.execute(it); true }, { asks += it.key; PhoneLyrics.Found.Miss }, {})
        val l = DapLyrics({ null }, { state }, { System.nanoTime() / 1_000_000 }, real)
        l.shown(true)
        phone("YOASOBI", l)
        val end = System.currentTimeMillis() + 30_000
        while (asks.isEmpty()) {
            check(System.currentTimeMillis() < end) { "no lookup" }
            Thread.sleep(20)
        }
        assertEquals(listOf(PhoneKey("Idol", "YOASOBI")), asks.toList())
        exec.shutdown()
    }
}
