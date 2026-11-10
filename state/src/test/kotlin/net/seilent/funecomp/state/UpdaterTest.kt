package net.seilent.funecomp.state

import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.HelloReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdaterTest {
    private fun hello(features: Long) = HelloReply(1, 17, "v1", "t", 240, 320, 240, 320, 16384, 30_000, 5_000, features, 1, 1, 4)

    private val on = DeviceState().withHello(hello(Groups.all))

    private val off = DeviceState().withHello(hello(Groups.all and Groups.mask("update").inv()))

    private val noApp = hello(Groups.all and Groups.mask("appPkg").inv())

    @Test
    fun theHelloSetsTheUpdater() {
        assertEquals(true, on.updater)
        assertEquals(false, off.updater)
        assertEquals(true, off.withHello(hello(Groups.all)).updater)
    }

    @Test
    fun aDropKeepsTheLastAnswer() {
        assertEquals(false, off.unlinked().updater)
        assertEquals(true, on.unlinked().updater)
        assertNull(off.unlinked().hello)
    }

    @Test
    fun aFreshDeviceHasNoAnswer() {
        assertNull(DeviceState().updater)
    }

    @Test
    fun aLink17HelloSetsTheInstaller() {
        assertEquals(true, on.installer)
        assertEquals(false, DeviceState().withHello(noApp).installer)
        assertNull(DeviceState().withHello(noApp.copy(minor = 16)).installer)
    }

    @Test
    fun aDropKeepsTheInstallerAnswer() {
        assertEquals(false, DeviceState().withHello(noApp).unlinked().installer)
        assertEquals(true, on.unlinked().installer)
    }

    @Test
    fun aFreshDeviceHasNoInstallerAnswer() {
        assertNull(DeviceState().installer)
    }
}
