package net.seilent.funecomp.state

import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.SvcInfo
import net.seilent.funecomp.proto.SvcState
import net.seilent.funecomp.proto.gen.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AccountsTest {
    private fun hello(features: Long) = HelloReply(1, 17, "v1", "t", 240, 320, 240, 320, 16384, 30_000, 5_000, features, 1, 1, 4)

    private val both = Groups.all or Groups.mask("svcLogin", "svcAccount")

    private val on = DeviceState().withHello(hello(both))

    private fun state(vararg s: SvcInfo) = SvcState(s.toList()).encode()

    private fun DeviceState.push(payload: ByteArray) = reduce(Msg.SVC_STATE, Node.decode("SVC_STATE", payload), 0)

    @Test
    fun signedInReadsAbsentFalseAndTrue() {
        val d = on.push(state(SvcInfo("a", "A"), SvcInfo("b", "B", signedIn = false), SvcInfo("c", "C", signedIn = true)))
        assertEquals(listOf(null, false, true), listOf("a", "b", "c").map { d.accounts!!.getValue(it).signedIn })
    }

    @Test
    fun theAccountReadsOnlyWhileSignedIn() {
        val d = on.push(state(SvcInfo("a", "A", signedIn = true, account = "Night Owl"), SvcInfo("b", "B", signedIn = false, account = "Night Owl"), SvcInfo("c", "C", account = "Night Owl"), SvcInfo("d", "D", signedIn = true, account = "")))
        assertEquals(listOf("Night Owl", null, null, null), listOf("a", "b", "c", "d").map { d.accounts!!.getValue(it).account })
        assertFalse("Night Owl" in d.accounts!!.getValue("a").toString())
    }

    @Test
    fun downloadsReadsAbsentAndZeroApart() {
        val d = on.push(state(SvcInfo("a", "A", signedIn = true), SvcInfo("b", "B", signedIn = true, downloads = 0), SvcInfo("c", "C", signedIn = true, downloads = 12345)))
        assertEquals(listOf(null, 0L, 12345L), listOf("a", "b", "c").map { d.accounts!!.getValue(it).downloads })
    }

    @Test
    fun aPushReplacesTheWholeList() {
        val d = on.push(state(SvcInfo("a", "A", signedIn = true), SvcInfo("b", "B", signedIn = true)))
        val e = d.push(state(SvcInfo("b", "B", signedIn = false)))
        assertEquals(mapOf("b" to SvcAccount(false)), e.accounts)
        assertEquals(emptyMap<String, SvcAccount>(), e.push(state()).accounts)
    }

    @Test
    fun noStateYetIsApartFromAnEmptyState() {
        assertNull(on.accounts)
        assertEquals(emptyMap<String, SvcAccount>(), on.push(state()).accounts)
    }

    @Test
    fun aNewHelloAndADropClearIt() {
        val d = on.push(state(SvcInfo("a", "A", signedIn = true)))
        assertNull(d.withHello(hello(both)).accounts)
        val u = d.unlinked()
        assertNull(u.accounts)
        assertNull(u.hello)
    }

    @Test
    fun noAccountWithoutBit35OrBit32OnTheDevice() {
        val payload = state(SvcInfo("a", "A", signedIn = true))
        assertNull(DeviceState().withHello(hello(Groups.all and Groups.mask("svcAccount").inv() or Groups.mask("svcLogin"))).push(payload).accounts)
        assertNull(DeviceState().withHello(hello(Groups.mask("svcAccount"))).push(payload).accounts)
        assertNull(DeviceState().push(payload).accounts)
    }
}
