package net.seilent.funecomp

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException

class BindKeystoreTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "bindtest"
    private val key = "rfcomm:AA:BB:CC:00:00:01"
    private val token = ByteArray(16) { (0x10 + it).toByte() }

    private fun store() = KeyStore.getInstance(KeystoreSeal.STORE).apply { load(null) }

    @Before
    fun setUp() {
        store().deleteEntry(alias)
        BindTokens.reset()
    }

    @After
    fun tearDown() {
        BindTokens.reset()
        store().deleteEntry(alias)
    }

    @Test
    fun theKeystoreSealRoundTripsAndRefusesATamperedBlob() {
        val seal = KeystoreSeal(alias)
        assertTrue(seal.ensure())
        assertFalse(seal.ensure())
        val blob = seal.seal(key, token)
        assertArrayEquals(token, seal.open(key, blob))
        val b = android.util.Base64.decode(blob, android.util.Base64.NO_WRAP)
        b[b.size - 1] = (b[b.size - 1] + 1).toByte()
        val tampered = android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
        assertTrue(runCatching { seal.open(key, tampered) }.exceptionOrNull() is AEADBadTagException)
        assertTrue(runCatching { seal.open("rfcomm:AA:BB:CC:00:00:02", blob) }.exceptionOrNull() is AEADBadTagException)
    }

    @Test
    fun aTamperedBlobReadsNullAndADeletedAliasDropsTheBlobs() {
        val prefs = context.getSharedPreferences(BindTokens.FILE, Context.MODE_PRIVATE)
        val kept = prefs.all
        try {
            prefs.edit().clear().commit()
            val seal = KeystoreSeal(alias)
            seal.ensure()
            val blob = seal.seal(key, token)
            val b = android.util.Base64.decode(blob, android.util.Base64.NO_WRAP)
            b[b.size - 1] = (b[b.size - 1] + 1).toByte()
            prefs.edit().putString(key, android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)).commit()
            val exec = Executors.newSingleThreadExecutor()
            BindTokens.init(context, seal, exec)
            assertNull(BindTokens.get(key))
            exec.submit {}.get(5, TimeUnit.SECONDS)
            assertFalse(prefs.contains(key))
            BindTokens.reset()
            prefs.edit().putString(key, seal.seal(key, token)).commit()
            store().deleteEntry(alias)
            val again = Executors.newSingleThreadExecutor()
            BindTokens.init(context, KeystoreSeal(alias), again)
            assertNull(BindTokens.get(key))
            again.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(emptySet<String>(), prefs.all.keys)
            assertTrue(store().containsAlias(alias))
        } finally {
            BindTokens.reset()
            prefs.edit().clear().apply { kept.forEach { (k, v) -> if (v is String) putString(k, v) } }.commit()
        }
    }
}
