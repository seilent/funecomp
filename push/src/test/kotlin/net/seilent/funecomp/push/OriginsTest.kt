package net.seilent.funecomp.push

import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funekit.artist.ImgOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OriginsTest {
    @Test
    fun kitOriginsAreTheWireOrigins() {
        assertEquals(Enums.ImgOrigin.names, ImgOrigin.entries.associate { it.id to it.word })
        for ((id, word) in Enums.ImgOrigin.names) {
            assertEquals(word, Origins.of(id)?.word)
            assertEquals(id, Origins.wire(Origins.of(id)))
        }
        assertEquals(ImgOrigin.LIBRARY, Origins.of(Enums.ImgOrigin.library))
        assertEquals(ImgOrigin.PHONE, Origins.of(Enums.ImgOrigin.phone))
        assertNull(Origins.of(null))
        assertNull(Origins.wire(null))
    }
}
