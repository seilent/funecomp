package net.seilent.funecomp

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class JvmSeal : Seal {
    @Volatile private var secret: SecretKey? = null
    @Volatile var sealFails = 0
    @Volatile var openFails: (() -> Exception)? = null
    @Volatile var sealing: (() -> Unit)? = null

    override fun ensure(): Boolean {
        if (secret != null) return false
        secret = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return true
    }

    override fun seal(key: String, token: ByteArray): String {
        sealing?.invoke()
        if (sealFails > 0) {
            sealFails--
            throw IllegalStateException("seal")
        }
        val iv = ByteArray(IV).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance(KeystoreSeal.GCM)
        c.init(Cipher.ENCRYPT_MODE, secret!!, GCMParameterSpec(KeystoreSeal.TAG_BITS, iv))
        c.updateAAD(key.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + c.doFinal(token))
    }

    override fun open(key: String, blob: String): ByteArray {
        openFails?.let { throw it() }
        val b = Base64.getDecoder().decode(blob)
        require(b.size > IV) { "short blob" }
        val c = Cipher.getInstance(KeystoreSeal.GCM)
        c.init(Cipher.DECRYPT_MODE, secret!!, GCMParameterSpec(KeystoreSeal.TAG_BITS, b, 0, IV))
        c.updateAAD(key.toByteArray(Charsets.UTF_8))
        return c.doFinal(b, IV, b.size - IV)
    }

    private companion object {
        const val IV = 12
    }
}
