package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.Band
import net.seilent.funecomp.proto.Eq
import net.seilent.funecomp.proto.EqName
import net.seilent.funecomp.proto.EqPresetReply
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Profile
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Proto15Test {
    private class Sink : Out {
        val frames = ArrayList<Pair<Int, ByteArray>>()
        override fun push(type: Int, payload: ByteArray) {
            frames += type to payload
        }

        fun eqs() = frames.filter { it.first == Msg.EQ }.map { Eq.from(Node.decode("EQ", it.second)) }
    }

    private val logs = ArrayList<String>()

    private fun device(minor: Int = Proto.minor, cardEq: List<String> = emptyList(), faults: List<String> = emptyList()) =
        Device(Options(Board.of("v1"), Faults(faults), minor = minor, cardEq = cardEq, log = { logs += it }), Library.synthetic()) { 1000L }

    private val wired = Enums.EqOutput.wired
    private val bt = Enums.EqOutput.bt
    private val ints = Profile(-40, listOf(Band(Enums.BandType.peak, 34, 1000000, -30, 141)))
    private val other = Profile(-61, listOf(Band(Enums.BandType.lowShelf, -1, 105000, 64, 70)))

    private fun req(d: Device, payload: ByteArray, out: Sink = Sink()) = d.request(Msg.EQ_PRESET, Node.decode("EQ_PRESET", payload), out)

    private fun save(d: Device, name: String?, output: Int = wired, p: Profile? = ints, replace: Boolean? = null, out: Sink = Sink()) =
        req(d, Req.eqPreset(Enums.EqPresetOp.save, output, name = name, ints = p, replace = replace), out)

    private fun remove(d: Device, key: String, out: Sink = Sink()) = req(d, Req.eqPreset(Enums.EqPresetOp.remove, key = key), out)

    private fun rename(d: Device, key: String, name: String, replace: Boolean? = null, out: Sink = Sink()) =
        req(d, Req.eqPreset(Enums.EqPresetOp.rename, key = key, name = name, replace = replace), out)

    private fun key(a: Answer) = EqPresetReply.from(Node.decode("EQ_PRESET.reply", (a as? Answer.Ok ?: throw AssertionError("failed: ${(a as Answer.Fail).code} ${a.text}")).payload)).key

    private fun code(a: Answer) = (a as Answer.Fail).code

    private fun list(d: Device, output: Int = wired): Pair<String?, List<EqName>> {
        val out = Sink()
        d.greet(out)
        val pages = out.eqs().filter { it.output == output }
        return pages.first().key to pages.flatMap { it.names }
    }

    private fun profileOf(d: Device, output: Int = wired): Profile? {
        val out = Sink()
        d.greet(out)
        return out.eqs().first { it.output == output }.ints
    }

    @Test
    fun saveStoresThePresetMovesTheOutputAndPushesBeforeTheReply() {
        val d = device()
        val out = Sink()
        val a = save(d, "HD 650", out = out)
        assertEquals("/data/funeos/eq/HD 650.txt", key(a))
        val pushed = out.eqs()
        assertEquals(setOf(wired, bt), pushed.map { it.output }.toSet())
        assertEquals("/data/funeos/eq/HD 650.txt", pushed.first { it.output == wired }.key)
        assertEquals("off", pushed.first { it.output == bt }.key)
        val (k, names) = list(d)
        assertEquals("/data/funeos/eq/HD 650.txt", k)
        assertEquals(EqName("/data/funeos/eq/HD 650.txt", "HD 650", true), names[2])
        assertEquals(EqName("off", "OFF"), names[0])
        assertNull(names[3].preset)
        assertEquals(ints, profileOf(d))
        assertTrue(logs.any { it == "eq preset 0 key=null -> ok /data/funeos/eq/HD 650.txt" })
    }

    @Test
    fun aTakenNameIsRefusedAndReplaceWritesInPlaceKeepingTheName() {
        val d = device()
        save(d, "HD 650")
        assertEquals(Err.exists, code(save(d, "HD 650", output = bt)))
        assertEquals(Err.exists, code(save(d, "hd 650", output = bt)))
        assertEquals("off", list(d, bt).first)
        assertEquals("/data/funeos/eq/HD 650.txt", key(save(d, "hd 650", output = bt, p = other, replace = true)))
        val (k, names) = list(d, bt)
        assertEquals("/data/funeos/eq/HD 650.txt", k)
        assertEquals(listOf(EqName("/data/funeos/eq/HD 650.txt", "HD 650", true)), names.filter { it.preset == true })
        assertEquals(other, profileOf(d, bt))
    }

    @Test
    fun replaceOntoACardNameIsStillRefused() {
        val d = device(cardEq = listOf("Sennheiser HD 650"))
        assertEquals(Err.exists, code(save(d, "Sennheiser HD 650")))
        assertEquals(Err.exists, code(save(d, "sennheiser hd 650", replace = true)))
        assertTrue(list(d).second.none { it.preset == true })
    }

    @Test
    fun aHiddenFileAtThePathIsTakenButNotListed() {
        val d = device(faults = listOf("eq-hidden=Old"))
        assertEquals(Err.exists, code(save(d, "Old")))
        assertTrue(list(d).second.none { it.name == "Old" })
        assertEquals("/data/funeos/eq/Preset 1.txt", key(save(d, null, p = null)))
    }

    @Test
    fun theTwoHundredthPresetFitsAndTheNextIsNoSpace() {
        val d = device()
        for (i in 1..200) assertEquals("/data/funeos/eq/P$i.txt", key(save(d, "P$i")))
        assertEquals(Err.noSpace, code(save(d, "P201")))
        assertEquals("/data/funeos/eq/P7.txt", key(save(d, "p7", replace = true)))
        val names = list(d).second
        assertEquals(202, names.size)
        assertEquals("P200", names.last().name)
    }

    @Test
    fun presetsListFirstInNumberOrderAndCardsFallOffTheCap() {
        val d = device()
        for (n in listOf("Preset 10", "preset 2", "Preset 1")) save(d, n)
        val names = list(d).second
        assertEquals(listOf("OFF", "CUSTOM", "Preset 1", "preset 2", "Preset 10", "Headphone Model 001"), names.take(6).map { it.name })
        assertEquals(202, names.size)
        assertEquals("autoeq/oratory1990/Headphone Model 197.txt", names.last().key)
    }

    @Test
    fun cardNamesSortWithTheDeviceOrder() {
        val d = device(cardEq = listOf("Headphone Model 1000", "headphone model 05b", "Abc"))
        val names = list(d).second.drop(2).map { it.name }
        assertEquals("Abc", names[0])
        assertEquals(listOf("Headphone Model 005", "headphone model 05b", "Headphone Model 006"), names.subList(5, 8))
        assertEquals(200, names.size)
        assertEquals("Headphone Model 198", names.last())
        assertFalse("Headphone Model 1000" in names)
    }

    @Test
    fun saveWithoutNameOrIntsTakesTheLowestFreePresetAndTheCustom() {
        val d = device()
        val custom = Profile(-35, listOf(Band(Enums.BandType.peak, -1, 3_150_000, -25, 200)))
        d.request(Msg.EQ_SET, Node.decode("EQ_SET", Req.eqSet(wired, ints = custom)), Sink())
        assertEquals("/data/funeos/eq/Preset 1.txt", key(save(d, null, p = null)))
        assertEquals(custom, profileOf(d))
        assertEquals("/data/funeos/eq/Preset 2.txt", key(save(d, null, output = bt, p = null)))
        remove(d, "/data/funeos/eq/Preset 1.txt")
        assertEquals("/data/funeos/eq/Preset 1.txt", key(save(d, null, p = null)))
    }

    @Test
    fun removeTurnsEveryOutputOnItOff() {
        val d = device()
        save(d, "HD 650")
        save(d, "HD 650", output = bt, replace = true)
        val out = Sink()
        assertNull(key(remove(d, "/data/funeos/eq/HD 650.txt", out)))
        assertEquals(listOf("off", "off"), listOf(wired, bt).map { o -> out.eqs().first { it.output == o }.key })
        assertTrue(list(d).second.none { it.preset == true })
        assertEquals(Err.readOnly, code(remove(d, "custom")))
        assertEquals(Err.readOnly, code(remove(d, "off")))
        assertEquals(Err.readOnly, code(remove(d, "autoeq/oratory1990/Headphone Model 007.txt")))
        assertEquals(Err.notFound, code(remove(d, "/data/funeos/eq/HD 650.txt")))
        assertEquals(Err.badValue, code(req(d, Req.eqPreset(Enums.EqPresetOp.remove))))
    }

    @Test
    fun renameMovesBothOutputsAndRefusesATakenName() {
        val d = device()
        save(d, "HD 650")
        save(d, "HD 650", output = bt, replace = true)
        save(d, "Warm")
        assertEquals(Err.exists, code(rename(d, "/data/funeos/eq/HD 650.txt", "WARM")))
        assertEquals(Err.badValue, code(rename(d, "/data/funeos/eq/HD 650.txt", "a/b")))
        assertEquals("/data/funeos/eq/hd 650.txt", key(rename(d, "/data/funeos/eq/HD 650.txt", "hd 650")))
        assertEquals("/data/funeos/eq/hd 650.txt", list(d, bt).first)
        val out = Sink()
        assertEquals("/data/funeos/eq/Sennheiser.txt", key(rename(d, "/data/funeos/eq/hd 650.txt", "Sennheiser", out = out)))
        assertEquals(setOf("/data/funeos/eq/Sennheiser.txt"), out.eqs().filter { it.output == bt }.map { it.key }.toSet())
        assertEquals("/data/funeos/eq/Sennheiser.txt", list(d, bt).first)
        assertEquals(listOf("Sennheiser", "Warm"), list(d).second.filter { it.preset == true }.map { it.name })
        assertEquals(ints, profileOf(d, bt))
        assertEquals("/data/funeos/eq/Sennheiser.txt", key(rename(d, "/data/funeos/eq/Sennheiser.txt", "Sennheiser")))
    }

    @Test
    fun minor4ClearsTheBitAndLeavesOutThePresetTag() {
        val d = device(minor = 4)
        assertFalse(Groups.allowed(Msg.EQ_PRESET, d.helloReply().features))
        assertTrue(Groups.allowed(Msg.EQ_SET, d.helloReply().features))
        save(d, "HD 650")
        assertTrue(list(d).second.all { it.preset == null })
        assertTrue(Groups.allowed(Msg.EQ_PRESET, device().helloReply().features))
    }
}
