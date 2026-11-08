package net.seilent.funecomp

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class FontFileTest {
    @Test
    fun theSemilightFaceIsTheReferenceFile() {
        val md5 = MessageDigest.getInstance("MD5").digest(File("src/main/res/font/selawksl.ttf").readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("b3a62850ef4e907d244369bdd5c23bfc", md5)
    }
}
