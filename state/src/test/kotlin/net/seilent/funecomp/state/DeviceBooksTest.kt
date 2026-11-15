package net.seilent.funecomp.state

import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.HelloReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceBooksTest {
    private val bit = Groups.mask("books")

    private fun hello(features: Long, minor: Int = 21) = HelloReply(1, minor, "v1", "t", 240, 320, 240, 320, 16384, 30_000, 5_000, features, 1, 1, 4)

    @Test
    fun booksNeedsBit48OnBothSides() {
        assertTrue(DeviceState().withHello(hello(Groups.all), bit).books)
        assertFalse(DeviceState().withHello(hello(Groups.all and bit.inv()), bit).books)
        assertFalse(DeviceState().withHello(hello(Groups.all), Groups.all and bit.inv()).books)
        assertFalse(DeviceState().withHello(hello(0), 0).books)
        assertFalse(DeviceState().books)
    }

    @Test
    fun theBitDecidesAtAnyMinor() {
        for (minor in listOf(20, 22)) assertTrue(DeviceState().withHello(hello(bit, minor), bit).books)
    }

    @Test
    fun aMinor21HelloWithoutBit48HasNoBooks() {
        val d = DeviceState().withHello(hello(Groups.all and bit.inv()), Groups.all)
        assertEquals(21, d.hello!!.minor)
        assertFalse(d.books)
    }

    @Test
    fun aDropTakesBooksAway() {
        val d = DeviceState().withHello(hello(bit), bit)
        assertTrue(d.books)
        assertFalse(d.unlinked().books)
    }
}
