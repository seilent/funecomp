package net.seilent.funecomp

import net.seilent.funecomp.art.Memo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoTest {
    @Test
    fun sameKeyDecodesOnce() {
        val m = Memo<String>()
        var made = 0
        val make = { made++; "art $made" }
        assertEquals("art 1", m.get("utada@1", make))
        assertEquals("art 1", m.get("utada@1", make))
        assertEquals(1, made)
        assertEquals("art 2", m.get("utada@2", make))
        assertEquals("art 3", m.get("radiohead@1", make))
        assertEquals("art 4", m.get("utada@2", make))
        assertEquals(4, made)
    }

    @Test
    fun alternatingArtistsDetectOnceEach() {
        val faces = Memo<String>(64)
        var detected = 0
        for (key in listOf("konoco@1", "azki@1", "konoco@1", "azki@1", "konoco@1")) faces.get(key) { detected++; key }
        assertEquals(2, detected)
    }

    @Test
    fun capacityDropsTheLeastRecent() {
        val m = Memo<String>(2)
        var made = 0
        m.get("a") { made++; "a" }
        m.get("b") { made++; "b" }
        m.get("a") { made++; "a" }
        m.get("c") { made++; "c" }
        m.get("a") { made++; "a" }
        assertEquals(3, made)
        m.get("b") { made++; "b" }
        assertEquals(4, made)
    }

    @Test
    fun clearDropsEverything() {
        val m = Memo<String>(2)
        var made = 0
        m.get("a") { made++; "a" }
        m.get("b") { made++; "b" }
        m.clear()
        m.get("a") { made++; "a" }
        m.get("b") { made++; "b" }
        assertEquals(4, made)
    }

    @Test
    fun failedDecodeKeepsNothing() {
        val m = Memo<String>()
        assertEquals("a", m.get("a@1") { "a" })
        assertNull(m.get("b@1") { null })
        var made = 0
        assertEquals("b", m.get("b@1") { made++; "b" })
        assertEquals(1, made)
        assertEquals("b", m.get("b@1") { made++; "c" })
        assertEquals(1, made)
    }
}
