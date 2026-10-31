package net.seilent.funecomp.state

import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.SvcLogin
import net.seilent.funecomp.proto.SvcLoginEnd
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SvcLoginsTest {
    private fun hello(features: Long) = HelloReply(1, 16, "v1", "t", 240, 320, 240, 320, 16384, 30_000, 5_000, features, 1, 1, 4)

    private val on = DeviceState().withHello(hello(Groups.all or Groups.mask("svcLogin")))

    private fun login(seq: Long, code: String = "ABCDE", service: String = "tidal") = SvcLogin(service, "https://link.svcdemo.test/$code", "link.svcdemo.test", code, 300, seq).encode()

    private fun end(seq: Long, result: Int = Enums.SvcLoginResult.done, service: String = "tidal") = SvcLoginEnd(service, seq, result).encode()

    private fun DeviceState.push(type: Int, payload: ByteArray) = reduce(type, Node.decode(Msg.info.getValue(type).name, payload), 0)

    @Test
    fun aNewSeqReplacesTheLoginAndItsEndClosesIt() {
        var d = on.push(Msg.SVC_LOGIN, login(7))
        assertEquals(7L, d.logins.shown?.seq)
        assertTrue(d.logins.shown!!.ok)
        d = d.push(Msg.SVC_LOGIN, login(8, "FGHIJ"))
        assertEquals(8L, d.logins.shown?.seq)
        assertEquals("FGHIJ", d.logins.shown?.userCode)
        d = d.push(Msg.SVC_LOGIN_END, end(7))
        assertEquals(8L, d.logins.shown?.seq)
        d = d.push(Msg.SVC_LOGIN_END, end(8, service = "other"))
        assertEquals(8L, d.logins.shown?.seq)
        d = d.push(Msg.SVC_LOGIN_END, end(8))
        assertNull(d.logins.shown)
        assertTrue(d.logins.open.isEmpty())
    }

    @Test
    fun aSeenLoginIsNotShownAgainUntilUnseen() {
        var d = on.push(Msg.SVC_LOGIN, login(7))
        d = d.copy(logins = d.logins.seen("tidal", 6))
        assertEquals(7L, d.logins.shown?.seq)
        d = d.copy(logins = d.logins.seen("tidal", 7))
        assertNull(d.logins.shown)
        d = d.push(Msg.SVC_LOGIN, login(7))
        assertNull(d.logins.shown)
        d = d.copy(logins = d.logins.unseen())
        assertEquals(7L, d.logins.shown?.seq)
        d = d.copy(logins = d.logins.seen("tidal", 7)).push(Msg.SVC_LOGIN, login(9))
        assertEquals(9L, d.logins.shown?.seq)
    }

    @Test
    fun aBadLinkIsKeptButNeverShown() {
        val d = on.push(Msg.SVC_LOGIN, SvcLogin("tidal", "link.svcdemo.test/ABCDE", "link.svcdemo.test", "ABCDE", 300, 7).encode())
        assertFalse(d.logins.open.getValue("tidal").ok)
        assertNull(d.logins.shown)
        assertNull(d.copy(logins = d.logins.unseen()).logins.shown)
        assertNull(on.push(Msg.SVC_LOGIN, SvcLogin("tidal", "https://link.svcdemo.test/A", null, "A", 300, 7).encode()).logins.shown)
    }

    @Test
    fun withoutBit32OrAServiceOrASeqNothingIsKept() {
        val off = DeviceState().withHello(hello(Groups.all and Groups.mask("svcLogin").inv()))
        assertNull(off.push(Msg.SVC_LOGIN, login(7)).logins.shown)
        assertNull(on.push(Msg.SVC_LOGIN, SvcLogin(null, "https://link.svcdemo.test/A", "link.svcdemo.test", "A", 300, 7).encode()).logins.shown)
        assertNull(on.push(Msg.SVC_LOGIN, SvcLogin("tidal", "https://link.svcdemo.test/A", "link.svcdemo.test", "A", 300, null).encode()).logins.shown)
        val d = on.push(Msg.SVC_LOGIN, login(7))
        assertEquals(7L, d.copy(hello = d.hello!!.copy(features = 0)).push(Msg.SVC_LOGIN_END, end(7)).logins.shown?.seq)
    }

    @Test
    fun aNewHelloDropsTheLogins() {
        val d = on.push(Msg.SVC_LOGIN, login(7))
        assertNull(d.withHello(hello(Groups.all or Groups.mask("svcLogin"))).logins.shown)
    }
}
