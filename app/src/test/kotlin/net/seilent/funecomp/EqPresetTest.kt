package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.EqName
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.Profile
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.proto.gen.Tags
import net.seilent.funecomp.state.AutoEq
import net.seilent.funecomp.state.AutoEqResult
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class EqPresetTest {
    @get:Rule
    val rule = createComposeRule()

    private val wired = Enums.EqOutput.wired
    private val bt = Enums.EqOutput.bt
    private val hd650 = "/data/funeos/eq/Sennheiser HD 650.txt"
    private fun eqPage(output: Int = wired) = listOf(Page.Home, Page.Settings, Page.Eq(output))

    private fun start(
        pages: List<Page> = eqPage(),
        minor: Int = Proto.minor,
        cardEq: List<String> = emptyList(),
        faults: List<String> = emptyList(),
        prep: (SimControl) -> Unit = {},
        wrap: (SimControl) -> Control = { it },
    ): Pair<SimControl, Shell> {
        val c = SimControl("ap80", minor = minor, faults = faults, cardEq = cardEq)
        prep(c)
        val control = wrap(c)
        val shell = Shell(Nav(pages))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE), control, shell) }
        }
        settle(1000)
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

    private fun importFile() {
        tap("eq.import")
        item(0)
    }

    private fun enter(text: String) {
        rule.onNodeWithTag("entry").performTextClearance()
        rule.onNodeWithTag("entry").performTextInput(text)
        rule.onNodeWithTag("entry").performImeAction()
        settle()
    }

    private fun toast(shell: Shell) = shell.toast.artist to shell.toast.title

    private fun saves(c: SimControl) = c.presets.filter { it.int(Tags.EqPreset.op) == Enums.EqPresetOp.save }

    private fun replaces(c: SimControl) = c.presets.map { it.bool(Tags.EqPreset.replace) }

    private fun profileText(text: String) = rule.onNode(hasText(text, ignoreCase = true) and hasAnyAncestor(hasTestTag("eq.profile"))).assertExists()

    private val eqRow = SemanticsMatcher("eq row") { n ->
        val t = n.config.getOrNull(SemanticsProperties.TestTag) ?: ""
        t in setOf("eq.profile", "eq.save", "eq.delete", "eq.rename", "eq.import", "eq.preamp") || Regex("eq\\.band\\.[0-9]+").matches(t)
    }

    private fun rows() = rule.onAllNodes(eqRow).fetchSemanticsNodes().sortedBy { it.boundsInRoot.top }.map { it.config[SemanticsProperties.TestTag] }

    private fun saved(c: SimControl, name: String, output: Int = wired) =
        c.eqPreset(Enums.EqPresetOp.save, output, null, name, AutoEq.profile((AutoEq.parse(SimControl.HD650, name) as AutoEqResult.Ok).eq), false) { _, _ -> }

    @Test
    fun applyFromSearchSavesOnePresetAndPopsToTheEqPage() {
        val (c, shell) = start()
        tap("eq.import")
        item(1)
        settle(1500)
        rule.onNodeWithTag("aeq.field").performClick()
        rule.onNodeWithTag("aeq.field").performTextInput("hd 650")
        settle(300)
        tap("aeq.row.Sennheiser HD 650")
        assertEquals("Sennheiser HD 650", shell.sheet.title)
        assertEquals(listOf("apply", "cancel"), shell.sheet.items)
        assertEquals(0, shell.sheet.focus)
        assertEquals(listOf("preamp -6.1 db", "10 bands"), shell.sheet.info)
        val eqSets = c.sent.count { it == "EQ_SET" }
        item(0)
        settle(1500)
        assertEquals(1, c.presets.size)
        val f = c.presets.single()
        assertEquals(Enums.EqPresetOp.save, f.int(Tags.EqPreset.op))
        assertEquals(wired, f.int(Tags.EqPreset.output))
        assertEquals("Sennheiser HD 650", f.str(Tags.EqPreset.name))
        assertNull(f.bool(Tags.EqPreset.replace))
        assertEquals(-61, f.node(Tags.EqPreset.ints)?.let { Profile.from(it) }?.preamp10)
        assertEquals(eqSets, c.sent.count { it == "EQ_SET" })
        assertEquals(hd650, c.device.eq.getValue(wired).key)
        assertEquals(Page.Eq(wired), shell.nav.top)
        profileText("Sennheiser HD 650")
    }

    @Test
    fun applyFromFileKeepsTheStack() {
        val (c, shell) = start()
        importFile()
        item(0)
        assertEquals(eqPage(), shell.nav.stack.toList())
        assertEquals(hd650, c.device.eq.getValue(wired).key)
        assertEquals(1, c.presets.size)
    }

    @Test
    fun aSecondImportAsksBeforeReplacingAndKeepSendsNothing() {
        val (c, shell) = start()
        importFile()
        item(0)
        importFile()
        item(0)
        assertEquals("replace preset?", shell.sheet.title)
        assertEquals(listOf("replace preset", "keep preset"), shell.sheet.items)
        assertEquals(1, shell.sheet.focus)
        assertEquals(listOf("Sennheiser HD 650"), shell.sheet.info)
        assertEquals(listOf<Boolean?>(null, null), replaces(c))
        item(1)
        assertEquals(2, c.presets.size)
        c.eqText = SimControl.HD650.replace("Preamp: -6.1 dB", "Preamp: -3.0 dB")
        importFile()
        item(0)
        item(0)
        assertEquals(listOf(null, null, null, true), replaces(c))
        assertEquals(-30, c.device.eq.getValue(wired).ints?.preamp10)
        assertEquals(hd650, c.device.eq.getValue(wired).key)
        assertFalse(shell.sheet.shown)
    }

    @Test
    fun aCardClashOffersThatProfileOrATypedName() {
        val (c, shell) = start(cardEq = listOf("sennheiser hd 650"))
        importFile()
        item(0)
        assertEquals("Sennheiser HD 650", shell.sheet.title)
        assertEquals(listOf("select that profile", "save as"), shell.sheet.items)
        assertEquals(listOf("name taken"), shell.sheet.info)
        assertEquals(0, shell.sheet.focus)
        assertTrue(replaces(c).none { it == true })
        val eqSets = c.sent.count { it == "EQ_SET" }
        item(0)
        assertEquals(eqSets + 1, c.sent.count { it == "EQ_SET" })
        assertEquals("autoeq/sennheiser hd 650.txt", c.device.eq.getValue(wired).key)
        profileText("sennheiser hd 650")
        assertEquals(1, c.presets.size)
    }

    @Test
    fun saveAsRefusesABadNameAndSavesATypedOne() {
        val (c, shell) = start(cardEq = listOf("Sennheiser HD 650"))
        importFile()
        item(0)
        item(1)
        assertEquals("save preset", shell.sheet.title)
        enter("custom")
        assertEquals("save preset" to "name not allowed", toast(shell))
        assertTrue(shell.sheet.shown)
        assertEquals(1, c.presets.size)
        enter("HD 650 mine")
        assertEquals(2, c.presets.size)
        assertEquals("HD 650 mine", c.presets.last().str(Tags.EqPreset.name))
        assertNull(c.presets.last().bool(Tags.EqPreset.replace))
        assertEquals("/data/funeos/eq/HD 650 mine.txt", c.device.eq.getValue(wired).key)
        assertFalse(shell.sheet.shown)
    }

    @Test
    fun saveAsFromSearchPopsOnOk() {
        val (c, shell) = start(pages = eqPage() + Page.AutoEq(wired), cardEq = listOf("Sennheiser HD 650"))
        rule.onNodeWithTag("aeq.field").performClick()
        rule.onNodeWithTag("aeq.field").performTextInput("hd 650")
        settle(300)
        tap("aeq.row.Sennheiser HD 650")
        item(0)
        item(1)
        enter("Sennheiser HD 650 mine")
        settle(1500)
        assertEquals(Page.Eq(wired), shell.nav.top)
        assertEquals("/data/funeos/eq/Sennheiser HD 650 mine.txt", c.device.eq.getValue(wired).key)
    }

    @Test
    fun aNameOnBothAPresetAndACardTakesThePreset() {
        val (c, shell) = start(prep = { c ->
            saved(c, "sennheiser HD 650")
            val m = c.device.eq.getValue(wired)
            c.device = c.device.copy(eq = c.device.eq + (wired to m.copy(names = m.names + EqName("autoeq/Sennheiser HD 650.txt", "Sennheiser HD 650"))))
        })
        importFile()
        item(0)
        assertEquals("replace preset?", shell.sheet.title)
        item(0)
        assertEquals(listOf(null, null, true), replaces(c))
        assertEquals("/data/funeos/eq/sennheiser HD 650.txt", c.device.eq.getValue(wired).key)
    }

    @Test
    fun aTwelveWithNothingListedToastsNameTaken() {
        val (c, shell) = start(faults = listOf("eq-hidden=Sennheiser HD 650"))
        importFile()
        item(0)
        assertFalse(shell.sheet.shown)
        assertEquals("preset not saved" to "name taken", toast(shell))
        assertEquals(1, c.presets.size)
        assertEquals(listOf(Err.exists), c.failed)
    }

    @Test
    fun theBluetoothPivotSavesToBluetooth() {
        val (c, _) = start(pages = eqPage(bt))
        importFile()
        item(0)
        assertEquals(bt, c.presets.single().int(Tags.EqPreset.output))
        assertEquals(hd650, c.device.eq.getValue(bt).key)
        assertEquals("off", c.device.eq.getValue(wired).key)
    }

    @Test
    fun tooManyPresetsToasts() {
        val (c, shell) = start(prep = { c -> (1..200).forEach { saved(c, "P$it") } })
        importFile()
        item(0)
        assertEquals("too many presets" to "Sennheiser HD 650", toast(shell))
        assertEquals(listOf(Err.noSpace), c.failed)
    }

    private fun oldConfirm(c: SimControl, shell: Shell) {
        importFile()
        assertEquals(listOf("cancel"), shell.sheet.items)
        assertEquals(listOf("preamp -6.1 db", "10 bands", "device has no eq presets"), shell.sheet.info)
        item(0)
        assertTrue(c.presets.isEmpty())
        assertFalse("EQ_PRESET" in c.sent)
    }

    @Test
    fun aMinor4PeerKeepsTheCancelOnlyConfirm() {
        val (c, shell) = start(minor = 4)
        oldConfirm(c, shell)
    }

    @Test
    fun aMinor5PeerWithoutTheBitKeepsTheCancelOnlyConfirm() {
        val (c, shell) = start(minor = 5, faults = listOf("features=${Groups.all and Groups.mask("eqPresets").inv()}"))
        assertEquals(5, c.device.hello?.minor)
        oldConfirm(c, shell)
    }

    @Test
    fun anInvalidNameHasNoApply() {
        val (c, shell) = start()
        c.eqFileName = "custom.txt"
        importFile()
        assertEquals(listOf("cancel"), shell.sheet.items)
        assertEquals(listOf("preamp -6.1 db", "10 bands", "invalid name"), shell.sheet.info)
    }

    @Test
    fun presetRowsFollowTheProfileKey() {
        val (c, _) = start(prep = { it.eqSet(wired, key = "custom") })
        assertEquals(listOf("eq.profile", "eq.save", "eq.import", "eq.preamp", "eq.band.0"), rows().take(5))
        saved(c, "HD 650")
        settle()
        assertEquals(listOf("eq.profile", "eq.delete", "eq.rename", "eq.import", "eq.preamp", "eq.band.0"), rows().take(6))
        c.eqSet(wired, key = "autoeq/oratory1990/Headphone Model 007.txt")
        settle()
        assertEquals(listOf("eq.profile", "eq.import", "eq.preamp", "eq.band.0"), rows().take(4))
        c.eqSet(wired, key = "off")
        settle()
        assertEquals(listOf("eq.profile", "eq.import"), rows())
    }

    private fun editGain(c: SimControl, file: String) {
        tap("eq.band.0")
        tap("eq.band.0.gain")
        enter("3.5")
        val e = c.device.eq.getValue(wired)
        assertEquals("custom", e.key)
        assertEquals(35, e.ints?.bands?.first()?.gain10)
        assertTrue(file in c.device.eq.getValue(wired).names.map { it.key })
    }

    @Test
    fun aPresetShowsItsBandsAndAnEditBecomesCustom() {
        val (c, _) = start(prep = { saved(it, "HD 650") })
        rule.onNode(hasText("-6.1 db", ignoreCase = true) and hasAnyAncestor(hasTestTag("eq.preamp"))).assertExists()
        rule.onNode(hasText("ls 105 hz +6.4 db q 0.70", ignoreCase = true) and hasAnyAncestor(hasTestTag("eq.band.0"))).assertExists()
        editGain(c, "/data/funeos/eq/HD 650.txt")
    }

    @Test
    fun aCardFileShowsItsBandsAndAnEditBecomesCustom() {
        val card = "autoeq/oratory1990/Headphone Model 007.txt"
        val (c, _) = start(prep = { it.eqSet(wired, key = card) })
        rule.onNode(hasText("-3.0 db", ignoreCase = true) and hasAnyAncestor(hasTestTag("eq.preamp"))).assertExists()
        rule.onNode(hasText("pk 1 khz -2.0 db q 1.41", ignoreCase = true) and hasAnyAncestor(hasTestTag("eq.band.0"))).assertExists()
        editGain(c, card)
    }

    @Test
    fun noPresetRowsWithoutTheBit() {
        start(minor = 4, prep = { it.eqSet(wired, key = "custom") })
        assertEquals(listOf("eq.profile", "eq.import", "eq.preamp", "eq.band.0"), rows().take(4))
    }

    @Test
    fun savePresetSendsNoNameOrInts() {
        val (c, _) = start(prep = { it.eqSet(wired, key = "custom") })
        tap("eq.save")
        val f = c.presets.single()
        assertEquals(wired, f.int(Tags.EqPreset.output))
        assertNull(f.str(Tags.EqPreset.name))
        assertNull(f.node(Tags.EqPreset.ints))
        assertEquals("/data/funeos/eq/Preset 1.txt", c.device.eq.getValue(wired).key)
        settle()
        assertEquals(listOf("eq.profile", "eq.delete", "eq.rename", "eq.import", "eq.preamp"), rows().take(5))
    }

    @Test
    fun deleteKeepsOrRemovesTheKeyCapturedAtOpen() {
        val (c, shell) = start(prep = {
            saved(it, "HD 650")
            it.eqSet(bt, key = "/data/funeos/eq/HD 650.txt")
        })
        tap("eq.delete")
        assertEquals("delete preset?", shell.sheet.title)
        assertEquals(listOf("delete preset", "keep preset"), shell.sheet.items)
        assertEquals(1, shell.sheet.focus)
        item(1)
        assertEquals(1, c.presets.size)
        tap("eq.delete")
        c.eqSet(wired, key = "custom")
        settle()
        item(0)
        val f = c.presets.last()
        assertEquals(Enums.EqPresetOp.remove, f.int(Tags.EqPreset.op))
        assertEquals("/data/funeos/eq/HD 650.txt", f.str(Tags.EqPreset.key))
        assertEquals("off", c.device.eq.getValue(bt).key)
        assertTrue(c.device.eq.getValue(wired).names.none { it.preset == true })
    }

    @Test
    fun renameRefusesLocallyAndMovesBothOutputs() {
        val (c, shell) = start(prep = {
            saved(it, "HD 650")
            saved(it, "Warm", bt)
            it.eqSet(bt, key = "/data/funeos/eq/HD 650.txt")
        })
        tap("eq.rename")
        assertEquals("rename preset", shell.sheet.title)
        enter("a/b")
        assertEquals("rename preset" to "name not allowed", toast(shell))
        assertTrue(shell.sheet.shown)
        enter("HD 650")
        assertFalse(shell.sheet.shown)
        assertEquals(2, c.presets.size)
        tap("eq.rename")
        enter("WARM")
        assertEquals("rename preset" to "name taken", toast(shell))
        assertEquals(3, c.presets.size)
        assertTrue(shell.sheet.shown)
        assertEquals("rename preset", shell.sheet.title)
        rule.onNodeWithTag("entry").assert(hasText("WARM"))
        enter("hd 650")
        assertEquals("/data/funeos/eq/hd 650.txt", c.device.eq.getValue(wired).key)
        tap("eq.rename")
        enter("Sennheiser")
        val r = c.presets.last()
        assertEquals(Enums.EqPresetOp.rename, r.int(Tags.EqPreset.op))
        assertEquals("/data/funeos/eq/hd 650.txt", r.str(Tags.EqPreset.key))
        assertNull(r.bool(Tags.EqPreset.replace))
        assertEquals("/data/funeos/eq/Sennheiser.txt", c.device.eq.getValue(wired).key)
        assertEquals("/data/funeos/eq/Sennheiser.txt", c.device.eq.getValue(bt).key)
        profileText("Sennheiser")
    }

    @Test
    fun aDeviceRefusalOfTheNameToastsNameNotAllowed() {
        val (_, shell) = start(prep = { saved(it, "HD 650") }, wrap = { c ->
            object : Control by c {
                override fun eqPreset(op: Int, output: Int?, key: String?, name: String?, ints: Profile?, replace: Boolean, done: (Int?, String?) -> Unit) =
                    done(if (name == "Bad") Err.badValue else Err.busy, null)
            }
        })
        tap("eq.rename")
        enter("Bad")
        assertEquals("rename preset" to "name not allowed", toast(shell))
        assertTrue(shell.sheet.shown)
        rule.onNodeWithTag("entry").assert(hasText("Bad"))
        enter("Other")
        assertEquals("preset not renamed" to "HD 650", toast(shell))
        tap("eq.delete")
        item(0)
        assertEquals("preset not deleted" to "HD 650", toast(shell))
    }

    @Test
    fun aPresetSaveFailureKeepsTheSearchPage() {
        val (_, shell) = start(pages = eqPage() + Page.AutoEq(wired), wrap = { c ->
            object : Control by c {
                override fun eqPreset(op: Int, output: Int?, key: String?, name: String?, ints: Profile?, replace: Boolean, done: (Int?, String?) -> Unit) =
                    done(-1, null)
            }
        })
        rule.onNodeWithTag("aeq.field").performClick()
        rule.onNodeWithTag("aeq.field").performTextInput("hd 650")
        settle(300)
        tap("aeq.row.Sennheiser HD 650")
        item(0)
        settle(1500)
        assertEquals(Page.AutoEq(wired), shell.nav.top)
        assertEquals("preset not saved" to "Sennheiser HD 650", toast(shell))
    }
}
