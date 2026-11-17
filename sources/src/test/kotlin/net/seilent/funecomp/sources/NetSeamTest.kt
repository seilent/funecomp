package net.seilent.funecomp.sources

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI

class NetSeamTest {
    @Test
    fun theTestJvmSendsEveryHostButLoopbackToADeadProxy() {
        for (url in listOf("https://musicbrainz.org/ws/2/", "http://lrclib.net/api/get")) {
            val p = ProxySelector.getDefault().select(URI(url)).single()
            assertEquals(url, Proxy.Type.HTTP, p.type())
            val a = p.address() as InetSocketAddress
            assertEquals(url, "127.0.0.1" to 9, a.hostString to a.port)
        }
        assertEquals(Proxy.NO_PROXY, ProxySelector.getDefault().select(URI("http://127.0.0.1:8080/big")).single())
    }
}
