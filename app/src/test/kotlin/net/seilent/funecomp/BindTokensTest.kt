package net.seilent.funecomp

import android.content.Context
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.ProviderException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BindTokensTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(BindTokens.FILE, Context.MODE_PRIVATE)
    private val a = "rfcomm:AA:BB:CC:00:00:01"
    private val b = "rfcomm:AA:BB:CC:00:00:02"
    private val t1 = ByteArray(16) { (it + 1).toByte() }
    private val t2 = ByteArray(16) { (0x40 + it).toByte() }

    private class Steps : Executor {
        val tasks = ArrayDeque<Runnable>()

        override fun execute(r: Runnable) {
            tasks += r
        }

        fun run() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    @After
    fun tearDown() = BindTokens.reset()

    private fun ready(seal: JvmSeal = JvmSeal().apply { ensure() }): Pair<JvmSeal, Steps> {
        val steps = Steps()
        BindTokens.init(app, seal, steps)
        steps.run()
        return seal to steps
    }

    @Test
    fun putGetAndDrop() {
        val (_, steps) = ready()
        assertTrue(BindTokens.put(a, t1))
        assertArrayEquals(t1, BindTokens.get(a))
        assertNull(BindTokens.get(b))
        BindTokens.drop(a, t1)
        assertNull(BindTokens.get(a))
        steps.run()
        assertFalse(prefs.contains(a))
    }

    @Test
    fun putAndGetCopy() {
        ready()
        val given = t1.copyOf()
        BindTokens.put(a, given)
        given.fill(0)
        assertArrayEquals(t1, BindTokens.get(a))
        BindTokens.get(a)!!.fill(0)
        assertArrayEquals(t1, BindTokens.get(a))
    }

    @Test
    fun aStaleDropKeepsTheNewerToken() {
        ready()
        BindTokens.put(a, t1)
        BindTokens.put(a, t2)
        BindTokens.drop(a, t1)
        assertArrayEquals(t2, BindTokens.get(a))
    }

    @Test
    fun aTokenOfAnotherLengthIsRefused() {
        ready()
        assertFalse(BindTokens.put(a, ByteArray(15)))
        assertFalse(BindTokens.put(a, ByteArray(17)))
        assertNull(BindTokens.get(a))
    }

    @Test
    fun aStoredTokenLoadsAndABlobUnderAnotherKeyOrCorruptIsDropped() {
        val seal = JvmSeal().apply { ensure() }
        prefs.edit().putString(a, seal.seal(a, t1)).putString(b, seal.seal(a, t2)).putString("tcp:127.0.0.1:1", "%%%").putString("tcp:127.0.0.1:2", "AAAAAAAAAAAAAAAAAAAAAAAA").commit()
        ready(seal)
        assertArrayEquals(t1, BindTokens.get(a))
        assertNull(BindTokens.get(b))
        assertNull(BindTokens.get("tcp:127.0.0.1:1"))
        assertNull(BindTokens.get("tcp:127.0.0.1:2"))
        assertEquals(setOf(a), prefs.all.keys)
    }

    @Test
    fun aKeystoreFaultReadsNullAndKeepsTheBlob() {
        val seal = JvmSeal().apply { ensure() }
        val blob = seal.seal(a, t1)
        prefs.edit().putString(a, blob).commit()
        seal.openFails = { ProviderException("keystore") }
        ready(seal)
        assertNull(BindTokens.get(a))
        assertEquals(blob, prefs.getString(a, null))
    }

    @Test
    fun aFailingSealKeepsTheMemoryCopyThrowsNothingAndWritesAtTheNextMutation() {
        val (seal, steps) = ready()
        seal.sealFails = 2
        assertTrue(BindTokens.put(a, t1))
        steps.run()
        assertArrayEquals(t1, BindTokens.get(a))
        assertFalse(prefs.contains(a))
        BindTokens.put(a, t1)
        steps.run()
        assertArrayEquals(t1, seal.open(a, prefs.getString(a, null)!!))
    }

    @Test
    fun aFailingSealIsTriedOnceMoreAsTheNextTask() {
        val (seal, steps) = ready()
        seal.sealFails = 1
        BindTokens.put(a, t1)
        steps.run()
        assertArrayEquals(t1, seal.open(a, prefs.getString(a, null)!!))
    }

    @Test
    fun writesKeepTheCallOrder() {
        val seal = JvmSeal().apply { ensure() }
        val exec = Executors.newSingleThreadExecutor()
        BindTokens.init(app, seal, exec)
        exec.submit {}.get(5, TimeUnit.SECONDS)
        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)
        seal.sealing = {
            parked.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        BindTokens.put(a, t1)
        assertTrue(parked.await(5, TimeUnit.SECONDS))
        BindTokens.drop(a, t1)
        BindTokens.put(a, t2)
        seal.sealing = null
        release.countDown()
        exec.submit {}.get(5, TimeUnit.SECONDS)
        assertArrayEquals(t2, BindTokens.get(a))
        assertArrayEquals(t2, seal.open(a, prefs.getString(a, null)!!))
        exec.shutdownNow()
    }

    @Test
    fun aFirstInstallKeepsTheWindowTokenPutBeforeTheLoad() {
        val seal = JvmSeal()
        val steps = Steps()
        BindTokens.init(app, seal, steps)
        BindTokens.put(a, t1)
        steps.run()
        assertArrayEquals(t1, BindTokens.get(a))
        assertArrayEquals(t1, seal.open(a, prefs.getString(a, null)!!))
    }

    @Test
    fun aMissingKeyDropsTheStoredBlobs() {
        val old = JvmSeal().apply { ensure() }
        prefs.edit().putString(b, old.seal(b, t2)).commit()
        val steps = Steps()
        BindTokens.init(app, JvmSeal(), steps)
        BindTokens.put(a, t1)
        steps.run()
        assertNull(BindTokens.get(b))
        assertEquals(setOf(a), prefs.all.keys)
    }

    @Test(timeout = 20_000)
    fun aLoadParkedPast3SecondsReadsNull() {
        BindTokens.init(app, JvmSeal(), Steps())
        BindTokens.put(a, t1)
        val at = System.nanoTime()
        assertNull(BindTokens.get(a))
        val took = System.nanoTime() - at
        assertTrue(took >= 2_900_000_000L)
        assertTrue(took < 10_000_000_000L)
    }

    @Test
    fun theKeyUpperCasesAnRfcommAddress() {
        assertEquals("rfcomm:AA:BB:CC:DD:EE:0F", BindTokens.key("rfcomm:aa:bb:cc:dd:ee:0f"))
        assertEquals("tcp:127.0.0.1:5000", BindTokens.key("tcp:127.0.0.1:5000"))
    }

    @Test
    fun resetLeavesNoExecutorThread() {
        BindTokens.init(app, JvmSeal())
        BindTokens.put(a, t1)
        assertNotNull(BindTokens.get(a))
        BindTokens.reset()
        Thread.getAllStackTraces().keys.filter { it.name == "bind" }.forEach { it.join(2000) }
        assertTrue(Thread.getAllStackTraces().keys.none { it.name == "bind" && it.isAlive })
        assertNull(BindTokens.get(a))
    }

    @Test
    fun onlySheetStateMintsATap() {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val mint = Regex("\\bTap\\(")
        val outside = ArrayList<String>()
        var inside = 0
        File(root, "app/src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val text = f.readText()
            val start = text.indexOf("class SheetState")
            val end = if (start < 0) start else blockEnd(text, start)
            for (m in mint.findAll(text)) if (m.range.first in start until end) inside++ else outside += "${f.name}: ${m.range.first}"
        }
        assertEquals(emptyList<String>(), outside)
        assertEquals(1, inside)
    }

    private fun blockEnd(text: String, from: Int): Int {
        var depth = 0
        for (i in text.indexOf('{', from) until text.length) {
            if (text[i] == '{') depth++
            if (text[i] == '}' && --depth == 0) return i
        }
        return text.length
    }

    @Test
    fun aTapIsGoodOnceAndFor30Seconds() {
        var now = 1_000L
        val tap = Tap(now) { now }
        assertTrue(tap.take())
        assertFalse(tap.take())
        now += 31_000
        assertFalse(Tap(1_000L) { now }.take())
        assertTrue(Tap(now - Tap.LIFE_MS) { now }.take())
    }
}
