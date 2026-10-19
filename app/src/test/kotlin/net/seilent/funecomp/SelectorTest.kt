package net.seilent.funecomp

import net.seilent.funecomp.proto.Row
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.ui.Send
import net.seilent.funecomp.ui.flip
import net.seilent.funecomp.ui.picked
import net.seilent.funecomp.ui.selects
import net.seilent.funecomp.ui.selector
import net.seilent.funecomp.ui.typedMinutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorTest {
    private val volumeStep = Row("volumeStep", "VOLUME STEP", "output", Enums.RowKind.steps, listOf("2", "5", "10"), words = listOf("2%", "5%", "10%"))
    private val crossfeed = Row("crossfeed", "CROSSFEED", "output", Enums.RowKind.choice, listOf("off", "default", "chuMoy", "janMeier"), words = listOf("OFF", "DEFAULT", "CHU MOY", "JAN MEIER"))
    private val usb = Row("usbMode", "USB MODE", "system", Enums.RowKind.draft, listOf("storage", "dock", "audio"), words = listOf("STORAGE", "DOCK", "AUDIO"))
    private val codec = Row("btCodec", "BLUETOOTH CODEC", "output", Enums.RowKind.draft, listOf("auto", "aptx", "aac", "sbc"), words = listOf("AUTO", "APTX", "AAC", "SBC"), flags = listOf(Enums.RowFlag.restart))
    private val quickplay = Row("quickplay", "QUICKPLAY", "display", Enums.RowKind.toggle, listOf("off", "on"), words = listOf("OFF", "ON"))
    private val phone = Row("companion", "PHONE APP", "system", Enums.RowKind.toggle, listOf("off", "on"), words = listOf("OFF", "ON"), flags = listOf(Enums.RowFlag.readOnly))
    private val balance = Row("balance", "BALANCE", "output", Enums.RowKind.range, min = -20, max = 20, step = 1)
    private val clock = Row("dateTime", "DATE/TIME", "system", Enums.RowKind.draft)
    private val steps = listOf("0", "15", "30", "45", "60", "90", "120")
    private val words = listOf("OFF", "15 MIN", "30 MIN", "45 MIN", "60 MIN", "90 MIN", "120 MIN")
    private val sleep = Row("sleep", "SLEEP TIMER", "power", Enums.RowKind.draft, steps, min = 0, max = 999, words = words)
    private val oldSleep = Row("sleep", "SLEEP TIMER", "power", Enums.RowKind.draft, steps, words = words)

    @Test
    fun itemsAreTheValueWordsFocusedOnTheLiveValue() {
        val s = volumeStep.selector("5", 0)
        assertEquals(listOf("2%", "5%", "10%"), s.items)
        assertEquals(1, s.focus)
        assertEquals(emptyList<String>(), s.info)
        assertFalse(s.typed)
        assertEquals(3, crossfeed.selector("janMeier", 0).focus)
    }

    @Test
    fun aLiveValueNotInTheValuesFocusesTheFirst() {
        assertEquals(0, volumeStep.selector("7", 0).focus)
        assertEquals(0, volumeStep.selector(null, 0).focus)
    }

    @Test
    fun wordsLongerThanTheValuesAreIgnored() {
        val r = volumeStep.copy(words = listOf("2%", "5%", "10%", "20%"))
        assertEquals(listOf("2%", "5%", "10%"), r.selector("5", 0).items)
    }

    @Test
    fun whichRowsSelect() {
        assertTrue(volumeStep.selects())
        assertTrue(crossfeed.selects())
        assertTrue(usb.selects())
        assertTrue(codec.selects())
        assertTrue(sleep.selects())
        assertFalse(quickplay.selects())
        assertFalse(phone.selects())
        assertFalse(balance.selects())
        assertFalse(clock.selects())
        assertFalse(volumeStep.copy(flags = listOf(Enums.RowFlag.readOnly)).selects())
    }

    @Test
    fun aPickSendsOnlyWhenItDiffersFromTheLiveValue() {
        assertNull(volumeStep.picked(1, "5", 0))
        assertEquals(Send.Set("volumeStep", "10"), volumeStep.picked(2, "5", 0))
        assertEquals(Send.Set("crossfeed", "chuMoy"), crossfeed.picked(2, "off", 0))
    }

    @Test
    fun aPickReadsTheValueLiveAtPickTime() {
        val opened = volumeStep.selector("5", 0)
        assertEquals(1, opened.focus)
        assertEquals(Send.Set("volumeStep", "5"), volumeStep.picked(opened.focus, "10", 0))
        assertNull(volumeStep.picked(2, "10", 0))
    }

    @Test
    fun draftRowsSendDraftNotSet() {
        assertEquals(Send.Draft(Enums.DraftKind.usbMode, "dock"), usb.picked(1, "storage", 0))
        assertNull(usb.picked(0, "storage", 0))
        assertEquals(Send.Draft(Enums.DraftKind.btCodec, "aac"), codec.picked(2, "auto", 0))
    }

    @Test
    fun aToggleFlipsToTheNextValue() {
        assertEquals(Send.Set("quickplay", "on"), quickplay.flip("off"))
        assertEquals(Send.Set("quickplay", "off"), quickplay.flip("on"))
        assertEquals(Send.Set("quickplay", "off"), quickplay.flip("auto"))
        assertEquals(Send.Set("quickplay", "off"), quickplay.flip(null))
        val three = quickplay.copy(values = listOf("a", "b", "c"))
        assertEquals(Send.Set("quickplay", "a"), three.flip("c"))
    }

    @Test
    fun sleepFocusesTheNearestStepAndShowsTheTimeLeft() {
        val s = sleep.selector(null, 29)
        assertEquals(words + "type value", s.items)
        assertEquals(2, s.focus)
        assertEquals(listOf("29 min left"), s.info)
        assertTrue(s.typed)
        val off = sleep.selector(null, 0)
        assertEquals(0, off.focus)
        assertEquals(emptyList<String>(), off.info)
    }

    @Test
    fun sleepWithoutMaxHasNoTypedItem() {
        val s = oldSleep.selector(null, 0)
        assertEquals(words, s.items)
        assertFalse(s.typed)
        assertFalse(sleep.copy(max = 0).selector(null, 0).typed)
        assertFalse(sleep.copy(min = 5, max = 5).selector(null, 0).typed)
        assertTrue(sleep.copy(min = null).selector(null, 0).typed)
    }

    @Test
    fun aSleepStepPickReArms() {
        assertEquals(Send.Minutes(30), sleep.picked(2, null, 29))
        assertEquals(Send.Minutes(30), sleep.picked(2, null, 30))
        assertEquals(Send.Minutes(15), sleep.picked(1, null, 0))
        assertEquals(Send.Minutes(0), sleep.picked(0, null, 12))
        assertNull(sleep.picked(0, null, 0))
    }

    @Test
    fun typedMinutesInTheRowRange() {
        assertEquals(Send.Minutes(45), sleep.typedMinutes("45", 0))
        assertEquals(Send.Minutes(999), sleep.typedMinutes("999", 0))
        assertEquals(Send.Minutes(7), sleep.typedMinutes(" 07 ", 3))
        assertEquals(Send.Minutes(0), sleep.typedMinutes("0", 12))
        assertNull(sleep.typedMinutes("0", 0))
        val refuse = Send.Refuse("sleep timer", "0 to 999")
        for (t in listOf("1000", "-1", "4.5", "abc", "", " ", "99999999999")) assertEquals(t, refuse, sleep.typedMinutes(t, 0))
    }

    @Test
    fun typedMinutesTakeTheMaxFromTheRow() {
        val r = sleep.copy(max = 200)
        assertEquals(Send.Minutes(200), r.typedMinutes("200", 0))
        assertEquals(Send.Refuse("sleep timer", "0 to 200"), r.typedMinutes("201", 0))
        assertEquals(Send.Refuse("sleep timer", "0 to 200"), r.typedMinutes("999", 0))
    }
}
