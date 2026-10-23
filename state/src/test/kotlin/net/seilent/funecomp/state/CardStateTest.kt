package net.seilent.funecomp.state

import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardStateTest {
    private val hello = HelloReply(1, 15, "v1", "x", 240, 320, 240, 320, 4096, 3000, 1000, 0, 1, 1)

    private fun session() = DeviceState().withHello(hello)

    private fun DeviceState.card(v: Int?) = withState(State(track = 1, card = v), now = 0)

    private fun DeviceState.walk(vararg v: Int?) = v.fold(this) { d, c -> d.card(c) }

    private fun edges(vararg v: Int?): List<Int> {
        var d = session()
        return v.map { c -> d = d.card(c); d.card.epoch }
    }

    @Test
    fun busyToReadyIsOneEdge() {
        assertEquals(listOf(0, 1), edges(Enums.Card.busy, Enums.Card.ready))
        val d = session().walk(Enums.Card.busy)
        assertTrue(d.cardDown)
        assertTrue(PushGate.card(d))
        assertFalse(d.card(Enums.Card.ready).cardDown)
    }

    @Test
    fun busyOutBusyReadyEndsInOneEdgeAtTheReady() {
        assertEquals(listOf(0, 0, 0, 1), edges(Enums.Card.busy, Enums.Card.out, Enums.Card.busy, Enums.Card.ready))
        val d = session().walk(Enums.Card.busy, Enums.Card.out)
        assertTrue(d.cardDown)
        assertTrue(session().walk(Enums.Card.error).cardDown)
    }

    @Test
    fun outToReadyIsAnEdge() {
        assertEquals(listOf(0, 1), edges(Enums.Card.out, Enums.Card.ready))
    }

    @Test
    fun absentForeverIsReadyWithNoEdge() {
        assertEquals(listOf(0, 0, 0), edges(null, null, null))
        val d = session().walk(null, null)
        assertFalse(d.cardDown)
        assertFalse(PushGate.card(d))
        assertEquals(null, d.card.value)
    }

    @Test
    fun aNewSessionWhoseFirstStateIsReadyFiresTheEdgeOnce() {
        assertEquals(listOf(1, 1, 1), edges(Enums.Card.ready, Enums.Card.ready, Enums.Card.ready))
    }

    @Test
    fun aNewSessionWhoseFirstStateIsBusyFiresAtTheReady() {
        assertEquals(listOf(0, 0, 1), edges(Enums.Card.busy, Enums.Card.busy, Enums.Card.ready))
    }

    @Test
    fun theEpochCountsEdgesAndResetsPerSession() {
        assertEquals(listOf(1, 1, 2, 2, 2, 3), edges(Enums.Card.ready, Enums.Card.busy, Enums.Card.ready, Enums.Card.ready, Enums.Card.out, Enums.Card.ready))
        val before = session().walk(Enums.Card.ready, Enums.Card.busy, Enums.Card.ready)
        assertEquals(2, before.card.epoch)
        val next = before.withHello(hello)
        assertEquals(0, next.card.epoch)
        assertFalse(next.cardDown)
        assertEquals(1, next.card(Enums.Card.ready).card.epoch)
    }

    @Test
    fun aStateWithoutTheTagKeepsTheLastValue() {
        val d = session().walk(Enums.Card.busy, null)
        assertTrue(d.cardDown)
        assertEquals(1, d.card(null).card(Enums.Card.ready).card.epoch)
    }

    @Test
    fun aRefusalMakesTheNextReadyAnEdgeWithNoBusySeen() {
        val ready = CardView().with(Enums.Card.ready)
        assertEquals(1, ready.epoch)
        assertEquals(1, ready.with(Enums.Card.ready).epoch)
        val refused = ready.refused()
        assertEquals(Enums.Card.ready, refused.value)
        assertEquals(1, refused.epoch)
        assertFalse(refused.down)
        assertEquals(2, refused.with(Enums.Card.ready).epoch)
        assertEquals(2, refused.with(Enums.Card.ready).with(Enums.Card.ready).epoch)
    }
}
