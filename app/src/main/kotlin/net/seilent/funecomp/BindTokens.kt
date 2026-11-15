package net.seilent.funecomp

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import net.seilent.funecomp.proto.gen.Proto
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.MessageDigest
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface Seal {
    fun ensure(): Boolean

    fun seal(key: String, token: ByteArray): String

    fun open(key: String, blob: String): ByteArray
}

class KeystoreSeal(private val alias: String) : Seal {
    private fun store() = KeyStore.getInstance(STORE).apply { load(null) }

    private fun secret(): SecretKey = store().getKey(alias, null) as? SecretKey ?: throw UnrecoverableKeyException("no key $alias")

    override fun ensure(): Boolean {
        if (store().containsAlias(alias)) return false
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).apply { init(spec) }.generateKey()
        return true
    }

    override fun seal(key: String, token: ByteArray): String {
        val c = Cipher.getInstance(GCM)
        c.init(Cipher.ENCRYPT_MODE, secret())
        c.updateAAD(key.toByteArray(Charsets.UTF_8))
        val out = c.doFinal(token)
        return Base64.encodeToString(c.iv + out, Base64.NO_WRAP)
    }

    override fun open(key: String, blob: String): ByteArray {
        val b = Base64.decode(blob, Base64.NO_WRAP)
        require(b.size > IV) { "short blob" }
        val c = Cipher.getInstance(GCM)
        c.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(TAG_BITS, b, 0, IV))
        c.updateAAD(key.toByteArray(Charsets.UTF_8))
        return c.doFinal(b, IV, b.size - IV)
    }

    companion object {
        const val STORE = "AndroidKeyStore"
        const val GCM = "AES/GCM/NoPadding"
        const val IV = 12
        const val TAG_BITS = 128
    }
}

object BindTokens {
    const val FILE = "bind"
    private const val ALIAS = "bind"
    private const val LOAD_WAIT_MS = 3000L
    private const val TAG = Remote.TAG

    private val lock = Any()
    private val map = HashMap<String, ByteArray>()
    private val touched = HashSet<String>()
    private var exec: Executor? = null
    private var prefs: SharedPreferences? = null
    private var seal: Seal? = null
    private var loaded: CountDownLatch? = null

    fun key(spec: String): String = if (spec.startsWith(RFCOMM)) RFCOMM + spec.removePrefix(RFCOMM).uppercase() else spec

    fun init(context: Context, seal: Seal? = null, exec: Executor? = null) {
        synchronized(lock) {
            if (this.exec != null) {
                (exec as? ExecutorService)?.shutdownNow()
                return
            }
            val e = exec ?: Executors.newSingleThreadExecutor { Thread(it, "bind").apply { isDaemon = true } }
            this.exec = e
            this.seal = seal ?: KeystoreSeal(ALIAS)
            prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            loaded = CountDownLatch(1)
            run(e) { load() }
            touched.forEach { persist(it) }
        }
    }

    fun put(key: String, token: ByteArray): Boolean {
        if (token.size != Proto.bindTokenBytes) return false
        synchronized(lock) {
            map.put(key, token.copyOf())?.fill(0)
            touched += key
            persist(key)
        }
        return true
    }

    fun get(key: String): ByteArray? {
        val l = synchronized(lock) { loaded }
        if (l != null && !l.await(LOAD_WAIT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "bind tokens not loaded in $LOAD_WAIT_MS ms")
            return null
        }
        return synchronized(lock) { map[key]?.copyOf() }
    }

    fun drop(key: String, sent: ByteArray) = synchronized(lock) {
        val held = map[key] ?: return@synchronized
        if (!MessageDigest.isEqual(held, sent)) return@synchronized
        map.remove(key)?.fill(0)
        touched += key
        persist(key)
    }

    internal fun reset() {
        val e = synchronized(lock) {
            map.values.forEach { it.fill(0) }
            map.clear()
            touched.clear()
            prefs = null
            seal = null
            loaded?.countDown()
            loaded = null
            exec.also { exec = null }
        }
        (e as? ExecutorService)?.let {
            it.shutdownNow()
            it.awaitTermination(LOAD_WAIT_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun run(e: Executor, task: () -> Unit) {
        try {
            e.execute(task)
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun persist(key: String) {
        val e = exec ?: return
        run(e) { write(key, retry = true) }
    }

    private fun write(key: String, retry: Boolean) {
        val (p, s, token) = synchronized(lock) { Triple(prefs ?: return, seal ?: return, map[key]?.copyOf()) }
        if (token == null) {
            p.edit().remove(key).commit()
            return
        }
        val blob = try {
            s.seal(key, token)
        } catch (e: Exception) {
            Log.w(TAG, "bind token seal failed ${e.javaClass.simpleName}")
            if (retry) synchronized(lock) { exec }?.let { run(it) { write(key, retry = false) } }
            null
        } finally {
            token.fill(0)
        }
        if (blob != null) p.edit().putString(key, blob).commit()
    }

    private fun load() {
        try {
            val (p, s) = synchronized(lock) { (prefs ?: return) to (seal ?: return) }
            val made = try {
                s.ensure()
            } catch (e: Exception) {
                Log.w(TAG, "bind key check failed ${e.javaClass.simpleName}")
                false
            }
            val stored = p.all.filterValues { it is String }.mapValues { it.value as String }
            if (made) {
                if (stored.isNotEmpty()) p.edit().apply { stored.keys.forEach { remove(it) } }.commit()
                Log.i(TAG, "bind key made, ${stored.size} stored tokens dropped")
                return
            }
            for ((key, blob) in stored) {
                when (val o = open(s, key, blob)) {
                    is Opened.Token -> {
                        synchronized(lock) { if (key !in touched) map[key] = o.bytes.copyOf() }
                        o.bytes.fill(0)
                    }
                    is Opened.Drop -> p.edit().remove(key).commit()
                    is Opened.Keep -> {}
                }
            }
        } finally {
            synchronized(lock) { loaded }?.countDown()
        }
    }

    private sealed interface Opened {
        class Token(val bytes: ByteArray) : Opened

        object Drop : Opened

        object Keep : Opened
    }

    private fun open(s: Seal, key: String, blob: String, again: Boolean = true): Opened = try {
        val t = s.open(key, blob)
        if (t.size == Proto.bindTokenBytes) {
            Opened.Token(t)
        } else {
            t.fill(0)
            Log.w(TAG, "bind token dropped, ${t.size} bytes")
            Opened.Drop
        }
    } catch (e: Exception) {
        when (e) {
            is AEADBadTagException, is KeyPermanentlyInvalidatedException, is UnrecoverableKeyException, is IllegalArgumentException -> Opened.Drop
            is KeyStoreException, is ProviderException -> if (again) open(s, key, blob, false) else Opened.Keep
            else -> Opened.Keep
        }.also { if (it !is Opened.Token) Log.w(TAG, "bind token ${if (it is Opened.Drop) "dropped" else "kept"}, ${e.javaClass.simpleName}") }
    }

    private const val RFCOMM = "rfcomm:"
}
