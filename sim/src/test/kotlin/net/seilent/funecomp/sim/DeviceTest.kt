package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.Band
import net.seilent.funecomp.proto.Eq
import net.seilent.funecomp.proto.ImgAbort
import net.seilent.funecomp.proto.ImgBeginReply
import net.seilent.funecomp.proto.ImgListReply
import net.seilent.funecomp.proto.Lib
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Profile
import net.seilent.funecomp.proto.Queue
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.Schema
import net.seilent.funecomp.proto.SetReply
import net.seilent.funecomp.proto.Settings
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32

class DeviceTest {
    private class Sink : Out {
        val frames = ArrayList<Pair<Int, ByteArray>>()
        override fun push(type: Int, payload: ByteArray) {
            frames += type to payload
        }

        fun of(type: Int) = frames.filter { it.first == type }.map { Node.decode(Msg.info.getValue(type).name, it.second) }
    }

    private var t = 1000L
    private fun device(board: String = "ap80", vararg faults: String) =
        Device(Options(Board.of(board), Faults(faults.toList())), Library.synthetic()) { t }

    private fun req(d: Device, type: Int, payload: ByteArray, out: Sink = Sink()) =
        d.request(type, Node.decode(Msg.info.getValue(type).name, payload), out).also { if (it is Answer.Ok && it.state) out.push(Msg.STATE, d.state()) }

    private fun ok(a: Answer) = (a as? Answer.Ok ?: throw AssertionError("failed: ${(a as Answer.Fail).code} ${a.text}")).payload

    private fun fail(a: Answer) = a as Answer.Fail

    private fun st(d: Device) = State.from(Node.decode("STATE", d.state()))

    private fun at(d: Device, slot: Long, repeat: Int) {
        ok(req(d, Msg.TRANSPORT, Req.transport(Enums.TransportCmd.repeat, repeat = repeat)))
        ok(req(d, Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.playAt, st(d).queueRev, slot = slot)))
    }

    @Test
    fun nextAtTheEndWithRepeatOffDoesNothing() {
        val d = device()
        val last = st(d).slots!! - 1
        at(d, last, Enums.Repeat.off)
        t += 5000
        ok(req(d, Msg.TRANSPORT, Req.transport(Enums.TransportCmd.next)))
        val s = st(d)
        assertEquals(last, s.slot)
        assertEquals(5000L, s.positionMs)
        assertEquals(true, s.playing)
    }

    @Test
    fun nextAtTheEndWithRepeatAllWrapsToTheFirst() {
        val d = device()
        at(d, st(d).slots!! - 1, Enums.Repeat.all)
        t += 5000
        ok(req(d, Msg.TRANSPORT, Req.transport(Enums.TransportCmd.next)))
        assertEquals(0L, st(d).slot)
        assertEquals(0L, st(d).positionMs)
    }

    @Test
    fun prevAtTheStartWithRepeatOffRestartsTheTrack() {
        val d = device()
        at(d, 0, Enums.Repeat.off)
        t += 2000
        ok(req(d, Msg.TRANSPORT, Req.transport(Enums.TransportCmd.prev)))
        val s = st(d)
        assertEquals(0L, s.slot)
        assertEquals(0L, s.positionMs)
        assertEquals(true, s.playing)
    }

    @Test
    fun prevAtTheStartWithRepeatAllWrapsToTheLast() {
        val d = device()
        at(d, 0, Enums.Repeat.all)
        t += 2000
        ok(req(d, Msg.TRANSPORT, Req.transport(Enums.TransportCmd.prev)))
        assertEquals(st(d).slots!! - 1, st(d).slot)
        assertEquals(0L, st(d).positionMs)
    }

    @Test
    fun prevFromThreeSecondsInRestartsTheTrack() {
        val d = device()
        at(d, 2, Enums.Repeat.off)
        t += 3000
        ok(req(d, Msg.TRANSPORT, Req.transport(Enums.TransportCmd.prev)))
        assertEquals(2L, st(d).slot)
        assertEquals(0L, st(d).positionMs)
        t += 1000
        ok(req(d, Msg.TRANSPORT, Req.transport(Enums.TransportCmd.prev)))
        assertEquals(1L, st(d).slot)
    }

    @Test
    fun schemaFilesParseForEveryBoard() {
        for (b in listOf("ap80", "v1", "r1")) {
            val d = device(b)
            assertTrue(d.rows.all { it.kind in Enums.RowKind.names })
            val out = Sink()
            d.greet(out)
            val pages = out.of(Msg.SCHEMA).map { Schema.from(it) }
            assertEquals(6, pages.last().pivotCount)
            assertTrue(pages.last().last)
            assertEquals(d.schemaRows().size, pages.sumOf { it.rows.size })
            val text = Settings.from(out.of(Msg.SETTINGS).single()).text
            val keys = text.lines().filter { it.isNotEmpty() }.map { it.substringBefore('=') }.toSet()
            val rowKeys = d.schemaRows().mapNotNull { it.key }.toSet()
            assertEquals(setOf("btCodecHeadphones"), keys - rowKeys)
            assertTrue(d.rows.none { it.key == "pairPhone" || it.key == "playKey" })
            val codec = d.rows.single { it.key == "btCodec" }
            assertEquals("bluetooth", codec.pivot)
            assertEquals("CODEC", codec.label)
            assertEquals(listOf("auto", "aptx", "aac", "sbc"), codec.values)
            assertEquals("LOCK VOLUME", d.rows.single { it.key == "lockKnob" }.label)
        }
        assertEquals(8, device("ap80").rows.first { it.key == "digitalFilter" }.values.size)
        assertEquals(listOf("FAST", "FAST PHASE", "SLOW", "SLOW PHASE", "NOS"), device("v1").rows.first { it.key == "digitalFilter" }.words)
        assertEquals(4, device("r1").rows.first { it.key == "digitalFilter" }.values.size)
        assertTrue(listOf("ap80", "v1", "r1").all { b -> device(b).rows.none { it.key == "dre" } })
        assertEquals(listOf(Enums.RowFlag.readOnly, Enums.RowFlag.boardOnly), device("v1").rows.first { it.key == "companion" }.flags)
    }

    @Test
    fun rowCountsAtTheDefaultsAndWithEveryRuntimeRowShown() {
        for ((b, n) in listOf("ap80" to 46, "v1" to 54, "r1" to 49)) assertEquals(b, n, device(b).schemaRows().size)
        for ((b, n) in listOf("ap80" to 49, "v1" to 57, "r1" to 52)) {
            val d = Device(Options(Board.of(b), headset = true, companion = true), Library.synthetic()) { t }
            ok(req(d, Msg.SET, Req.set("navBar=on\n")))
            assertEquals(b, n, d.schemaRows().size)
            assertEquals(b, d.rows.size, d.schemaRows().size)
        }
    }

    @Test
    fun conditionedRowsComeAndGoWithANewRev() {
        val d = device("v1")
        val out = Sink()
        d.greet(out)
        val rev = Schema.from(out.of(Msg.SCHEMA).last()).rev
        assertTrue(d.schemaRows().none { it.key == "navOrder" || it.key == "navButton" })
        ok(req(d, Msg.SET, Req.set("navBar=on\n"), out))
        assertTrue("navOrder=backLeft" in Settings.from(out.of(Msg.SETTINGS).last()).text.lines())
        out.frames.clear()
        d.tick(out)
        val pages = out.of(Msg.SCHEMA).map { Schema.from(it) }
        assertTrue(pages.isNotEmpty() && pages.all { it.rev == rev!! + 1 })
        val display = pages.flatMap { it.rows }.filter { it.pivot == "display" }.map { it.key }
        assertEquals(display.indexOf("navBar") + 1, display.indexOf("navOrder"))
        assertEquals(display.indexOf("navBar") + 2, display.indexOf("navButton"))
        out.frames.clear()
        d.tick(out)
        assertTrue(out.of(Msg.SCHEMA).isEmpty())
        ok(req(d, Msg.SET, Req.set("navBar=off\noutput=headphones\n"), out))
        d.tick(out)
        assertTrue(d.schemaRows().none { it.key in setOf("navOrder", "navButton", "outputPriority", "companionPair") })
        ok(req(d, Msg.SET, Req.set("output=auto\n"), out))
        assertTrue(d.schemaRows().any { it.key == "outputPriority" })
        val pair = Device(Options(Board.of("v1"), companion = true), Library.synthetic()) { t }
        assertTrue(pair.schemaRows().any { it.key == "companionPair" })
    }

    @Test
    fun liveWordsMoveTheRevOnlyWhenTheyChange() {
        val d = device("v1")
        val out = Sink()
        d.greet(out)
        ok(req(d, Msg.DRAFT, Req.draft(Enums.DraftKind.clock, unixS = 1767225600, zoneS = 0), out))
        out.frames.clear()
        d.tick(out)
        val first = out.of(Msg.SCHEMA).map { Schema.from(it) }
        assertEquals(listOf("2026-01-01 00:00"), first.flatMap { it.rows }.single { it.key == "dateTime" }.words)
        val rev = first.last().rev!!
        out.frames.clear()
        t += 59_000
        d.tick(out)
        assertTrue(out.of(Msg.SCHEMA).isEmpty())
        t += 1_000
        d.tick(out)
        val next = out.of(Msg.SCHEMA).map { Schema.from(it) }
        assertEquals(rev + 1, next.last().rev)
        assertEquals(listOf("2026-01-01 00:01"), next.flatMap { it.rows }.single { it.key == "dateTime" }.words)
        assertEquals(listOf("${Library.synthetic().tracks.size} SONGS"), d.schemaRows().single { it.key == "rescan" }.words)
        assertEquals(listOf("sim.v1"), d.schemaRows().single { it.key == "about" }.words)
        assertEquals(listOf("1 ON"), d.schemaRows().single { it.key == "folders" }.words)
    }

    @Test
    fun aRescanShowsScanningUntilItEnds() {
        val d = device("v1", "scan-ms=2000")
        val out = Sink()
        d.greet(out)
        val rev = Schema.from(out.of(Msg.SCHEMA).last()).rev!!
        ok(req(d, Msg.ACTION, Req.action(Enums.ActionCmd.rescan)))
        out.frames.clear()
        d.tick(out)
        val scanning = out.of(Msg.SCHEMA).map { Schema.from(it) }
        assertEquals(rev + 1, scanning.last().rev)
        assertEquals(listOf("SCANNING"), scanning.flatMap { it.rows }.single { it.key == "rescan" }.words)
        out.frames.clear()
        t += 2_000
        d.tick(out)
        val done = out.of(Msg.SCHEMA).map { Schema.from(it) }
        assertEquals(rev + 2, done.last().rev)
        assertEquals(listOf("${Library.synthetic().tracks.size} SONGS"), done.flatMap { it.rows }.single { it.key == "rescan" }.words)
        assertEquals(listOf("${Library.synthetic().tracks.size} SONGS"), device("v1").also { ok(req(it, Msg.ACTION, Req.action(Enums.ActionCmd.rescan))) }.schemaRows().single { it.key == "rescan" }.words)
    }

    @Test
    fun everyConnectionGetsTheNewSchema() {
        val d = device("v1")
        val a = Sink()
        val b = Sink()
        d.greet(a)
        d.greet(b)
        val rev = Schema.from(a.of(Msg.SCHEMA).last()).rev!!
        ok(req(d, Msg.SET, Req.set("navBar=on\n"), a))
        a.frames.clear()
        b.frames.clear()
        d.tick(a)
        d.tick(b)
        assertEquals(rev + 1, Schema.from(a.of(Msg.SCHEMA).last()).rev)
        assertEquals(rev + 1, Schema.from(b.of(Msg.SCHEMA).last()).rev)
        assertTrue(b.of(Msg.SCHEMA).map { Schema.from(it) }.flatMap { it.rows }.any { it.key == "navOrder" })
        b.frames.clear()
        d.tick(b)
        assertTrue(b.of(Msg.SCHEMA).isEmpty())
    }

    @Test
    fun rowsOffAndTheHeadsetValue() {
        val off = device("v1", "rows-off=checkCard+usbStorage", "rows-off=wifi")
        assertTrue(off.schemaRows().none { it.key in setOf("checkCard", "usbStorage", "wifi") })
        val d = device("v1")
        assertEquals(listOf("auto", "headphones"), d.schemaRows().single { it.key == "output" }.values)
        assertEquals(listOf("JACK"), d.schemaRows().single { it.key == "usbOutput" }.words)
        ok(req(d, Msg.SET, Req.set("output=bluetooth\n")))
        assertEquals(listOf("AUTO", "JACK", "BLUETOOTH"), d.schemaRows().single { it.key == "output" }.words)
        val h = Device(Options(Board.of("v1"), headset = true), Library.synthetic()) { t }
        assertEquals(listOf("auto", "headphones", "bluetooth"), h.schemaRows().single { it.key == "output" }.values)
        assertEquals(listOf("headphones", "bluetooth"), h.schemaRows().single { it.key == "usbOutput" }.values)
    }

    @Test
    fun anR1IsDockOnlyAndNamesItsUpdate() {
        val d = device("r1")
        val hello = d.helloReply()
        assertEquals("r1", hello.board)
        assertEquals(480 to 800, hello.screenW to hello.screenH)
        val usb = d.schemaRows().single { it.key == "usbMode" }
        assertEquals(listOf("dock"), usb.values)
        assertTrue(Enums.RowFlag.readOnly in usb.flags)
        assertEquals(Err.refused, fail(req(d, Msg.DRAFT, Req.draft(Enums.DraftKind.usbMode, "storage"))).code)
        assertTrue(d.schemaRows().none { it.key in setOf("usbFeedback", "usbOutput", "wifi", "whenIdle", "outputGain", "scale") })
        assertTrue(d.schemaRows().any { it.key == "size" } && d.schemaRows().any { it.key == "led" } && d.schemaRows().any { it.key == "chargeLimit" })
        assertEquals(listOf("storage", "dock", "audio", "mtp"), device("v1").rows.single { it.key == "usbMode" }.values)
    }
    @Test
    fun crossfeedBrightnessAndArtistPhotoRowsOnEveryBoard() {
        for ((b, bright) in listOf("ap80" to "55", "v1" to "35", "r1" to "35")) {
            val d = device(b)
            val keys = d.rows.map { it.key }
            assertEquals(keys.indexOf("eq") + 1, keys.indexOf("crossfeed"))
            val display = d.schemaRows().filter { it.pivot == "display" }.map { it.key }
            assertEquals("brightness", display.first())
            assertEquals(display.indexOf("artistPhoto") + 1, display.indexOf("navBar"))
            assertEquals("navBar", display.last())
            val xf = d.rows.first { it.key == "crossfeed" }
            assertEquals(Enums.RowKind.choice, xf.kind)
            assertEquals(listOf("off", "default", "chuMoy", "janMeier"), xf.values)
            assertEquals(listOf("OFF", "DEFAULT", "CHU MOY", "JAN MEIER"), xf.words)
            val br = d.rows.first { it.key == "brightness" }
            assertEquals(Enums.RowKind.steps, br.kind)
            assertEquals((10..100 step 5).map { it.toString() }, br.values)
            assertEquals((10..100 step 5).map { "$it%" }, br.words)
            assertEquals(Enums.RowKind.toggle, d.rows.first { it.key == "artistPhoto" }.kind)
            val out = Sink()
            d.greet(out)
            val text = Settings.from(out.of(Msg.SETTINGS).single()).text.lines()
            assertTrue("crossfeed=off" in text && "brightness=$bright" in text && "artistPhoto=on" in text)
            val set = Sink()
            val r = SetReply.from(Node.decode("SET.reply", ok(req(d, Msg.SET, Req.set("crossfeed=chuMoy\nbrightness=72\nartistPhoto=off\n"), set))))
            assertEquals(listOf("crossfeed", "brightness", "artistPhoto"), r.kept)
            val s = Settings.from(set.of(Msg.SETTINGS).single()).text.lines()
            assertTrue("crossfeed=chuMoy" in s && "brightness=70" in s && "artistPhoto=off" in s)
            assertEquals(listOf("crossfeed"), fail(req(d, Msg.SET, Req.set("crossfeed=bs2b\n"))).refused)
        }
    }
    @Test
    fun saverRowsAndTheNewOutputRowsOnEveryBoard() {
        for (b in listOf("ap80", "v1", "r1")) {
            val d = device(b)
            val keys = d.rows.map { it.key }
            assertEquals(keys.indexOf("saverSeconds") + 1, keys.indexOf("saverLyrics"))
            assertEquals(keys.indexOf("saverLyrics") + 1, keys.indexOf("saverDrift"))
            assertEquals(keys.indexOf("crossfeed") + 1, keys.indexOf("tube"))
            assertEquals(keys.indexOf("tube") + 1, keys.indexOf("stereoWidth"))
            val row = d.rows.first { it.key == "saverLyrics" }
            assertEquals("display", row.pivot)
            assertEquals(Enums.RowKind.toggle, row.kind)
            assertEquals("SAVER LYRICS", row.label)
            assertEquals(listOf("off", "on"), row.values)
            assertTrue(row.flags.isEmpty())
            assertEquals(listOf("OFF", "50%", "75%", "125%", "150%", "200%"), d.rows.first { it.key == "stereoWidth" }.words)
            assertEquals(listOf("PHONE", "CARD LOG", "OFF"), d.rows.first { it.key == "scrobble" }.words)
            val out = Sink()
            d.greet(out)
            val text = Settings.from(out.of(Msg.SETTINGS).single()).text.lines()
            assertTrue("saverLyrics=on" in text && "saverDrift=on" in text && "tube=off" in text && "stereoWidth=off" in text && "scrobble=phone" in text)
            val set = Sink()
            val r = SetReply.from(Node.decode("SET.reply", ok(req(d, Msg.SET, Req.set("saverLyrics=off\nstereoWidth=150\n"), set))))
            assertEquals(listOf("saverLyrics", "stereoWidth"), r.kept)
            assertTrue("saverLyrics=off" in Settings.from(set.of(Msg.SETTINGS).single()).text.lines())
            assertEquals(listOf("saverLyrics"), fail(req(d, Msg.SET, Req.set("saverLyrics=yes\n"))).refused)
        }
    }
    @Test
    fun boardRowsFollowTheBoardFlags() {
        val v1 = device("v1")
        val ap80 = device("ap80")
        val r1 = device("r1")
        for (d in listOf(v1, r1)) {
            val size = d.rows.first { it.key == "size" }
            assertEquals(Enums.RowKind.draft, size.kind)
            assertEquals(listOf("standard", "large"), size.values)
            assertEquals(listOf(Enums.RowFlag.readOnly, Enums.RowFlag.boardOnly, Enums.RowFlag.restart), size.flags)
            assertTrue(d.rows.none { it.key == "scale" })
        }
        val keys = v1.rows.map { it.key }
        assertEquals(keys.indexOf("dateTime") + 1, keys.indexOf("wifi"))
        val wifi = v1.rows.first { it.key == "wifi" }
        assertEquals(Enums.RowKind.info, wifi.kind)
        assertEquals(listOf(Enums.RowFlag.readOnly, Enums.RowFlag.boardOnly), wifi.flags)
        assertTrue(ap80.rows.any { it.key == "scale" } && ap80.rows.none { it.key == "size" || it.key == "wifi" })
        assertTrue(ap80.rows.none { it.key in setOf("whenIdle", "chargeLimit", "led", "dsdOutput", "outputGain", "usbFeedback", "usbOutput") })
        assertTrue(v1.rows.map { it.key }.containsAll(listOf("whenIdle", "chargeLimit", "led", "dsdOutput", "outputGain", "usbFeedback", "usbOutput")))
        for (d in listOf(v1, ap80, r1)) assertTrue(d.rows.none { it.key in setOf("btCodecHeadphones", "binary", "track", "pcm", "update", "exportSettings", "importSettings", "keyboardTest") })
        val out = Sink()
        v1.greet(out)
        assertTrue("size=standard" in Settings.from(out.of(Msg.SETTINGS).single()).text.lines())
        assertEquals(listOf("size"), fail(req(v1, Msg.SET, Req.set("size=large\n"))).refused)
    }
    @Test
    fun setMergesPlainRowsOnly() {
        val d = device()
        val out = Sink()
        val r = SetReply.from(Node.decode("SET.reply", ok(req(d, Msg.SET, Req.set("balance=99\nvolumeStep=4\nclock=13h\nusbMode=dock\nbtRole=headphones\nsampleRate=1\n"), out))))
        assertEquals(listOf("balance", "volumeStep"), r.kept)
        assertEquals(listOf("clock", "usbMode", "btRole", "sampleRate"), r.refused)
        val s = Settings.from(out.of(Msg.SETTINGS).single())
        assertEquals(r.rev, s.rev)
        assertTrue("balance=20" in s.text.lines())
        assertTrue("volumeStep=5" in s.text.lines())
        assertTrue("clock=24h" in s.text.lines())
        assertTrue("usbMode=storage" in s.text.lines())
        val hue = Sink()
        ok(req(d, Msg.SET, Req.set("accentHue=237\n"), hue))
        assertTrue("accentHue=240" in Settings.from(hue.of(Msg.SETTINGS).single()).text.lines())
        val top = Sink()
        ok(req(d, Msg.SET, Req.set("accentHue=359\n"), top))
        assertTrue("accentHue=0" in Settings.from(top.of(Msg.SETTINGS).single()).text.lines())
        val none = fail(req(d, Msg.SET, Req.set("companion=on\n")))
        assertEquals(Err.refused, none.code)
        assertEquals(listOf("companion"), none.refused)
    }

    @Test
    fun draftRowsGoThroughDraft() {
        val d = device()
        val out = Sink()
        ok(req(d, Msg.DRAFT, Req.draft(Enums.DraftKind.btCodec, "aac", Enums.BtRole.receiver), out))
        assertTrue("btCodec=aac" in Settings.from(out.of(Msg.SETTINGS).last()).text.lines())
        assertEquals(Err.refused, fail(req(d, Msg.DRAFT, Req.draft(Enums.DraftKind.btCodec, "ldac", Enums.BtRole.headphones))).code)
        assertEquals(Err.badValue, fail(req(d, Msg.DRAFT, Req.draft(Enums.DraftKind.btCodec, "ldac", Enums.BtRole.receiver))).code)
        val v1 = device("v1")
        ok(req(v1, Msg.DRAFT, Req.draft(Enums.DraftKind.btCodec, "aptx", Enums.BtRole.receiver)))
        ok(req(v1, Msg.DRAFT, Req.draft(Enums.DraftKind.usbMode, "mtp")))
        assertEquals(Err.badValue, fail(req(d, Msg.DRAFT, Req.draft(Enums.DraftKind.usbMode, "mtp"))).code)
        assertEquals(Err.badValue, fail(req(d, Msg.DRAFT, Req.draft(Enums.DraftKind.clock, unixS = -86400, zoneS = -18000))).code)
        val sleep = Sink()
        ok(req(d, Msg.DRAFT, Req.draft(Enums.DraftKind.sleep, minutes = 30), sleep))
        assertEquals(1800L, State.from(sleep.of(Msg.STATE).last()).sleepLeftS)
        ok(req(d, Msg.DRAFT, Req.draft(Enums.DraftKind.sleep, minutes = 37), sleep))
        assertEquals(2220L, State.from(sleep.of(Msg.STATE).last()).sleepLeftS)
        ok(req(v1, Msg.DRAFT, Req.draft(Enums.DraftKind.sleep, minutes = 999), sleep))
        assertEquals(59940L, State.from(sleep.of(Msg.STATE).last()).sleepLeftS)
        assertEquals(Err.badValue, fail(req(d, Msg.DRAFT, Req.draft(Enums.DraftKind.sleep, minutes = 1000))).code)
        assertEquals(Err.badValue, fail(req(d, Msg.DRAFT, Req.draft(Enums.DraftKind.sleep, minutes = -1))).code)
    }
    @Test
    fun draftSleepTakesTheRowMin() {
        val d = Device(Options(Board("ap80", 360, 480, 360, 480, "schema-sleepmin.txt")), Library.synthetic()) { t }
        assertEquals(Err.badValue, fail(req(d, Msg.DRAFT, Req.draft(Enums.DraftKind.sleep, minutes = 4))).code)
        val out = Sink()
        ok(req(d, Msg.DRAFT, Req.draft(Enums.DraftKind.sleep, minutes = 5), out))
        assertEquals(300L, State.from(out.of(Msg.STATE).last()).sleepLeftS)
    }

    @Test
    fun sleepTimerCountsDownInStateAndPausesAtExpiry() {
        val d = device()
        ok(req(d, Msg.TRANSPORT, Req.transport(Enums.TransportCmd.play)))
        ok(req(d, Msg.DRAFT, Req.draft(Enums.DraftKind.sleep, minutes = 30)))
        val greet = Sink()
        d.greet(greet)
        val row = greet.of(Msg.SCHEMA).flatMap { Schema.from(it).rows }.single { it.key == "sleep" }
        assertEquals(listOf("OFF", "15 MIN", "30 MIN", "45 MIN", "60 MIN", "90 MIN", "120 MIN"), row.words)
        assertEquals(0 to 999, row.min to row.max)
        assertEquals(null, row.step)
        t += 61_000
        assertEquals(1739L, State.from(Node.decode("STATE", d.state())).sleepLeftS)
        t = 1000L + 1_800_000
        val out = Sink()
        d.tick(out)
        d.tick(out)
        val states = out.of(Msg.STATE).map { State.from(it) }
        assertEquals(1, states.size)
        assertEquals(0L, states[0].sleepLeftS)
        assertEquals(false, states[0].playing)
    }

    @Test
    fun eqSetKeysAndRanges() {
        val d = device()
        assertEquals(Err.badValue, fail(req(d, Msg.EQ_SET, Req.eqSet(Enums.EqOutput.wired, "/etc/passwd"))).code)
        val out = Sink()
        ok(req(d, Msg.EQ_SET, Req.eqSet(Enums.EqOutput.bt, "autoeq/oratory1990/Headphone Model 007.txt"), out))
        val pages = out.of(Msg.EQ).map { Eq.from(it) }
        assertTrue(pages.size >= 2)
        assertTrue(pages.all { it.output == Enums.EqOutput.bt && it.key == "autoeq/oratory1990/Headphone Model 007.txt" })
        assertEquals(0L, pages.first().page.offset)
        assertFalse(pages.last().page.more)
        assertEquals(200, pages.sumOf { it.names.size })
        val bad = Profile(-10, listOf(Band(Enums.BandType.peak, -1, 1_000_000, 200, 141)))
        assertEquals(Err.badValue, fail(req(d, Msg.EQ_SET, Req.eqSet(Enums.EqOutput.wired, ints = bad))).code)
        val good = Profile(-35, listOf(Band(Enums.BandType.peak, -1, 3_150_000, -25, 200)))
        val o2 = Sink()
        ok(req(d, Msg.EQ_SET, Req.eqSet(Enums.EqOutput.wired, ints = good), o2))
        assertEquals(good, Eq.from(o2.of(Msg.EQ).first()).ints)
        assertEquals("custom", Eq.from(o2.of(Msg.EQ).first()).key)
    }

    @Test
    fun libraryKeysPerView() {
        val d = device()
        val out = Sink()
        ok(req(d, Msg.LIB_GET, Req.libGet(Enums.View.artists, "", 0, 500, d.lib.rev), out))
        val artists = out.frames.map { Lib.from(Node.decode("LIB", it.second)) }.flatMap { it.rows }
        assertTrue(artists.any { it.key == "宇多田ヒカル" })
        assertTrue(artists.any { it.key == "Skrillex" })
        assertTrue(artists.any { it.key == "n-buna" })
        val a = Sink()
        ok(req(d, Msg.LIB_GET, Req.libGet(Enums.View.artistAlbums, "Utada Hikaru", 0, 100, d.lib.rev), a))
        val albums = Lib.from(a.of(Msg.LIB).single()).rows
        assertTrue(albums.all { it.key == java.lang.Long.toUnsignedString(it.first!!) })
        assertEquals(setOf("First Love", "BADモード", "Hikari"), albums.map { it.name }.toSet())
        val tracks = Sink()
        val album = albums.first { it.name == "First Love" }
        ok(req(d, Msg.LIB_GET, Req.libGet(Enums.View.albumTracks, album.key, 0, 100, d.lib.rev), tracks))
        assertEquals(5, Lib.from(tracks.of(Msg.LIB).single()).rows.size)
        assertEquals(Err.staleRev, fail(req(d, Msg.LIB_GET, Req.libGet(Enums.View.albumTracks, "12345", 0, 100, d.lib.rev))).code)

        val play = Sink()
        ok(req(d, Msg.PLAY, Req.play(Enums.View.albumTracks, album.key, d.lib.rev, 2, false), play))
        val s = State.from(play.of(Msg.STATE).single())
        assertEquals("In My Room", s.title)
        assertEquals(5L, s.slots)
        assertEquals(Err.staleRev, fail(req(d, Msg.PLAY, Req.play(Enums.View.albumTracks, album.key, d.lib.rev + 1, 0, false))).code)
    }

    @Test
    fun queueOpsCheckTheRev() {
        val d = device()
        val first = Sink()
        d.greet(first)
        val rev = State.from(first.of(Msg.STATE).single()).queueRev!!
        assertEquals(Err.staleRev, fail(req(d, Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.remove, rev + 5, slot = 0))).code)
        val out = Sink()
        ok(req(d, Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.move, rev, slot = 0, to = 3), out))
        assertEquals(rev + 1, State.from(out.of(Msg.STATE).single()).queueRev)
        assertTrue(out.of(Msg.QUEUE).isEmpty())
        val page = Sink()
        ok(req(d, Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.page, offset = 0, count = 10), page))
        val q = Queue.from(page.of(Msg.QUEUE).single())
        assertEquals(10, q.items.size)
        assertTrue(q.page.more)
        assertEquals(rev + 1, q.rev)
    }

    @Test
    fun shuffleLibraryNeedsTracks() {
        val empty = Device(Options(Board.of("ap80")), Library(emptyList())) { t }
        val first = Sink()
        empty.greet(first)
        val rev = State.from(first.of(Msg.STATE).single()).queueRev
        val none = Sink()
        assertEquals(Err.noLibrary, fail(req(empty, Msg.ACTION, Req.action(Enums.ActionCmd.shuffleLibrary), none)).code)
        assertTrue(none.of(Msg.STATE).isEmpty())
        assertEquals(rev, State.from(Node.decode("STATE", empty.state())).queueRev)
        val d = device()
        val out = Sink()
        ok(req(d, Msg.ACTION, Req.action(Enums.ActionCmd.shuffleLibrary), out))
        val s = State.from(out.of(Msg.STATE).single())
        assertEquals(d.lib.tracks.size.toLong(), s.slots)
        assertEquals(true, s.shuffle)
    }

    private fun jpeg(size: Int, w: Int = 360, h: Int = 480): ByteArray {
        val head = byteArrayOf(-1, -40, -1, -64, 0, 17, 8, (h shr 8).toByte(), h.toByte(), (w shr 8).toByte(), w.toByte(), 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1)
        return ByteArray(size).also { head.copyInto(it); it[size - 2] = -1; it[size - 1] = -39 }
    }

    private fun crc(b: ByteArray) = CRC32().apply { update(b) }.value

    @Test
    fun imagePushRules() {
        val d = device()
        val img = jpeg(50_000)
        val out = Sink()
        fun begin(name: String) = ImgBeginReply.from(Node.decode("IMG_BEGIN.reply",
            ok(req(d, Msg.IMG_BEGIN, Req.imgBegin(Enums.ImgKind.artist, name, null, img.size.toLong(), crc(img), 360, 480), out))))
        fun data(off: Int, n: Int) = d.imgData(Node.decode("IMG_DATA", Req.imgData(off.toLong(), img.copyOfRange(off, off + n))), out)
        assertEquals(0L, begin("Ichiko Aoba").offset)
        data(0, 16368)
        data(16368, 100)
        assertEquals(16368L, begin("Ichiko Aoba").offset)
        assertEquals(crc(img.copyOf(16368)), ImgBeginReply.from(Node.decode("IMG_BEGIN.reply",
            ok(req(d, Msg.IMG_BEGIN, Req.imgBegin(Enums.ImgKind.artist, "Ichiko Aoba", null, img.size.toLong(), crc(img), 360, 480), out)))).crc)
        begin("Sigur Rós")
        val superseded = out.of(Msg.IMG_ABORT).map { ImgAbort.from(it) }
        assertEquals(Enums.AbortCode.superseded, superseded.last().code)
        assertEquals("Ichiko Aoba", superseded.last().name)
        data(5, 10)
        assertEquals(Enums.AbortCode.offsetMismatch, ImgAbort.from(out.of(Msg.IMG_ABORT).last()).code)
        assertEquals(Err.refused, fail(req(d, Msg.IMG_END, Req.imgEnd(crc(img)))).code)

        begin("Sigur Rós")
        var off = 0
        while (off < img.size) {
            val n = minOf(16368, img.size - off)
            data(off, n)
            off += n
        }
        ok(req(d, Msg.IMG_END, Req.imgEnd(crc(img))))
        assertEquals(img.size.toLong(), begin("sigur rós").offset)
        val list = ImgListReply.from(Node.decode("IMG_LIST.reply", ok(req(d, Msg.IMG_LIST, Req.imgList(0, 10)))))
        assertEquals(listOf("Sigur Rós"), list.entries.map { it.name })
        ok(req(d, Msg.IMG_DEL, Req.imgDel("SIGUR RÓS")))
        assertEquals(0L, ImgListReply.from(Node.decode("IMG_LIST.reply", ok(req(d, Msg.IMG_LIST, Req.imgList(0, 10))))).page.total)
    }

    private fun store(d: Device, name: String, img: ByteArray, w: Int = 360, h: Int = 480) {
        val out = Sink()
        ok(req(d, Msg.IMG_BEGIN, Req.imgBegin(Enums.ImgKind.artist, name, null, img.size.toLong(), crc(img), w, h), out))
        var off = 0
        while (off < img.size) {
            val n = minOf(16368, img.size - off)
            d.imgData(Node.decode("IMG_DATA", Req.imgData(off.toLong(), img.copyOfRange(off, off + n))), out)
            off += n
        }
        ok(req(d, Msg.IMG_END, Req.imgEnd(crc(img))))
    }

    @Test
    fun aBackdropOverrideSetsTheHelloSizeAndTheImageSizeCheck() {
        val d = Device(Options(Board.of("v1").withBackdrop(480, 800), Faults(emptyList())), Library.synthetic()) { t }
        val hello = d.helloReply()
        assertEquals(480 to 800, hello.backdropW to hello.backdropH)
        assertEquals(240 to 320, hello.screenW to hello.screenH)
        val img = jpeg(5_000, 480, 800)
        assertEquals(Err.badValue, fail(req(d, Msg.IMG_BEGIN, Req.imgBegin(Enums.ImgKind.artist, "Mili", null, img.size.toLong(), crc(img), 240, 320))).code)
        store(d, "Mili", img, 480, 800)
        assertEquals(listOf("Mili"), ImgListReply.from(Node.decode("IMG_LIST.reply", ok(req(d, Msg.IMG_LIST, Req.imgList(0, 10))))).entries.map { it.name })
    }

    private fun mbidLib() = Library(listOf(
        Library.track("Utada Hikaru, Skrillex", "Hikari", "Face My Fears", 1, 220_000, "Utada Hikaru", listOf("mb-u", "")),
        Library.track("Utada Hikaru", "First Love", "Automatic", 1, 200_000, "Utada Hikaru", listOf("mb-u")),
        Library.track("Mili", "Miracle Milk", "Rubber Child", 1, 200_000),
    ))

    @Test
    fun libFileSixthColumnHoldsTheMbids() {
        val f = java.io.File.createTempFile("lib", ".tsv")
        f.writeText("Utada Hikaru, Skrillex\tHikari\tFace My Fears\t220000\tUtada Hikaru\tmb-u;\nMili\tMiracle Milk\tRubber Child\t200000\n")
        val l = Library.load(f)
        f.delete()
        assertEquals(listOf("mb-u", ""), l.tracks[0].mbids)
        assertEquals(listOf(""), l.tracks[1].mbids)
        assertEquals("Utada Hikaru", l.tracks[0].albumArtist)
    }

    @Test
    fun protocol11TagsAndTheirAbsenceAt10() {
        for (minor in listOf(1, 0)) {
            val d = Device(Options(Board.of("ap80"), minor = minor), mbidLib()) { t }
            val q = Sink()
            ok(req(d, Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.page, offset = 0, count = 10), q))
            val items = Queue.from(q.of(Msg.QUEUE).single()).items
            val l = Sink()
            ok(req(d, Msg.LIB_GET, Req.libGet(Enums.View.artists, "", 0, 100, d.lib.rev), l))
            val rows = Lib.from(l.of(Msg.LIB).single()).rows.associateBy { it.name }
            val list = ImgListReply.from(Node.decode("IMG_LIST.reply", ok(req(d, Msg.IMG_LIST, Req.imgList(0, 10)))))
            val st = Sink()
            d.greet(st)
            assertEquals(listOf("mb-u", ""), State.from(st.of(Msg.STATE).single()).mbids)
            if (minor == 1) {
                assertEquals(listOf("Utada Hikaru", "Skrillex"), items[0].artists)
                assertEquals(listOf("mb-u", ""), items[0].mbids)
                assertEquals(listOf("Mili"), items[2].artists)
                assertEquals("mb-u", rows.getValue("Utada Hikaru").mbid)
                assertEquals(null, rows.getValue("Skrillex").mbid)
                assertEquals(0L, list.bytes)
                assertEquals(Options.IMG_CAP, list.cap)
            } else {
                assertTrue(items.all { it.artists.isEmpty() && it.mbids.isEmpty() })
                assertTrue(rows.values.all { it.mbid == null })
                assertEquals(null, list.bytes)
                assertEquals(null, list.cap)
            }
        }
    }

    @Test
    fun storeEvictsTheLeastRecentlyShownPastTheCap() {
        val d = Device(Options(Board.of("ap80"), imgCap = 120_000), mbidLib()) { t }
        store(d, "Utada Hikaru", jpeg(50_000))
        store(d, "Mili", jpeg(50_001))
        d.state()
        store(d, "Skrillex", jpeg(50_002))
        assertEquals(setOf("utada hikaru", "skrillex"), d.stored.keys)
        val list = ImgListReply.from(Node.decode("IMG_LIST.reply", ok(req(d, Msg.IMG_LIST, Req.imgList(0, 10)))))
        assertEquals(100_002L, list.bytes)
        store(d, "Mili", jpeg(130_000))
        assertEquals(setOf("mili"), d.stored.keys)
    }

    @Test
    fun aDeleteLogsTheNameAndRemovesTheStoredFile() {
        val dir = kotlin.io.path.createTempDirectory("img").toFile()
        val logs = ArrayList<String>()
        val d = Device(Options(Board.of("ap80"), imgDir = dir, log = { logs += it }), mbidLib()) { t }
        store(d, "Utada Hikaru", jpeg(50_000))
        assertEquals(1, dir.listFiles()!!.size)
        ok(req(d, Msg.IMG_DEL, Req.imgDel("Utada Hikaru")))
        ok(req(d, Msg.IMG_DEL, Req.imgDel("Mili")))
        assertEquals(0, dir.listFiles()!!.size)
        assertEquals(listOf("deleted name=Utada Hikaru", "absent name=Mili"), logs.filter { it.startsWith("deleted") || it.startsWith("absent") })
    }

    @Test
    fun faultsParse() {
        val f = Faults(listOf("drop-after-bytes=131072,stall-ms=10", "enum=STATE.output=9", "refuse=SET", "bye=companionOff", "busy=UPD_BEGIN:2", "upd-abort-at=4096"))
        assertEquals(131072L, f.dropAfterBytes)
        assertEquals(Msg.STATE, f.enums.single().type)
        assertEquals(setOf(Msg.SET), f.refuse)
        assertEquals(Enums.ByeReason.companionOff, f.bye)
        assertEquals(2, f.busy[Msg.UPD_BEGIN]?.get())
        assertEquals(4096L, f.updAbortAt)
        val n = Faults(listOf("fail=IMG_DEL:14:2", "fail=IMG_TAG:3"))
        assertEquals(14, n.fail[Msg.IMG_DEL])
        assertEquals(2, n.failLeft[Msg.IMG_DEL]?.get())
        assertEquals(null, n.failLeft[Msg.IMG_TAG])
        for (bad in listOf("fail=IMG_DEL:14:x", "nope=1", "enum=STATE.nope=1", "refuse=NOPE", "busy=UPD_BEGIN", "busy=NOPE:1")) {
            try {
                Faults(listOf(bad))
                throw AssertionError("$bad parsed")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
