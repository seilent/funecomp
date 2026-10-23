package net.seilent.funecomp

import androidx.compose.ui.graphics.ImageBitmap
import net.seilent.funecomp.link.ArtGet
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PhoneCoverTest {
    private val tasks = ArrayList<() -> Unit>()
    private val asked = ArrayList<Long>()
    private var answer: (Long) -> ArtGet.Outcome? = { ArtGet.Outcome.Image(it, 200, 200, byteArrayOf(1)) }
    private val c = PhoneCover({ crc, _ -> asked += crc; answer(crc) }, { r -> tasks += { r.run() } }, { ImageBitmap(8, 8) })

    private fun phone(crc: Long?) = State(source = Enums.Source.phone, output = Enums.Output.phoneReceiver, track = 0, title = "Idol", phoneArtCrc = crc)

    private fun drain() {
        while (tasks.isNotEmpty()) tasks.removeAt(0)()
    }

    @Test
    fun aCrcIsFetchedOnceAndShownUnderIt() {
        c.state(phone(7))
        c.state(phone(7))
        drain()
        assertEquals(listOf(7L), asked)
        assertNotNull(c.image(7))
        assertNull(c.image(8))
        c.state(phone(7))
        drain()
        assertEquals(listOf(7L), asked)
    }

    @Test
    fun noCrcAsksNothing() {
        c.state(phone(null))
        drain()
        assertEquals(emptyList<Long>(), asked)
    }

    @Test
    fun aNewCrcRefetchesAndKeepsTheOldUntilItArrives() {
        c.state(phone(7))
        drain()
        c.state(phone(8))
        assertNotNull(c.image(7))
        drain()
        assertNotNull(c.image(8))
        assertEquals(listOf(7L, 8L), asked)
    }

    @Test
    fun notFoundKeepsTheGlyphUntilTheCrcChanges() {
        answer = { ArtGet.Outcome.Failed(Err.notFound) }
        c.state(phone(7))
        drain()
        c.state(phone(7))
        drain()
        assertEquals(listOf(7L), asked)
        assertNull(c.image(7))
        answer = { ArtGet.Outcome.Image(it, 200, 200, byteArrayOf(1)) }
        c.state(phone(8))
        drain()
        assertNotNull(c.image(8))
    }

    @Test
    fun staleDropsTheImage() {
        c.state(phone(7))
        drain()
        answer = { ArtGet.Outcome.Failed(Err.staleRev) }
        c.state(phone(8))
        drain()
        assertNull(c.image(7))
        assertNull(c.image(8))
    }

    @Test
    fun anotherFailureAsksAgainOnTheNextState() {
        answer = { null }
        c.state(phone(7))
        drain()
        answer = { ArtGet.Outcome.Image(it, 200, 200, byteArrayOf(1)) }
        c.state(phone(7))
        drain()
        assertEquals(listOf(7L, 7L), asked)
        assertNotNull(c.image(7))
    }

    @Test
    fun aReplyWithAnotherCrcIsKeptUnderTheReplyCrcWhileTheAskStaysOnTheRequest() {
        answer = { ArtGet.Outcome.Image(9, 200, 200, byteArrayOf(1)) }
        c.state(phone(7))
        drain()
        assertNull(c.image(7))
        assertNotNull(c.image(9))
        c.state(phone(7))
        drain()
        assertEquals(listOf(7L), asked)
        c.state(phone(9))
        drain()
        assertEquals(listOf(7L, 9L), asked)
        assertNotNull(c.image(9))
    }

    @Test
    fun anotherSourceClearsAndALateReplyIsDropped() {
        c.state(phone(7))
        c.state(State(source = Enums.Source.local, track = 5))
        drain()
        assertNull(c.image(7))
        c.state(phone(7))
        drain()
        assertNotNull(c.image(7))
        c.clear()
        assertNull(c.image(7))
    }
}
