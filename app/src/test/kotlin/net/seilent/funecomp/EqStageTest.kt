package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import net.seilent.funecomp.proto.Band
import net.seilent.funecomp.proto.Eq
import net.seilent.funecomp.proto.EqName
import net.seilent.funecomp.proto.Graphic
import net.seilent.funecomp.proto.GraphicBand
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.PageInfo
import net.seilent.funecomp.proto.Profile
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.proto.gen.Tags
import net.seilent.funecomp.state.EqSlots
import net.seilent.funecomp.state.EqStage
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.Control
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class EqStageTest {
    @get:Rule
    val rule = createComposeRule()

    private val wired = Enums.EqOutput.wired
    private val bt = Enums.EqOutput.bt
    private val ints = Profile(-30, listOf(Band(Enums.BandType.peak, 34, 1000000, -20, 141)))
    private val card = "autoeq/oratory1990/Headphone Model 001.txt"

    private fun eq(output: Int = wired, stage: EqStage = EqStage.USER) = listOf(Page.Home, Page.Settings, Page.Eq(output, stage))

    private fun start(pages: List<Page>, minor: Int = Proto.minor, app: Long = LINK_FEATURES, prep: (SimControl) -> Unit = {}, wrap: (SimControl) -> Control = { it }): Pair<SimControl, Shell> {
        val c = SimControl("v1", minor = minor, appFeatures = app)
        prep(c)
        val control = wrap(c)
        val shell = Shell(Nav(pages))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE), control, shell) }
        }
        settle(1000)
        c.eqSets.clear()
        c.presets.clear()
        return c to shell
    }

    private fun settle(ms: Long = 500) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun tap(tag: String) {
        rule.onNodeWithTag(tag).performClick()
        settle()
    }

    private fun item(i: Int) = tap("sheet.item.$i")

    private fun enter(text: String) {
        rule.onNodeWithTag("entry").performTextClearance()
        rule.onNodeWithTag("entry").performTextInput(text)
        rule.onNodeWithTag("entry").performImeAction()
        settle()
    }

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun shows(tag: String, text: String) = rule.onAllNodes(hasText(text, ignoreCase = true) and hasAnyAncestor(hasTestTag(tag))).fetchSemanticsNodes().isNotEmpty()

    private fun tags(n: Node) = n.entries.map { it.tag }.toSet()

    private fun barOn(tag: String): Boolean {
        val bar = rule.onAllNodes(hasTestTag("bar") and hasAnyAncestor(hasTestTag("hub.eq"))).fetchSemanticsNodes().single().boundsInRoot.top
        return abs(bar - rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top) < 1f
    }

    private fun noStageBody(n: Node) {
        assertNull(n.str(Tags.EqPreset.slot))
        assertNull(n.long(Tags.EqPreset.target))
        assertNull(n.int(Tags.EqPreset.output))
    }

    private fun follow(c: SimControl, prep: () -> Unit = {}) {
        prep()
        c.eqHeadset("WH-1000XM4")
    }

    @Test
    fun eachStagePageOpensFromItsRowOnThePlayingPivot() {
        val (c, shell) = start(listOf(Page.Home, Page.Settings), prep = { it.eqSet(EqSlots.BLUETOOTH, key = "custom") })
        tap("pivot.effects")
        settle(500)
        assertTrue(shows("set.eqEar", "off"))
        tap("set.eqEar")
        assertEquals(Page.Eq(wired, EqStage.CORRECTION), shell.nav.top)
        assertTrue(exists("hub.correction eq"))
        Espresso.pressBack()
        settle(1000)
        val s = c.device.playback.state
        c.device = c.device.withState(s.copy(output = Enums.Output.btHeadset, eqSlot = EqSlots.BLUETOOTH, eqEarSlot = EqSlots.EAR_BLUETOOTH), 0)
        settle()
        tap("set.eq")
        assertEquals(Page.Eq(bt, EqStage.USER), shell.nav.top)
        assertTrue(exists("hub.eq"))
        assertTrue(shows("eq.profile", "custom"))
    }

    @Test
    fun theEnableSheetSendsOnAlone() {
        val (c, shell) = start(eq())
        assertTrue(shows("eq.enable", "on"))
        tap("eq.enable")
        assertEquals(listOf("on", "off"), shell.sheet.items)
        assertEquals(0, shell.sheet.focus)
        item(0)
        assertTrue(c.eqSets.isEmpty())
        tap("eq.enable")
        item(1)
        val b = c.eqSets.single()
        assertEquals(setOf(Tags.EqSet.slot, Tags.EqSet.on, Tags.EqSet.target), tags(b))
        assertEquals(EqSlots.HEADPHONES, b.str(Tags.EqSet.slot))
        assertEquals(false, b.bool(Tags.EqSet.on))
        assertTrue(shows("eq.enable", "off"))
        assertTrue(shows("eq.profile", "off"))
        tap("eq.enable")
        assertEquals(1, shell.sheet.focus)
    }

    @Test
    fun modeToGraphicAndBackKeepsBothSides() {
        val (c, _) = start(eq(), prep = { it.eqSet(EqSlots.HEADPHONES, ints = ints) })
        tap("eq.mode")
        item(1)
        assertEquals(Enums.EqMode.graphic, c.eqSets.last().int(Tags.EqSet.mode))
        assertTrue(exists("eq.preset"))
        assertFalse(exists("eq.profile"))
        tap("eq.preset")
        item(1)
        assertEquals("flat", c.eqSets.last().str(Tags.EqSet.graphicKey))
        assertEquals(Enums.EqMode.graphic, c.eqSets.last().int(Tags.EqSet.mode))
        assertTrue(exists("eq.gband.0"))
        tap("eq.mode")
        item(0)
        assertTrue(shows("eq.profile", "custom"))
        assertTrue(shows("eq.preamp", "-3.0 db"))
        assertEquals("flat", c.device.eq.getValue(EqSlots.HEADPHONES).graphicKey)
    }

    @Test
    fun aGraphicBandTypedGainSendsTenGainsWithModeGraphic() {
        val (c, _) = start(eq(), prep = { it.eqSet(EqSlots.HEADPHONES, mode = Enums.EqMode.graphic, graphicKey = "flat") })
        assertTrue(shows("eq.gpreamp", "0.0 db"))
        tap("eq.gband.3")
        enter("4.5")
        val b = c.eqSets.last()
        assertEquals(EqSlots.HEADPHONES, b.str(Tags.EqSet.slot))
        assertEquals(Enums.EqMode.graphic, b.int(Tags.EqSet.mode))
        val gains = Graphic.from(b.node(Tags.EqSet.gains)!!).bands
        assertEquals(List(10) { if (it == 3) 45 else 0 }, gains.map { it.gain10 })
        assertEquals("custom", c.device.eq.getValue(EqSlots.HEADPHONES).graphicKey)
        assertEquals("graphic custom", EqSlots.value(c.device.eq.getValue(EqSlots.HEADPHONES)))
    }

    @Test
    fun graphicWithPresetOffOrMissingListsNoPreampAndNoBands() {
        val (c, _) = start(eq(), prep = { it.eqSet(EqSlots.HEADPHONES, mode = Enums.EqMode.graphic, graphicKey = "off") })
        assertTrue(exists("eq.preset"))
        assertFalse(exists("eq.gpreamp"))
        assertFalse(exists("eq.gband.0"))
        val gone = "/data/funeos/eq/graphic/Gone.ini"
        val m = c.device.eq.getValue(EqSlots.HEADPHONES)
        c.device = c.device.withEq(Eq(null, m.key, m.names, m.ints, PageInfo(0, m.total, false), slot = EqSlots.HEADPHONES, mode = Enums.EqMode.graphic, graphicKey = gone, graphicNames = m.graphicNames, graphicOffset = 0, graphicTotal = m.graphicNames.size.toLong(), on = true, target = m.target))
        settle()
        assertTrue(shows("eq.preset", "Gone (missing)"))
        assertFalse(exists("eq.gpreamp"))
        assertFalse(exists("eq.gband.0"))
    }

    @Test
    fun aFollowingHeadsetTitlesTheProfileSheetAndListsTheDefaultFirst() {
        val (c, shell) = start(eq(bt, EqStage.CORRECTION), prep = { follow(it) })
        assertTrue(shows("eq.profile", "default (off)"))
        tap("eq.profile")
        assertEquals("profile WH-1000XM4", shell.sheet.title)
        assertEquals("DEFAULT (OFF)", shell.sheet.items.first())
        item(0)
        val b = c.eqSets.last()
        assertEquals(EqSlots.DEFAULT, b.str(Tags.EqSet.key))
        assertEquals(EqSlots.EAR_BLUETOOTH, b.str(Tags.EqSet.slot))
        assertEquals(c.device.eq.getValue(EqSlots.EAR_BLUETOOTH).target, b.long(Tags.EqSet.target))
    }

    @Test
    fun parametricRowsFollowOnlyACustomDefault() {
        val (c, _) = start(eq(bt, EqStage.CORRECTION), prep = { follow(it) { it.eqSet(EqSlots.EAR_BLUETOOTH, ints = ints) } })
        assertTrue(shows("eq.profile", "default (custom)"))
        assertTrue(shows("eq.preamp", "-3.0 db"))
        assertTrue(exists("eq.band.0"))
        tap("eq.profile")
        item(2)
        val b = c.eqSets.last()
        assertEquals("custom", b.str(Tags.EqSet.key))
        assertEquals(EqSlots.EAR_BLUETOOTH, b.str(Tags.EqSet.slot))
        assertTrue(shows("eq.profile", "default (custom)"))
        tap("eq.profile")
        item(1)
        assertTrue(shows("eq.profile", "off"))
        assertFalse(exists("eq.preamp"))
    }

    @Test
    fun aFileOrOffDefaultListsNoParametricRows() {
        val (c, _) = start(eq(bt, EqStage.CORRECTION), prep = { follow(it) { it.eqSet(EqSlots.EAR_BLUETOOTH, key = card) } })
        assertTrue(shows("eq.profile", "default (headphone model 001)"))
        assertFalse(exists("eq.preamp"))
        assertFalse(exists("eq.band.0"))
        c.eqHeadset(null)
        c.eqSet(EqSlots.EAR_BLUETOOTH, key = "off")
        c.eqHeadset("WH-1000XM4")
        settle()
        assertTrue(shows("eq.profile", "default (off)"))
        assertFalse(exists("eq.preamp"))
    }

    @Test
    fun graphicRowsFollowOnlyAPresentGraphicDefault() {
        val (c, _) = start(eq(bt, EqStage.CORRECTION), prep = { follow(it) { it.eqSet(EqSlots.EAR_BLUETOOTH, mode = Enums.EqMode.graphic, graphicKey = "flat") } })
        assertTrue(shows("eq.preset", "default (graphic flat)"))
        assertTrue(exists("eq.gpreamp"))
        assertTrue(exists("eq.gband.0"))
        c.eqHeadset(null)
        c.eqSet(EqSlots.EAR_BLUETOOTH, mode = Enums.EqMode.graphic, graphicKey = "off")
        c.eqHeadset("WH-1000XM4")
        settle()
        assertEquals(List(10) { 0 }, c.device.eq.getValue(EqSlots.EAR_BLUETOOTH).gains!!.bands.map { it.gain10 })
        assertTrue(shows("eq.preset", "default (off)"))
        assertFalse(exists("eq.gpreamp"))
        assertFalse(exists("eq.gband.0"))
        val m = c.device.eq.getValue(EqSlots.EAR_BLUETOOTH)
        val missing = listOf(EqName(EqSlots.DEFAULT, "DEFAULT (GRAPHIC GONE (MISSING))")) + m.graphicNames.drop(1)
        c.device = c.device.withEq(Eq(null, m.key, m.names, m.ints, PageInfo(0, m.total, false), slot = EqSlots.EAR_BLUETOOTH, mode = Enums.EqMode.graphic, graphicKey = EqSlots.DEFAULT, graphicNames = missing, gains = m.gains, graphicOffset = 0, graphicTotal = missing.size.toLong(), on = m.on, target = m.target, headset = m.headset))
        settle()
        assertFalse(exists("eq.gpreamp"))
        assertFalse(exists("eq.gband.0"))
    }

    @Test
    fun aHeadsetChangeClosesAnOpenSheetAndAStaleReplyDropsTheEdit() {
        val (c, shell) = start(eq(bt, EqStage.CORRECTION), prep = { follow(it) })
        tap("eq.profile")
        assertTrue(shell.sheet.shown)
        c.eqHeadset("Other")
        settle()
        assertFalse(shell.sheet.shown)
        val toasts = shell.toast.shows
        c.sim.eqHeadset("Third", null)
        tap("eq.enable")
        item(0)
        assertEquals(Err.staleRev, c.failed.last())
        assertEquals(toasts, shell.toast.shows)
        assertTrue(shows("eq.enable", "off"))
    }

    @Test
    fun anOwnPickStillListsTheDefaultFirstAndPickingItFollowsAgain() {
        val (c, shell) = start(eq(bt, EqStage.CORRECTION), prep = { follow(it) })
        tap("eq.profile")
        item(2)
        assertEquals("custom", c.eqSets.last().str(Tags.EqSet.key))
        assertTrue(shows("eq.profile", "custom"))
        tap("eq.profile")
        assertEquals("profile WH-1000XM4", shell.sheet.title)
        assertEquals("DEFAULT (OFF)", shell.sheet.items.first())
        assertEquals(2, shell.sheet.focus)
        item(0)
        val b = c.eqSets.last()
        assertEquals(setOf(Tags.EqSet.slot, Tags.EqSet.key, Tags.EqSet.target), tags(b))
        assertEquals(EqSlots.DEFAULT, b.str(Tags.EqSet.key))
        assertTrue(shows("eq.profile", "default (off)"))
    }

    @Test
    fun theEnableOnAFollowingHeadsetMakesItsOwnPick() {
        val (c, _) = start(eq(bt, EqStage.CORRECTION), prep = { follow(it) })
        assertTrue(shows("eq.profile", "default (off)"))
        tap("eq.enable")
        item(0)
        assertEquals(true, c.eqSets.single().bool(Tags.EqSet.on))
        assertTrue(shows("eq.enable", "on"))
        assertTrue(shows("eq.profile", "off"))
        assertEquals("off", c.device.eq.getValue(EqSlots.EAR_BLUETOOTH).key)
    }

    @Test
    fun aGraphicDefaultPickFromAnOwnPresetSendsGraphicKeyDefault() {
        val (c, shell) = start(eq(bt, EqStage.CORRECTION), prep = { follow(it) { it.eqSet(EqSlots.EAR_BLUETOOTH, mode = Enums.EqMode.graphic, graphicKey = "flat") } })
        tap("eq.preset")
        assertEquals("profile WH-1000XM4", shell.sheet.title)
        assertEquals("DEFAULT (GRAPHIC FLAT)", shell.sheet.items.first())
        item(1)
        assertTrue(shows("eq.preset", "off"))
        tap("eq.preset")
        item(0)
        val b = c.eqSets.last()
        assertEquals(EqSlots.DEFAULT, b.str(Tags.EqSet.graphicKey))
        assertEquals(Enums.EqMode.graphic, b.int(Tags.EqSet.mode))
        assertEquals(EqSlots.EAR_BLUETOOTH, b.str(Tags.EqSet.slot))
        assertTrue(shows("eq.preset", "default (graphic flat)"))
    }

    @Test
    fun aGraphicCustomSavesThenRenamesAndDeletesWithModeGraphic() {
        val gains = List(10) { GraphicBand(4 + 6 * it, 10) }
        val (c, _) = start(eq(), prep = { it.eqSet(EqSlots.HEADPHONES, mode = Enums.EqMode.graphic, gains = gains) })
        assertTrue(shows("eq.preset", "custom"))
        tap("eq.save")
        val save = c.presets.single()
        assertEquals(Enums.EqPresetOp.save, save.int(Tags.EqPreset.op))
        assertEquals(EqSlots.HEADPHONES, save.str(Tags.EqPreset.slot))
        assertEquals(Enums.EqMode.graphic, save.int(Tags.EqPreset.mode))
        assertEquals(1L, save.long(Tags.EqPreset.target))
        assertNull(save.str(Tags.EqPreset.name))
        assertNull(save.node(Tags.EqPreset.gains))
        assertNull(save.node(Tags.EqPreset.ints))
        assertTrue(shows("eq.preset", "Preset 1"))
        assertTrue(exists("eq.delete"))
        assertTrue(barOn("eq.delete"))
        tap("eq.rename")
        enter("Warm")
        val rename = c.presets.last()
        assertEquals(Enums.EqPresetOp.rename, rename.int(Tags.EqPreset.op))
        assertEquals("/data/funeos/eq/graphic/Preset 1.ini", rename.str(Tags.EqPreset.key))
        assertEquals("Warm", rename.str(Tags.EqPreset.name))
        assertEquals(Enums.EqMode.graphic, rename.int(Tags.EqPreset.mode))
        noStageBody(rename)
        assertTrue(shows("eq.preset", "Warm"))
        tap("eq.delete")
        item(0)
        val remove = c.presets.last()
        assertEquals(Enums.EqPresetOp.remove, remove.int(Tags.EqPreset.op))
        assertEquals("/data/funeos/eq/graphic/Warm.ini", remove.str(Tags.EqPreset.key))
        assertEquals(Enums.EqMode.graphic, remove.int(Tags.EqPreset.mode))
        noStageBody(remove)
        assertTrue(shows("eq.preset", "off"))
        assertTrue(barOn("eq.preset"))
    }

    @Test
    fun aStaleSaveIsSilentAndAutoEqSheetsCloseOnANewHeadset() {
        val (c, shell) = start(eq(bt, EqStage.CORRECTION), prep = { follow(it) })
        tap("eq.profile")
        item(2)
        val toasts = shell.toast.shows
        c.sim.eqHeadset("Third", null)
        tap("eq.save")
        assertEquals(Err.staleRev, c.failed.last())
        assertEquals(toasts, shell.toast.shows)
        c.eqHeadset("WH-1000XM4")
        settle()
        tap("eq.import")
        assertTrue(shell.sheet.shown)
        c.eqHeadset("Other")
        settle()
        assertFalse(shell.sheet.shown)
        tap("eq.import")
        item(0)
        assertEquals(listOf("apply", "cancel"), shell.sheet.items)
        c.eqHeadset("WH-1000XM4")
        settle()
        assertFalse(shell.sheet.shown)
        tap("eq.import")
        item(1)
        settle(1500)
        rule.onNodeWithTag("aeq.field").performClick()
        rule.onNodeWithTag("aeq.field").performTextInput("hd 650")
        settle(300)
        tap("aeq.row.Sennheiser HD 650")
        assertEquals(listOf("apply", "cancel"), shell.sheet.items)
        c.eqHeadset("Other")
        settle()
        assertFalse(shell.sheet.shown)
        assertEquals(toasts, shell.toast.shows)
    }

    @Test
    fun autoEqApplyOnTheCorrectionSlotSavesAPresetThere() {
        val (c, _) = start(eq(wired, EqStage.CORRECTION))
        tap("eq.import")
        item(0)
        item(0)
        val save = c.presets.last()
        assertEquals(Enums.EqPresetOp.save, save.int(Tags.EqPreset.op))
        assertEquals(EqSlots.EAR_HEADPHONES, save.str(Tags.EqPreset.slot))
        assertEquals(c.device.eq.getValue(EqSlots.EAR_HEADPHONES).target, save.long(Tags.EqPreset.target))
        assertNull(save.int(Tags.EqPreset.output))
        tap("eq.rename")
        enter("Mine")
        val rename = c.presets.last()
        assertEquals(Enums.EqPresetOp.rename, rename.int(Tags.EqPreset.op))
        assertEquals("Mine", rename.str(Tags.EqPreset.name))
        noStageBody(rename)
        tap("eq.delete")
        item(0)
        val remove = c.presets.last()
        assertEquals(Enums.EqPresetOp.remove, remove.int(Tags.EqPreset.op))
        assertNull(remove.str(Tags.EqPreset.slot))
        assertNull(remove.long(Tags.EqPreset.target))
        assertNull(remove.int(Tags.EqPreset.output))
    }

    @Test
    fun aStageSessionSendsSlotAndTargetOnEveryEdit() {
        val (c, _) = start(eq(), prep = { it.eqSet(EqSlots.HEADPHONES, ints = ints) })
        tap("eq.profile")
        item(1)
        tap("eq.preamp")
        enter("-6")
        assertEquals(2, c.eqSets.size)
        for (b in c.eqSets) {
            assertEquals(EqSlots.HEADPHONES, b.str(Tags.EqSet.slot))
            assertEquals(1L, b.long(Tags.EqSet.target))
            assertNull(b.int(Tags.EqSet.output))
        }
    }

    @Test
    fun aTwoSlotSessionHasOneEqPageAndSendsSlotWithoutTarget() {
        val (c, shell) = start(listOf(Page.Home, Page.Settings), minor = 14, prep = { it.eqSet(EqSlots.HEADPHONES, ints = ints) })
        tap("pivot.effects")
        settle(500)
        tap("set.eqEar")
        assertEquals(Page.Settings, shell.nav.top)
        tap("set.eq")
        assertEquals(Page.Eq(wired, EqStage.USER), shell.nav.top)
        assertFalse(exists("eq.enable"))
        assertTrue(exists("eq.mode"))
        val m = c.device.eq.getValue(EqSlots.HEADPHONES)
        c.device = c.device.copy(eq = c.device.eq + (EqSlots.HEADPHONES to m.copy(target = 5)))
        settle()
        tap("eq.profile")
        item(1)
        tap("eq.preamp")
        enter("-6")
        assertEquals(2, c.eqSets.size)
        for (b in c.eqSets) {
            assertEquals(EqSlots.HEADPHONES, b.str(Tags.EqSet.slot))
            assertNull(b.long(Tags.EqSet.target))
            assertNull(b.bool(Tags.EqSet.on))
            assertNull(b.int(Tags.EqSet.output))
        }
    }

    @Test
    fun aTwoSlotSessionReadsOffForADisabledStage() {
        val (c, _) = start(eq(), app = LINK_FEATURES and Groups.mask("eqStages").inv(), prep = { it.eqSet(EqSlots.HEADPHONES, ints = ints) })
        assertTrue(shows("eq.profile", "custom"))
        val other = object : net.seilent.funecomp.sim.Out {
            override fun push(type: Int, payload: ByteArray) {}
        }
        c.sim.linked(other, LINK_FEATURES)
        c.sim.request(Msg.EQ_SET, Node.decode("EQ_SET", Req.eqSet(slot = EqSlots.HEADPHONES, on = false, target = 1)), other)
        val two = object : net.seilent.funecomp.sim.Out {
            val eqs = ArrayList<ByteArray>()
            override fun push(type: Int, payload: ByteArray) {
                if (type == Msg.EQ) eqs += payload
            }
        }
        c.sim.linked(two, LINK_FEATURES and Groups.mask("eqStages").inv())
        c.sim.greet(two)
        two.eqs.forEach { c.push(Msg.EQ, it) }
        settle()
        assertTrue(shows("eq.profile", "off"))
        assertFalse(exists("eq.preamp"))
    }

    @Test
    fun anOutputSessionOnAGraphicDeviceHasNoModeRow() {
        start(eq(), app = LINK_FEATURES and Groups.mask("eqSlots", "eqStages").inv())
        assertTrue(exists("eq.profile"))
        assertFalse(exists("eq.mode"))
    }

    @Test
    fun anOutputSessionSendsOutputAndHasNoModeRow() {
        val (c, _) = start(eq(), minor = 11, prep = { it.eqSet(EqSlots.HEADPHONES, ints = ints) })
        assertFalse(exists("eq.mode"))
        assertFalse(exists("eq.enable"))
        tap("eq.profile")
        item(1)
        tap("eq.preamp")
        enter("-6")
        assertEquals(2, c.eqSets.size)
        for (b in c.eqSets) {
            assertEquals(wired, b.int(Tags.EqSet.output))
            assertNull(b.str(Tags.EqSet.slot))
            assertNull(b.long(Tags.EqSet.target))
        }
    }
}
