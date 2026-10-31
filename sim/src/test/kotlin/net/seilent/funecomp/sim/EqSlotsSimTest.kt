package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.Band
import net.seilent.funecomp.proto.Eq
import net.seilent.funecomp.proto.GraphicBand
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Profile
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Feature
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.state.EqSlots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EqSlotsSimTest {
    private class Sink : Out {
        val frames = ArrayList<Pair<Int, ByteArray>>()
        override fun push(type: Int, payload: ByteArray) {
            frames += type to payload
        }

        fun eqs() = frames.filter { it.first == Msg.EQ }.map { Eq.from(Node.decode("EQ", it.second)) }

        fun last(slot: String) = eqs().last { it.slot == slot && it.page.offset == 0L }

        fun graphic(slot: String) = eqs().last { it.slot == slot && it.graphicOffset == 0L }
    }

    private val app15 = Groups.all and (1L shl Feature.eqStages).inv()
    private val app29 = Groups.all
    private val app10 = Groups.all and Groups.mask("eqSlots", "eqStages").inv()
    private val ints = Profile(-30, listOf(Band(Enums.BandType.peak, 34, 1000000, -20, 141)))
    private val gains = listOf(40, 30, 10, 0, -10, -20, 0, 10, 30, 60).mapIndexed { i, g -> GraphicBand(4 + 6 * i, g) }

    private fun device(minor: Int = Proto.minor, headset: String? = null) =
        Device(Options(Board.of("v1"), Faults(emptyList()), minor = minor, eqHeadset = headset), Library.synthetic()) { 1000L }

    private fun linked(d: Device, app: Long) = Sink().also {
        d.linked(it, app)
        d.greet(it)
    }

    private fun set(d: Device, out: Sink, payload: ByteArray) = d.request(Msg.EQ_SET, Node.decode("EQ_SET", payload), out)

    private fun preset(d: Device, out: Sink, payload: ByteArray) = d.request(Msg.EQ_PRESET, Node.decode("EQ_PRESET", payload), out)

    private fun code(a: Answer) = (a as? Answer.Fail)?.code

    @Test
    fun eachFormFollowsTheAppMask() {
        val d = device()
        val old = Sink().also { d.linked(it, app10) }
        assertEquals(2, d.helloReply(old).eqSlots.size)
        d.greet(old)
        assertEquals(setOf(Enums.EqOutput.wired, Enums.EqOutput.bt), old.eqs().map { it.output }.toSet())
        assertTrue(old.eqs().all { it.slot == null && it.on == null })
        assertNull(State.from(Node.decode("STATE", d.state(null, old))).eqSlot)

        val two = linked(d, app15)
        assertEquals(listOf(EqSlots.HEADPHONES, EqSlots.BLUETOOTH), d.helloReply(two).eqSlots.map { it.key })
        assertEquals(setOf(EqSlots.HEADPHONES, EqSlots.BLUETOOTH), two.eqs().map { it.slot }.toSet())
        assertTrue(two.eqs().all { it.output == null && it.on == null && it.target == null })
        val st2 = State.from(Node.decode("STATE", d.state(null, two)))
        assertEquals(EqSlots.HEADPHONES, st2.eqSlot)
        assertNull(st2.eqEarSlot)

        val four = linked(d, app29)
        assertEquals(listOf(EqSlots.HEADPHONES, EqSlots.BLUETOOTH, EqSlots.EAR_HEADPHONES, EqSlots.EAR_BLUETOOTH), d.helloReply(four).eqSlots.map { it.key })
        assertEquals(listOf(Enums.BandType.off, Enums.BandType.peak, Enums.BandType.lowShelf, Enums.BandType.highShelf), d.helloReply(four).bandTypes)
        val pages = four.eqs()
        assertEquals(4, pages.map { it.slot }.toSet().size)
        assertTrue(pages.all { it.on != null && it.target == 1L })
        assertEquals(false, pages.last { it.slot == EqSlots.EAR_BLUETOOTH }.on)
        assertEquals(EqSlots.EAR_HEADPHONES, State.from(Node.decode("STATE", d.state(null, four))).eqEarSlot)
    }

    @Test
    fun aPushSendsTheProfileNamesThenTheGraphicNamesAsTheDevice() {
        val d = device()
        val out = linked(d, app29)
        val run = out.eqs().filter { it.slot == EqSlots.HEADPHONES }
        val split = run.indexOfFirst { it.graphicOffset != null }
        assertTrue(split > 0)
        val profile = run.take(split)
        val graphic = run.drop(split)
        assertTrue(profile.all { it.graphicNames.isEmpty() && it.graphicOffset == null && it.graphicTotal == null && it.page.more })
        assertEquals(profile.first().page.total.toInt(), profile.sumOf { it.names.size })
        assertTrue(graphic.all { it.names.isEmpty() && it.page.offset == profile.first().page.total && it.graphicTotal == 4L })
        assertEquals(0L, graphic.first().graphicOffset)
        assertEquals(listOf("off", "flat", "/data/funeos/eq/graphic/Rock.ini", "custom"), graphic.flatMap { it.graphicNames }.map { it.key })
        assertFalse(graphic.last().page.more)
        assertTrue(run.all { it.mode != null && it.graphicKey != null })
    }

    @Test
    fun theMinorClearsTheEqBits() {
        assertEquals(0L, device(11).helloReply().features and Groups.mask("eqSlots", "eqGraphic"))
        assertTrue(device(11).helloReply().eqSlots.isEmpty())
        assertEquals(Groups.mask("eqSlots", "eqGraphic"), device(14).helloReply().features and Groups.mask("eqSlots", "eqGraphic"))
        assertEquals(0L, device(14).helloReply().features and Groups.mask("eqStages"))
        assertEquals(Groups.mask("eqStages"), device().helloReply().features and Groups.mask("eqStages"))
    }

    @Test
    fun aStaleTargetIsError7AndAHeadsetChangePushesTheNewTarget() {
        val d = device()
        val out = linked(d, app29)
        assertNull(code(set(d, out, Req.eqSet(slot = EqSlots.EAR_BLUETOOTH, on = true, target = 1))))
        assertEquals(true, out.last(EqSlots.EAR_BLUETOOTH).on)
        d.eqHeadset("WH-1000XM4", out)
        val p = out.last(EqSlots.EAR_BLUETOOTH)
        assertEquals(2L, p.target)
        assertEquals("WH-1000XM4", p.headset)
        assertEquals(Err.staleRev, code(set(d, out, Req.eqSet(slot = EqSlots.EAR_BLUETOOTH, key = "custom", target = 1))))
        assertNull(code(set(d, out, Req.eqSet(slot = EqSlots.EAR_BLUETOOTH, key = "custom", target = 2))))
        assertNull(code(set(d, out, Req.eqSet(slot = EqSlots.HEADPHONES, key = "custom", target = 1))))
    }

    @Test
    fun slotRequestsNeedTheSlotFormAndModeRulesHold() {
        val d = device()
        val out = linked(d, app29)
        assertEquals(Err.badValue, code(set(d, out, Req.eqSet(output = Enums.EqOutput.wired, key = "off"))))
        assertEquals(Err.badValue, code(set(d, out, Req.eqSet(slot = "aux", key = "off"))))
        assertNull(code(set(d, out, Req.eqSet(slot = EqSlots.HEADPHONES, ints = ints))))
        assertNull(code(set(d, out, Req.eqSet(slot = EqSlots.HEADPHONES, mode = Enums.EqMode.graphic, gains = gains))))
        val g = out.last(EqSlots.HEADPHONES)
        assertEquals(Enums.EqMode.graphic, g.mode)
        assertEquals("custom", g.graphicKey)
        assertEquals("custom", g.key)
        assertEquals(ints, g.ints)
        assertEquals(gains, g.gains!!.bands)
        assertNull(code(set(d, out, Req.eqSet(slot = EqSlots.HEADPHONES, ints = ints.copy(preamp10 = -40)))))
        val p = out.last(EqSlots.HEADPHONES)
        assertEquals(Enums.EqMode.parametric, p.mode)
        assertEquals("custom", p.graphicKey)
        assertEquals(Err.badValue, code(set(d, out, Req.eqSet(slot = EqSlots.HEADPHONES, ints = ints, graphicKey = "flat"))))
        assertNull(code(set(d, out, Req.eqSet(slot = EqSlots.HEADPHONES, mode = Enums.EqMode.graphic, graphicKey = "off"))))
        assertNull(out.last(EqSlots.HEADPHONES).gains)
        val old = linked(d, app10)
        assertEquals(Err.badValue, code(set(d, old, Req.eqSet(slot = EqSlots.HEADPHONES, key = "off"))))
        assertNull(code(set(d, old, Req.eqSet(output = Enums.EqOutput.bt, key = "custom"))))
    }

    @Test
    fun aTwoSlotSessionReadsOffForADisabledStage() {
        val d = device()
        val four = linked(d, app29)
        assertNull(code(set(d, four, Req.eqSet(slot = EqSlots.HEADPHONES, ints = ints))))
        assertNull(code(set(d, four, Req.eqSet(slot = EqSlots.HEADPHONES, on = false, target = 1))))
        assertEquals("custom", four.last(EqSlots.HEADPHONES).key)
        assertEquals(false, four.last(EqSlots.HEADPHONES).on)
        val two = linked(d, app15)
        assertEquals("off", two.last(EqSlots.HEADPHONES).key)
        assertEquals("off", two.last(EqSlots.HEADPHONES).graphicKey)
        assertNull(code(set(d, two, Req.eqSet(slot = EqSlots.HEADPHONES, key = "custom"))))
        assertEquals("custom", two.last(EqSlots.HEADPHONES).key)
        d.greet(four)
        assertEquals(true, four.last(EqSlots.HEADPHONES).on)
    }

    @Test
    fun aHeadsetFollowsTheDefaultUntilItsOwnPick() {
        val d = device(headset = "WH-1000XM4")
        val out = linked(d, app29)
        val f = out.last(EqSlots.EAR_BLUETOOTH)
        assertEquals(EqSlots.DEFAULT, f.key)
        assertEquals(EqSlots.DEFAULT, f.graphicKey)
        assertEquals("DEFAULT (OFF)", f.names.first().name)
        assertEquals(EqSlots.DEFAULT, out.graphic(EqSlots.EAR_BLUETOOTH).graphicNames.first().key)
        assertEquals(List(10) { 0 }, f.gains!!.bands.map { it.gain10 })
        assertEquals("WH-1000XM4", f.headset)
        assertNull(code(set(d, out, Req.eqSet(slot = EqSlots.EAR_BLUETOOTH, key = "custom", target = 1))))
        val own = out.last(EqSlots.EAR_BLUETOOTH)
        assertEquals("custom", own.key)
        assertEquals("DEFAULT (OFF)", own.names.first().name)
        assertEquals(EqSlots.DEFAULT, out.graphic(EqSlots.EAR_BLUETOOTH).graphicNames.first().key)
        assertEquals(Err.badValue, code(set(d, out, Req.eqSet(slot = EqSlots.EAR_BLUETOOTH, key = EqSlots.DEFAULT, on = true, target = 1))))
        assertNull(code(set(d, out, Req.eqSet(slot = EqSlots.EAR_BLUETOOTH, key = EqSlots.DEFAULT, target = 1))))
        assertEquals(EqSlots.DEFAULT, out.last(EqSlots.EAR_BLUETOOTH).key)
        val pushed = out.eqs().size
        assertNull(code(set(d, out, Req.eqSet(slot = EqSlots.EAR_BLUETOOTH, on = false, target = 1))))
        assertTrue(out.eqs().size > pushed)
        assertEquals(EqSlots.DEFAULT, out.last(EqSlots.EAR_BLUETOOTH).key)
        assertNull(code(set(d, out, Req.eqSet(slot = EqSlots.EAR_BLUETOOTH, on = true, target = 1))))
        val enabled = out.last(EqSlots.EAR_BLUETOOTH)
        assertEquals("off", enabled.key)
        assertEquals(true, enabled.on)
        assertNull(code(set(d, out, Req.eqSet(slot = EqSlots.EAR_BLUETOOTH, key = EqSlots.DEFAULT, target = 1))))
        assertEquals(false, out.last(EqSlots.EAR_BLUETOOTH).on)
        d.eqHeadset(null, out)
        assertNull(code(set(d, out, Req.eqSet(slot = EqSlots.EAR_BLUETOOTH, ints = ints, target = 2))))
        d.eqHeadset("WH-1000XM4", out)
        val custom = out.last(EqSlots.EAR_BLUETOOTH)
        assertEquals("DEFAULT (CUSTOM)", custom.names.first().name)
        assertEquals(ints, custom.ints)
        assertNull(code(set(d, out, Req.eqSet(slot = EqSlots.EAR_BLUETOOTH, mode = Enums.EqMode.graphic, graphicKey = "/data/funeos/eq/graphic/Rock.ini", target = 3))))
        assertEquals("custom", out.last(EqSlots.EAR_BLUETOOTH).key)
    }

    @Test
    fun anUnnamedGraphicSaveTakesTheNextPresetName() {
        val d = device()
        val out = linked(d, app29)
        assertNull(code(set(d, out, Req.eqSet(slot = EqSlots.HEADPHONES, mode = Enums.EqMode.graphic, gains = gains, target = 1))))
        assertNull(code(preset(d, out, Req.eqPreset(Enums.EqPresetOp.save, slot = EqSlots.HEADPHONES, mode = Enums.EqMode.graphic, target = 1))))
        val first = "/data/funeos/eq/graphic/Preset 1.ini"
        assertEquals(first, out.last(EqSlots.HEADPHONES).graphicKey)
        val names = out.graphic(EqSlots.HEADPHONES).graphicNames
        assertEquals("Preset 1", names.single { it.key == first }.name)
        assertEquals(true, names.single { it.key == first }.preset)
        assertNull(code(set(d, out, Req.eqSet(slot = EqSlots.HEADPHONES, mode = Enums.EqMode.graphic, gains = gains.map { it.copy(gain10 = 0) }, target = 1))))
        assertNull(code(preset(d, out, Req.eqPreset(Enums.EqPresetOp.save, slot = EqSlots.HEADPHONES, mode = Enums.EqMode.graphic, target = 1))))
        assertEquals("/data/funeos/eq/graphic/Preset 2.ini", out.last(EqSlots.HEADPHONES).graphicKey)
    }
}
