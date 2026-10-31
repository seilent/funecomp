package net.seilent.funecomp.state

import net.seilent.funecomp.proto.Eq
import net.seilent.funecomp.proto.EqName
import net.seilent.funecomp.proto.EqSlot
import net.seilent.funecomp.proto.FrameReader
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.PageInfo
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Feature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class EqSlotsTest {
    private fun body(name: String): ByteArray {
        val hex = File("../third_party/funeos/vectors.txt").readLines().filter { it.startsWith("hex $name ") }.joinToString("") { it.substringAfter("hex $name ") }
        val b = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return FrameReader().feed(b, 0, b.size).single().payload
    }

    private val hello = HelloReply.from(Node.decode("HELLO.reply", body("hello_reply_slots")))
    private val four = listOf(
        EqSlot(EqSlots.HEADPHONES, "HEADPHONES"),
        EqSlot(EqSlots.BLUETOOTH, "BLUETOOTH"),
        EqSlot(EqSlots.EAR_HEADPHONES, "CORRECTION HEADPHONES"),
        EqSlot(EqSlots.EAR_BLUETOOTH, "CORRECTION BLUETOOTH"),
    )
    private val stages = hello.copy(features = hello.features or (1L shl Feature.eqStages), eqSlots = four)
    private val app15 = 1L shl Feature.eqSlots
    private val app29 = app15 or (1L shl Feature.eqStages)

    private fun form(h: HelloReply, app: Long) = DeviceState().withHello(h, app).eqForm

    @Test
    fun theFormNeedsEachBitInBothMasksAndEnoughListedSlots() {
        assertEquals(EqForm.OUTPUT, form(hello, 0))
        assertEquals(EqForm.SLOTS, form(hello, app15))
        assertEquals(EqForm.SLOTS, form(stages, app15))
        assertEquals(listOf(EqSlots.HEADPHONES, EqSlots.BLUETOOTH), DeviceState().withHello(stages, app15).eqSlots)
        assertEquals(EqForm.STAGES, form(stages, app29))
        assertEquals(four.map { it.key }, DeviceState().withHello(stages, app29).eqSlots)
        assertEquals(EqForm.OUTPUT, form(hello.copy(eqSlots = hello.eqSlots.take(1)), app15))
        assertEquals(EqForm.SLOTS, form(stages.copy(eqSlots = four.take(3)), app29))
        assertEquals(EqForm.OUTPUT, form(stages.copy(features = stages.features and (1L shl Feature.eqSlots).inv()), app29))
        assertEquals(listOf(EqSlots.HEADPHONES, EqSlots.BLUETOOTH), DeviceState().withHello(hello, 0).eqSlots)
    }

    @Test
    fun thePlayingSlotFollowsStateOnASlotSessionAndTheOutputOnAnOutputSession() {
        val bt = State(output = Enums.Output.btHeadset, eqSlot = EqSlots.HEADPHONES, eqEarSlot = EqSlots.EAR_HEADPHONES)
        val old = DeviceState().withHello(stages, 0).withState(bt, 1)
        assertEquals(EqSlots.BLUETOOTH, old.playingEqSlot(EqStage.USER))
        assertNull(old.playingEqSlot(EqStage.CORRECTION))
        val now = DeviceState().withHello(stages, app29).withState(bt, 1)
        assertEquals(EqSlots.HEADPHONES, now.playingEqSlot(EqStage.USER))
        assertEquals(EqSlots.EAR_HEADPHONES, now.playingEqSlot(EqStage.CORRECTION))
        assertNull(DeviceState().withHello(stages, app15).withState(bt, 1).playingEqSlot(EqStage.CORRECTION))
    }

    @Test
    fun rowValuesFollowTheDapProfileText() {
        val hd = "/data/funeos/eq/HD 650.txt"
        val listed = listOf(EqName("off", "OFF"), EqName("custom", "CUSTOM"), EqName(hd, "HD 650"))
        assertEquals("off", EqSlots.value(null))
        assertEquals("off", EqSlots.value(EqMirror(key = "off", names = listed, complete = true)))
        assertEquals("custom", EqSlots.value(EqMirror(key = "custom", names = listed, complete = true)))
        assertEquals("off", EqSlots.value(EqMirror(key = "custom", names = listed, complete = true, on = false)))
        assertEquals("HD 650", EqSlots.value(EqMirror(key = hd, names = listed, complete = true)))
        val following = listOf(EqName(EqSlots.DEFAULT, "DEFAULT (HD 650)")) + listed
        assertEquals("default (hd 650)", EqSlots.value(EqMirror(key = EqSlots.DEFAULT, names = following, complete = true)))
        val graphic = listOf(EqName("off", "OFF"), EqName("flat", "FLAT"), EqName("/data/funeos/eq/graphic/Rock.ini", "ROCK"), EqName("custom", "CUSTOM"))
        assertEquals("off", EqSlots.value(EqMirror(key = hd, mode = Enums.EqMode.graphic, graphicKey = "off", graphicNames = graphic, graphicTotal = 4)))
        assertEquals("graphic flat", EqSlots.value(EqMirror(key = hd, mode = Enums.EqMode.graphic, graphicKey = "flat", graphicNames = graphic, graphicTotal = 4)))
        assertEquals("graphic ROCK", EqSlots.value(EqMirror(key = hd, mode = Enums.EqMode.graphic, graphicKey = "/data/funeos/eq/graphic/Rock.ini", graphicNames = graphic, graphicTotal = 4)))
    }

    @Test
    fun aKeyWithNoListedEntryReadsTheDapMissingName() {
        val names = listOf(EqName("off", "OFF"), EqName("custom", "CUSTOM"))
        assertEquals("Foo (missing)", EqSlots.value(EqMirror(key = "/data/funeos/eq/Foo ParametricEQ.txt", names = names, complete = true)))
        assertEquals("graphic Bar (missing)", EqSlots.value(EqMirror(mode = Enums.EqMode.graphic, graphicKey = "/data/funeos/eq/graphic/Bar.ini", graphicNames = names, graphicTotal = 2)))
    }

    @Test
    fun theMissingFormWaitsForTheWholeRun() {
        val key = "/data/funeos/eq/Late.txt"
        val page1 = Eq(Enums.EqOutput.wired, key, listOf(EqName("off", "OFF"), EqName("custom", "CUSTOM")), null, PageInfo(0, 3, true))
        val d = DeviceState().withEq(page1)
        assertEquals("Late", EqSlots.value(d.eq[EqSlots.HEADPHONES]))
        assertEquals("LATE", EqSlots.value(d.withEq(page1.copy(names = listOf(EqName(key, "LATE")), page = PageInfo(2, 3, false))).eq[EqSlots.HEADPHONES]))
        assertEquals("Late (missing)", EqSlots.value(d.withEq(page1.copy(names = listOf(EqName("/data/funeos/eq/Other.txt", "OTHER")), page = PageInfo(2, 3, false))).eq[EqSlots.HEADPHONES]))
        val graphic = Eq(Enums.EqOutput.wired, "off", emptyList(), null, PageInfo(0, 0, false), mode = Enums.EqMode.graphic, graphicKey = "/data/funeos/eq/graphic/Late.ini", graphicNames = listOf(EqName("off", "OFF")), graphicOffset = 0, graphicTotal = 2)
        assertEquals("graphic Late", EqSlots.value(DeviceState().withEq(graphic).eq[EqSlots.HEADPHONES]))
    }

    @Test
    fun theDefaultFormIsReadFromTheDefaultItemName() {
        assertEquals(EqDefault.CUSTOM, EqSlots.defaultForm("DEFAULT (CUSTOM)"))
        assertEquals(EqDefault.OFF, EqSlots.defaultForm("DEFAULT (OFF)"))
        assertEquals(EqDefault.FILE, EqSlots.defaultForm("DEFAULT (HD 650)"))
        assertEquals(EqDefault.FILE, EqSlots.defaultForm("DEFAULT (HD 650 (MISSING))"))
        assertEquals(EqDefault.GRAPHIC, EqSlots.defaultForm("DEFAULT (GRAPHIC FLAT)"))
        assertEquals(EqDefault.GRAPHIC_MISSING, EqSlots.defaultForm("DEFAULT (GRAPHIC X (MISSING))"))
        assertNull(EqSlots.defaultForm("HD 650"))
        val m = EqMirror(key = EqSlots.DEFAULT, names = listOf(EqName(EqSlots.DEFAULT, "DEFAULT (CUSTOM)")), mode = Enums.EqMode.graphic, graphicKey = EqSlots.DEFAULT, graphicNames = listOf(EqName(EqSlots.DEFAULT, "DEFAULT (GRAPHIC ROCK (MISSING))")))
        assertEquals(EqDefault.GRAPHIC_MISSING, m.defaultForm())
        assertEquals(EqDefault.CUSTOM, m.copy(mode = Enums.EqMode.parametric).defaultForm())
    }
}
